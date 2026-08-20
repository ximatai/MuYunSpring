import type { ModuleContext } from '@muyun/web-core';

interface DetailController<TRecord> {
  beginLoad(record: TRecord, mode: 'edit' | 'view'): void;
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

  function invalidatePendingRequests() {
    requestSequence += 1;
  }

  async function openRecord(record: TRecord, mode: 'edit' | 'view', skipLoad = false) {
    const id = record.id == null ? undefined : String(record.id);
    if (!id) return;
    const sequence = ++requestSequence;
    detail.beginLoad(record, mode);
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
    } catch {
      if (sequence !== requestSequence) return;
      detail.failLoad();
    } finally {
      if (sequence === requestSequence) detail.finishLoad();
    }
  }

  async function openRecycleBinRecord(record: TRecord) {
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

  return { invalidatePendingRequests, openRecord, openRecycleBinRecord };
}
