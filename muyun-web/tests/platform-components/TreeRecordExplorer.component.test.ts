import { flushPromises, mount } from '@vue/test-utils';
import { assert, it } from 'vitest';
import type { ModuleContext } from '@/web-core/index.ts';
import type { TreeRecordBase } from '@/platform-components/index.ts';
import TreeRecordExplorer from '@/platform-components/TreeRecordExplorer.vue';

it('ignores stale tree responses after the explorer reloads', async () => {
  const requests: Array<ReturnType<typeof deferredTreeResponse>> = [];
  const context = createTreeContext(requests);
  const wrapper = mount(TreeRecordExplorer, {
    props: { context, reloadKey: 0, searchMode: 'none' },
    global: {
      stubs: {
        UiSpin: { template: '<div class="spin" />' },
        UiEmpty: { template: '<div class="empty" />' },
        UiTree: { props: ['nodes'], template: '<div class="tree">{{ nodes[0]?.title }}</div>' },
      },
    },
  });

  await flushPromises();
  await wrapper.setProps({ reloadKey: 1 });
  await flushPromises();
  assert.equal(requests.length, 2);

  requests[1].resolve(treeResponse('tenant-b'));
  await flushPromises();
  requests[0].resolve(treeResponse('tenant-a'));
  await flushPromises();

  assert.equal(wrapper.find('.tree').text(), 'tenant-b');
  assert.deepEqual(wrapper.emitted('loaded'), [[[{ id: 'tenant-b', title: 'tenant-b' }]]]);
  wrapper.unmount();
});

it('passes upstream navigator criteria to the server-side tree query', async () => {
  const requests: Array<ReturnType<typeof deferredTreeResponse>> = [];
  const treeRequests: unknown[] = [];
  const context = createTreeContext(requests, treeRequests);
  const wrapper = mount(TreeRecordExplorer, {
    props: {
      context,
      searchMode: 'none',
      externalQueryValues: { tenantId: 'tenant-a' },
    },
    global: {
      stubs: {
        UiSpin: { template: '<div />' },
        UiEmpty: { template: '<div />' },
        UiTree: { template: '<div />' },
      },
    },
  });

  await flushPromises();
  assert.deepEqual(treeRequests, [{ externalQueryValues: { tenantId: 'tenant-a' } }]);
  requests[0].resolve(treeResponse('tenant-a'));
  await flushPromises();
  wrapper.unmount();
});

it('forwards the tree adapter deselect event to clear an externally owned selection', async () => {
  const requests: Array<ReturnType<typeof deferredTreeResponse>> = [];
  const wrapper = mount(TreeRecordExplorer, {
    props: { context: createTreeContext(requests), selectedId: 'rule-1', searchMode: 'none' },
    global: {
      stubs: {
        UiSpin: { template: '<div />' },
        UiEmpty: { template: '<div />' },
        UiTree: { name: 'UiTree', props: ['nodes'], template: '<div />' },
      },
    },
  });

  await flushPromises();
  requests[0].resolve(treeResponse('rule-1'));
  await flushPromises();
  wrapper.findComponent({ name: 'UiTree' }).vm.$emit('deselect');

  assert.deepEqual(wrapper.emitted('deselect'), [[]]);
  assert.isUndefined(wrapper.emitted('select'));
  wrapper.unmount();
});

