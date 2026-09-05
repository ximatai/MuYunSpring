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
    source: { instanceId: 'tree', node: { key: 'first', title: 'first' }, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: { key: 'second', title: 'second' },
      position: 'after' as const,
    },
    operation: 'move' as const,
  });
  await flushPromises();

  assert.deepEqual(sortCalls, [
    { id: 'first', request: { previousId: 'second', nextId: null, parentId: 'root' } },
  ]);
  assert.deepEqual(wrapper.emitted('sorted'), [[]]);
  wrapper.unmount();
});

it('rejects inside drops for business-disallowed tree parents', async () => {
  const records = [
    { record: { id: 'folder', title: 'folder', categoryKind: 'folder' }, children: [] },
    { record: { id: 'dictionary', title: 'dictionary', categoryKind: 'dictionary' }, children: [] },
    { record: { id: 'moving', title: 'moving', categoryKind: 'folder' }, children: [] },
  ];
  const context = {
    moduleAlias: 'platform.dictionary_category',
    runtime: { ready: Promise.resolve(), snapshot: () => ({ sortPartitionFields: [] }) },
    abilities: {
      tree: () => ({
        tree: async () => ({ records }),
        sort: async () => 1,
      }),
    },
  } as unknown as ModuleContext<TreeRecordBase>;
  const wrapper = mount(TreeRecordExplorer, {
    props: {
      context,
      sorting: true,
      searchMode: 'none',
      canDropInside: (record) => record.categoryKind === 'folder',
    },
    global: {
      stubs: {
        UiSpin: { template: '<div />' },
        UiEmpty: { template: '<div />' },
        UiTree: { name: 'UiTree', props: ['allowDrop'], template: '<div />' },
      },
    },
  });

  await flushPromises();
  const allowDrop = wrapper.findComponent({ name: 'UiTree' }).props('allowDrop') as (
    event: unknown,
  ) => boolean;
  const event = (target: string, position: 'inside' | 'before') => ({
    source: { instanceId: 'tree', node: { key: 'moving' }, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: { key: target },
      position,
    },
    operation: 'move' as const,
  });

  assert.isFalse(allowDrop(event('dictionary', 'inside')));
  assert.isTrue(allowDrop(event('folder', 'inside')));
  assert.isTrue(allowDrop(event('dictionary', 'before')));
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
    source: { instanceId: 'tree', node: { key: 'first' }, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: { key: 'second' },
      position: 'after' as const,
    },
    operation: 'move' as const,
  });
  await flushPromises();

  assert.deepEqual(sortCalls, []);
  wrapper.unmount();
});

it('uses resource sort partition metadata when the host runtime belongs to another entity', async () => {
  const sortCalls: Array<{ id: string; request: unknown }> = [];
  const records = [
    {
      record: { id: 'first', title: 'first', applicationAlias: 'platform', categoryId: 'category-1' },
      children: [],
    },
    {
      record: { id: 'second', title: 'second', applicationAlias: 'platform', categoryId: 'category-1' },
      children: [],
    },
  ];
  const context = {
    moduleAlias: 'platform.dictionary_category',
    runtime: { ready: Promise.resolve(), snapshot: () => ({ sortPartitionFields: ['applicationAlias'] }) },
    abilities: {
      tree: () => ({
        tree: async () => ({ records }),
        sort: async (id: string, request: unknown) => sortCalls.push({ id, request }),
      }),
    },
  } as unknown as ModuleContext<TreeRecordBase>;
  const wrapper = mount(TreeRecordExplorer, {
    props: { context, sorting: true, sortPartitionFields: ['categoryId'], searchMode: 'none' },
    global: {
      stubs: {
        UiSpin: true,
        UiEmpty: true,
        UiTree: { name: 'UiTree', props: ['allowDrop'], template: '<div />' },
      },
    },
  });

  await flushPromises();
  const tree = wrapper.findComponent({ name: 'UiTree' });
  const event = {
    source: { instanceId: 'tree', node: { key: 'second' }, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: { key: 'first' },
      position: 'before' as const,
    },
    operation: 'move' as const,
  };
  assert.isTrue((tree.props('allowDrop') as (value: unknown) => boolean)(event));
  tree.vm.$emit('drop', event);
  await flushPromises();

  assert.deepEqual(sortCalls, [
    { id: 'second', request: { previousId: null, nextId: 'first', parentId: 'root' } },
  ]);
  wrapper.unmount();
});

