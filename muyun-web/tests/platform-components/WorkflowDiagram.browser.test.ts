import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page, commands, userEvent } from 'vitest/browser';
import WorkflowDiagram from '@/platform-components/WorkflowDiagram.vue';
import {
  createWorkflowDiagramModel,
  serializeWorkflowDiagramLayout,
} from '@/platform-components/workflowDiagramModel';
import '@/styles.css';
const nodes = [
  { nodeKey: 'start', nodeType: 'start', title: '提交' },
  { nodeKey: 'approval', nodeType: 'approval', title: '采购审批', approvalMode: 'all' },
  { nodeKey: 'end', nodeType: 'end', title: '完成' },
];
const routes = [
  { routeKey: 'in', sourceNodeKey: 'start', targetNodeKey: 'approval', title: '进入审批' },
  { routeKey: 'out', sourceNodeKey: 'approval', targetNodeKey: 'end', title: '完成采购' },
];
function fixture(props = {}) {
  const wrapper = mount(WorkflowDiagram, { props: { nodes, routes, ...props }, attachTo: document.body });
  wrapper.element.setAttribute('style', 'width:320px');
  return wrapper;
}
it('selects real SVG nodes and routes, supports keyboard selection and keeps zoom/pan out of the draft', async () => {
  const wrapper = fixture({ interactive: true, editable: true });
  await expect.poll(() => wrapper.find('[data-workflow-node="approval"]').exists()).toBe(true);
  await page.getByRole('button', { name: '采购审批 审批 · 全部通过', exact: true }).click();
  expect(wrapper.emitted('select')?.at(-1)).toEqual(['approval']);
  await page.getByRole('button', { name: '路径：进入审批', exact: true }).click();
  expect(wrapper.emitted('selectRoute')?.at(-1)).toEqual(['in']);
  const target = wrapper.get('[data-workflow-node="start"]').element as SVGElement;
  target.focus();
  await userEvent.keyboard('{Enter}');
  expect(wrapper.emitted('select')?.at(-1)).toEqual(['start']);
  await page.getByRole('button', { name: /^放\s*大$/, exact: true }).click();
  await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '115%', exact: true }).click();
  await expect.element(page.getByRole('button', { name: '100%', exact: true })).toBeVisible();
  const viewport = wrapper.get('.diagram-viewport').element;
  const viewportBounds = viewport.getBoundingClientRect();
  const start = { x: 12, y: 20 };
  const hit = document.elementFromPoint(viewportBounds.left + start.x, viewportBounds.top + start.y);
  expect(hit).not.toBeNull();
  expect(viewport.contains(hit)).toBe(true);
  expect(hit!.closest('[data-workflow-node], [data-workflow-route]')).toBeNull();
  const node = wrapper.get('[data-workflow-node="start"]').element;
  const before = node.getBoundingClientRect();
  await commands.workflowCanvasDrag('.diagram-viewport', 70, 40, start);
  const after = node.getBoundingClientRect();
  expect(Math.abs(after.left - before.left - 70)).toBeLessThanOrEqual(1);
  expect(Math.abs(after.top - before.top - 40)).toBeLessThanOrEqual(1);
  expect(wrapper.emitted('layoutChange')).toBeUndefined();
});
it('persists node dragging with business identities and locks positions in published/read-only views', async () => {
  const wrapper = fixture({ interactive: true, editable: true });
  await expect.poll(() => wrapper.find('[data-workflow-node="approval"]').exists()).toBe(true);
  await commands.workflowCanvasDrag('[data-workflow-node="approval"]', 60, 35);
  await expect.poll(() => wrapper.emitted('layoutChange')?.length).toBe(1);
  const text = wrapper.emitted('layoutChange')![0]![0] as string;
  const stored = JSON.parse(text);
  expect(Object.keys(stored.nodes)).toEqual(['start', 'approval', 'end']);
  const original = createWorkflowDiagramModel(nodes, routes).nodes.find((node) => node.key === 'approval')!;
  // Pointer coordinates cross the runner iframe transform and round to integer graph positions.
  expect(Math.abs(stored.nodes.approval.x - original.x - 60)).toBeLessThanOrEqual(1);
  expect(Math.abs(stored.nodes.approval.y - original.y - 35)).toBeLessThanOrEqual(1);
  await wrapper.setProps({ layoutJson: text, editable: false });
  expect(wrapper.get('[data-workflow-node="approval"]').attributes('transform')).toBe(
    `translate(${stored.nodes.approval.x},${stored.nodes.approval.y})`,
  );
  await commands.workflowCanvasDrag('[data-workflow-node="approval"]', 50, 25);
  expect(wrapper.emitted('layoutChange')).toHaveLength(1);
  await expect.element(page.getByRole('button', { name: '自动整理' })).not.toBeInTheDocument();
  await wrapper.setProps({ editable: true });
  await page.getByRole('button', { name: '自动整理', exact: true }).click();
  expect(wrapper.emitted('layoutChange')?.at(-1)).toEqual([
    serializeWorkflowDiagramLayout(createWorkflowDiagramModel(nodes, routes).nodes, routes),
  ]);
});
it('focuses the current task at readable scale and resizes when a hidden workspace becomes visible', async () => {
  const longNodes = Array.from({ length: 16 }, (_, i) => ({
    nodeKey: `n${i}`,
    nodeType: i === 0 ? 'start' : i === 15 ? 'end' : 'approval',
    nodeTitle: `节点${i}`,
    nodeStatus: i === 12 ? 'active' : 'waiting',
  }));
  const longRoutes = longNodes.slice(1).map((node, i) => ({
    routeKey: `e${i}`,
    sourceNodeKey: longNodes[i]!.nodeKey,
    targetNodeKey: node.nodeKey,
  }));
  const wrapper = fixture({ nodes: longNodes, routes: longRoutes });
  await expect.element(page.getByRole('img', { name: '节点12 审批 · 当前节点', exact: true })).toBeVisible();
  const viewport = wrapper.get('.diagram-viewport').element;
  const active = wrapper.get('[data-workflow-node="n12"]').element;
  const bounds = viewport.getBoundingClientRect(),
    node = active.getBoundingClientRect();
  expect(node.top).toBeGreaterThan(bounds.top);
  expect(node.bottom).toBeLessThan(bounds.bottom);
  await page.getByRole('button', { name: /^全\s*图$/, exact: true }).click();
  await page.getByRole('button', { name: '定位当前节点', exact: true }).click();
  await expect.element(page.getByRole('button', { name: '100%', exact: true })).toBeVisible();
  await wrapper.setProps({
    nodes: longNodes.map((node) => ({ ...node, nodeStatus: node.nodeKey === 'n3' ? 'active' : 'completed' })),
  });
  const next = wrapper.get('[data-workflow-node="n3"]').element.getBoundingClientRect();
  expect(next.top).toBeGreaterThan(viewport.getBoundingClientRect().top);
  expect(next.bottom).toBeLessThan(viewport.getBoundingClientRect().bottom);
  wrapper.element.setAttribute('style', 'display:none;width:280px');
  await expect.poll(() => (wrapper.get('.diagram-canvas').element as HTMLElement).style.width).toBe('0px');
  wrapper.element.setAttribute('style', 'width:280px');
  await expect.poll(() => (wrapper.get('.diagram-canvas').element as HTMLElement).style.width).toBe('278px');
});
it('keeps parallel branch exits separately visible and selectable', async () => {
  const wrapper = fixture({
    interactive: true,
    routes: [routes[0]!, { ...routes[0]!, routeKey: 'alternative', title: '另一路径' }, routes[1]!],
  });
  await expect.poll(() => wrapper.find('[data-workflow-route="alternative"]').exists()).toBe(true);
  const first = wrapper.get('[data-workflow-route="in"] path:last-of-type').attributes('d');
  const second = wrapper.get('[data-workflow-route="alternative"] path:last-of-type').attributes('d');
  expect(first).not.toBe(second);
  await page.getByRole('button', { name: '路径：进入审批', exact: true }).click();
  await page.getByRole('button', { name: '路径：另一路径', exact: true }).click();
  expect(wrapper.emitted('selectRoute')).toEqual([['in'], ['alternative']]);
});

