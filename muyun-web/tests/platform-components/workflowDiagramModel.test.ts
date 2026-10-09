import { expect, it } from 'vitest';
import type { WorkflowNode, WorkflowRoute } from '@muyun/web-contracts';
import {
  createWorkflowDiagramModel,
  serializeWorkflowDiagramLayout,
} from '@/platform-components/workflowDiagramModel';
const nodes: WorkflowNode[] = [
  { nodeKey: 'start', nodeType: 'start', title: '提交' },
  { nodeKey: 'branch', nodeType: 'branch', title: '金额分流', routeMode: 'manual' },
  { nodeKey: 'left', nodeType: 'approval', title: '大额审批' },
  { nodeKey: 'right', nodeType: 'approval', title: '普通审批' },
  { nodeKey: 'join', nodeType: 'converge', title: '汇聚', convergeMode: 'any' },
  { nodeKey: 'end', nodeType: 'end', title: '结束' },
];
const routes: WorkflowRoute[] = [
  { routeKey: 'in', sourceNodeKey: 'start', targetNodeKey: 'branch' },
  { routeKey: 'left', sourceNodeKey: 'branch', targetNodeKey: 'left', title: '大额采购' },
  { routeKey: 'right', sourceNodeKey: 'branch', targetNodeKey: 'right', defaultRoute: true },
  { routeKey: 'left-out', sourceNodeKey: 'left', targetNodeKey: 'join' },
  { routeKey: 'right-out', sourceNodeKey: 'right', targetNodeKey: 'join' },
  { routeKey: 'out', sourceNodeKey: 'join', targetNodeKey: 'end' },
];
it('lays branches out in independent lanes, places convergence below both paths and preserves business facts', () => {
  const before = JSON.stringify({ nodes, routes });
  const model = createWorkflowDiagramModel(nodes, routes);
  const byKey = new Map(model.nodes.map((node) => [node.key, node]));
  const left = byKey.get('left')!,
    right = byKey.get('right')!,
    join = byKey.get('join')!;
  expect(Math.abs(left.x - right.x)).toBeGreaterThanOrEqual(left.width);
  expect(join.y).toBeGreaterThan(Math.max(left.y + left.height, right.y + right.height));
  expect(byKey.get('branch')!.subtitle).toContain('人工单选');
  expect(join.subtitle).toContain('任一到达');
  expect(model.routes.find((route) => route.key === 'right')!.title).toBe('默认出口');
  expect(JSON.stringify({ nodes, routes })).toBe(before);
  expect(createWorkflowDiagramModel(nodes, routes)).toEqual(model);
});
it('restores node positions across title/status changes but reflows after insertion or rewiring', () => {
  const stored = serializeWorkflowDiagramLayout(
    createWorkflowDiagramModel(nodes, routes).nodes.map((node) => ({
      ...node,
      x: node.x + 37,
      y: node.y + 19,
    })),
    routes,
  );
  const original = createWorkflowDiagramModel(nodes, routes, stored);
  const renamed = createWorkflowDiagramModel(
    nodes.map((node) => ({ ...node, title: '更新名称', nodeStatus: 'completed' })),
    [...routes].reverse(),
    stored,
  );
  expect(renamed.nodes.map(({ x, y }) => ({ x, y }))).toEqual(original.nodes.map(({ x, y }) => ({ x, y })));
  const changed = routes.map((route) =>
    route.routeKey === 'left-out' ? { ...route, targetNodeKey: 'end' } : route,
  );
  expect(createWorkflowDiagramModel(nodes, changed, stored)).toEqual(
    createWorkflowDiagramModel(nodes, changed),
  );
  const inserted = [...nodes, { nodeKey: 'inserted', nodeType: 'approval' }];
  expect(createWorkflowDiagramModel(inserted, routes, stored)).toEqual(
    createWorkflowDiagramModel(inserted, routes),
  );
});
it('discards malformed, unsupported and out-of-range presentation positions without losing the graph', () => {
  const normal = createWorkflowDiagramModel(nodes, routes);
  for (const invalid of ['{', '[]', 'null', '{"version":2}', '{"nodes":{"branch":{"x":10,"y":20}}}']) {
    expect(createWorkflowDiagramModel(nodes, routes, invalid)).toEqual(normal);
  }
  const stored = JSON.parse(serializeWorkflowDiagramLayout(normal.nodes, routes));
  stored.nodes.branch = { x: 100001, y: 0 };
  stored.nodes.left = { x: '9', y: 0 };
  expect(createWorkflowDiagramModel(nodes, routes, JSON.stringify(stored))).toEqual(normal);
});
it('uses frozen route names and runtime status without turning a displayed condition into a decision', () => {
  const runtimeNodes = nodes.map(({ title, ...node }) => ({
    ...node,
    nodeTitle: title,
    nodeStatus: 'waiting',
  }));
  const runtimeRoutes = routes.map((route) => ({
    routeKey: route.routeKey,
    sourceNodeKey: route.sourceNodeKey,
    targetNodeKey: route.targetNodeKey,
    routeStatus: 'ineffective',
  }));
  const semantic = JSON.stringify({
    nodes,
    links: routes.map((route) => ({ ...route, conditionExpression: 'true' })),
  });
  const model = createWorkflowDiagramModel(runtimeNodes, runtimeRoutes, undefined, semantic);
  expect(model.routes.find((route) => route.key === 'left')).toMatchObject({
    title: '大额采购',
    status: 'ineffective',
  });
  expect(model.routes.find((route) => route.key === 'right')).toMatchObject({
    title: '默认出口',
    status: 'ineffective',
  });
  expect(model.nodes.find((node) => node.key === 'left')!.subtitle).toContain('未到达');
});
it('keeps parallel routes independent, ignores unresolved endpoints and accepts an empty draft', () => {
  const model = createWorkflowDiagramModel(nodes, [
    ...routes,
    { ...routes[1]!, routeKey: 'another-left' },
    { routeKey: 'missing', sourceNodeKey: 'start', targetNodeKey: 'absent' },
  ]);
  expect(model.routes.filter((route) => route.target === 'left')).toHaveLength(2);
  expect(model.nodes).toHaveLength(nodes.length);
  expect(createWorkflowDiagramModel([], [])).toEqual({ nodes: [], routes: [] });
});
