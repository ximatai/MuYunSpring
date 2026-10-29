import { expect, it } from 'vitest';
import { createLanguageContext } from '@muyun/web-core';
import {
  createWorkflowApprovalNode,
  createWorkflowDesign,
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
it('removes serial nested branches with bounded graph work instead of enumerating every path', () => {
  let branched = insertWorkflowBranch(design, 'in', 'outer');
  let route = 'outer_exit1';
  for (let index = 0; index < 24; index++) {
    const key = `nested_${index}`;
    branched = insertWorkflowBranch(branched, route, key);
    route = `${key}_next`;
  }
  const snapshot = JSON.stringify(branched);
  let reads = 0;
  // An access budget fails deterministically before an exponential traversal can freeze the test runner.
  const bounded = {
    ...branched,
    links: branched.links.map(
      (link) =>
        new Proxy(link, {
          get(target, property, receiver) {
            if (property === 'sourceNodeKey' && ++reads > branched.links.length * 10)
              throw new Error('Branch removal exceeded the graph traversal budget');
            return Reflect.get(target, property, receiver);
          },
        }),
    ),
  };
  expect(removeWorkflowBranch(bounded, 'outer')).toEqual(design);
  expect(JSON.stringify(branched)).toBe(snapshot);
});
it('still rejects cycles and paths that do not reach the paired join', () => {
  const branched = insertWorkflowBranch(design, 'in', 'b');
  const cycle = {
    ...branched,
    links: [...branched.links, { routeKey: 'cycle', sourceNodeKey: 'b_path1', targetNodeKey: 'b' }],
  };
  expect(() => removeWorkflowBranch(cycle, 'b')).toThrow('循环');
  const broken = { ...branched, links: branched.links.filter((link) => link.routeKey !== 'b_return1') };
  expect(() => removeWorkflowBranch(broken, 'b')).toThrow('未到达');
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

it('produces English branch defaults at creation and keeps existing titles verbatim', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const before = {
    ...design,
    nodes: design.nodes.map((node) => ({ ...node, title: '条件分支' })),
    links: design.links.map((route) => ({ ...route, title: '路径1' })),
  };
  const next = appendWorkflowBranchPath(
    insertWorkflowBranch(before, 'in', 'b', language),
    'b',
    'third',
    language,
  );
  expect(next.nodes.slice(0, 3)).toEqual(before.nodes);
  expect(next.links.find((route) => route.routeKey === 'in')?.title).toBe('路径1');
  expect(next.nodes.slice(3).map((node) => node.title)).toEqual([
    'Conditional branch',
    'Branch approval 1',
    'Branch approval 2',
    'Branch join',
    'Branch approval 3',
  ]);
  expect(next.links.slice(2).map((route) => route.title)).toEqual([
    'Path 1',
    'Converge',
    'Path 2',
    'Converge',
    'Continue',
    'Path 3',
    'Converge',
  ]);
});

it('preserves supplied approval titles even when they match generated Chinese defaults', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  for (const title of ['条件分支', '分支审批1', '路径2', '继续', '  User title  ']) {
    const node = createWorkflowApprovalNode('new', title);
    expect(node.title).toBe(title);
    const next = insertWorkflowNode(design, 'in', node, language);
    expect(next.nodes.at(-1)?.title).toBe(title);
    expect(next.links.at(-1)?.title).toBe('Continue');
  }
});

it('produces structural errors in the current language directly from the shared editor', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const node = createWorkflowApprovalNode('new', '原文');
  expect(() => insertWorkflowNode(design, '', node, language)).toThrow('Choose a route for the new node');
  expect(() => insertWorkflowBranch(design, '', 'b', language)).toThrow('Choose a route for the new branch');
  expect(() => insertWorkflowNode(design, 'in', { ...node, nodeKey: 'approve' }, language)).toThrow(
    'The node key already exists',
  );
  expect(() => appendWorkflowBranchPath(design, 'missing', 'b', language)).toThrow(
    'The branch must have a valid paired join',
  );
  expect(() => removeWorkflowBranch(design, 'missing', language)).toThrow(
    'The branch must have a valid paired join',
  );
  expect(() => removeWorkflowNode(design, 'start', language)).toThrow('Start and end nodes must be retained');
  expect(() => updateWorkflowRoute(design, 'missing', {}, language)).toThrow('The route does not exist');
  language.setLanguage('zh-CN');
  expect(() => insertWorkflowNode(design, '', node, language)).toThrow('请选择需要插入节点的路径');
});

it('creates initial defaults using the supplied language', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const next = createWorkflowDesign(true, language);
  expect(next.nodes.map((node) => node.title)).toEqual(['Submit', 'Approval', 'Approval completed', 'Done']);
  expect(next.links.every((route) => route.title === 'Continue')).toBe(true);
});
