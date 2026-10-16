import { expect, it } from 'vitest';
import {
  insertWorkflowNode,
  removeWorkflowNode,
  insertWorkflowBranch,
  appendWorkflowBranchPath,
  removeWorkflowBranch,
  updateWorkflowRoute,
} from '@/platform-components/workflowDesignEditing';
import type { WorkflowDesign } from '@muyun/web-contracts';
const design: WorkflowDesign = {
  nodes: [
    { nodeKey: 'start', nodeType: 'start' },
    { nodeKey: 'approve', nodeType: 'approval' },
    { nodeKey: 'end', nodeType: 'end' },
  ],
  links: [
    { routeKey: 'in', sourceNodeKey: 'start', targetNodeKey: 'approve', conditionExpression: '{amount}>0' },
    { routeKey: 'out', sourceNodeKey: 'approve', targetNodeKey: 'end' },
  ],
};
it('inserts in the selected path while preserving its condition and without mutating the source', () => {
  const next = insertWorkflowNode(design, 'in', { nodeKey: 'review', nodeType: 'approval' });
  expect(next.links.find((route) => route.routeKey === 'in')).toMatchObject({
    targetNodeKey: 'review',
    conditionExpression: '{amount}>0',
  });
  expect(next.links.find((route) => route.sourceNodeKey === 'review')?.targetNodeKey).toBe('approve');
  expect(design.links[0]?.targetNodeKey).toBe('approve');
  expect(removeWorkflowNode(next, 'review')).toEqual(design);
});
it('refuses an ambiguous branch insertion or removal rather than silently changing route meaning', () => {
  expect(() => insertWorkflowNode(design, '', { nodeKey: 'x', nodeType: 'approval' })).toThrow('请选择');
  expect(() =>
    removeWorkflowNode(
      {
        ...design,
        links: [...design.links, { routeKey: 'other', sourceNodeKey: 'approve', targetNodeKey: 'start' }],
      },
      'approve',
    ),
  ).toThrow('复杂路径');
  expect(() =>
    removeWorkflowNode(
      {
        ...design,
        links: design.links.map((route) =>
          route.routeKey === 'out' ? { ...route, conditionExpression: '{done}' } : route,
        ),
      },
      'approve',
    ),
  ).toThrow('复杂路径');
  expect(() => removeWorkflowNode(design, 'start')).toThrow('保留');
});
it('creates a paired branch with independent paths, supports nested insertion and restores the upstream condition when removed', () => {
  const branched = insertWorkflowBranch(design, 'in', 'b');
  expect(branched.nodes.find((node) => node.nodeKey === 'b')).toMatchObject({
    nodeType: 'branch',
    convergeNodeKey: 'b_join',
  });
  expect(branched.links.filter((link) => link.sourceNodeKey === 'b')).toHaveLength(2);
  expect(branched.nodes.find((node) => node.nodeKey === 'b_path1')?.participantPolicyText).toBe(
    '{"rules":[]}',
  );
  const appended = appendWorkflowBranchPath(branched, 'b', 'third');
  expect(appended.links.filter((link) => link.sourceNodeKey === 'b')).toHaveLength(3);
  const nested = insertWorkflowBranch(appended, 'b_exit1', 'nested');
  expect(removeWorkflowBranch(nested, 'nested')).toEqual(appended);
  expect(removeWorkflowBranch(nested, 'b')).toEqual(design);
  expect(design.nodes).toHaveLength(3);
});
it('refuses deleting a branch that also receives an external path', () => {
  const branched = insertWorkflowBranch(design, 'in', 'b');
  branched.links.push({ routeKey: 'cross', sourceNodeKey: 'approve', targetNodeKey: 'b_path1' });
  expect(() => removeWorkflowBranch(branched, 'b')).toThrow('外部交叉');
});
it('default exit is unique per branch, clears its ignored condition, and leaves other branches unchanged', () => {
  const branched = insertWorkflowBranch(design, 'in', 'b');
  const first = updateWorkflowRoute(branched, 'b_exit1', {
    defaultRoute: true,
    conditionExpression: '{amount}>100',
  });
  const second = updateWorkflowRoute(first, 'b_exit2', { defaultRoute: true });
  expect(second.links.find((link) => link.routeKey === 'b_exit1')?.defaultRoute).toBe(false);
  expect(second.links.find((link) => link.routeKey === 'b_exit2')).toMatchObject({
    defaultRoute: true,
    conditionExpression: undefined,
  });
  expect(second.links.find((link) => link.routeKey === 'in')?.conditionExpression).toBe('{amount}>0');
  expect(branched.links.find((link) => link.routeKey === 'b_exit1')?.defaultRoute).toBeUndefined();
});
