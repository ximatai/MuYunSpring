import { computed, onScopeDispose, ref, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue';
import type { WebPageResponse } from '@muyun/web-contracts';
import type { RecordReadQuery, RecordReadView } from './recordReadView';

/** One mounted view session; no cross-view or cross-principal cache of authorized results. */
export function useRecordReadSession<TRecord extends Record<string, unknown>>(options: {
  view: MaybeRefOrGetter<RecordReadView<TRecord>>;
  query?: MaybeRefOrGetter<RecordReadQuery | undefined>;
  pageSize?: number;
  contentLanguage?: MaybeRefOrGetter<string | undefined>;
}) {
  const result = shallowRef<WebPageResponse<TRecord>>();
  const loading = ref(false);
  const error = shallowRef<unknown>();
  const pageNum = ref(1);
  const pageSize = ref(positiveInteger(options.pageSize ?? 20));
  const quickSearch = ref('');
  const selected = shallowRef<TRecord>();
  const detail = shallowRef<Record<string, unknown>>();
  const detailLoading = ref(false);
  const detailError = shallowRef<unknown>();
  let disposed = false;
  let activeIdentity: string | undefined;
  let queryRevision = 0;
  let detailRevision = 0;
  let queryController: AbortController | undefined;
  let detailController: AbortController | undefined;
  const identity = computed(() => {
    const view = toValue(options.view);
    return JSON.stringify([view.sourceId, view.viewCode, view.scopeKey]);
  });

  function closeDetail() {
    detailRevision++;
    detailController?.abort();
    selected.value = undefined;
    detail.value = undefined;
    detailError.value = undefined;
    detailLoading.value = false;
  }

  async function select(record: TRecord) {
    if (disposed) return;
    const view = toValue(options.view);
    const key = view.recordKey(record);
    const current = result.value?.records.find((row) => view.recordKey(row) === key);
    if (!current || !view.detail) return;
    closeDetail();
    const revision = detailRevision;
    selected.value = current;
    detailLoading.value = true;
    const controller = new AbortController();
    detailController = controller;
    try {
      const value = await view.detail.load(current, {
        signal: controller.signal,
        contentLanguage: toValue(options.contentLanguage),
      });
      if (disposed || revision !== detailRevision) return;
      if (!isRecord(value)) throw new Error('Read detail requires a record projection');
      detail.value = value;
    } catch (cause) {
      if (!disposed && revision === detailRevision) detailError.value = cause;
    } finally {
      if (!disposed && revision === detailRevision) detailLoading.value = false;
    }
  }

  async function refresh() {
    if (disposed) return;
    // A synchronous host query watcher may run before the identity watcher. Never send
    // previous-principal search text or retain selection in that intermediate transition.
    if (identity.value !== activeIdentity) {
      activeIdentity = identity.value;
      closeDetail();
      pageNum.value = 1;
      quickSearch.value = '';
    }
    const revision = ++queryRevision;
    queryController?.abort();
    const controller = new AbortController();
    queryController = controller;
    const previousSelection = selected.value;
    closeDetail();
    const selectionRevision = detailRevision;
    result.value = undefined;
    error.value = undefined;
    loading.value = true;
    try {
      // Clear obsolete facts synchronously, but let one host update finish before pairing
      // its view/identity with query props. Superseded transitions never reach the adapter.
      await Promise.resolve();
      if (disposed || revision !== queryRevision) return;
      const view = toValue(options.view);
      if (
        !view.sourceId?.trim() ||
        !view.viewCode?.trim() ||
        !view.scopeKey?.trim() ||
        !view.descriptor.page?.list?.fields
      )
        throw new Error('Read view requires an identity, authorization scope and compiled list descriptor');
      const key = previousSelection && view.recordKey(previousSelection);
      const input = JSON.parse(JSON.stringify(toValue(options.query) ?? {}));
      delete input.unpaged;
      const response = await view.query(
        {
          ...input,
          page: { pageNum: pageNum.value, pageSize: pageSize.value },
          quickSearch: quickSearch.value || undefined,
        },
        { signal: controller.signal, contentLanguage: toValue(options.contentLanguage) },
      );
      if (disposed || revision !== queryRevision) return;
      validatePage(response);
      const keys = response.records.map((record) => view.recordKey(record));
      if (keys.some((key) => typeof key !== 'string' || !key.trim()) || new Set(keys).size !== keys.length)
        throw new Error('Read projection requires unique, non-empty record keys');
      result.value = response;
      pageNum.value = response.pageNum;
      pageSize.value = response.pageSize;
      const retained = key && response.records.find((row) => view.recordKey(row) === key);
      if (retained && selectionRevision === detailRevision) void select(retained);
    } catch (cause) {
      if (!disposed && revision === queryRevision) error.value = cause;
    } finally {
      if (!disposed && revision === queryRevision) loading.value = false;
    }
  }

  function search(keyword: string) {
    quickSearch.value = keyword;
    return goPage(1);
  }
  function goPage(value: number) {
    pageNum.value = positiveInteger(value);
    closeDetail();
    return refresh();
  }
  function resizePage(value: number) {
    pageSize.value = positiveInteger(value);
    return goPage(1);
  }
  watch(
    [identity, () => toValue(options.view)],
    () => {
      void refresh();
    },
    { immediate: true, flush: 'sync' },
  );
  watch(
    () => JSON.stringify(toValue(options.query)),
    () => void goPage(1),
    { flush: 'sync' },
  );
  watch(
    () => toValue(options.contentLanguage),
    () => void refresh(),
    { flush: 'sync' },
  );
  onScopeDispose(() => {
    disposed = true;
    queryRevision++;
    queryController?.abort();
    closeDetail();
  });
  return {
    result,
    loading,
    error,
    pageNum,
    pageSize,
    quickSearch,
    selected,
    detail,
    detailLoading,
    detailError,
    refresh,
    search,
    goPage,
    resizePage,
    select,
    closeDetail,
  };
}

function positiveInteger(value: number) {
  if (!Number.isSafeInteger(value) || value < 1)
    throw new RangeError('Pagination requires positive integers');
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Adapter errors must not become authoritative pagination or selectable rows. */
function validatePage<TRecord extends Record<string, unknown>>(response: WebPageResponse<TRecord>) {
  if (
    !isRecord(response) ||
    !Array.isArray(response.records) ||
    !response.records.every(isRecord) ||
    !Number.isSafeInteger(response.pageNum) ||
    response.pageNum < 1 ||
    !Number.isSafeInteger(response.pageSize) ||
    response.pageSize < 1 ||
    !Number.isSafeInteger(response.total) ||
    response.total < 0 ||
    !Number.isSafeInteger(response.pages) ||
    response.pages < 0 ||
    typeof response.totalKnown !== 'boolean' ||
    response.records.length > response.pageSize
  )
    throw new Error('Read query requires a valid paged response');
}