it('chooses tree sort neighbors only within the runtime partition', async () => {
  const sortCalls: Array<{ id: string; request: unknown }> = [];
  const records = ['first', 'foreign', 'second', 'last'].map((id) => ({
    record: { id, title: id, scope: id === 'foreign' ? 'b' : 'a' },
    children: [],
  }));
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
    global: { stubs: { UiSpin: true, UiEmpty: true, UiTree: { name: 'UiTree', template: '<div />' } } },
  });
  await flushPromises();
  wrapper.findComponent({ name: 'UiTree' }).vm.$emit('drop', {
    source: { instanceId: 'tree', node: { key: 'last' }, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: { key: 'second' },
      position: 'before' as const,
    },
    operation: 'move' as const,
  });
  await flushPromises();
  assert.deepEqual(sortCalls, [
    { id: 'last', request: { previousId: 'first', nextId: 'second', parentId: 'root' } },
  ]);
  wrapper.unmount();
});

it('does not persist a same-parent drop that leaves the tree order unchanged', async () => {
  const sortCalls: unknown[] = [];
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
        sort: async (...args: unknown[]) => sortCalls.push(args),
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
    source: { instanceId: 'tree', node: { key: 'first' }, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: { key: 'second' },
      position: 'before' as const,
    },
    operation: 'move' as const,
  });
  await flushPromises();

  assert.deepEqual(sortCalls, []);
  assert.isUndefined(wrapper.emitted('sorted'));
  wrapper.unmount();
});

