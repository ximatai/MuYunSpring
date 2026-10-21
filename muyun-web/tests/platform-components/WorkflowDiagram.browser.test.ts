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
  const zoomWidth = wrapper.get('.diagram-zoom-value').element.getBoundingClientRect().width;
  await page.getByRole('button', { name: '缩小', exact: true }).click();
  await expect.element(page.getByRole('button', { name: '85%', exact: true })).toBeVisible();
  expect(wrapper.get('.diagram-zoom-value').element.getBoundingClientRect().width).toBe(zoomWidth);
  await page.getByRole('button', { name: '85%', exact: true }).click();
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
  const before = wrapper.get('[data-workflow-node="start"]').element.getBoundingClientRect();
  const viewportBefore = wrapper.get('.diagram-viewport').element.getBoundingClientRect();
  await wrapper.setProps({ editable: false });
  await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
  wrapper.element.setAttribute('style', 'width:760px');
  await expect.poll(() => (wrapper.get('.diagram-canvas').element as HTMLElement).style.width).toBe('758px');
  await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
  const after = wrapper.get('[data-workflow-node="start"]').element.getBoundingClientRect();
  const viewportAfter = wrapper.get('.diagram-viewport').element.getBoundingClientRect();
  expect(after.left - viewportAfter.left).toBeCloseTo(before.left - viewportBefore.left, 0);
  expect(after.top - viewportAfter.top).toBeCloseTo(before.top - viewportBefore.top, 0);
  expect(after.width).toBeCloseTo(before.width, 0);
});

it('preserves the viewport when a selected route property drawer narrows the canvas', async () => {
  await page.viewport(1920, 1080);
  const wrapper = fixture({ interactive: true, selectedRouteKey: 'in' });
  wrapper.element.setAttribute('style', 'width:1400px');
  await expect.poll(() => wrapper.find('[data-workflow-route="in"]').exists()).toBe(true);
  const before = wrapper.get('[data-workflow-route="in"]').element.getBoundingClientRect();
  const viewportBefore = wrapper.get('.diagram-viewport').element.getBoundingClientRect();
  wrapper.element.setAttribute('style', 'width:640px');
  await expect.poll(() => (wrapper.get('.diagram-canvas').element as HTMLElement).style.width).toBe('638px');
  const route = wrapper.get('[data-workflow-route="in"]').element.getBoundingClientRect();
  const viewportAfter = wrapper.get('.diagram-viewport').element.getBoundingClientRect();
  expect(route.left - viewportAfter.left).toBeCloseTo(before.left - viewportBefore.left, 0);
  expect(route.top - viewportAfter.top).toBeCloseTo(before.top - viewportBefore.top, 0);
});

