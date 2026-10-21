import { expect, it } from 'vitest';
import { copyWorkflowFragment, pasteWorkflowFragment } from '@/platform-components/workflowDesignClipboard';
import { createWorkflowDesign, insertWorkflowBranch } from '@/platform-components/workflowDesignEditing';
it('copies node configuration without database identities and preserves insertion-path conditions', () => {
  const source = createWorkflowDesign(false);
  source.nodes[1] = {
    ...source.nodes[1],
    id: 'db-node',
    version: 9,
    nodeStatus: 'completed',
    participantPolicyText: 'user:alice',
  };
  source.links[0].conditionExpression = '{amount}>10';
  const before = JSON.stringify(source);
  const text = copyWorkflowFragment(source, ['approval']);
  const result = pasteWorkflowFragment(source, source.links[0].routeKey, text, 'copy');
  const node = result.design.nodes.at(-1)!;
  expect(node.participantPolicyText).toBe('user:alice');
  expect(node.id).toBeUndefined();
  expect(node.version).toBeUndefined();
  expect(node.nodeStatus).toBeUndefined();
  expect(result.design.links[0]).toMatchObject({
    targetNodeKey: node.nodeKey,
    conditionExpression: '{amount}>10',
  });
  expect(result.design.links.at(-1)).toMatchObject({
    sourceNodeKey: node.nodeKey,
    targetNodeKey: 'approval',
  });
  expect(JSON.stringify(source)).toBe(before);
  expect(
    pasteWorkflowFragment(result.design, source.links[0].routeKey, text, 'another').design.nodes,
  ).toHaveLength(source.nodes.length + 2);
});
it('expands a selected nested branch to its complete domain and remaps paired joins and internal selectors', () => {
  const source = insertWorkflowBranch(
    insertWorkflowBranch(createWorkflowDesign(false), 'route_1', 'outer'),
    'outer_exit1',
    'inner',
  );
  source.nodes.find((node) => node.nodeKey === 'inner')!.selectorNodeKey = 'inner_path1';
  const text = copyWorkflowFragment(source, ['outer']);
  const result = pasteWorkflowFragment(source, 'route_2', text, 'copy');
  const copied = result.design.nodes.filter((node) => result.keys.includes(node.nodeKey));
  expect(copied).toHaveLength(8);
  for (const branch of copied.filter((node) => node.nodeType === 'branch')) {
    expect(
      copied.some((node) => node.nodeKey === branch.convergeNodeKey && node.nodeType === 'converge'),
    ).toBe(true);
    if (branch.selectorNodeKey) expect(result.keys).toContain(branch.selectorNodeKey);
  }
  const all = JSON.parse(
    copyWorkflowFragment(
      source,
      source.nodes.map((node) => node.nodeKey),
    ),
  );
  expect(all.nodes.some((node: { nodeType: string }) => ['start', 'end'].includes(node.nodeType))).toBe(
    false,
  );
});
it('rejects partial, disconnected or malformed selections and unsafe clipboard graphs', () => {
  const source = insertWorkflowBranch(createWorkflowDesign(false), 'route_1', 'b');
  expect(() => copyWorkflowFragment(source, ['b_join'])).toThrow();
  expect(() => copyWorkflowFragment(source, ['b_path1', 'b_path2'])).toThrow();
  expect(() => copyWorkflowFragment(source, ['start', 'end'])).toThrow();
  for (const text of ['null', '{}', 'unrelated text', 'x'.repeat(1_000_001)])
    expect(() => pasteWorkflowFragment(source, 'route_1', text, 'copy')).toThrow();
  const fragment = JSON.parse(copyWorkflowFragment(source, ['b']));
  fragment.links.push({ routeKey: 'cycle', sourceNodeKey: fragment.exit, targetNodeKey: fragment.entry });
  expect(() => pasteWorkflowFragment(source, 'route_1', JSON.stringify(fragment), 'copy')).toThrow();
  const copied = copyWorkflowFragment(source, ['b']);
  const pasted = pasteWorkflowFragment(source, 'route_1', copied, 'copy').design;
  expect(() => pasteWorkflowFragment(pasted, 'route_1', copied, 'copy')).toThrow();
});
