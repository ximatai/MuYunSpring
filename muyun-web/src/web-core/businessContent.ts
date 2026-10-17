import type { HttpClient } from './http';
import { actionResultData } from './actionResult';
import type {
  BusinessContentGenerationSource,
  BusinessContentGenerationBatchCommand,
  BusinessContentGenerationBatchResult,
} from '@muyun/web-contracts';
import type { BusinessContentSources, BusinessContentRevision } from '@muyun/web-contracts';
import type {
  BusinessContentReading,
  BusinessContentGenerationStatus,
  BusinessContentTranslationMaintenance,
  BusinessContentTranslationChange,
  BusinessContentTranslationRevision,
} from '@muyun/web-contracts';

export type BusinessContentRelationScope =
  | {
      kind?: 'aggregate';
      parentId: string;
      relationCode: string;
      workflowInstanceId?: never;
      history?: never;
    }
  | {
      kind: 'workflowEvent';
      workflowInstanceId: string;
      history?: boolean;
      parentId?: never;
      relationCode?: never;
    };

export function businessContentGenerationBatch(
  http: HttpClient,
  source: BusinessContentGenerationSource,
  command: BusinessContentGenerationBatchCommand,
  operation: 'status' | 'request',
  signal?: AbortSignal,
) {
  const segment = encodeURIComponent;
  if ((source.parentId == null) !== (source.relationCode == null))
    throw new Error('An aggregate generation source requires its parent and relation');
  const base = `/${segment(source.moduleAlias)}`;
  const owner =
    source.parentId != null
      ? `${base}/view/${segment(source.parentId)}/relations/${segment(source.relationCode!)}`
      : base;
  return http.request<BusinessContentGenerationBatchResult>({
    method: 'POST',
    path: `${owner}/content/generation${operation === 'status' ? '/status' : ''}`,
    body: { ...command, ids: [...command.ids] },
    signal,
  });
}

/** The source belongs to the actual row, including an owned aggregate child. */
export function businessContentPath(
  moduleAlias: string,
  id: string,
  relation?: BusinessContentRelationScope,
) {
  const segment = encodeURIComponent;
  if (relation?.kind === 'workflowEvent') {
    const owner = relation.history ? '/workflow/history' : '/workflow/runtime/instance';
    return `${owner}/${segment(relation.workflowInstanceId)}/events/${segment(id)}/content`;
  }
  const base = `/${segment(moduleAlias)}/view/${segment(relation?.parentId ?? id)}`;
  return relation
    ? `${base}/relations/${segment(relation.relationCode)}/${segment(id)}/content`
    : `${base}/content`;
}

// Share only an explicit draft snapshot, never a mutable authentication client by itself.
const pending = new WeakMap<
  object,
  {
    http: HttpClient;
    path: string;
    version: unknown;
    sessionKey?: string | number;
    request: Promise<BusinessContentSources>;
  }
>();
export function loadBusinessContentSources(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  relation?: BusinessContentRelationScope,
  snapshot?: object,
  sessionKey?: string | number,
) {
  const path = businessContentPath(moduleAlias, id, relation);
  const version = snapshot && 'version' in snapshot ? snapshot.version : undefined;
  const previous = snapshot ? pending.get(snapshot) : undefined;
  if (
    previous?.http === http &&
    previous.path === path &&
    previous.version === version &&
    previous.sessionKey === sessionKey
  )
    return previous.request;
  const request = http.request<BusinessContentSources>({ path }).finally(() => {
    if (snapshot && pending.get(snapshot)?.request === request) pending.delete(snapshot);
  });
  if (snapshot) pending.set(snapshot, { http, path, version, sessionKey, request });
  return request;
}

export function loadBusinessContentHistory(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  field: string,
  page: number,
  relation?: BusinessContentRelationScope,
) {
  return http.request<BusinessContentRevision[]>({
    path: `${businessContentPath(moduleAlias, id, relation)}/${encodeURIComponent(field)}/history`,
    query: { page, size: 20 },
  });
}

const readingPending = new WeakMap<
  object,
  Map<string, { http: HttpClient; request: Promise<BusinessContentReading> }>
