import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import {
  useRecordReadRefreshGuard,
  provideRecordReadRefreshGuard,
  useBusinessContentReading,
  type HttpClient,
} from '@muyun/web-core';
import { expect, it, vi } from 'vitest';
import {
  useModuleRecordDataChanges,
  moduleContentPresentationDependencies,
} from '@/dynamic-page-runtime/useModuleRecordDataChanges';
import type { ResolvedModuleUiDescriptor } from '@muyun/web-contracts';
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

it('defers content and reference presentation refreshes until nested maintenance closes', async () => {
  const maintaining = ref(true);
  const refreshList = vi.fn();
  const refreshRecord = vi.fn(async () => true);
  const child = defineComponent({
    setup() {
      useRecordReadRefreshGuard(() => maintaining.value);
      return () => null;
    },
  });
  const wrapper = mount(
    defineComponent({
      setup() {
        useModuleRecordDataChanges({
          moduleAlias: 'quality.product',
          presentationModules: () => ['quality.category'],
          recordId: () => 'product',
          blocked: () => false,
          invalidate: vi.fn(),
          refreshList,
          refreshRecord,
          onError: vi.fn(),
        });
        return () => h(child);
      },
    }),
  );
  await appDataChangeDispatcher.dispatch({
    changeSetId: 'reading-confirmed',
    changes: [{ type: 'business-content-changed', moduleAlias: 'quality.category' }],
  });
  await appDataChangeDispatcher.dispatch({
    changeSetId: 'unrelated-reading',
    changes: [{ type: 'business-content-changed', moduleAlias: 'quality.other' }],
  });
  await settle();
  expect(refreshList).not.toHaveBeenCalled();
  maintaining.value = false;
  await settle();
  expect(refreshList).toHaveBeenCalledOnce();
  expect(refreshRecord).toHaveBeenCalledOnce();
  wrapper.unmount();
});

it('uses read-only list and detail reference declarations as presentation dependencies', () => {
  const ui = {
    page: {
      list: { fields: { fields: [{ reference: { targetModuleAlias: 'quality.category' } }] } },
      detail: { display: { fields: [{ referenceSummary: { targetModuleAlias: 'quality.product' } }] } },
    },
    detailRelations: [{ targetModuleAlias: 'quality.inspection' }],
  } as unknown as ResolvedModuleUiDescriptor;
  expect(moduleContentPresentationDependencies(ui)).toEqual([
    'quality.category',
    'quality.product',
    'quality.inspection',
  ]);
});

it.each([false, true])(
  'refreshes an unchanged business snapshot from committed content facts (old request pending: %s)',
  async (pending) => {
    let current = 'Previous reading';
    const record = { id: 'record', version: 4, description: '原文' };
    let releaseOld!: (value: unknown) => void;
    const request = vi.fn(async () => {
      const result = {
        recordVersion: 4,
        language: 'en',
        fields: {
          description: {
            originalText: '原文',
            text: current,
            actualLanguage: 'en',
            source: null,
            translation: null,
            originalFallback: false,
          },
        },
      };
      if (pending && request.mock.calls.length === 1)
        return new Promise((resolve) => {
          releaseOld = () => resolve(result);
        });
      return result;
    });
    const http = { request } as unknown as HttpClient;
    let reader!: ReturnType<typeof useBusinessContentReading>;
    const child = defineComponent({
      setup() {
        reader = useBusinessContentReading(() => ({
          http,
          moduleAlias: 'quality.product',
          record,
          fieldName: 'description',
          content: { key: 'description', policy: 'MANUAL_TRANSLATIONS' },
          language: 'en',
          mode: 'view',
        }));
        return () => null;
      },
    });
    const controller = defineComponent({
      setup() {
        useModuleRecordDataChanges({
          moduleAlias: 'quality.product',
          recordId: () => record.id,
          blocked: () => false,
          invalidate: vi.fn(),
          refreshList: vi.fn(),
          refreshRecord: vi.fn(async () => true),
          onError: vi.fn(),
        });
        return () => null;
      },
    });
    const wrapper = mount(
      defineComponent({
        setup() {
          provideRecordReadRefreshGuard();
          return () => h('div', [h(controller), h(child)]);
        },
      }),
    );
    await settle();
    expect(reader.reading.value?.text).toBe(pending ? undefined : 'Previous reading');
    current = 'Confirmed reading';
    await appDataChangeDispatcher.dispatch({
      changeSetId: `same-version-reading-${pending}`,
      changes: [{ type: 'business-content-changed', moduleAlias: 'quality.product' }],
    });
    await settle();
    expect(request).toHaveBeenCalledTimes(2);
    if (pending) {
      releaseOld(undefined);
      await settle();
    }
    expect(reader.reading.value?.text).toBe('Confirmed reading');
    expect(record).toEqual({ id: 'record', version: 4, description: '原文' });
    wrapper.unmount();
  },
);
