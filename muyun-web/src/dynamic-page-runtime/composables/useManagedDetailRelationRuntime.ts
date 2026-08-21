import { computed, ref, watch, type Ref } from 'vue';
import type { ManagedDetailRelationClient } from '@muyun/web-core';
import type {
  ResolvedDetailRelationDescriptor,
  WebPageResponse,
  WebQueryRequest,
} from '@muyun/web-contracts';

export interface ManagedDetailRelationRuntimeOptions<TRecord extends Record<string, unknown>> {
  relation: Ref<ResolvedDetailRelationDescriptor | undefined>;
  parentId: Ref<string | undefined>;
  parentPersisted: Ref<boolean>;
  mutationEnabled: Ref<boolean>;
  can?: (actionCode: string) => boolean;
  clientOf(parentId: string, relationCode: string): ManagedDetailRelationClient<TRecord>;
}

/**
 * Source-neutral association session. List rendering stays in RecordQueryListPanel; this
 * collaborator owns fixed-gateway transport, mutation serialization and cross-parent isolation.
 */
export function useManagedDetailRelationRuntime<TRecord extends Record<string, unknown>>(
  options: ManagedDetailRelationRuntimeOptions<TRecord>,
) {
  const records = ref<TRecord[]>([]) as Ref<TRecord[]>;
  const loading = ref(false);
  const saving = ref(false);
  const error = ref<unknown>();
  const reloadKey = ref(0);
  let querySequence = 0;
  let mutationSequence = 0;

  const executable = computed(() => {
    const relation = options.relation.value;
    const actionCode = relation?.queryContract?.actionCode;
    return relation?.queryContract?.managedGateway === true &&
      typeof actionCode === 'string' &&
      actionCode.length > 0 &&
      options.can?.(actionCode) !== false &&
      options.parentPersisted.value
      ? relation
      : undefined;
  });
  const mutable = computed(() =>
    options.mutationEnabled.value ? executable.value?.mutationContract : undefined,
  );
  const sessionIdentity = computed(
    () =>
      `${options.parentId.value ?? ''}:${executable.value?.code ?? ''}:${options.mutationEnabled.value ? 'mutable' : 'readonly'}`,
  );
  const busy = computed(() => loading.value || saving.value);

  function requireClient() {
    const relation = executable.value;
    const parentId = options.parentId.value;
    if (!relation || !parentId)
      throw new Error('managed detail relation is not executable for the current parent');
    return options.clientOf(parentId, relation.code);
  }

  async function query(request?: WebQueryRequest): Promise<WebPageResponse<TRecord>> {
    const token = ++querySequence;
    const session = sessionIdentity.value;
    if (!executable.value) {
      records.value = [];
      return {
        records: [],
        total: 0,
        pageNum: 1,
        pageSize: request?.page?.pageSize ?? 20,
        pages: 0,
        totalKnown: true,
      };
    }
    loading.value = true;
    error.value = undefined;
    try {
      const response = await requireClient().query(request);
      if (token === querySequence && session === sessionIdentity.value)
        records.value = response.records ?? [];
      return response;
    } catch (cause) {
      if (token === querySequence && session === sessionIdentity.value) {
        error.value = cause;
        throw cause;
      }
      return {
        records: [],
        total: 0,
        pageNum: 1,
        pageSize: request?.page?.pageSize ?? 20,
        pages: 0,
        totalKnown: true,
      };
    } finally {
      if (token === querySequence && session === sessionIdentity.value) loading.value = false;
    }
  }

  function refresh() {
    reloadKey.value += 1;
  }

  async function create(record: TRecord) {
    const actionCode = mutable.value?.createActionCode;
    if (!mutable.value?.createAllowed || !actionCode || options.can?.(actionCode) === false) {
      throw new Error('managed detail relation create is not allowed');
    }
    return mutate((client) => client.insert(record));
  }

  async function update(id: string, record: TRecord) {
    const actionCode = mutable.value?.updateActionCode;
    if (!mutable.value?.updateAllowed || !actionCode || options.can?.(actionCode) === false) {
      throw new Error('managed detail relation update is not allowed');
    }
    return mutate((client) => client.update(id, record));
  }

  async function remove(id: string, version: number) {
    const actionCode = mutable.value?.deleteActionCode;
    if (!mutable.value?.deleteAllowed || !actionCode || options.can?.(actionCode) === false) {
      throw new Error('managed detail relation delete is not allowed');
    }
    return mutate((client) => client.delete(id, { version }));
  }

  async function mutate(action: (client: ManagedDetailRelationClient<TRecord>) => Promise<unknown>) {
    if (busy.value) throw new Error('managed detail relation operation is already running');
    const token = ++mutationSequence;
    const session = sessionIdentity.value;
    const client = requireClient();
    saving.value = true;
    error.value = undefined;
    try {
      await action(client);
      if (token === mutationSequence && session === sessionIdentity.value) {
        refresh();
        return true;
      }
      return false;
    } catch (cause) {
      if (token === mutationSequence && session === sessionIdentity.value) {
        error.value = cause;
        throw cause;
      }
      return false;
    } finally {
      if (token === mutationSequence && session === sessionIdentity.value) saving.value = false;
    }
  }

  watch(sessionIdentity, () => {
    querySequence += 1;
    mutationSequence += 1;
    records.value = [];
    loading.value = false;
    saving.value = false;
    error.value = undefined;
  });

  return {
    records,
    loading,
    saving,
    busy,
    error,
    reloadKey,
    executable,
    mutable,
    query,
    refresh,
    create,
    update,
    remove,
  };
}
