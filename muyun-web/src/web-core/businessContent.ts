import type { HttpClient } from './http';
import type { BusinessContentSources, BusinessContentRevision } from '@muyun/web-contracts';

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