it('selects clicked nodes and routes without changing the user zoom or pan', async () => {
  await page.viewport(1280, 804);
  const wrapper = fixture({
    interactive: true,
    onSelect: (key: string) => void wrapper.setProps({ selectedNodeKey: key, selectedRouteKey: '' }),
    onSelectRoute: (key: string) => void wrapper.setProps({ selectedNodeKey: '', selectedRouteKey: key }),
  });
  wrapper.element.setAttribute('style', 'width:900px');
  try {
    await expect.poll(() => wrapper.find('[data-workflow-node="start"]').exists()).toBe(true);
    await page.getByRole('button', { name: /^放\s*大$/, exact: true }).click();
    await commands.workflowCanvasDrag('.diagram-viewport', 70, -60, { x: 12, y: 20 });
    const before = wrapper.get('[data-workflow-node="start"]').element.getBoundingClientRect();
    await page.getByRole('button', { name: '提交 开始', exact: true }).click();
    await expect.poll(() => wrapper.props('selectedNodeKey')).toBe('start');
    await page.getByRole('button', { name: '路径：进入审批', exact: true }).click();
    await expect.poll(() => wrapper.props('selectedRouteKey')).toBe('in');
    await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
    const after = wrapper.get('[data-workflow-node="start"]').element.getBoundingClientRect();
    expect(after.left).toBeCloseTo(before.left, 0);
    expect(after.top).toBeCloseTo(before.top, 0);
    expect(after.width).toBeCloseTo(before.width, 0);
    expect(wrapper.emitted('layoutChange')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});

it('locates the start and end independently from the designer toolbar without changing the draft', async () => {
  await page.viewport(1280, 804);
  const wrapper = fixture({ interactive: true, editable: true, presentation: 'designer' });
  wrapper.element.setAttribute('style', 'width:900px;height:600px');
  try {
    await expect.poll(() => wrapper.find('[data-workflow-node="end"]').exists()).toBe(true);
    for (const [name, key] of [
      ['定位结束节点', 'end'],
      ['定位开始节点', 'start'],
    ]) {
      await page.getByRole('button', { name, exact: true }).click();
      const bounds = wrapper.get(`[data-workflow-node="${key}"]`).element.getBoundingClientRect();
      const viewport = wrapper.get('.diagram-viewport').element.getBoundingClientRect();
      expect((bounds.left + bounds.right) / 2).toBeCloseTo((viewport.left + viewport.right) / 2, 0);
      expect((bounds.top + bounds.bottom) / 2).toBeCloseTo((viewport.top + viewport.bottom) / 2, 0);
    }
    expect(wrapper.findAll('button').find((button) => button.text() === '自动整理')).toBeDefined();
    expect(wrapper.emitted('layoutChange')).toBeUndefined();
    expect(wrapper.emitted('select')).toBeUndefined();
    await wrapper.setProps({ nodes: nodes.filter((node) => node.nodeType !== 'end'), routes: [] });
    await expect.element(page.getByRole('button', { name: '定位结束节点', exact: true })).toBeDisabled();
  } finally {
    wrapper.unmount();
  }
});

it('scopes designer shortcuts to the canvas, supports multi-selection, and guards read-only mutation commands', async () => {
  await page.viewport(1000, 800);
  const wrapper = fixture({
    interactive: true,
    editable: true,
    presentation: 'designer',
    canUndo: true,
    canRedo: true,
    selectedNodeKeys: ['approval'],
  });
  wrapper.element.setAttribute('style', 'height:600px;width:960px');
  try {
    await expect.poll(() => wrapper.find('[data-workflow-node="approval"]').exists()).toBe(true);
    await page.getByRole('button', { name: '提交 开始', exact: true }).click({ modifiers: ['Shift'] });
    expect(wrapper.emitted('selectionChange')?.at(-1)).toEqual([['approval', 'start']]);
    const canvas = wrapper.get('.diagram-viewport').element as HTMLElement;
    canvas.focus();
    await userEvent.keyboard('{Control>}z{/Control}');
    expect(wrapper.emitted('undo')).toHaveLength(1);
    await userEvent.keyboard('{Control>}{Shift>}z{/Shift}{/Control}');
    expect(wrapper.emitted('redo')).toHaveLength(1);
    await userEvent.keyboard('{Delete}');
    await userEvent.keyboard('{Backspace}');
    expect(wrapper.emitted('deleteSelection')).toHaveLength(2);
    await wrapper.setProps({ editable: false });
    canvas.focus();
    await userEvent.keyboard('{Delete}');
    await userEvent.keyboard('{Backspace}');
    expect(wrapper.emitted('deleteSelection')).toHaveLength(2);
    await userEvent.keyboard('{Meta>}z{/Meta}');
    await userEvent.keyboard('{Control>}y{/Control}');
    canvas.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, clipboardData: new DataTransfer() }));
    expect(wrapper.emitted('undo')).toHaveLength(1);
    expect(wrapper.emitted('redo')).toHaveLength(1);
    expect(wrapper.emitted('paste')).toBeUndefined();
    await userEvent.keyboard('{Meta>}a{/Meta}');
    expect(wrapper.emitted('selectionChange')?.at(-1)).toEqual([['start', 'approval', 'end']]);
    const outside = document.createElement('textarea');
    document.body.append(outside);
    outside.focus();
    await userEvent.keyboard('{Control>}z{/Control}');
    expect(wrapper.emitted('undo')).toHaveLength(1);
    outside.remove();
  } finally {
    wrapper.unmount();
  }
});

