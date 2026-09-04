import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
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
  const wrapper = mount(UiTree, {
    props: {
      displayMode: 'flat',
      nodes: [{ key: 'group:one', title: '分组', children: [first, second] }],
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
  expect(wrapper.findAll('[data-ui-tree-key]')).toHaveLength(3);
  expect(wrapper.get('[data-ui-tree-key="item:first"] .item').text()).toBe('第一项');
  expect(wrapper.findAllComponents({ name: 'UiRecordExplorerItem' })[1]?.props('selected')).toBe(true);

  const items = wrapper.findAllComponents({ name: 'UiRecordExplorerItem' });
  items[1]!.vm.$emit('click');
  items[2]!.vm.$emit('action', action);
  expect(wrapper.emitted('deselect')).toEqual([[]]);
  expect(wrapper.emitted('action')).toEqual([[action, second]]);

  const checkboxes = wrapper.findAllComponents({ name: 'UiCheckbox' });
  await checkboxes[2]!.vm.$emit('update:checked', true);

  expect(wrapper.emitted('update:checkedKeys')).toEqual([[['item:first', 'item:second']]]);
  expect(wrapper.emitted('check')?.[0]?.[0]).toMatchObject({
    node: second,
    checked: true,
    checkedKeys: ['item:first', 'item:second'],
    halfCheckedKeys: [],
  });
});

it('keeps flat native drops and same-instance sorting on the shared tree contracts', () => {
  const first = { key: 'item:first', title: '第一项' };
  const second = { key: 'item:second', title: '第二项' };
  const allowExternalDrop = vi.fn(() => true);
  const wrapper = mount(UiTree, {
    props: {
      displayMode: 'flat',
      nodes: [first, second],
      draggable: true,
      allowDrop: () => true,
      allowExternalDrop,
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', template: '<div />' },
        UiRecordExplorerItem: { name: 'UiRecordExplorerItem', template: '<span />' },
      },
    },
    attachTo: document.body,
  });

  const source = wrapper.get('[data-ui-tree-key="item:second"]');
  const target = wrapper.get('[data-ui-tree-key="item:first"]');
  Object.defineProperty(target.element, 'getBoundingClientRect', {
    value: () => ({ top: 0, height: 100 }),
  });
  source.element.dispatchEvent(
    new MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 20, clientY: 80 }),
  );
  target.element.dispatchEvent(
    new MouseEvent('mousemove', { bubbles: true, buttons: 1, clientX: 20, clientY: 25 }),
  );
  target.element.dispatchEvent(
    new MouseEvent('mouseup', { bubbles: true, button: 0, clientX: 20, clientY: 25 }),
  );

  expect(wrapper.emitted('drop')?.[0]?.[0]).toMatchObject({
    dragNode: second,
    dropNode: first,
    dropPosition: -1,
    dropToGap: true,
  });

  const dataTransfer = {
    types: ['application/x-muyun-ui-tree'],
    getData: () => JSON.stringify({ field: 'title' }),
  } as unknown as DataTransfer;
  const nativeEvent = new Event('drop', { bubbles: true, cancelable: true }) as DragEvent;
  Object.assign(nativeEvent, { clientY: 50, dataTransfer });
  target.element.dispatchEvent(nativeEvent);

  expect(allowExternalDrop).toHaveBeenCalled();
  expect(wrapper.emitted('external-drop')?.[0]?.[0]).toMatchObject({
    dropNode: first,
    dropPosition: 0,
    dropToGap: false,
    payload: { field: 'title' },
    payloadType: 'application/x-muyun-ui-tree',
  });
  wrapper.unmount();
});

