import { computed, onScopeDispose, ref, watch } from 'vue';
import type {
  BusinessContentGenerationBatchResult,
  BusinessContentGenerationSource,
} from '@muyun/web-contracts';
import type { HttpClient } from './http';
import { businessContentGenerationBatch } from './businessContent';

interface Selection {
  active: boolean;
  http?: HttpClient;
  source?: BusinessContentGenerationSource | null;
  records: readonly { id: string; version?: unknown }[];
  language: string;
  includeChildren: boolean;
  sessionKey?: unknown;
}
const activeStates = new Set(['PENDING', 'RUNNING', 'RETRY']);
const failedStates = new Set(['FAILED', 'CANCELLED', 'STALE']);

/** A topic-specific selection session: previews never enqueue; polling never retries generation. */
export function useBusinessContentGenerationBatch(options: () => Selection) {
  const result = ref<BusinessContentGenerationBatchResult>();
  const loading = ref(false);
  const failed = ref(false);
  const error = ref<unknown>();
  const pollingPaused = ref(false);
  let epoch = 0;
  let polls = 0;
  let disposed = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let controller: AbortController | undefined;

  const items = computed(() => result.value?.items ?? []);
  const pending = computed(() => items.value.some((item) => activeStates.has(item.status.state)));
  const canGenerate = computed(() =>
    items.value.some(
      (item) =>
        item.status.state === 'MISSING' || (item.status.state === 'UNAVAILABLE' && !item.status.reference),
    ),
  );
  const canRetry = computed(() =>
    items.value.some(
      (item) =>
        failedStates.has(item.status.state) ||
        (item.status.state === 'UNAVAILABLE' && Boolean(item.status.reference)),
    ),
  );

  function clear() {
    if (timer) clearTimeout(timer);
    timer = undefined;
    controller?.abort();
    controller = undefined;
  }
  function reset() {
    epoch++;
    clear();
    polls = 0;
    result.value = undefined;
    loading.value = false;
    failed.value = false;
    error.value = undefined;
    pollingPaused.value = false;
  }
  function valid(current: Selection) {
    return (
      current.active &&
      current.http &&
      current.source &&
      current.records.length > 0 &&
      current.records.length <= 50
    );
  }
  async function load(generate: boolean, retry = false) {
    const current = options();
    if (disposed || !valid(current)) return;
    const token = ++epoch;
    clear();
    controller = new AbortController();
    const source = { ...current.source! };
    const command = {
      ids: current.records.map((row) => row.id),
      language: current.language,
      includeChildren: current.includeChildren,
      retry,
    };
    loading.value = true;
    failed.value = false;
    error.value = undefined;
    pollingPaused.value = false;
    try {
      const response = await businessContentGenerationBatch(
        current.http!,
        source,
        command,
        generate ? 'request' : 'status',
        controller.signal,
      );
      if (disposed || token !== epoch) return;
      if (response.language !== command.language) throw new Error('Generation selection language changed');
      result.value = response;
      if (pending.value) {
        if (polls < 60)
          timer = setTimeout(() => {
            if (token === epoch && !disposed) {
              polls++;
              void load(false);
            }
          }, 2_000);
        else pollingPaused.value = true;
      }
    } catch (cause) {
      if (!disposed && token === epoch) {
        failed.value = true;
        error.value = cause;
      }
    } finally {
      if (!disposed && token === epoch) loading.value = false;
    }
  }
  async function preview() {
    polls = 0;
    await load(false);
  }
  async function request(retry = false) {
    if (loading.value || !result.value || failed.value) return;
    polls = 0;
    await load(true, retry);
  }
  watch(
    () => {
      const current = options();
      return [
        current.http,
        current.sessionKey,
        JSON.stringify([
          current.active,
          current.source?.moduleAlias,
          current.source?.parentId,
          current.source?.relationCode,
          current.records.map((row) => [row.id, row.version]),
          current.language,
          current.includeChildren,
        ]),
      ];
    },
    () => {
      reset();
      void load(false);
    },
    { immediate: true, flush: 'sync' },
  );
  onScopeDispose(() => {
    disposed = true;
    reset();
  });
  return {
    result,
    items,
    loading,
    failed,
    error,
    pollingPaused,
    pending,
    canGenerate,
    canRetry,
    preview,
    request,
  };
}
