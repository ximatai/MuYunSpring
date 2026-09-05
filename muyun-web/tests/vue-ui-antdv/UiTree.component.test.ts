import { mount, flushPromises } from '@vue/test-utils';
import { expect, it, vi, afterEach } from 'vitest';
import { nextTick } from 'vue';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';

it('delegates tree checkbox rendering to Ant Tree and exposes a normalized check event', () => {
  const parent = { key: 'group:one', title: '分组' };
  const child = { key: 'item:first', title: '第一项' };
  const wrapper = mount(UiTree, {
    props: {
      nodes: [{ ...parent, children: [child] }],
      checkable: true,
      checkedKeys: [child.key],
    },
    global: {
      stubs: {
        ATree: {
          name: 'ATree',
          props: ['checkable', 'checkedKeys'],
          template: '<div />',
        },
        UiRecordExplorerItem: true,
      },
    },
  });

  const tree = wrapper.findComponent({ name: 'ATree' });
  expect(tree.props('checkable')).toBe(true);
  expect(tree.props('checkedKeys')).toEqual([child.key]);

  tree.vm.$emit('check', [child.key], {
    node: { key: child.key, dataRef: child },
    checked: true,
    halfCheckedKeys: [parent.key],
  });

  expect(wrapper.emitted('update:checkedKeys')).toEqual([[[child.key]]]);
  expect(wrapper.emitted('check')).toEqual([
    [
      {
        node: child,
        checked: true,
        checkedKeys: [child.key],
        halfCheckedKeys: [parent.key],
        nativeEvent: undefined,
      },
    ],
  ]);
});

it('disables tree checkboxes rejected by the public check contract', () => {
  const locked = { key: 'item:locked', title: '只读项' };
  const editable = { key: 'item:editable', title: '可选项' };
  const wrapper = mount(UiTree, {
    props: {
      nodes: [{ key: 'group:one', title: '分组', children: [locked, editable] }],
      checkable: true,
      canCheck: (node) => node.key !== locked.key,
    },
    global: {
      stubs: {
        ATree: {
          name: 'ATree',
          props: ['treeData'],
          template: '<div />',
        },
        UiRecordExplorerItem: true,
      },
    },
  });

  expect(wrapper.findComponent({ name: 'ATree' }).props('treeData')).toEqual([
    {
      key: 'group:one',
      title: '分组',
      children: [{ ...locked, disableCheckbox: true }, editable],
    },
  ]);
});

it('renders flat nodes with the shared item renderer and independent checkboxes', async () => {
  const first = { key: 'item:first', title: '第一项', secondary: '字段' };
  const action = { key: 'inspect', title: '查看' };
  const second = { key: 'item:second', title: '第二项', actions: [action] };
  const secondWithChild = { ...second, children: [{ key: 'nested', title: '不应出现在平铺视图' }] };
  const wrapper = mount(UiTree, {
    props: {
      displayMode: 'flat',
      nodes: [first, secondWithChild],
      checkable: true,
      checkedKeys: [first.key],
      selectedKey: first.key,
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', template: '<div />' },
        UiCheckbox: {
          name: 'UiCheckbox',
          props: ['checked', 'disabled'],
          emits: ['update:checked'],
          template: '<button data-test="checkbox" />',
        },
        UiRecordExplorerItem: {
          name: 'UiRecordExplorerItem',
          props: ['title', 'secondary', 'selected', 'clickable', 'actions'],
          emits: ['click', 'action'],
          template: '<span class="item">{{ title }}</span>',
        },
      },
    },
  });

  expect(wrapper.findComponent({ name: 'ATree' }).exists()).toBe(false);
  expect(wrapper.findAll('[data-ui-tree-key]')).toHaveLength(2);
  expect(wrapper.get('[data-ui-tree-key="item:first"] .item').text()).toBe('第一项');
  expect(wrapper.findAllComponents({ name: 'UiRecordExplorerItem' })[0]?.props('selected')).toBe(true);

  const items = wrapper.findAllComponents({ name: 'UiRecordExplorerItem' });
  items[0]!.vm.$emit('click');
  items[1]!.vm.$emit('action', action);
  expect(wrapper.emitted('deselect')).toEqual([[]]);
  expect(wrapper.emitted('action')).toEqual([[action, second]]);

  const checkboxes = wrapper.findAllComponents({ name: 'UiCheckbox' });
  await checkboxes[1]!.vm.$emit('update:checked', true);

  expect(wrapper.emitted('update:checkedKeys')).toEqual([[['item:first', 'item:second']]]);
  expect(wrapper.emitted('check')?.[0]?.[0]).toMatchObject({
    node: second,
    checked: true,
    checkedKeys: ['item:first', 'item:second'],
    halfCheckedKeys: [],
  });
});