it('emits deselect when Ant Tree clears the selected keys after a second click', () => {
  const wrapper = mount(UiTree, {
    props: {
      nodes: [{ key: 'rule-1', title: '规则' }],
      selectedKey: 'rule-1',
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  wrapper.findComponent({ name: 'ATree' }).vm.$emit('select', []);

  expect(wrapper.emitted('deselect')).toEqual([[]]);
});

it('normalizes adapter drag events before exposing them to page composers', () => {
  const field = { key: 'field:title', title: '考试名称' };
  const slot = { key: 'slot:list', title: '列表' };
  const wrapper = mount(UiTree, {
    props: { nodes: [field, slot], draggable: true, nativeDragSource: true },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['draggable', 'allowDrop'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });
  const tree = wrapper.findComponent({ name: 'ATree' });
  tree.vm.$emit('dragstart', { node: { key: field.key, dataRef: field } });
  tree.vm.$emit('drop', {
    dragNode: { key: field.key, dataRef: field },
    node: { key: slot.key, dataRef: slot, pos: '0-1' },
    dropPosition: 1,
    dropToGap: false,
  });

  expect(wrapper.emitted('drag-start')).toEqual([[{ node: field, nativeEvent: undefined }]]);
  expect(wrapper.emitted('drop')).toEqual([
    [{ dragNode: field, dropNode: slot, dropPosition: 0, dropToGap: false, nativeEvent: undefined }],
  ]);
  expect(tree.props('draggable')).toBe(true);
  const allowDrop = tree.props('allowDrop') as (event: unknown) => boolean;
  expect(
    allowDrop({
      dragNode: { key: field.key, dataRef: field },
      node: { key: slot.key, dataRef: slot, pos: '0-1' },
      dropPosition: 1,
      dropToGap: false,
    }),
  ).toBe(true);
});

it('accepts a native payload dropped from another tree without relying on Ant Tree drag state', () => {
  const slot = { key: 'slot:list', title: '列表' };
  const wrapper = mount(UiTree, {
    props: {
      nodes: [slot],
      allowExternalDrop: () => true,
    },
    global: {
      stubs: {
        ATree: {
          name: 'ATree',
          props: ['treeData'],
          template: '<div><slot name="title" v-for="node in treeData" :key="node.key" v-bind="node" /></div>',
        },
        UiRecordExplorerItem: true,
      },
    },
  });

  const target = wrapper.get('[data-ui-tree-key="slot:list"]').element;
  Object.defineProperty(target, 'getBoundingClientRect', {
    value: () => ({ top: 0, height: 100 }),
  });
  const dataTransfer = { dropEffect: 'none' } as unknown as DataTransfer;
  const nativeEvent = new Event('drop', { bubbles: true, cancelable: true }) as DragEvent;
  Object.assign(nativeEvent, { clientY: 50, dataTransfer });
  target.dispatchEvent(nativeEvent);

  expect(nativeEvent.defaultPrevented).toBe(true);
  expect(wrapper.emitted('external-drop')).toEqual([
    [{ dropNode: slot, dropPosition: 0, dropToGap: false, nativeEvent }],
  ]);
});

it('normalizes a title-originated same-tree drop when the tree adapter does not emit it', async () => {
  const first = { key: 'item:first', title: '第一项' };
  const second = { key: 'item:second', title: '第二项' };
  const wrapper = mount(UiTree, {
    props: { nodes: [first, second], draggable: true, allowDrop: () => true },
    global: {
      stubs: {
        ATree: {
          name: 'ATree',
          props: ['treeData'],
          template: '<div><slot name="title" v-for="node in treeData" :key="node.key" v-bind="node" /></div>',
        },
        UiRecordExplorerItem: true,
      },
    },
  });

  const target = wrapper.get('[data-ui-tree-key="item:first"]');
  Object.defineProperty(target.element, 'getBoundingClientRect', {
    value: () => ({ top: 0, height: 100 }),
  });
  await wrapper.get('[data-ui-tree-key="item:second"]').trigger('dragstart');
  await target.trigger('dragover', { clientY: 25 });
  await target.trigger('drop', { clientY: 25 });

  expect(wrapper.emitted('drop')).toHaveLength(1);
  expect(wrapper.emitted('drop')?.[0]?.[0]).toMatchObject({
    dragNode: second,
    dropNode: first,
    dropPosition: -1,
    dropToGap: true,
  });
});

it('enables an explicit native title drag source for cross-tree payload producers', async () => {
  const item = { key: 'item:first', title: '第一项' };
  const wrapper = mount(UiTree, {
    props: { nodes: [item], draggable: true, nativeDragSource: true },
    global: {
      stubs: {
        ATree: {
          name: 'ATree',
          props: ['treeData'],
          template: '<div><slot name="title" v-for="node in treeData" :key="node.key" v-bind="node" /></div>',
        },
        UiRecordExplorerItem: true,
      },
    },
  });

  const nativeEvent = new Event('dragstart', { bubbles: true }) as DragEvent;
  await wrapper.get('[data-ui-tree-key="item:first"]').trigger('dragstart', { nativeEvent });

  expect(wrapper.get('[data-ui-tree-key="item:first"]').attributes('draggable')).toBe('true');
  expect(wrapper.emitted('drag-start')).toHaveLength(1);
  expect(wrapper.emitted('drag-start')?.[0]?.[0]).toMatchObject({ node: item });
  expect(wrapper.emitted('drag-start')?.[0]?.[0]).toHaveProperty('nativeEvent');
});

it('sorts through the platform mouse adapter when native tree drag events are unavailable', async () => {
  const first = { key: 'item:first', title: '第一项' };
  const second = { key: 'item:second', title: '第二项' };
  const wrapper = mount(UiTree, {
    props: { nodes: [first, second], draggable: true, allowDrop: () => true },
    global: {
      stubs: {
        ATree: {
          name: 'ATree',
          props: ['treeData', 'draggable'],
          template: '<div><slot name="title" v-for="node in treeData" :key="node.key" v-bind="node" /></div>',
        },
        UiRecordExplorerItem: true,
      },
    },
    attachTo: document.body,
  });

  const source = wrapper.get('[data-ui-tree-key="item:second"]');
  const target = wrapper.get('[data-ui-tree-key="item:first"]');
  Object.defineProperty(target.element, 'getBoundingClientRect', {
    value: () => ({ top: 0, height: 100 }),
  });
  source.element.dispatchEvent(
    new MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 20, clientY: 80 }),
  );
  target.element.dispatchEvent(
    new MouseEvent('mousemove', { bubbles: true, buttons: 1, clientX: 20, clientY: 25 }),
  );
  target.element.dispatchEvent(
    new MouseEvent('mouseup', { bubbles: true, button: 0, clientX: 20, clientY: 25 }),
  );

  expect(wrapper.emitted('drop')?.[0]?.[0]).toMatchObject({
    dragNode: second,
    dropNode: first,
    dropPosition: -1,
    dropToGap: true,
  });
});

it('does not duplicate a tree drop when the native renderer reports the gesture', () => {
  const first = { key: 'item:first', title: '第一项' };
  const second = { key: 'item:second', title: '第二项' };
  const wrapper = mount(UiTree, {
    props: { nodes: [first, second], draggable: true, allowDrop: () => true },
    global: {
      stubs: {
        ATree: {
          name: 'ATree',
          props: ['treeData', 'draggable'],
          template: '<div><slot name="title" v-for="node in treeData" :key="node.key" v-bind="node" /></div>',
        },
        UiRecordExplorerItem: true,
      },
    },
    attachTo: document.body,
  });

  const source = wrapper.get('[data-ui-tree-key="item:second"]');
  const target = wrapper.get('[data-ui-tree-key="item:first"]');
  Object.defineProperty(target.element, 'getBoundingClientRect', {
    value: () => ({ top: 0, height: 100 }),
  });
  source.element.dispatchEvent(
    new MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 20, clientY: 80 }),
  );
  target.element.dispatchEvent(
    new MouseEvent('mousemove', { bubbles: true, buttons: 1, clientX: 20, clientY: 25 }),
  );

  wrapper.findComponent({ name: 'ATree' }).vm.$emit('drop', {
    dragNode: { key: second.key, dataRef: second },
    node: { key: first.key, dataRef: first, pos: '0-0' },
    dropPosition: -1,
    dropToGap: true,
  });

  target.element.dispatchEvent(
    new MouseEvent('mouseup', { bubbles: true, button: 0, clientX: 20, clientY: 25 }),
  );

  expect(wrapper.emitted('drop')).toHaveLength(1);
  wrapper.unmount();
});

