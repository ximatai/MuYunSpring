import type { HttpClient } from './http';
import type { BusinessContentSources, BusinessContentRevision } from '@muyun/web-contracts';
import type {
  BusinessContentReading,
  BusinessContentTranslationMaintenance,
  BusinessContentTranslationChange,
  BusinessContentTranslationRevision,
} from '@muyun/web-contracts';

export interface BusinessContentRelationScope {
  parentId: string;
  relationCode: string;
}

/** The source belongs to the actual row, including an owned aggregate child. */
export function businessContentPath(
  moduleAlias: string,
  id: string,
  relation?: BusinessContentRelationScope,
) {
  const segment = encodeURIComponent;
  const base = `/${segment(moduleAlias)}/view/${segment(relation?.parentId ?? id)}`;
  return relation
    ? `${base}/relations/${segment(relation.relationCode)}/${segment(id)}/content`
    : `${base}/content`;
}

// Share only an explicit draft snapshot, never a mutable authentication client by itself.
const pending = new WeakMap<
  object,
  { http: HttpClient; path: string; version: unknown; request: Promise<BusinessContentSources> }
>();
export function loadBusinessContentSources(
  http: HttpClient,
  moduleAlias: string,
  id: string,
  relation?: BusinessContentRelationScope,
  snapshot?: object,
) {
  const path = businessContentPath(moduleAlias, id, relation);
  const version = snapshot && 'version' in snapshot ? snapshot.version : undefined;
  const previous = snapshot ? pending.get(snapshot) : undefined;
  if (previous?.http === http && previous.path === path && previous.version === version)
    return previous.request;
  const request = http.request<BusinessContentSources>({ path }).finally(() => {
    if (snapshot && pending.get(snapshot)?.request === request) pending.delete(snapshot);
  });
  if (snapshot) pending.set(snapshot, { http, path, version, request });
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
) {
  const path = `${businessContentPath(moduleAlias, id, relation)}/reading`;
  const version = snapshot && 'version' in snapshot ? snapshot.version : undefined;
  const key = JSON.stringify([path, language, version]);
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
  return http.request<BusinessContentTranslationMaintenance>({
    path: `${translationPath(moduleAlias, id, field, language, relation)}/${access === 'edit' ? 'prepare' : 'review'}`,
    method: 'POST',
  });
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
      return response;
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
