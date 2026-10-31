import { onUnmounted, watch } from 'vue';
import { provideRecordReadRefreshGuard } from '@muyun/web-core';
import { webDataChangeTypes, type ResolvedModuleUiDescriptor } from '@muyun/web-contracts';
import { usePageDataChange } from '../platform-admin-runtime/pageRealtime';

interface ModuleRecordDataChangeOptions {
  moduleAlias: string;
  recordId: () => string | undefined;
  presentationModules?: () => Iterable<string>;
  blocked: () => boolean;
  invalidate: (recordIds?: string[]) => void;
  refreshList: () => void;
  /** Return false if interaction changed while preparing the authorized read. */
  refreshRecord: (recordId: string, isCurrent: () => boolean) => Promise<boolean>;
  onError: (cause: unknown) => void;
}

/** Committed facts refresh read views; editors retain their drafts until interaction ends. */
export function useModuleRecordDataChanges(options: ModuleRecordDataChangeOptions) {
  const readSessions = provideRecordReadRefreshGuard();
  const blocked = () => options.blocked() || readSessions.blocked.value;
  const pendingRecords = new Set<string>();
  let collectionChanged = false;
  let pendingList = false;
  let active = true;
  let running = false;
  let timer: ReturnType<typeof setTimeout> | undefined;

  function schedule() {
    if (!active || running || timer || blocked() || !pendingList) return;
    timer = setTimeout(() => void flush(), 0);
  }

  async function flush() {
    timer = undefined;
    if (!active || running || blocked() || !pendingList) return;
    running = true;
    const id = options.recordId();
    const refreshDetail = Boolean(id && (collectionChanged || pendingRecords.has(id)));
    pendingList = false;
    collectionChanged = false;
    pendingRecords.clear();
    try {
      readSessions.invalidate();
      options.refreshList();
      if (id && refreshDetail) {
        const refreshed = await options.refreshRecord(
          id,
          () => active && options.recordId() === id && !blocked(),
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
    predicate: (change) =>
      (change.moduleAlias === options.moduleAlias ||
        (change.type === webDataChangeTypes.businessContentChanged &&
          [...(options.presentationModules?.() ?? [])].includes(change.moduleAlias))) &&
      [
        webDataChangeTypes.recordCreated,
        webDataChangeTypes.recordUpdated,
        webDataChangeTypes.recordDeleted,
        webDataChangeTypes.collectionChanged,
        webDataChangeTypes.businessContentChanged,
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
  watch(blocked, schedule);
  onUnmounted(() => {
    active = false;
    if (timer) clearTimeout(timer);
    pendingRecords.clear();
  });
  return {
    /** Explicit read refresh, without manufacturing a committed business event. */
    refresh() {
      const id = options.recordId();
      if (!active || !id) return;
      options.invalidate([id]);
      pendingRecords.add(id);
      pendingList = true;
      schedule();
    },
  };
}

/** Only declared read surfaces participate; values and labels cannot manufacture dependencies. */
export function moduleContentPresentationDependencies(ui?: ResolvedModuleUiDescriptor): string[] {
  const views = [
    ui?.defaultEditor,
    ui?.page?.list?.fields,
    ui?.page?.detail.display,
    ui?.page?.detail.editor,
    ...(ui?.editorSurfaces?.map((surface) => surface.editor) ?? []),
    ...(ui?.editorContributions?.map((contribution) => contribution.editor) ?? []),
  ];
  return [
    ...new Set([
      ...views.flatMap(
        (view) =>
          view?.fields.flatMap((field) =>
            [field.reference?.targetModuleAlias, field.referenceSummary?.targetModuleAlias].filter(
              (alias): alias is string => Boolean(alias),
            ),
          ) ?? [],
      ),
      ...(ui?.detailRelations?.map((relation) => relation.targetModuleAlias) ?? []),
    ]),
  ];
}