it('bridges a pointer drag to a non-tree drop target with its serialized payload', () => {
  const sourceNode = { key: 'item:source', title: '来源' };
  const wrapper = mount(UiTree, {
    props: {
      displayMode: 'flat',
      nodes: [sourceNode],
      draggable: true,
      dragPayloadOf: () => ({ kind: 'field', fieldId: 'field-title' }),
      dragPayloadType: 'application/x-muyun-page-composer',
    },
    global: { stubs: { ATree: { name: 'ATree', template: '<div />' }, UiRecordExplorerItem: true } },
    attachTo: document.body,
  });
  const externalTarget = document.createElement('div');
  document.body.append(externalTarget);
  const originalElementFromPoint = document.elementFromPoint;
  Object.defineProperty(document, 'elementFromPoint', {
    configurable: true,
    value: () => externalTarget,
  });
  let receivedPayload = '';
  externalTarget.addEventListener('drop', (event) => {
    receivedPayload = (event as DragEvent).dataTransfer?.getData('application/x-muyun-page-composer') ?? '';
  });

  const source = wrapper.get('[data-ui-tree-key="item:source"]');
  source.element.dispatchEvent(
    new MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 20, clientY: 80 }),
  );
  document.dispatchEvent(
    new MouseEvent('mousemove', { bubbles: true, buttons: 1, clientX: 20, clientY: 25 }),
  );
  document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, button: 0, clientX: 20, clientY: 25 }));

  expect(JSON.parse(receivedPayload)).toEqual({ kind: 'field', fieldId: 'field-title' });
  Object.defineProperty(document, 'elementFromPoint', {
    configurable: true,
    value: originalElementFromPoint,
  });
  externalTarget.remove();
  wrapper.unmount();
});