afterEach(() => vi.restoreAllMocks());
const item = (key: string) => ({ key, title: key, isLeaf: true });
function tree(props: Partial<InstanceType<typeof UiTree>['$props']> = {}) {
  return mount(UiTree, {
    attachTo: document.body,
    props: {
      nodes: [item('first'), item('second')],
      draggable: true,
      allowDrop: (event) => event.target.kind === 'node' && event.target.position !== 'inside',
      ...props,
    },
  });
}
async function gesture(source: Element, target: Element, y = 50, release = true) {
  await nextTick();
  Object.defineProperty(target, 'getBoundingClientRect', {
    configurable: true,
    value: () => ({ top: 0, left: 0, width: 100, height: 100 }),
  });
  source.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 }));
  target.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, buttons: 1, clientX: 100, clientY: y }));
  await flushPromises();
  if (release) {
    target.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: 100, clientY: y }));
    await nextTick();
  }
}
it.each(['tree', 'flat'] as const)(
  'sorts %s through one gesture and clears feedback',
  async (displayMode) => {
    const wrapper = tree({ displayMode });
    const source = wrapper.get('[data-ui-tree-key="second"]');
    const target = wrapper.get('[data-ui-tree-key="first"]');
    await gesture(source.element, target.element, 10, false);
    expect(source.classes()).toContain('ui-tree-node--dragging');
    expect(target.classes()).toContain('ui-tree-node--drop-before');
    await target.trigger('mouseup', { clientX: 100, clientY: 10 });
    expect(wrapper.emitted('drop')).toHaveLength(1);
    expect(wrapper.emitted('drop')![0][0]).toMatchObject({
      source: { node: { key: 'second' } },
      target: { kind: 'node', node: { key: 'first' }, position: 'before' },
      operation: 'move',
    });
    expect(target.classes()).not.toContain('ui-tree-node--drop-before');
  },
);
it.each(['tree', 'flat'] as const)(
  'does not drag disabled %s nodes or start below threshold',
  async (displayMode) => {
    const wrapper = tree({ displayMode, nodes: [{ ...item('first'), disabled: true }, item('second')] });
    await gesture(
      wrapper.get('[data-ui-tree-key="first"]').element,
      wrapper.get('[data-ui-tree-key="second"]').element,
    );
    expect(wrapper.emitted('drag-start')).toBeUndefined();
    const source = wrapper.get('[data-ui-tree-key="second"]');
    await source.trigger('mousedown', { button: 0 });
    await source.trigger('mousemove', { buttons: 1, clientX: 1, clientY: 1 });
    await source.trigger('mouseup');
    expect(wrapper.emitted('drop')).toBeUndefined();
  },
);
it.each(['escape', 'blur', 'unmount', 'source-change'] as const)(
  'cleans both surfaces on %s',
  async (cancel) => {
    const source = tree({ dragOperations: ['copy'], dragPayloadOf: () => ({ field: 'x' }) });
    const target = tree({ allowDrop: () => true });
    const node = source.get('[data-ui-tree-key="first"]');
    const destination = target.get('[data-ui-tree-key="second"]');
    await gesture(node.element, destination.element, 50, false);
    expect(destination.classes()).toContain('ui-tree-node--drop-inside');
    if (cancel === 'escape') document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    if (cancel === 'blur') window.dispatchEvent(new Event('blur'));
    if (cancel === 'unmount') source.unmount();
    if (cancel === 'source-change') {
      await source.setProps({ draggable: false });
      await destination.trigger('mousemove', { buttons: 1, clientX: 120, clientY: 50 });
    }
    await nextTick();
    expect(destination.classes()).not.toContain('ui-tree-node--drop-inside');
    await destination.trigger('mouseup');
    expect(target.emitted('drop')).toBeUndefined();
  },
);
it('rechecks target permission on release and shows rejection', async () => {
  let allowed = true;
  const wrapper = tree({ allowDrop: () => allowed });
  const target = wrapper.get('[data-ui-tree-key="second"]');
  await gesture(wrapper.get('[data-ui-tree-key="first"]').element, target.element, 50, false);
  allowed = false;
  await target.trigger('mouseup', { clientY: 50 });
  expect(wrapper.emitted('drop')).toBeUndefined();
});
it.each(['copy', 'move'] as const)(
  'transports %s across trees without a native payload event',
  async (operation) => {
    const source = tree({
      dragOperations: [operation],
      dragPayloadType: 'fields',
      dragPayloadOf: () => ({ id: 'field' }),
    });
    const target = tree({ allowDrop: () => true });
    await gesture(
      source.get('[data-ui-tree-key="first"]').element,
      target.get('[data-ui-tree-key="second"]').element,
    );
    expect(target.emitted('drop')).toHaveLength(1);
    const event = target.emitted('drop')![0][0] as import('@/vue-ui-antdv/types').UiTreeDropEvent;
    expect(event.operation).toBe(operation);
    expect(event.source.payload).toEqual({ id: 'field' });
    expect(event.source.instanceId).not.toBe(event.target.instanceId);
    expect(source.props('nodes')).toHaveLength(2);
  },
);
it('accepts inside on a structural leaf and a genuinely empty root', async () => {
  const wrapper = tree({ allowDrop: () => true });
  await gesture(
    wrapper.get('[data-ui-tree-key="first"]').element,
    wrapper.get('[data-ui-tree-key="second"]').element,
  );
  expect(wrapper.emitted('drop')![0][0]).toMatchObject({
    target: { position: 'inside', node: { isLeaf: true } },
  });
  const empty = tree({ nodes: [], allowDrop: () => true });
  await gesture(wrapper.get('[data-ui-tree-key="first"]').element, empty.get('[data-ui-drop-root]').element);
  expect(empty.emitted('drop')![0][0]).toMatchObject({ target: { kind: 'root', position: 'inside' } });
});
it('does not allow drops without an explicit policy', async () => {
  const wrapper = tree({ allowDrop: undefined });
  await gesture(
    wrapper.get('[data-ui-tree-key="first"]').element,
    wrapper.get('[data-ui-tree-key="second"]').element,
  );
  expect(wrapper.emitted('drop')).toBeUndefined();
});
it('uses the same rules for keyboard sorting and cross-instance root drops', async () => {
  const source = tree();
  const target = tree({ nodes: [], allowDrop: () => true });
  await nextTick();
  const row = source.get('[data-ui-tree-key="first"]');
  (row.element as HTMLElement).focus();
  await row.trigger('keydown', { key: ' ' });
  (target.get('[data-ui-drop-root]').element as HTMLElement).focus();
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await nextTick();
  expect(target.emitted('drop')![0][0]).toMatchObject({ target: { kind: 'root' }, operation: 'move' });
});
it('keeps real tree cascade and strict checkbox behavior', async () => {
  const wrapper = tree({
    checkable: true,
    nodes: [{ key: 'parent', title: 'Parent', children: [item('first'), item('second')] }],
    expandedKeys: ['parent'],
  });
  await wrapper.findAll('.ant-tree-checkbox')[1]!.trigger('click');
  expect(wrapper.emitted('check')!.at(-1)![0]).toMatchObject({
    checkedKeys: ['first'],
    halfCheckedKeys: ['parent'],
  });
  await wrapper.setProps({ checkStrictly: true, checkedKeys: [] });
  await wrapper.findAll('.ant-tree-checkbox')[0]!.trigger('click');
  expect(wrapper.emitted('check')!.at(-1)![0]).toMatchObject({
    checkedKeys: ['parent'],
    halfCheckedKeys: [],
  });
});
it('keeps controls independent from dragging', async () => {
  const wrapper = tree({
    checkable: true,
    displayMode: 'flat',
    nodes: [{ ...item('first'), actions: [{ key: 'inspect', title: 'Inspect' }] }],
  });
  const button = wrapper.get('button');
  await button.trigger('mousedown', { button: 0 });
  await wrapper.trigger('mousemove', { buttons: 1, clientX: 100, clientY: 100 });
  await wrapper.trigger('mouseup');
  expect(wrapper.emitted('drag-start')).toBeUndefined();
  await button.trigger('click');
  expect(wrapper.emitted('action')).toHaveLength(1);
});
it('keeps a controlled branch loading until its owner supplies the outcome', async () => {
  const wrapper = tree({
    nodes: [{ key: 'root', title: 'Root', isLeaf: false }],
    loadStrategy: 'controlled',
    branchStates: { root: { status: 'loading' } },
    minLoadingDurationMs: 0,
  });
  expect(wrapper.get('[role="status"]').text()).toContain('加载中');
  await wrapper.vm.refreshNode('root');
  expect(wrapper.emitted('load-request')).toBeUndefined();
  await wrapper.setProps({
    branchStates: {
      root: {
        status: 'error',
        error: '失败',
        failedRequest: { node: item('root'), reason: 'load-more', cursor: 'next' },
      },
    },
  });
  await wrapper.get('button').trigger('click');
  expect(wrapper.emitted('load-request')![0][0]).toMatchObject({ reason: 'load-more', cursor: 'next' });
  expect(wrapper.emitted('load-request')![0][0]).not.toHaveProperty('signal');
  await wrapper.setProps({
    nodes: [{ key: 'root', title: 'Root', children: [] }],
    branchStates: { root: { status: 'loaded', hasMore: false } },
  });
  expect(wrapper.text()).toContain('暂无子节点');
});
it('managed refresh, error retry and pagination have visible node feedback', async () => {
  let fail = true;
  const loader = vi.fn(async (_node, request) => {
    if (fail) throw new Error('加载失败');
    return request.reason === 'load-more'
      ? { mode: 'append' as const, nodes: [item('second')], hasMore: false }
      : { mode: 'replace' as const, nodes: [item('first')], hasMore: true, nextCursor: 'page2' };
  });
  const wrapper = tree({
    nodes: [{ key: 'root', title: 'Root', isLeaf: false }],
    loadChildren: loader,
    minLoadingDurationMs: 0,
    expandedKeys: ['root'],
  });
  await wrapper.vm.refreshNode('root');
  await flushPromises();
  expect(wrapper.text()).toContain('加载失败');
  fail = false;
  await wrapper.vm.retryNode('root');
  await flushPromises();
  expect(wrapper.text()).toContain('加载更多');
  await wrapper.vm.loadMore('root');
  await wrapper.vm.loadMore('root');
  await flushPromises();
  expect(loader).toHaveBeenCalledTimes(3);
  expect(wrapper.text()).not.toContain('加载更多');
});
it('reconciles cascade checkboxes when managed children arrive', async () => {
  const wrapper = tree({
    nodes: [{ key: 'root', title: 'Root', isLeaf: false }],
    expandedKeys: ['root'],
    checkedKeys: ['root'],
    checkable: true,
    minLoadingDurationMs: 0,
    loadChildren: async () => ({ mode: 'replace', nodes: [item('child')], hasMore: false }),
  });
  await flushPromises();
  expect(wrapper.findAll('.ant-tree-checkbox-checked')).toHaveLength(2);
});
it('retains uncontrolled selection and lets controlled owners reject a selection change', async () => {
  const wrapper = tree({ displayMode: 'flat' });
  await wrapper.get('[data-ui-tree-key="first"] .ui-record-explorer-item').trigger('click');
  expect(wrapper.get('[data-ui-tree-key="first"] .ui-record-explorer-item').classes()).toContain(
    'ui-record-explorer-item-selected',
  );
  await wrapper.setProps({ selectedKey: 'second' });
  await wrapper.get('[data-ui-tree-key="first"] .ui-record-explorer-item').trigger('click');
  expect(wrapper.get('[data-ui-tree-key="second"] .ui-record-explorer-item').classes()).toContain(
    'ui-record-explorer-item-selected',
  );
  await wrapper.setProps({ selectedKey: undefined });
  expect(wrapper.find('.ui-record-explorer-item-selected').exists()).toBe(false);
});
it('releases a successful empty branch on automatic collapse and can load it again', async () => {
  vi.useFakeTimers();
  try {
    const loader = vi.fn(async () => ({ mode: 'replace' as const, nodes: [], hasMore: false }));
    const wrapper = tree({
      nodes: [{ key: 'root', title: 'Root', isLeaf: false }],
      loadChildren: loader,
      reloadOnReexpand: true,
      minLoadingDurationMs: 0,
    });
    const renderer = wrapper.findComponent({ name: 'ATree' });
    renderer.vm.$emit('expand', ['root'], { expanded: true, node: { key: 'root' } });
    await flushPromises();
    expect(wrapper.text()).toContain('暂无子节点');
    await vi.advanceTimersByTimeAsync(200);
    expect(wrapper.emitted('update:expandedKeys')!.at(-1)).toEqual([[]]);
    renderer.vm.$emit('expand', ['root'], { expanded: true, node: { key: 'root' } });
    await flushPromises();
    expect(loader).toHaveBeenCalledTimes(2);
  } finally {
    vi.useRealTimers();
  }
});
it('navigates, expands and selects the focused tree row from the keyboard', async () => {
  const wrapper = tree({
    draggable: false,
    nodes: [{ key: 'parent', title: 'Parent', children: [item('child')] }],
  });
  const parent = wrapper.get('[data-ui-tree-key="parent"]');
  (parent.element as HTMLElement).focus();
  await parent.trigger('keydown', { key: 'ArrowRight' });
  await parent.trigger('keydown', { key: 'ArrowRight' });
  expect((document.activeElement as HTMLElement).dataset.uiTreeKey).toBe('child');
  await wrapper.get('[data-ui-tree-key="child"]').trigger('keydown', { key: 'Enter' });
  expect(wrapper.emitted('select')![0][0]).toMatchObject({ key: 'child' });
  await wrapper.get('[data-ui-tree-key="child"]').trigger('keydown', { key: 'ArrowLeft' });
  expect(document.activeElement).toBe(parent.element);
});
