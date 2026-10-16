import { onUnmounted, watch } from 'vue';
import { webDataChangeTypes } from '@muyun/web-contracts';
import { usePageDataChange } from '../platform-admin-runtime/pageRealtime';

interface ModuleRecordDataChangeOptions {
  moduleAlias: string;
  recordId: () => string | undefined;
  blocked: () => boolean;
  invalidate: (recordIds?: string[]) => void;
  refreshList: () => void;
  /** Return false if interaction changed while preparing the authorized read. */
  refreshRecord: (recordId: string, isCurrent: () => boolean) => Promise<boolean>;
  onError: (cause: unknown) => void;
}

/** Committed facts refresh read views; editors retain their drafts until interaction ends. */
export function useModuleRecordDataChanges(options: ModuleRecordDataChangeOptions) {
  const pendingRecords = new Set<string>();
  let collectionChanged = false;
  let pendingList = false;
  let active = true;
  let running = false;
  let timer: ReturnType<typeof setTimeout> | undefined;

  function schedule() {
    if (!active || running || timer || options.blocked() || !pendingList) return;
    timer = setTimeout(() => void flush(), 0);
  }

  async function flush() {
    timer = undefined;
    if (!active || running || options.blocked() || !pendingList) return;
    running = true;
    const id = options.recordId();
    const refreshDetail = Boolean(id && (collectionChanged || pendingRecords.has(id)));
    pendingList = false;
    collectionChanged = false;
    pendingRecords.clear();
    try {
      options.refreshList();
      if (id && refreshDetail) {
        const refreshed = await options.refreshRecord(
          id,
          () => active && options.recordId() === id && !options.blocked(),
        );
        if (!refreshed && active && options.recordId() === id) {
          pendingRecords.add(id);
          pendingList = true;
        }
      }
    } catch (cause) {
      if (active) options.onError(cause);
    } finally {
      running = false;
      schedule();
    }
  }

  usePageDataChange({
    moduleAlias: options.moduleAlias,
    predicate: (change) =>
      [
        webDataChangeTypes.recordCreated,
        webDataChangeTypes.recordUpdated,
        webDataChangeTypes.recordDeleted,
        webDataChangeTypes.collectionChanged,
      ].some((type) => type === change.type),
    handler: (_changeSet, changes) => {
      for (const change of changes) {
        if (change.type === webDataChangeTypes.collectionChanged || !change.recordId) {
          collectionChanged = true;
        } else {
          pendingRecords.add(change.recordId);
          if (change.resourceKey && change.scope) pendingRecords.add(change.scope);
        }
      }
      options.invalidate(
        collectionChanged
          ? undefined
          : [
              ...new Set(
                changes.flatMap((change) =>
                  [change.recordId, change.resourceKey ? change.scope : undefined].filter(
                    (id): id is string => Boolean(id),
                  ),
                ),
              ),
            ],
      );
      pendingList = true;
      schedule();
    },
  });
  watch(options.blocked, schedule);
  onUnmounted(() => {
    active = false;
    if (timer) clearTimeout(timer);
    pendingRecords.clear();
  });
}
