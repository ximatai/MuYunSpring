import { nextTick, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { useManagedDetailRelationRuntime } from '@/dynamic-page-runtime/composables/useManagedDetailRelationRuntime.ts';
import type { ManagedDetailRelationClient } from '@muyun/web-core';
import type { ResolvedDetailRelationDescriptor, WebPageResponse } from '@muyun/web-contracts';

describe('managed detail relation runtime', () => {
  it('clears a stale query session when the parent changes and ignores its late response', async () => {
    const pending = deferred<WebPageResponse<Record<string, unknown>>>();
    const parentId = ref<string | undefined>('parent-a');
    const client = clientOf({ query: vi.fn(() => pending.promise) });
    const runtime = useManagedDetailRelationRuntime({
      relation: ref(relation()),
      parentId,
      parentPersisted: ref(true),
      parentDirty: ref(false),
      clientOf: () => client,
    });

    const query = runtime.query();
    expect(runtime.loading.value).toBe(true);
    parentId.value = 'parent-b';
    await nextTick();
    expect(runtime.loading.value).toBe(false);
    expect(runtime.records.value).toEqual([]);

    pending.resolve(page([{ id: 'stale' }]));
    await query;
    expect(runtime.records.value).toEqual([]);
  });

  it('serializes mutations and does not refresh a different parent session', async () => {
    const pending = deferred<{ record: Record<string, unknown> }>();
    const parentId = ref<string | undefined>('parent-a');
    const insert = vi.fn(() => pending.promise);
    const runtime = useManagedDetailRelationRuntime({
      relation: ref(relation()),
      parentId,
      parentPersisted: ref(true),
      parentDirty: ref(false),
      clientOf: () => clientOf({ insert }),
    });

    const first = runtime.create({ title: 'A' });
    await expect(runtime.create({ title: 'duplicate' })).rejects.toThrow('already running');
    parentId.value = 'parent-b';
    await nextTick();
    pending.resolve({ record: {} });
    await expect(first).resolves.toBe(false);
    expect(runtime.reloadKey.value).toBe(0);
    expect(runtime.saving.value).toBe(false);
  });

  it('fails closed for an unsaved or dirty parent without issuing HTTP', async () => {
    const client = clientOf({});
    const parentDirty = ref(true);
    const runtime = useManagedDetailRelationRuntime({
      relation: ref(relation()),
      parentId: ref('parent-a'),
      parentPersisted: ref(true),
      parentDirty,
      clientOf: () => client,
    });

    await expect(runtime.create({ title: 'blocked' })).rejects.toThrow('not allowed');
    expect(client.insert).not.toHaveBeenCalled();
    expect((await runtime.query()).records).toEqual([]);
    expect(client.query).not.toHaveBeenCalled();
  });

  it('fails closed for server-issued action permissions and sends delete version exactly once', async () => {
    const client = clientOf({});
    const denied = useManagedDetailRelationRuntime({
      relation: ref(relation()),
      parentId: ref('parent-a'),
      parentPersisted: ref(true),
      parentDirty: ref(false),
      can: () => false,
      clientOf: () => client,
    });

    expect((await denied.query()).records).toEqual([]);
    await expect(denied.create({ title: 'blocked' })).rejects.toThrow('not allowed');
    expect(client.query).not.toHaveBeenCalled();
    expect(client.insert).not.toHaveBeenCalled();

    const allowed = useManagedDetailRelationRuntime({
      relation: ref(relation()),
      parentId: ref('parent-a'),
      parentPersisted: ref(true),
      parentDirty: ref(false),
      can: () => true,
      clientOf: () => client,
    });
    await allowed.remove('child-1', 7);
    expect(client.delete).toHaveBeenCalledTimes(1);
    expect(client.delete).toHaveBeenCalledWith('child-1', { version: 7 });
  });

  it('keeps a current failed mutation retryable and suppresses stale query failures', async () => {
    const queryFailure = deferred<WebPageResponse<Record<string, unknown>>>();
    const parentId = ref<string | undefined>('parent-a');
    const client = clientOf({
      query: vi.fn(() => queryFailure.promise),
      insert: vi
        .fn()
        .mockRejectedValueOnce(new Error('save failed'))
        .mockResolvedValueOnce({ changed: true }),
    });
    const runtime = useManagedDetailRelationRuntime({
      relation: ref(relation()),
      parentId,
      parentPersisted: ref(true),
      parentDirty: ref(false),
      clientOf: () => client,
    });

    const staleQuery = runtime.query();
    parentId.value = 'parent-b';
    await nextTick();
    queryFailure.reject(new Error('stale query failed'));
    await expect(staleQuery).resolves.toMatchObject({ records: [] });

    await expect(runtime.create({ title: 'A' })).rejects.toThrow('save failed');
    expect(runtime.error.value).toBeInstanceOf(Error);
    expect(runtime.saving.value).toBe(false);
    await expect(runtime.create({ title: 'A' })).resolves.toBe(true);
    expect(runtime.reloadKey.value).toBe(1);
  });
});

function relation(): ResolvedDetailRelationDescriptor {
  return {
    code: 'properties',
    readOnly: false,
    sourceModuleAlias: 'platform.field_ui_control',
    sourceEntityAlias: 'field_ui_control',
    targetModuleAlias: 'platform.field_ui_control',
    targetEntityAlias: 'field_ui_control_property',
    parentBinding: 'fieldUiControlAlias',
    refreshOnDetailReload: true,
    queryContract: {
      managedGateway: true,
      actionCode: 'field_ui_control_property_query',
      pageable: true,
      queryable: false,
    },
    mutationContract: {
      createAllowed: true,
      updateAllowed: true,
      deleteAllowed: true,
      createActionCode: 'field_ui_control_property_create',
      updateActionCode: 'field_ui_control_property_update',
      deleteActionCode: 'field_ui_control_property_delete',
    },
  };
}

function clientOf(overrides: Partial<ManagedDetailRelationClient<Record<string, unknown>>>) {
  return {
    query: vi.fn(async () => page([])),
    insert: vi.fn(async () => ({ changed: true }) as never),
    update: vi.fn(async () => ({ changed: true }) as never),
    delete: vi.fn(async () => ({ changed: 1 }) as never),
    ...overrides,
  } as ManagedDetailRelationClient<Record<string, unknown>>;
}

function page(records: Record<string, unknown>[]): WebPageResponse<Record<string, unknown>> {
  return { records, total: records.length, pageNum: 1, pageSize: 20, pages: 1, totalKnown: true };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return { promise, resolve, reject };
}