it('persists module sibling moves with the parent and correct boundary neighbors', async () => {
  const sortCalls: Array<{ id: string; request: unknown }> = [];
  const records = [
    {
      record: { id: 'platform-application', title: '平台应用' },
      children: [
        { record: { id: 'module-first', title: '第一个模块' }, children: [] },
        { record: { id: 'module-last', title: '最后一个模块' }, children: [] },
      ],
    },
  ];
  const context = {
    moduleAlias: 'platform.module',
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
  const allowDrop = tree.props('allowDrop') as (event: unknown) => boolean;
  const event = (overrides: Record<string, unknown> = {}) => ({
    source: { instanceId: 'tree', node: { key: 'module-last' }, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: { key: 'module-first' },
      position: 'before' as const,
    },
    operation: 'move' as const,
    ...overrides,
  });

  assert.isTrue(allowDrop(event()));
  assert.isTrue(
    allowDrop(
      event({
        target: { kind: 'node', instanceId: 'tree', node: { key: 'module-first' }, position: 'inside' },
      }),
    ),
  );
  assert.isFalse(
    allowDrop(
      event({
        target: { kind: 'node', instanceId: 'tree', node: { key: 'module-last' }, position: 'inside' },
      }),
    ),
  );
  assert.isTrue(
    allowDrop(
      event({
        target: {
          kind: 'node',
          instanceId: 'tree',
          node: { key: 'platform-application' },
          position: 'before',
        },
      }),
    ),
  );

  tree.vm.$emit('drop', event());
  await flushPromises();

  assert.deepEqual(sortCalls, [
    {
      id: 'module-last',
      request: { previousId: null, nextId: 'module-first', parentId: 'platform-application' },
    },
  ]);
  assert.deepEqual(wrapper.emitted('sorted'), [[]]);
  wrapper.unmount();
});

it('disables tree sorting while the tree is filtered', async () => {
  const requests: Array<ReturnType<typeof deferredTreeResponse>> = [];
  const wrapper = mount(TreeRecordExplorer, {
    props: { context: createTreeContext(requests), sorting: true, keyword: '匹配', searchMode: 'none' },
    global: {
      stubs: {
        UiSpin: { template: '<div />' },
        UiEmpty: { template: '<div />' },
        UiTree: {
          name: 'UiTree',
          props: ['nodes', 'draggable', 'allowDrop'],
          template: '<div />',
        },
      },
    },
  });

  await flushPromises();
  requests[0].resolve({
    records: [
      { record: { id: 'first', title: '匹配节点' }, children: [] },
      { record: { id: 'second', title: '其他节点' }, children: [] },
    ],
  });
  await flushPromises();

  const tree = wrapper.findComponent({ name: 'UiTree' });
  assert.isFalse(tree.props('draggable'));
  const allowDrop = tree.props('allowDrop') as (event: unknown) => boolean;
  assert.isFalse(
    allowDrop({
      source: { instanceId: 'tree', node: { key: 'first' }, operations: ['move'] as const },
      target: {
        instanceId: 'tree',
        kind: 'node' as const,
        node: { key: 'first' },
        position: 'after' as const,
      },
      operation: 'move' as const,
    }),
  );
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

it('aligns parent changes, root placement and cycle guards with the tree move protocol', async () => {
  const calls: unknown[] = [];
  type Node = { record: { id: string; title: string; parentId: string; tenantId: string }; children: Node[] };
  const node = (id: string, parentId: string, tenantId = 'a'): Node => ({
    record: { id, title: id, parentId, tenantId },
    children: [],
  });
  const first = node('first', 'root');
  const child = node('child', 'first');
  const grandchild = node('grandchild', 'child');
  child.children.push(grandchild);
  first.children.push(child);
  const second = node('second', 'root');
  const foreign = node('foreign', 'root', 'b');
  const context = {
    runtime: {
      ready: Promise.resolve(),
      snapshot: () => ({ sortPartitionFields: ['parentId', 'tenantId'] }),
    },
    abilities: {
      tree: () => ({
        tree: async () => ({ records: [first, second, foreign] }),
        sort: async (id: string, request: unknown) => {
          calls.push({ id, request });
        },
      }),
    },
  } as unknown as ModuleContext<TreeRecordBase>;
  const wrapper = mount(TreeRecordExplorer, {
    props: { context, sorting: true, searchMode: 'none' },
    global: {
      stubs: {
        UiSpin: true,
        UiEmpty: true,
        UiTree: {
          name: 'UiTree',
          props: ['nodes', 'allowDrop'],
          template: '<div />',
        },
      },
    },
  });
  await flushPromises();
  const tree = wrapper.findComponent({ name: 'UiTree' });
  const drop = (source: string, target: string, position: -1 | 0 | 1) => ({
    source: { instanceId: 'tree', node: { key: source }, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: { key: target },
      position: (position === 0 ? 'inside' : position < 0 ? 'before' : 'after') as
        | 'inside'
        | 'before'
        | 'after',
    },
    operation: 'move' as const,
  });
  assert.isTrue(tree.props('nodes')[1].isLeaf);
  for (const event of [
    drop('first', 'first', 0),
    drop('first', 'grandchild', 0),
    drop('first', 'grandchild', 1),
    drop('child', 'foreign', 0),
  ]) {
    assert.isFalse(tree.props('allowDrop')(event));
    wrapper.findComponent({ name: 'UiTree' }).vm.$emit('drop', event);
    await flushPromises();
  }
  assert.deepEqual(calls, []);
  for (const event of [
    drop('child', 'second', 0),
    drop('child', 'second', -1),
    drop('second', 'child', -1),
    drop('second', 'first', 0),
  ]) {
    assert.isTrue(tree.props('allowDrop')(event));
    wrapper.findComponent({ name: 'UiTree' }).vm.$emit('drop', event);
    await flushPromises();
  }
  assert.deepEqual(calls, [
    { id: 'child', request: { previousId: null, nextId: null, parentId: 'second' } },
    { id: 'child', request: { previousId: 'first', nextId: 'second', parentId: 'root' } },
    { id: 'second', request: { previousId: null, nextId: 'child', parentId: 'first' } },
    { id: 'second', request: { previousId: 'child', nextId: null, parentId: 'first' } },
  ]);
  await wrapper.setProps({ keyword: 'child' });
  assert.isFalse(tree.props('allowDrop')(drop('child', 'second', 0)));
  wrapper.unmount();
});
