import { computed, inject, provide, ref, type InjectionKey } from 'vue';
import {
  createModuleContext,
  displayModuleRuntime,
  withHttpHeaders,
  platformLanguageContext,
  type LanguageContext,
  type AppError,
  type HttpClient,
  type ModuleContext,
} from '@muyun/web-core';
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

/** `loaded` reports data readiness, not a DOM visibility or animation acknowledgement. */
export type ReferenceRecordOpenResult =
  | { status: 'loaded' }
  | {
      status: 'rejected';
      reason:
        | 'missing-host'
        | 'invalid-target'
        | 'access-denied'
        | 'busy'
        | 'leave-blocked'
        | 'superseded'
        | 'not-found'
        | 'load-failed';
    };

export type ReferenceRecordOpenCheck = () =>
  | Extract<ReferenceRecordOpenResult, { status: 'rejected' }>
  | undefined;

export interface ReferenceRecordDetailBrowser {
  revision: { value: number };
  active: { value: ActiveReferenceRecord | undefined };
  activeContext: { value: ModuleContext<QueryListRecord> | undefined };
  fields: { value: Map<string, RecordFormFieldDescriptor> };
  title: { value: string };
  canBrowse(targetModuleAlias: string, recordId: string): boolean;
  open(targetModuleAlias: string, recordId: string): Promise<ReferenceRecordOpenResult>;
  /** Presentation adapters register readiness here so every open entry uses the same gate. */
  setBeforeOpen(check: ReferenceRecordOpenCheck): () => void;
  /** Registers the owning full surface's leave authority. */
  setBeforeLeave(guard: () => boolean | Promise<boolean>): () => void;
  confirmLeave(): boolean | Promise<boolean>;
  setBusy(targetModuleAlias: string, recordId: string, busy: boolean): void;
  reportMutation(mutation: ReferenceRecordDetailMutation): void;
  close(): void | Promise<void>;
  /** Component teardown bypasses the user-facing busy close gate. */
  dispose(): void;
}

export const referenceRecordDetailBrowserKey: InjectionKey<ReferenceRecordDetailBrowser | undefined> = Symbol(
  'muyun.reference-record-detail-browser',
);