it('renders compact gateways without redundant decoration and keeps pointer and keyboard focus on the diamond', async () => {
  await page.viewport(1100, 850);
  const wrapper = fixture({
    nodes: [
      nodes[0],
      { nodeKey: 'branch', nodeType: 'branch', title: '条件分支', routeMode: 'auto' },
      nodes[1],
      { nodeKey: 'join', nodeType: 'converge', title: '分支汇聚', convergeMode: 'all' },
      nodes[2],
    ],
    routes: [
      { routeKey: 'a', sourceNodeKey: 'start', targetNodeKey: 'branch' },
      { routeKey: 'b', sourceNodeKey: 'branch', targetNodeKey: 'approval' },
      { routeKey: 'c', sourceNodeKey: 'approval', targetNodeKey: 'join' },
      { routeKey: 'd', sourceNodeKey: 'join', targetNodeKey: 'end' },
    ],
    interactive: true,
    editable: true,
    presentation: 'designer',
  });
  wrapper.element.setAttribute('style', 'height:760px;width:1050px');
  try {
    await expect.poll(() => wrapper.find('[data-workflow-node="branch"] path').exists()).toBe(true);
    await page.getByRole('button', { name: '全图', exact: true }).click();
    const branch = wrapper.get('[data-workflow-node="branch"]');
    const join = wrapper.get('[data-workflow-node="join"]');
    expect(branch.findAll('path')).toHaveLength(1);
    expect(join.findAll('path')).toHaveLength(1);
    expect(branch.findAll('path')[0].attributes('d')).toBe('M 72 0 L 144 36 L 72 72 L 0 36 Z');
    expect(wrapper.get('[data-workflow-node="approval"]').findAll('path')).toHaveLength(0);
    expect(branch.get('title').text()).toContain('条件分流');
    expect(join.get('title').text()).toContain('全部到达');
    const pointerFocus: string[] = [];
    branch.element.addEventListener('focus', () =>
      pointerFocus.push(getComputedStyle(branch.element).outlineStyle),
    );
    await page.getByRole('button', { name: /^条件分支 / }).click();
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['branch']);
    expect(pointerFocus).toEqual(['none']);
    (join.element as unknown as SVGElement).focus();
    expect(getComputedStyle(join.element).outlineStyle).toBe('none');
    await userEvent.keyboard('{Enter}');
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['join']);
    await wrapper.setProps({ selectedNodeKey: 'branch' });
    expect(branch.findAll('path')[0].attributes('stroke-width')).toBe('2');
    expect(getComputedStyle(branch.findAll('path')[0].element).fill).toBe('rgb(236, 242, 254)');
    for (const node of [branch, join]) {
      const body = node.findAll('path')[0].element.getBoundingClientRect();
      for (const text of node.findAll('text')) {
        if (getComputedStyle(text.element).display === 'none') continue;
        const bounds = text.element.getBoundingClientRect();
        expect(bounds.left).toBeGreaterThanOrEqual(body.left);
        expect(bounds.right).toBeLessThanOrEqual(body.right);
        expect(bounds.top).toBeGreaterThanOrEqual(body.top);
        expect(bounds.bottom).toBeLessThanOrEqual(body.bottom);
      }
    }
  } finally {
    wrapper.unmount();
  }
});

it('distinguishes task types by accent color without replacing selection or runtime status styling', async () => {
  await page.viewport(1100, 850);
  const typedNodes = [
    { nodeKey: 'review', nodeType: 'approval', title: '审批', approvalMode: 'all' },
    { nodeKey: 'fyi', nodeType: 'approval', title: '知会', approvalMode: 'notice' },
    { nodeKey: 'business', nodeType: 'task', title: '业务任务', nodeStatus: 'active' },
    { nodeKey: 'done', nodeType: 'milestone', title: '里程碑', nodeStatus: 'completed' },
  ];
  const wrapper = fixture({ nodes: typedNodes, routes: [], interactive: true });
  wrapper.element.setAttribute('style', 'height:500px;width:1050px');
  try {
    await expect.poll(() => wrapper.find('[data-workflow-node="fyi"] rect').exists()).toBe(true);
    const node = (key: string) => wrapper.get(`[data-workflow-node="${key}"]`);
    const accent = (key: string) => getComputedStyle(node(key).findAll('rect')[1].element).fill;
    const colors = typedNodes.map((item) => accent(item.nodeKey));
    expect(new Set(colors).size).toBe(4);
    expect(node('fyi').attributes('aria-label')).toContain('知会');
    expect(getComputedStyle(node('business').findAll('rect')[0].element).stroke).toBe('rgb(0, 82, 217)');
    expect(getComputedStyle(node('done').findAll('rect')[0].element).fill).toBe('rgb(237, 249, 245)');
    await wrapper.setProps({ selectedNodeKey: 'fyi' });
    expect(getComputedStyle(node('fyi').findAll('rect')[0].element).stroke).toBe('rgb(0, 82, 217)');
    expect(typedNodes.map((item) => accent(item.nodeKey))).toEqual(colors);
    await wrapper.setProps({ nodes: typedNodes.map((item) => ({ ...item, nodeStatus: 'skipped' })) });
    expect(getComputedStyle(node('fyi').element).opacity).toBe('0.5');
    expect(typedNodes.map((item) => accent(item.nodeKey))).toEqual(colors);
  } finally {
    wrapper.unmount();
  }
});