it('preserves the selected node and user zoom when saving or resizing its workspace', async () => {
  await page.viewport(1920, 1080);
  const wrapper = fixture({ interactive: true, editable: true, selectedNodeKey: 'approval' });
  await expect.poll(() => wrapper.find('[data-workflow-node="approval"]').exists()).toBe(true);
  await page.getByRole('button', { name: /^放\s*大$/, exact: true }).click();
  await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
  await wrapper.setProps({ editable: false });
  await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
  wrapper.element.setAttribute('style', 'width:760px');
  await expect.poll(() => (wrapper.get('.diagram-canvas').element as HTMLElement).style.width).toBe('758px');
  await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
  const node = wrapper.get('[data-workflow-node="approval"]').element.getBoundingClientRect();
  const viewport = wrapper.get('.diagram-viewport').element.getBoundingClientRect();
  expect(node.left).toBeGreaterThan(viewport.left);
  expect(node.right).toBeLessThan(viewport.right);
  expect(node.top).toBeGreaterThan(viewport.top);
  expect(node.bottom).toBeLessThan(viewport.bottom);
});

it('keeps the selected route centered when its property drawer narrows the canvas', async () => {
  await page.viewport(1920, 1080);
  const wrapper = fixture({ interactive: true, selectedRouteKey: 'in' });
  wrapper.element.setAttribute('style', 'width:1400px');
  await expect.poll(() => wrapper.find('[data-workflow-route="in"]').exists()).toBe(true);
  wrapper.element.setAttribute('style', 'width:640px');
  await expect.poll(() => (wrapper.get('.diagram-canvas').element as HTMLElement).style.width).toBe('638px');
  const route = wrapper.get('[data-workflow-route="in"]').element.getBoundingClientRect();
  const viewport = wrapper.get('.diagram-viewport').element.getBoundingClientRect();
  expect((route.left + route.right) / 2).toBeCloseTo((viewport.left + viewport.right) / 2, 0);
  // SVG labels and arrowheads extend beyond the edge geometry used for centering.
  expect(Math.abs((route.top + route.bottom - viewport.top - viewport.bottom) / 2)).toBeLessThan(12);
});