export function createReferenceRecordDetailBrowser(
  http: HttpClient,
  language: LanguageContext = platformLanguageContext,
): ReferenceRecordDetailBrowser {
  const contexts = new Map<string, ModuleContext<QueryListRecord>>();
  const accessByModule = new Map<string, AccessState>();
  const deniedRecordKeys = new Set<string>();
  const revision = ref(0);
  const active = ref<ActiveReferenceRecord>();
  let requestRevision = 0;
  let navigationRevision = 0;
  let pendingLeave: Promise<boolean> | undefined;
  let beforeOpen: ReferenceRecordOpenCheck | undefined;
  let presentationBound = false;
  const checkBeforeOpen: ReferenceRecordOpenCheck = () =>
    beforeOpen
      ? beforeOpen()
      : presentationBound
        ? { status: 'rejected', reason: 'missing-host' }
        : undefined;
  let beforeLeave: (() => boolean | Promise<boolean>) | undefined;
  const activeContext = computed(() => {
    const current = active.value;
    return current ? contexts.get(current.targetModuleAlias) : undefined;
  });
  const fields = computed(() =>
    resolveRecordDetailFields(
      activeContext.value ? displayModuleRuntime(activeContext.value.runtime)?.uiDescriptor : undefined,
    ),
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
        http: withHttpHeaders(
          http,
          () => ({
            'X-MuYun-Record-Context':
              active.value?.targetModuleAlias === targetModuleAlias
                ? JSON.stringify({ moduleAlias: targetModuleAlias, recordId: active.value.recordId })
                : undefined,
          }),
          // A nested target browser or receipt lookup may already carry its own record identity.
          // Keep that identity rather than replacing it with the outer record's context.
          (request) =>
            request.path.startsWith(`/${encodeURIComponent(targetModuleAlias)}/`) &&
            !Object.keys(request.headers ?? {}).some((key) => key.toLowerCase() === 'x-muyun-record-context'),
        ),
        language,
        moduleAlias: targetModuleAlias,
        runtimeAccess: 'VIEW',
      });
      contexts.set(targetModuleAlias, context);
    }
    return context;
  };
  const confirmLeave = () => {
    if (active.value?.busy) return false;
    if (!active.value || !beforeLeave) return true;
    if (pendingLeave) return pendingLeave;
    const result = beforeLeave();
    if (typeof result === 'boolean') return result;
    const pending = result.finally(() => {
      if (pendingLeave === pending) pendingLeave = undefined;
    });
    pendingLeave = pending;
    return pending;
  };
  const transition = (
    commit: () => void | Promise<void>,
    reject?: (reason: 'leave-blocked' | 'superseded' | 'busy') => void,
  ) => {
    const navigation = ++navigationRevision;
    const request = requestRevision;
    const finish = (allowed: boolean) => {
      if (navigation !== navigationRevision || request !== requestRevision) return reject?.('superseded');
      if (active.value?.busy) return reject?.('busy');
      if (!allowed) return reject?.('leave-blocked');
      return commit();
    };
    try {
      const allowed = confirmLeave();
      return typeof allowed === 'boolean'
        ? finish(allowed)
        : allowed.then(finish, () => {
            reject?.('leave-blocked');
          });
    } catch {
      // Failed leave confirmations preserve the current record and its draft.
      reject?.('leave-blocked');
    }
  };
  const close = () =>
    transition(() => {
      requestRevision += 1;
      active.value = undefined;
    });
  const dispose = () => {
    pendingLeave = undefined;
    navigationRevision += 1;
    requestRevision += 1;
    active.value = undefined;
  };
  const load = async (targetModuleAlias: string, recordId: string): Promise<ReferenceRecordOpenResult> => {
    const currentRequestRevision = ++requestRevision;
    const superseded = (): ReferenceRecordOpenResult => ({ status: 'rejected', reason: 'superseded' });
    active.value = { targetModuleAlias, recordId, loading: true, failed: false };
    const context = contextFor(targetModuleAlias);
    try {
      await context.runtime.load();
      if (currentRequestRevision !== requestRevision) return superseded();
      accessByModule.set(targetModuleAlias, 'granted');
      revision.value += 1;
    } catch (cause) {
      if (currentRequestRevision !== requestRevision) return superseded();
      if (statusOf(cause) === 403) {
        accessByModule.set(targetModuleAlias, 'denied');
        revision.value += 1;
        active.value = undefined;
        return { status: 'rejected', reason: 'access-denied' };
      }
      active.value = { targetModuleAlias, recordId, loading: false, failed: true, failureKind: 'temporary' };
      return { status: 'rejected', reason: 'load-failed' };
    }
    try {
      const record = await context.crud.view(recordId);
      if (currentRequestRevision !== requestRevision) return superseded();
      active.value = { targetModuleAlias, recordId, record, loading: false, failed: false };
      return { status: 'loaded' };
    } catch (cause) {
      if (currentRequestRevision !== requestRevision) return superseded();
      if (statusOf(cause) === 403) {
        deniedRecordKeys.add(recordKey(targetModuleAlias, recordId));
        revision.value += 1;
        active.value = undefined;
        return { status: 'rejected', reason: 'access-denied' };
      }
      const notFound = statusOf(cause) === 404;
      active.value = {
        targetModuleAlias,
        recordId,
        loading: false,
        failed: true,
        failureKind: notFound ? 'notFound' : 'temporary',
      };
      return { status: 'rejected', reason: notFound ? 'not-found' : 'load-failed' };
    }
  };
  const open = async (targetModuleAlias: string, recordId: string): Promise<ReferenceRecordOpenResult> => {
    if (!targetModuleAlias || !recordId) return { status: 'rejected', reason: 'invalid-target' };
    if (
      accessByModule.get(targetModuleAlias) === 'denied' ||
      deniedRecordKeys.has(recordKey(targetModuleAlias, recordId))
    )
      return { status: 'rejected', reason: 'access-denied' };
    const unavailable = checkBeforeOpen();
    if (unavailable) return unavailable;
    if (active.value?.busy) return { status: 'rejected', reason: 'busy' };
    let result: ReferenceRecordOpenResult = { status: 'rejected', reason: 'leave-blocked' };
    await transition(
      async () => {
        // A leave confirmation or data request may outlive its presentation host.
        result = checkBeforeOpen() ?? (await load(targetModuleAlias, recordId));
        if (result.status === 'loaded') result = checkBeforeOpen() ?? result;
      },
      (reason) => {
        result = { status: 'rejected', reason };
      },
    );
    return result;
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
    setBeforeOpen(check) {
      presentationBound = true;
      beforeOpen = check;
      return () => {
        if (beforeOpen === check) beforeOpen = undefined;
      };
    },
    confirmLeave,
    setBeforeLeave(guard) {
      pendingLeave = undefined;
      beforeLeave = guard;
      return () => {
        if (beforeLeave === guard) beforeLeave = undefined;
      };
    },
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
        active.value = { ...current, record: mutation.record };
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