>();
export function loadBusinessContentReading(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  language: string,
  relation?: BusinessContentRelationScope,
  snapshot?: object,
  sessionKey?: string | number,
) {
  const path = `${businessContentPath(moduleAlias, id, relation)}/reading`;
  const version = snapshot && 'version' in snapshot ? snapshot.version : undefined;
  const key = JSON.stringify([path, language, version, sessionKey]);
  const requests = snapshot ? readingPending.get(snapshot) : undefined;
  const previous = requests?.get(key);
  if (previous?.http === http) return previous.request;
  const request = http.request<BusinessContentReading>({ path, query: { language } }).finally(() => {
    if (snapshot && readingPending.get(snapshot)?.get(key)?.request === request) {
      const pending = readingPending.get(snapshot)!;
      pending.delete(key);
      if (!pending.size) readingPending.delete(snapshot);
    }
  });
  if (snapshot) {
    const pending = requests ?? new Map();
    pending.set(key, { http, request });
    readingPending.set(snapshot, pending);
  }
  return request;
}

function translationPath(
  moduleAlias: string,
  id: string,
  field: string,
  language: string,
  relation?: BusinessContentRelationScope,
) {
  return `${businessContentPath(moduleAlias, id, relation)}/${encodeURIComponent(field)}/translations/${encodeURIComponent(language)}`;
}
export function prepareBusinessContentTranslation(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  field: string,
  language: string,
  access: 'edit' | 'confirm',
  relation?: BusinessContentRelationScope,
) {
  return http
    .request<BusinessContentTranslationMaintenance>({
      path: `${translationPath(moduleAlias, id, field, language, relation)}/${access === 'edit' ? 'prepare' : 'review'}`,
      method: 'POST',
    })
    .then(actionResultData);
}
export function changeBusinessContentTranslation(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  field: string,
  language: string,
  operation: 'draft' | 'confirm' | 'remove',
  change: BusinessContentTranslationChange,
  relation?: BusinessContentRelationScope,
  snapshot?: object,
) {
  return http
    .request<BusinessContentTranslationMaintenance>({
      path: `${translationPath(moduleAlias, id, field, language, relation)}/${operation}`,
      method: 'POST',
      body: change,
    })
    .then((response) => {
      // Translation changes deliberately preserve the business version. A subsequent
      // reading must not reuse a projection started before this translation write.
      if (snapshot) readingPending.delete(snapshot);
      return actionResultData(response);
    });
}
export function loadBusinessContentTranslationHistory(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  field: string,
  language: string,
  access: 'edit' | 'confirm',
  source: { revisionId: string; revision: number },
  page: number,
  relation?: BusinessContentRelationScope,
) {
  return http.request<BusinessContentTranslationRevision[]>({
    path: `${translationPath(moduleAlias, id, field, language, relation)}/${access === 'edit' ? 'history' : 'review-history'}`,
    query: { sourceRevisionId: source.revisionId, sourceRevision: source.revision, page, size: 20 },
  });
}

export interface BusinessContentGenerationOptions {
  relation?: BusinessContentRelationScope;
  snapshot?: object;
  signal?: AbortSignal;
}

function generationRequest(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  field: string,
  language: string,
  options: BusinessContentGenerationOptions,
  retry?: boolean,
) {
  return http
    .request<BusinessContentGenerationStatus>({
      path: `${translationPath(moduleAlias, id, field, language, options.relation)}/generation`,
      ...(retry === undefined ? {} : { method: 'POST' as const, query: { retry } }),
      ...(options.signal ? { signal: options.signal } : {}),
    })
    .then((response) => {
      const status = actionResultData(response);
      // A completed generation changes the reading projection without changing the business version.
      if (status.state === 'READY' && options.snapshot) readingPending.delete(options.snapshot);
      return status;
    });
}

/** Read-only status. No enqueue, retry or model invocation. */
export function loadBusinessContentGeneration(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  field: string,
  language: string,
  options: BusinessContentGenerationOptions = {},
) {
  return generationRequest(http, moduleAlias, id, field, language, options);
}

/** Explicit reading generation or manual-field drafting; source, identity and route are resolved by the server. */
export function requestBusinessContentGeneration(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  field: string,
  language: string,
  retry = false,
  options: BusinessContentGenerationOptions = {},
) {
  return generationRequest(http, moduleAlias, id, field, language, options, retry);
}
