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
  failed: boolean;
  failureKind?: 'notFound' | 'temporary';
};

export interface ReferenceRecordDetailBrowser {
  revision: { value: number };
  active: { value: ActiveReferenceRecord | undefined };
  activeContext: { value: ModuleContext<QueryListRecord> | undefined };
  fields: { value: Map<string, RecordFormFieldDescriptor> };
  title: { value: string };
  canBrowse(targetModuleAlias: string, recordId: string): boolean;
  open(targetModuleAlias: string, recordId: string): void;
  close(): void;
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
    requestRevision += 1;
    active.value = undefined;
  };
  const open = async (targetModuleAlias: string, recordId: string) => {
    if (
      !targetModuleAlias ||
      !recordId ||
      accessByModule.get(targetModuleAlias) === 'denied' ||
      deniedRecordKeys.has(recordKey(targetModuleAlias, recordId))
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
    close,
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
