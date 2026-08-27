import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';

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
