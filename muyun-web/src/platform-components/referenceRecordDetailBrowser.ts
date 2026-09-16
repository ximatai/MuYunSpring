import { computed, inject, provide, ref, type InjectionKey } from 'vue';
import { createModuleContext, type AppError, type HttpClient, type ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from './recordQueryListColumnModel';
import { resolveRecordDetailFields, type RecordFormFieldDescriptor } from './recordFormFieldModel';

type AccessState = 'unknown' | 'granted' | 'denied';
export type ActiveReferenceRecord = {
  targetModuleAlias: string;
  recordId: string;
  record?: QueryListRecord;
  loading: boolean;
  /** A record-only editor owns an in-flight target mutation. */
  busy?: boolean;
  failed: boolean;
  failureKind?: 'notFound' | 'temporary';
};

export type ReferenceRecordDetailMutation = {
  targetModuleAlias: string;
  recordId: string;
  type: 'saved' | 'deleted' | 'unavailable';
  record?: QueryListRecord;
};

export interface ReferenceRecordDetailBrowser {
  revision: { value: number };
  active: { value: ActiveReferenceRecord | undefined };
  activeContext: { value: ModuleContext<QueryListRecord> | undefined };
  fields: { value: Map<string, RecordFormFieldDescriptor> };
  title: { value: string };
  canBrowse(targetModuleAlias: string, recordId: string): boolean;
  open(targetModuleAlias: string, recordId: string): void;
  setBusy(targetModuleAlias: string, recordId: string, busy: boolean): void;
  reportMutation(mutation: ReferenceRecordDetailMutation): void;
  close(): void;
  /** Component teardown bypasses the user-facing busy close gate. */
  dispose(): void;
}

export const referenceRecordDetailBrowserKey: InjectionKey<ReferenceRecordDetailBrowser | undefined> = Symbol(
  'muyun.reference-record-detail-browser',
);

export function createReferenceRecordDetailBrowser(http: HttpClient): ReferenceRecordDetailBrowser {
  const contexts = new Map<string, ModuleContext<QueryListRecord>>();
  const accessByModule = new Map<string, AccessState>();
  const deniedRecordKeys = new Set<string>();
  const revision = ref(0);
  const active = ref<ActiveReferenceRecord>();
  let requestRevision = 0;
  const activeContext = computed(() => {
    const current = active.value;
    return current ? contexts.get(current.targetModuleAlias) : undefined;
  });
  const fields = computed(() =>
    resolveRecordDetailFields(activeContext.value?.runtime.snapshot()?.uiDescriptor),
  );
  const title = computed(() => {
    const record = active.value?.record;
    return String(record?.title ?? record?.name ?? record?.code ?? active.value?.recordId ?? '记录详情');
  });
  const contextFor = (targetModuleAlias: string) => {
    let context = contexts.get(targetModuleAlias);
    if (!context) {
      // The target context receives the host's base session transport. It never inherits source
      // menu/page headers, but retains session and tenant headers supplied by the app.
      context = createModuleContext<QueryListRecord>({
        http,
        moduleAlias: targetModuleAlias,
        runtimeAccess: 'VIEW',
      });
      contexts.set(targetModuleAlias, context);
    }
    return context;
  };
  const close = () => {
    if (active.value?.busy) return;
    requestRevision += 1;
    active.value = undefined;
  };
  const dispose = () => {
    requestRevision += 1;
    active.value = undefined;
  };
  const open = async (targetModuleAlias: string, recordId: string) => {
    if (
      !targetModuleAlias ||
      !recordId ||
      accessByModule.get(targetModuleAlias) === 'denied' ||
      deniedRecordKeys.has(recordKey(targetModuleAlias, recordId)) ||
      active.value?.busy
    )
      return;
    const currentRequestRevision = ++requestRevision;
    active.value = { targetModuleAlias, recordId, loading: true, failed: false };
    const context = contextFor(targetModuleAlias);
    try {
      await context.runtime.load();
      if (currentRequestRevision !== requestRevision) return;
      accessByModule.set(targetModuleAlias, 'granted');
      revision.value += 1;
    } catch (cause) {
      if (currentRequestRevision !== requestRevision) return;
      if (statusOf(cause) === 403) {
        accessByModule.set(targetModuleAlias, 'denied');
        revision.value += 1;
        active.value = undefined;
        return;
      }
      active.value = { targetModuleAlias, recordId, loading: false, failed: true, failureKind: 'temporary' };
      return;
    }
    try {
      const record = await context.crud.view(recordId);
      if (currentRequestRevision !== requestRevision) return;
      active.value = { targetModuleAlias, recordId, record, loading: false, failed: false };
    } catch (cause) {
      if (currentRequestRevision !== requestRevision) return;
      if (statusOf(cause) === 403) {
        deniedRecordKeys.add(recordKey(targetModuleAlias, recordId));
        revision.value += 1;
        active.value = undefined;
        return;
      }
      active.value = {
        targetModuleAlias,
        recordId,
        loading: false,
        failed: true,
        failureKind: statusOf(cause) === 404 ? 'notFound' : 'temporary',
      };
    }
  };
  return {
    revision,
    active,
    activeContext,
    fields,
    title,
    canBrowse: (targetModuleAlias, recordId) =>
      accessByModule.get(targetModuleAlias) !== 'denied' &&
      !deniedRecordKeys.has(recordKey(targetModuleAlias, recordId)),
    open,
    setBusy: (targetModuleAlias, recordId, busy) => {
      const current = active.value;
      if (!current || current.targetModuleAlias !== targetModuleAlias || current.recordId !== recordId)
        return;
      active.value = { ...current, busy };
    },
    reportMutation: (mutation) => {
      const current = active.value;
      if (
        !current ||
        current.targetModuleAlias !== mutation.targetModuleAlias ||
        current.recordId !== mutation.recordId
      )
        return;
      if (mutation.type === 'unavailable' || mutation.type === 'deleted') {
        deniedRecordKeys.add(recordKey(mutation.targetModuleAlias, mutation.recordId));
        requestRevision += 1;
        active.value = undefined;
      } else if (mutation.record) {
        active.value = { ...current, record: mutation.record, busy: false };
      } else {
        active.value = { ...current, busy: false };
      }
      revision.value += 1;
    },
    close,
    dispose,
  };
}

export function provideReferenceRecordDetailBrowser(browser: ReferenceRecordDetailBrowser) {
  provide(referenceRecordDetailBrowserKey, browser);
}

export function useReferenceRecordDetailBrowser() {
  return inject(referenceRecordDetailBrowserKey, undefined);
}

function statusOf(cause: unknown) {
  return typeof cause === 'object' && cause !== null && typeof (cause as AppError).status === 'number'
    ? (cause as AppError).status
    : undefined;
}

function recordKey(targetModuleAlias: string, recordId: string) {
  return `${targetModuleAlias}:${recordId}`;
}
