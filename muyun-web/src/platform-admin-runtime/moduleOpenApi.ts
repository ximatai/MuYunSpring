import type { HttpClient } from '@muyun/web-core';
import type { PageDescriptor } from '@muyun/web-contracts';
import { effectiveAuthToken } from './authSession';

export interface ModuleOpenApiDocument {
  openapi: string;
  info: { title?: string; version?: string };
  paths: Record<string, unknown>;
  components?: Record<string, unknown>;
  'x-muyun-module-alias'?: string;
}

const OPEN_API_PATH = /^\/openapi\/([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+)\/?$/;

/** Returns the module alias only for the dedicated, authenticated OpenAPI viewer path. */
export function moduleAliasFromOpenApiPath(pathname: string): string | undefined {
  return OPEN_API_PATH.exec(pathname)?.[1];
}

/** Creates the workbench page contract for one module's authenticated API document. */
export function createModuleOpenApiPageDescriptor(moduleAlias: string, moduleTitle?: string): PageDescriptor {
  return {
    pageType: 'platform-route',
    openMode: 'workbench-route',
    hostType: 'platform-route-host',
    title: `${moduleTitle ?? moduleAlias}.OpenAPI`,
    target: { route: `/openapi/${encodeURIComponent(moduleAlias)}`, moduleAlias },
    tabPolicy: { identity: 'by-target', closable: true, cacheable: true },
  };
}

/** Recognizes the app-owned OpenAPI tab without teaching the shared route resolver about this feature. */
export function isModuleOpenApiPage(descriptor?: PageDescriptor): boolean {
  if (descriptor?.pageType !== 'platform-route') {
    return false;
  }

  if (!('route' in descriptor.target)) {
    return false;
  }
  const moduleAlias = moduleAliasFromOpenApiPath(descriptor.target.route ?? '');
  return moduleAlias !== undefined && descriptor.target.moduleAlias === moduleAlias;
}

export function isOpenApiCatalogPath(pathname: string): boolean {
  return pathname === '/openapi' || pathname === '/openapi/';
}

export interface OpenApiModuleCatalogItem {
  moduleAlias: string;
  title: string;
  moduleKind: 'static' | 'dynamic';
  documentPath: string;
}

/** Loads the source-independent API document through the normal authenticated backend client. */
export function loadModuleOpenApi(client: HttpClient, moduleAlias: string) {
  return client.request<ModuleOpenApiDocument>({ path: `/${moduleAlias}/openapi` });
}

export function loadOpenApiCatalog(client: HttpClient) {
  return client.request<OpenApiModuleCatalogItem[]>({ path: '/platform.module/openapi/catalog' });
}

/**
 * Supplies the workbench session only to this application's frontend or configured backend origin.
 * Scalar may render user-selectable servers, so forwarding the token to an arbitrary server would
 * turn a documentation convenience into a credential leak.
 */
export function createOpenApiAuthenticatedFetch(): typeof fetch {
  const environment = import.meta.env ?? {};
  const token = effectiveAuthToken(environment.VITE_MUYUN_AUTH_TOKEN);
  const trustedOrigins = openApiTrustedOrigins(environment.VITE_MUYUN_API_BASE_URL);
  return async (input, init) => {
    const url = requestUrl(input);
    if (!token || !url || !trustedOrigins.has(url.origin)) {
      return fetch(input, init);
    }
    const headers = new Headers(input instanceof Request ? input.headers : undefined);
    new Headers(init?.headers).forEach((value, name) => headers.set(name, value));
    headers.set('Authorization', `Bearer ${token}`);
    return fetch(input, { ...init, credentials: init?.credentials ?? 'same-origin', headers });
  };
}

/** Resolves the backend server used by the workbench's interactive API client. */
export function openApiBackendBaseUrl() {
  const value = (import.meta.env ?? {}).VITE_MUYUN_API_BASE_URL?.trim();
  return value ? value.replace(/\/$/, '') : undefined;
}

function openApiTrustedOrigins(apiBaseUrl?: string) {
  const origin = typeof window === 'undefined' ? 'http://localhost' : window.location.origin;
  const trusted = new Set([origin]);
  if (apiBaseUrl) {
    trusted.add(new URL(apiBaseUrl, origin).origin);
  }
  return trusted;
}

function requestUrl(input: RequestInfo | URL) {
  const origin = typeof window === 'undefined' ? 'http://localhost' : window.location.origin;
  try {
    return new URL(input instanceof Request ? input.url : input.toString(), origin);
  } catch {
    return undefined;
  }
}