it('persists same-parent vertical drops through the standard tree sort contract', async () => {
  const sortCalls: Array<{ id: string; request: unknown }> = [];
  const records = [
    { record: { id: 'first', title: 'first' }, children: [] },
    { record: { id: 'second', title: 'second' }, children: [] },
  ];
  const context = {
    moduleAlias: 'iam.organization',
    runtime: { ready: Promise.resolve(), snapshot: () => ({ sortPartitionFields: [] }) },
    abilities: {
      tree: () => ({
        tree: async () => ({ records }),
        sort: async (id: string, request: unknown) => sortCalls.push({ id, request }),
      }),
    },
  } as unknown as ModuleContext<TreeRecordBase>;
  const wrapper = mount(TreeRecordExplorer, {
    props: { context, sorting: true, searchMode: 'none' },
    global: {
      stubs: {
        UiSpin: { template: '<div />' },
        UiEmpty: { template: '<div />' },
        UiTree: {
          name: 'UiTree',
          props: ['nodes', 'draggable', 'canDrag', 'allowDrop'],
          template: '<div />',
        },
      },
    },
  });

  await flushPromises();
  const tree = wrapper.findComponent({ name: 'UiTree' });
  assert.isTrue(tree.props('draggable'));
  tree.vm.$emit('drop', {
    dragNode: { key: 'first', title: 'first' },
    dropNode: { key: 'second', title: 'second' },
    dropPosition: 1,
    dropToGap: true,
  });
  await flushPromises();

  assert.deepEqual(sortCalls, [
    { id: 'first', request: { previousId: 'second', nextId: null, parentId: null } },
  ]);
  assert.deepEqual(wrapper.emitted('sorted'), [[]]);
  wrapper.unmount();
});

it('rejects tree drops across a runtime-declared sort partition', async () => {
  const sortCalls: Array<{ id: string; request: unknown }> = [];
  const records = [
    { record: { id: 'first', title: 'first', scope: 'scope-a' }, children: [] },
    { record: { id: 'second', title: 'second', scope: 'scope-b' }, children: [] },
  ];
  const context = {
    moduleAlias: 'iam.organization',
    runtime: { ready: Promise.resolve(), snapshot: () => ({ sortPartitionFields: ['scope'] }) },
    abilities: {
      tree: () => ({
        tree: async () => ({ records }),
        sort: async (id: string, request: unknown) => sortCalls.push({ id, request }),
      }),
    },
  } as unknown as ModuleContext<TreeRecordBase>;
  const wrapper = mount(TreeRecordExplorer, {
    props: { context, sorting: true, searchMode: 'none' },
    global: {
      stubs: {
        UiSpin: { template: '<div />' },
        UiEmpty: { template: '<div />' },
        UiTree: { name: 'UiTree', template: '<div />' },
      },
    },
  });

  await flushPromises();
  wrapper.findComponent({ name: 'UiTree' }).vm.$emit('drop', {
    dragNode: { key: 'first' },
    dropNode: { key: 'second' },
    dropPosition: 1,
    dropToGap: true,
  });
  await flushPromises();

  assert.deepEqual(sortCalls, []);
  wrapper.unmount();
});

function createTreeContext(
  requests: Array<ReturnType<typeof deferredTreeResponse>>,
  treeRequests: unknown[] = [],
) {
  const crud = {
    querySchema: async () => ({ fields: [] }),
    query: async () => ({ records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true }),
    view: async () => ({ id: 'record-1' }),
    insert: async (record: TreeRecordBase) => ({ record }),
    update: async (_id: string, record: TreeRecordBase) => ({ record }),
    delete: async () => 1,
    enable: async () => 1,
    disable: async () => 1,
  };
  const tree = {
    ...crud,
    tree: (request?: unknown) => {
      treeRequests.push(request);
      const pending = deferredTreeResponse();
      requests.push(pending);
      return pending.promise;
    },
    treeFlat: async () => ({ records: [] }),
    subtree: async () => ({ records: [] }),
    sort: async () => 1,
  };
  return {
    moduleAlias: 'iam.organization',
    http: { request: async () => undefined as never },
    crud,
    runtime: { ready: Promise.resolve(), snapshot: () => ({ sortPartitionFields: [] }) },
    abilities: { tree: () => tree },
  } as unknown as ModuleContext<TreeRecordBase>;
}

function deferredTreeResponse() {
  let resolve!: (value: ReturnType<typeof treeResponse>) => void;
  return {
    promise: new Promise<ReturnType<typeof treeResponse>>((nextResolve) => {
      resolve = nextResolve;
    }),
    resolve,
  };
}

function treeResponse(title: string) {
  return { records: [{ record: { id: title, title }, children: [] }] };
}
