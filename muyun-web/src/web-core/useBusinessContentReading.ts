import { computed, onScopeDispose, ref, watch } from 'vue';
import type {
  BusinessContentField,
  BusinessContentGenerationStatus,
  BusinessContentReadingField,
} from '@muyun/web-contracts';
import type { HttpClient } from './http';
import {
  loadBusinessContentGeneration,
  loadBusinessContentReading,
  requestBusinessContentGeneration,
  type BusinessContentRelationScope,
} from './businessContent';

interface ReadingOptions {
  active?: boolean;
  http?: HttpClient;
  moduleAlias?: string;
  record: Record<string, unknown>;
  fieldName: string;
  content: BusinessContentField;
  language: string;
  mode: 'create' | 'edit' | 'view';
  relation?: BusinessContentRelationScope;
  sessionKey?: string | number;
}

const active = new Set(['PENDING', 'RUNNING', 'RETRY']);
const retryable = new Set(['FAILED', 'CANCELLED', 'STALE', 'MISSING']);
const POLL_DELAY = 2_000;
const MAX_POLLS = 60;

/** Shared saved-record reading. Translation work never mutates the caller's business draft. */
export function useBusinessContentReading(options: () => ReadingOptions) {
  const reading = ref<BusinessContentReadingField>();
  const loading = ref(false);
  const failed = ref(false);
  const status = ref<BusinessContentGenerationStatus>();
  const generationBusy = ref(false);
  const generationFailed = ref(false);
  const pollingPaused = ref(false);
  let readEpoch = 0;
  let jobEpoch = 0;
  let disposed = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let controller: AbortController | undefined;

  const raw = () => {
    const current = options();
    return current.record[current.fieldName] == null ? null : String(current.record[current.fieldName]);
  };
  const key = () => {
    const current = options();
    return [
      current.active,
      current.http,
      current.moduleAlias,
      current.record.id,
      current.record.version,
      current.fieldName,
      current.content.key,
      current.content.policy,
      current.language,
      current.mode,
      current.relation?.parentId,
      current.relation?.relationCode,
      current.relation?.workflowInstanceId,
      current.relation?.history,
      current.sessionKey,
    ];
  };
  const matches = computed(() => reading.value?.originalText === raw());
  const eligible = computed(() => {
    const current = options();
    return (
      current.active !== false &&
      current.content.policy === 'AUTO_TRANSLATE' &&
      current.mode !== 'create' &&
      Boolean(current.http && current.moduleAlias && current.record.id != null) &&
      matches.value &&
      reading.value?.originalFallback === true &&
      Boolean(raw()?.trim() && reading.value.source?.sourceLanguage)
    );
  });
  const canRetry = computed(
    () =>
      eligible.value &&
      !generationBusy.value &&
      (generationFailed.value ||
        Boolean(
          status.value &&
          (retryable.has(status.value.state) ||
            (status.value.state === 'UNAVAILABLE' && status.value.reference)),
        )),
  );

  function cancelGeneration() {
    ++jobEpoch;
    clearTimeout(timer);
    timer = undefined;
    controller?.abort();
    controller = undefined;
    status.value = undefined;
    generationBusy.value = false;
    generationFailed.value = false;
    pollingPaused.value = false;
  }
  function sameKey(expected: unknown[]) {
    const current = key();
    return current.every((value, index) => value === expected[index]);
  }
  async function load() {
    cancelGeneration();
    const epoch = ++readEpoch;
    const expected = key();
    reading.value = undefined;
    loading.value = false;
    failed.value = false;
    const current = options();
    if (
      current.active === false ||
      !current.http ||
      !current.moduleAlias ||
      current.record.id == null ||
      current.mode === 'create'
    )
      return;
    loading.value = true;
    try {
      const result = await loadBusinessContentReading(
        current.http,
        current.moduleAlias,
        String(current.record.id),
        current.language,
        current.relation,
        current.record,
        current.sessionKey,
      );
      if (disposed || epoch !== readEpoch || !sameKey(expected)) return;
      if (result.language !== current.language || result.recordVersion !== (current.record.version ?? null))
        return;
      reading.value = result.fields[current.fieldName];
      if (eligible.value) void generate(false);
    } catch {
      if (!disposed && epoch === readEpoch && sameKey(expected)) failed.value = true;
    } finally {
      if (!disposed && epoch === readEpoch) loading.value = false;
    }
  }
  async function generate(retry: boolean) {
    if (!eligible.value || generationBusy.value) return;
    cancelGeneration();
    const epoch = jobEpoch;
    const expected = key();
    const original = raw();
    const current = options();
    const http = current.http!;
    const module = current.moduleAlias!;
    const id = String(current.record.id);
    const valid = () => !disposed && epoch === jobEpoch && sameKey(expected) && raw() === original;
    let polls = 0;
    const started = Date.now();
    controller = new AbortController();
    const requestOptions = {
      relation: current.relation,
      snapshot: current.record,
      signal: controller.signal,
    };
    generationBusy.value = true;
    generationFailed.value = false;

    async function receive(next: BusinessContentGenerationStatus) {
      if (!valid()) return;
      status.value = next;
      if (next.state === 'READY') {
        const result = await loadBusinessContentReading(
          http,
          module,
          id,
          current.language,
          current.relation,
          current.record,
          current.sessionKey,
        );
        if (
          valid() &&
          result.language === current.language &&
          result.recordVersion === (current.record.version ?? null)
        )
          reading.value = result.fields[current.fieldName];
      } else if (active.has(next.state)) {
        if (polls >= MAX_POLLS || Date.now() - started >= MAX_POLLS * POLL_DELAY) pollingPaused.value = true;
        else timer = setTimeout(() => void poll(), POLL_DELAY);
      }
      if (valid()) generationBusy.value = Boolean(timer) && !pollingPaused.value;
    }
    async function poll() {
      timer = undefined;
      if (!valid()) return;
      ++polls;
      try {
        await receive(
          await loadBusinessContentGeneration(
            http,
            module,
            id,
            current.fieldName,
            current.language,
            requestOptions,
          ),
        );
      } catch {
        if (valid()) {
          generationFailed.value = true;
          generationBusy.value = false;
        }
      }
    }
    try {
      await receive(
        await requestBusinessContentGeneration(
          http,
          module,
          id,
          current.fieldName,
          current.language,
          retry,
          requestOptions,
        ),
      );
    } catch {
      if (valid()) {
        generationFailed.value = true;
        generationBusy.value = false;
      }
    }
  }

  watch(
    key,
    (current, previous) => {
      // Standard forms replace immutable draft objects, including unchanged sibling rows.
      // A replacement only reloads when its logical record/version/session scope changes.
      if (!previous || current.some((value, index) => value !== previous[index])) void load();
    },
    { immediate: true },
  );
  // Keystrokes stop generation display/polling without issuing a source read per keystroke.
  watch(raw, cancelGeneration, { flush: 'sync' });
  onScopeDispose(() => {
    disposed = true;
    ++readEpoch;
    cancelGeneration();
  });
  return {
    reading,
    loading,
    failed,
    matches,
    status,
    generationBusy,
    generationFailed,
    pollingPaused,
    canRetry,
    load,
    retry: () => generate(true),
  };
}
