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

function createTreeContext(requests: Array<ReturnType<typeof deferredTreeResponse>>) {
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
    tree: () => {
      const request = deferredTreeResponse();
      requests.push(request);
      return request.promise;
    },
    treeFlat: async () => ({ records: [] }),
    subtree: async () => ({ records: [] }),
    sort: async () => 1,
  };
  return {
    moduleAlias: 'iam.organization',
    http: { request: async () => undefined as never },
    crud,
    runtime: { ready: Promise.resolve() },
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