it('uses the external target contract when Ant Tree receives a drag from a sibling tree', () => {
  const slot = { key: 'slot:form', title: '详情' };
  const wrapper = mount(UiTree, {
    props: {
      nodes: [slot],
      draggable: true,
      allowDrop: () => false,
      allowExternalDrop: () => true,
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['allowDrop'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  const allowDrop = wrapper.findComponent({ name: 'ATree' }).props('allowDrop') as (
    event: unknown,
  ) => boolean;
  expect(
    allowDrop({
      // A sibling tree's adapter node is intentionally opaque to this tree.
      dragNode: { key: 'metadata:field:title', dataRef: { external: true } },
      node: { key: slot.key, dataRef: slot, pos: '0-1' },
      dropPosition: 1,
      dropToGap: false,
    }),
  ).toBe(true);

  wrapper.findComponent({ name: 'ATree' }).vm.$emit('drop', {
    dragNode: { key: 'metadata:field:title', dataRef: { external: true } },
    node: { key: slot.key, dataRef: slot, pos: '0-1' },
    dropPosition: 1,
    dropToGap: false,
  });
  expect(wrapper.emitted('external-drop')).toEqual([
    [{ dropNode: slot, dropPosition: 0, dropToGap: false, nativeEvent: undefined }],
  ]);
});

it('delegates lazy children loading to Ant Tree so its switcher shows the built-in loading spinner', async () => {
  const loadChildren = vi.fn().mockResolvedValue(undefined);
  const wrapper = mount(UiTree, {
    props: {
      nodes: [{ key: 'tenant-1', title: '演示租户', isLeaf: false }],
      loadChildren,
      minLoadingDurationMs: 0,
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['loadData', 'loadedKeys'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  const loadData = wrapper.findComponent({ name: 'ATree' }).props('loadData') as (node: {
    key: string;
  }) => Promise<void>;
  await loadData({ key: 'tenant-1' });

  expect(loadChildren).toHaveBeenCalledWith({ key: 'tenant-1', title: '演示租户', isLeaf: false });
});

it('supports managed replace and append lazy results without exposing renderer details', async () => {
  const root = { key: 'root', title: '根节点', isLeaf: false };
  const loadChildren = vi.fn(async (_node, request) => {
    expect(request.reason).toBe('expand');
    expect(request.requestId).toMatch(/^ui-tree-load-/);
    expect(request.signal).toBeInstanceOf(AbortSignal);
    return { mode: 'replace' as const, nodes: [{ key: 'child', title: '子节点' }] };
  });
  const wrapper = mount(UiTree, {
    props: { nodes: [root], loadChildren, minLoadingDurationMs: 0 },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['loadData', 'treeData'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  const tree = wrapper.findComponent({ name: 'ATree' });
  await (tree.props('loadData') as (node: { key: string }) => Promise<void>)({ key: root.key });

  expect(tree.props('treeData')).toEqual([
    { key: 'root', title: '根节点', isLeaf: false, children: [{ key: 'child', title: '子节点' }] },
  ]);
  expect(wrapper.emitted('load-request')?.[0]?.[0]).toMatchObject({ node: root, reason: 'expand' });
});

it('exposes refresh and load-more commands for multi-mode lazy data sources', async () => {
  const root = { key: 'root', title: '根节点', isLeaf: false };
  const loadChildren = vi.fn(async (_node, request) =>
    request.reason === 'refresh'
      ? { mode: 'replace' as const, nodes: [{ key: 'first', title: '第一页' }], nextCursor: 'cursor-1' }
      : { mode: 'append' as const, nodes: [{ key: 'second', title: '第二页' }], hasMore: false },
  );
  const wrapper = mount(UiTree, {
    props: { nodes: [root], loadChildren, minLoadingDurationMs: 0 },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['treeData'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  await wrapper.vm.refreshNode('root');
  await wrapper.vm.loadMore('root');

  expect(loadChildren.mock.calls.map(([, request]) => request.reason)).toEqual(['refresh', 'load-more']);
  expect(loadChildren.mock.calls[1]?.[1].cursor).toBe('cursor-1');
  expect(wrapper.findComponent({ name: 'ATree' }).props('treeData')).toEqual([
    {
      key: 'root',
      title: '根节点',
      isLeaf: false,
      children: [
        { key: 'first', title: '第一页' },
        { key: 'second', title: '第二页' },
      ],
    },
  ]);
});

it('supports controlled lazy loading by emitting a request while keeping node ownership upstream', async () => {
  const root = { key: 'root', title: '根节点', isLeaf: false };
  const wrapper = mount(UiTree, {
    props: { nodes: [root], loadStrategy: 'controlled', minLoadingDurationMs: 0 },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['loadData', 'treeData'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  const tree = wrapper.findComponent({ name: 'ATree' });
  expect(tree.props('loadData')).toBeTypeOf('function');
  await (tree.props('loadData') as (node: { key: string }) => Promise<void>)({ key: root.key });

  expect(wrapper.emitted('load-request')).toHaveLength(1);
  expect(wrapper.emitted('load-request')?.[0]?.[0]).toMatchObject({ node: root, reason: 'expand' });
  expect(tree.props('treeData')).toEqual([root]);
});

it('exposes refresh and load-more requests in controlled mode without a local loader', async () => {
  const root = { key: 'root', title: '根节点', isLeaf: false };
  const wrapper = mount(UiTree, {
    props: { nodes: [root], loadStrategy: 'controlled', minLoadingDurationMs: 0 },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['treeData'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  await wrapper.vm.refreshNode('root');
  await wrapper.vm.loadMore('root');

  expect(wrapper.emitted('load-request')?.map((entry) => (entry[0] as { reason: string }).reason)).toEqual([
    'refresh',
    'load-more',
  ]);
  expect(wrapper.emitted('load-request')?.[1]?.[0]).toMatchObject({ node: root, reason: 'load-more' });
});

it('does not auto-collapse an empty branch while controlled data is still owned upstream', async () => {
  vi.useFakeTimers();
  const root = { key: 'root', title: '根节点', isLeaf: false, children: [] };
  const wrapper = mount(UiTree, {
    props: {
      nodes: [root],
      expandedKeys: [root.key],
      loadStrategy: 'controlled',
      minLoadingDurationMs: 300,
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['loadData'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  const loadData = wrapper.findComponent({ name: 'ATree' }).props('loadData') as (node: {
    key: string;
  }) => Promise<void>;
  const loading = loadData({ key: root.key });
  await vi.advanceTimersByTimeAsync(300);
  await loading;
  await vi.advanceTimersByTimeAsync(180);

  expect(wrapper.emitted('update:expandedKeys')).toBeUndefined();
  vi.useRealTimers();
});

it('keeps the lazy-loading spinner pending for the configured minimum duration', async () => {
  vi.useFakeTimers();
  const loadChildren = vi.fn().mockResolvedValue(undefined);
  const wrapper = mount(UiTree, {
    props: {
      nodes: [{ key: 'tenant-1', title: '演示租户', isLeaf: false }],
      loadChildren,
      minLoadingDurationMs: 300,
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['loadData', 'loadedKeys'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  const loadData = wrapper.findComponent({ name: 'ATree' }).props('loadData') as (node: {
    key: string;
  }) => Promise<void>;
  let settled = false;
  const loading = loadData({ key: 'tenant-1' }).then(() => {
    settled = true;
  });

  await vi.advanceTimersByTimeAsync(299);
  expect(settled).toBe(false);
  await vi.advanceTimersByTimeAsync(1);
  await loading;
  expect(settled).toBe(true);
  vi.useRealTimers();
});

it('collapses and reloads an empty lazy branch after its expand transition by default', async () => {
  vi.useFakeTimers();
  const node = { key: 'tenant-1', title: '空租户', isLeaf: false, children: undefined as [] | undefined };
  const loadChildren = vi.fn(async () => {
    node.children = [];
  });
  const wrapper = mount(UiTree, {
    props: {
      nodes: [node],
      expandedKeys: ['tenant-1'],
      minLoadingDurationMs: 0,
      loadChildren,
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['loadData', 'loadedKeys'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  const loadData = wrapper.findComponent({ name: 'ATree' }).props('loadData') as (node: {
    key: string;
  }) => Promise<void>;
  const loading = loadData({ key: 'tenant-1' });
  await vi.runAllTimersAsync();
  await loading;

  expect(wrapper.emitted('update:expandedKeys')?.at(-1)).toEqual([[]]);
  expect(wrapper.findComponent({ name: 'ATree' }).props('loadedKeys')).toEqual([]);
  await loadData({ key: 'tenant-1' });
  expect(loadChildren).toHaveBeenCalledTimes(2);
  vi.useRealTimers();
});

it('keeps a manually re-expanded empty branch open by cancelling its pending auto-collapse', async () => {
  vi.useFakeTimers();
  const node = { key: 'tenant-1', title: '空租户', isLeaf: false, children: undefined as [] | undefined };
  const wrapper = mount(UiTree, {
    props: {
      nodes: [node],
      expandedKeys: ['tenant-1'],
      minLoadingDurationMs: 0,
      loadChildren: async () => {
        node.children = [];
      },
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['loadData'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  const tree = wrapper.findComponent({ name: 'ATree' });
  const loadData = tree.props('loadData') as (node: { key: string }) => Promise<void>;
  const loading = loadData({ key: 'tenant-1' });
  await vi.advanceTimersByTimeAsync(0);
  tree.vm.$emit('expand', ['tenant-1'], { expanded: true, node: { key: 'tenant-1' } });
  await vi.runAllTimersAsync();
  await loading;

  expect(wrapper.emitted('update:expandedKeys')).toEqual([[['tenant-1']]]);
  vi.useRealTimers();
});

it('releases an explicitly collapsed lazy branch when reloading on re-expand is requested', async () => {
  vi.useFakeTimers();
  const node = { key: 'tenant-1', title: '演示租户', isLeaf: false, children: [] };
  const wrapper = mount(UiTree, {
    props: { nodes: [node], reloadOnReexpand: true },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['loadedKeys'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  wrapper
    .findComponent({ name: 'ATree' })
    .vm.$emit('expand', [], { expanded: false, node: { key: 'tenant-1' } });
  await vi.advanceTimersByTimeAsync(180);

  expect(wrapper.emitted('unload-children')).toEqual([[node]]);
  expect(wrapper.findComponent({ name: 'ATree' }).props('loadedKeys')).toEqual([]);
  vi.useRealTimers();
});

it('does not mark a branch loaded when it is collapsed before its lazy request settles', async () => {
  vi.useFakeTimers();
  let resolveLoad: (() => void) | undefined;
  const loadChildren = vi.fn(
    () =>
      new Promise<void>((resolve) => {
        resolveLoad = resolve;
      }),
  );
  const wrapper = mount(UiTree, {
    props: {
      nodes: [{ key: 'tenant-1', title: '演示租户', isLeaf: false }],
      expandedKeys: ['tenant-1'],
      reloadOnReexpand: true,
      minLoadingDurationMs: 0,
      loadChildren,
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', props: ['loadData', 'loadedKeys'], template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  const tree = wrapper.findComponent({ name: 'ATree' });
  const loadData = tree.props('loadData') as (node: { key: string }) => Promise<void>;
  const loading = loadData({ key: 'tenant-1' });
  tree.vm.$emit('expand', [], { expanded: false, node: { key: 'tenant-1' } });
  await wrapper.setProps({ expandedKeys: [] });
  await vi.advanceTimersByTimeAsync(180);
  resolveLoad?.();
  await loading;

  expect(loadChildren).toHaveBeenCalledTimes(1);
  expect(tree.props('loadedKeys')).toEqual([]);
  vi.useRealTimers();
});
