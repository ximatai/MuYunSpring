import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { useModuleRecordDataChanges } from '@/dynamic-page-runtime/useModuleRecordDataChanges';
import { appDataChangeDispatcher } from '@/platform-admin-runtime/realtime';

const settle = async () => {
  await flushPromises();
  await new Promise((resolve) => setTimeout(resolve, 5));
  await flushPromises();
};
const change = (id: string, recordId = 'r', moduleAlias = 'test') => ({
  changeSetId: id,
  changes: [{ type: 'record-updated', moduleAlias, recordId }],
});

it('refreshes sibling read views once, defers editing and busy views, filters other modules and releases subscriptions', async () => {
  const editing = ref(false);
  const busy = ref(false);
  const recordId = ref('r');
  const refreshList = vi.fn();
  const refreshRecord = vi.fn(async () => true);
  const invalidate = vi.fn();
  const sibling = vi.fn(async () => true);
  function harness(blocked: () => boolean, refresh: typeof refreshRecord) {
    return mount(
      defineComponent({
        setup() {
          useModuleRecordDataChanges({
            moduleAlias: 'test',
            recordId: () => recordId.value,
            blocked,
            invalidate,
            refreshList,
            refreshRecord: refresh,
            onError: vi.fn(),
          });
          return () => null;
        },
      }),
    );
  }
  const first = harness(() => editing.value || busy.value, refreshRecord);
  const second = harness(() => false, sibling);
  editing.value = true;
  await appDataChangeDispatcher.dispatch(change('draft-protected'));
  await appDataChangeDispatcher.dispatch(change('draft-protected'));
  await appDataChangeDispatcher.dispatch(change('foreign', 'r', 'another'));
  await settle();
  expect(refreshRecord).not.toHaveBeenCalled();
  expect(sibling).toHaveBeenCalledOnce();
  expect(invalidate).toHaveBeenCalledWith(['r']);
  busy.value = true;
  editing.value = false;
  await settle();
  expect(refreshRecord).not.toHaveBeenCalled();
  busy.value = false;
  await settle();
  expect(refreshRecord).toHaveBeenCalledOnce();
  await appDataChangeDispatcher.dispatch(change('different-record', 'other'));
  await settle();
  expect(refreshRecord).toHaveBeenCalledOnce();
  editing.value = true;
  await appDataChangeDispatcher.dispatch(change('pending-unmount'));
  first.unmount();
  second.unmount();
  editing.value = false;
  await appDataChangeDispatcher.dispatch(change('after-unmount'));
  await settle();
  expect(refreshRecord).toHaveBeenCalledOnce();
});

it('keeps a pending refresh when the user begins editing during authorization, then refreshes after leaving', async () => {
  const editing = ref(false);
  let finish!: (value: boolean) => void;
  const refreshed = vi.fn();
  const refreshRecord = vi.fn(async (_id: string, isCurrent: () => boolean) => {
    if (refreshRecord.mock.calls.length === 1) {
      await new Promise<boolean>((resolve) => {
        finish = resolve;
      });
    }
    if (!isCurrent()) return false;
    refreshed();
    return true;
  });
  mount(
    defineComponent({
      setup() {
        useModuleRecordDataChanges({
          moduleAlias: 'test',
          recordId: () => 'r',
          blocked: () => editing.value,
          invalidate: vi.fn(),
          refreshList: vi.fn(),
          refreshRecord,
          onError: vi.fn(),
        });
        return () => null;
      },
    }),
  );
  await appDataChangeDispatcher.dispatch(change('authorize-race'));
  await settle();
  editing.value = true;
  finish(true);
  await settle();
  expect(refreshed).not.toHaveBeenCalled();
  editing.value = false;
  await settle();
  expect(refreshed).toHaveBeenCalledOnce();
});

it('invalidates and refreshes owning parent details for managed child resource changes', async () => {
  const invalidate = vi.fn();
  const refreshRecord = vi.fn(async () => true);
  const wrapper = mount(
    defineComponent({
      setup() {
        useModuleRecordDataChanges({
          moduleAlias: 'test',
          recordId: () => 'parent',
          blocked: () => false,
          invalidate,
          refreshList: vi.fn(),
          refreshRecord,
          onError: vi.fn(),
        });
        return () => null;
      },
    }),
  );
  await appDataChangeDispatcher.dispatch({
    changeSetId: 'resource-owner',
    changes: [
      {
        type: 'record-updated',
        moduleAlias: 'test',
        recordId: 'child',
        resourceKey: 'properties',
        scope: 'parent',
      },
    ],
  });
  await settle();
  expect(invalidate).toHaveBeenCalledWith(['child', 'parent']);
  expect(refreshRecord).toHaveBeenCalledWith('parent', expect.any(Function));
  wrapper.unmount();
});
