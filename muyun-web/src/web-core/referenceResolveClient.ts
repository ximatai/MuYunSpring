import type { WebReferenceResolveRequest, WebReferenceResolveResponse } from '@muyun/web-contracts';
import type { HttpClient } from './http';

/**
 * Resolves candidates for a source field, including source-defined filtering,
 * projections and field patches. The transport is source-module based; callers
 * must not derive it from the referenced target module.
 */
export interface ReferenceResolveClient {
  resolve(fieldName: string, request?: WebReferenceResolveRequest): Promise<WebReferenceResolveResponse>;
}

export function createReferenceResolveClient(
  http: HttpClient,
  moduleAlias: string,
  resolvePath?: string,
): ReferenceResolveClient {
  const modulePath = `/${moduleAlias.replace(/^\/+|\/+$/g, '')}`;
  return {
    resolve: (fieldName, request) =>
      http.request<WebReferenceResolveResponse>({
        method: 'POST',
        path: resolvePath ?? `${modulePath}/references/${encodeURIComponent(fieldName)}/resolve`,
        body: request,
      }),
  };
}
