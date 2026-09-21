import type { ModuleContext } from '@muyun/web-core';
import type { RecordDetailTransitionOptions } from '../recordDetailController';

interface DetailController<TRecord> {
  beginLoad(record: TRecord, mode: 'edit' | 'view', options?: RecordDetailTransitionOptions): void;
  resolveLoad(record: TRecord): void;
  failLoad(): void;
  finishLoad(): void;
}

/**
 * Serializes record-detail requests for one module-page session.
 *
 * Loading a second record (or closing the detail) invalidates all earlier
 * completions. This is deliberately independent of the visual host so list,
 * card and drawer surfaces cannot accidentally diverge on request races.
 */
export function useRecordEditingSession<TRecord extends { id?: unknown }>(
  context: ModuleContext<TRecord>,
  detail: DetailController<TRecord>,
  onLoaded: () => void,
) {
  let requestSequence = 0;
  let pendingRecordLoad: Promise<void> | undefined;

  function invalidatePendingRequests() {
    requestSequence += 1;
  }

  /** Commits an already-authorized and loaded record through the standard detail controller. */
  function commitLoadedRecord(
    record: TRecord,
    mode: 'edit' | 'view',
    options: RecordDetailTransitionOptions = {},
  ) {
    requestSequence += 1;
    detail.beginLoad(record, mode, options);
    detail.resolveLoad(record);
    onLoaded();
    detail.finishLoad();
  }

  async function loadRecord(
    record: TRecord,
    mode: 'edit' | 'view',
    options: RecordDetailTransitionOptions = {},
    skipLoad = false,
    onLoadError?: (cause: unknown) => void,
  ) {
    const id = record.id == null ? undefined : String(record.id);
    if (!id) return;
    const sequence = ++requestSequence;
    detail.beginLoad(record, mode, options);
    if (skipLoad) {
      detail.resolveLoad(record);
      detail.finishLoad();
      return;
    }
    try {
      const loaded = await context.crud.view(id);
      if (sequence !== requestSequence) return;
      detail.resolveLoad(loaded);
      onLoaded();
    } catch (cause) {
      if (sequence !== requestSequence) return;
      detail.failLoad();
      onLoadError?.(cause);
    } finally {
      if (sequence === requestSequence) detail.finishLoad();
    }
  }

  function trackRecordLoad(load: Promise<void>) {
    pendingRecordLoad = load;
    const clear = () => {
      if (pendingRecordLoad === load) pendingRecordLoad = undefined;
    };
    void load.then(clear, clear);
    return load;
  }

  function openRecord(
    record: TRecord,
    mode: 'edit' | 'view',
    options: RecordDetailTransitionOptions = {},
    skipLoad = false,
    onLoadError?: (cause: unknown) => void,
  ) {
    return trackRecordLoad(loadRecord(record, mode, options, skipLoad, onLoadError));
  }

  async function loadRecycleBinRecord(record: TRecord) {
    const id = record.id == null ? undefined : String(record.id);
    if (!id) return;
    const sequence = ++requestSequence;
    detail.beginLoad(record, 'view');
    try {
      const loaded = await context.http.request<TRecord>({
        method: 'GET',
        path: `/${context.moduleAlias}/recycle-bin/view/${encodeURIComponent(id)}`,
      });
      if (sequence !== requestSequence) return;
      detail.resolveLoad(loaded);
      onLoaded();
    } catch {
      if (sequence !== requestSequence) return;
      detail.failLoad();
    } finally {
      if (sequence === requestSequence) detail.finishLoad();
    }
  }

  function openRecycleBinRecord(record: TRecord) {
    return trackRecordLoad(loadRecycleBinRecord(record));
  }

  /** Waits for the latest serialized detail load, including a replacement started while waiting. */
  async function settlePendingRecord(signal?: AbortSignal) {
    while (pendingRecordLoad) {
      const pending = pendingRecordLoad;
      await waitForRecordLoad(pending, signal);
      if (pendingRecordLoad === pending) return;
    }
  }

  function waitForRecordLoad(pending: Promise<void>, signal?: AbortSignal) {
    if (!signal) return pending;
    if (signal.aborted) {
      return Promise.reject(new DOMException('Record detail settlement was cancelled', 'AbortError'));
    }
    return new Promise<void>((resolve, reject) => {
      const abort = () => reject(new DOMException('Record detail settlement was cancelled', 'AbortError'));
      signal.addEventListener('abort', abort, { once: true });
      pending.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort));
    });
  }

  return {
    invalidatePendingRequests,
    commitLoadedRecord,
    openRecord,
    openRecycleBinRecord,
    settlePendingRecord,
  };
}
