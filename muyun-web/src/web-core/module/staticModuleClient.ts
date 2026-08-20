import type {
  TreeSortRequest,
  WebActionResultFacts,
  WebActionResultEnvelope,
  QuerySchema,
  WebListResponse,
  WebPageResponse,
  WebQueryRequest,
  WebTreeNode,
} from '@muyun/web-contracts';
import type { HttpClient } from '../http';

export interface StaticRecordMutationResult<TRecord> extends WebActionResultFacts {
  record: TRecord;
}

export type StaticCountMutationResult = number | WebActionResultEnvelope<number>;

export interface RecordActionRequest {
  version: number;
}

export interface QuerySchemaRequestOptions {
  uiConfigId?: string;
  queryTemplateId?: string;
}

/** Source-neutral client for a platform module's standard CRUD contract. */
export interface ModuleCrudClient<TRecord> {
  querySchema(options?: QuerySchemaRequestOptions): Promise<QuerySchema>;
  query(request?: WebQueryRequest): Promise<WebPageResponse<TRecord>>;
  view(id: string): Promise<TRecord>;
  insert(record: TRecord): Promise<StaticRecordMutationResult<TRecord>>;
  update(id: string, record: TRecord): Promise<StaticRecordMutationResult<TRecord>>;
  delete(id: string, request: RecordActionRequest): Promise<StaticCountMutationResult>;
  enable(id: string, request: RecordActionRequest): Promise<StaticCountMutationResult>;
  disable(id: string, request: RecordActionRequest): Promise<StaticCountMutationResult>;
}

/** Source-neutral tree extension for a platform module. */
export interface ModuleTreeClient<TRecord> extends ModuleCrudClient<TRecord> {
  tree(request?: WebQueryRequest): Promise<WebListResponse<WebTreeNode<TRecord>>>;
  treeFlat(options?: { rootId?: string; includeSelf?: boolean }): Promise<WebListResponse<TRecord>>;
  subtree(id: string, options?: { includeSelf?: boolean }): Promise<WebListResponse<WebTreeNode<TRecord>>>;
  sort(id: string, request: TreeSortRequest): Promise<StaticCountMutationResult>;
}

export interface ModuleEnableClient {
  enable(id: string, request: RecordActionRequest): Promise<StaticCountMutationResult>;
  disable(id: string, request: RecordActionRequest): Promise<StaticCountMutationResult>;
}

export function createModuleCrudClient<TRecord>(
  http: HttpClient,
  options: { moduleAlias: string },
): ModuleCrudClient<TRecord> {
  return createStaticResourceCrudClient(http, modulePathOf(options.moduleAlias));
}

/** The navigation-only read surface deliberately does not reuse the module query endpoint. */
export function createNavigatorReferenceCrudClient<TRecord>(
  http: HttpClient,
  options: { moduleAlias: string },
): ModuleCrudClient<TRecord> {
  const normal = createModuleCrudClient<TRecord>(http, options);
  const modulePath = modulePathOf(options.moduleAlias);
  return {
    ...normal,
    query: (request) =>
      http.request<WebPageResponse<TRecord>>({
        method: 'POST',
        path: `${modulePath}/navigator/reference/query`,
        body: request,
      }),
  };
}

export function createStaticResourceCrudClient<TRecord>(
  http: HttpClient,
  resourcePath: string,
): ModuleCrudClient<TRecord> {
  const modulePath = modulePathOf(resourcePath);
  return {
    querySchema: (options) =>
      http.request<QuerySchema>({
        path: `${modulePath}/query/schema`,
        query: {
          uiConfigId: options?.uiConfigId,
          queryTemplateId: options?.queryTemplateId,
        },
      }),
    query: (request) =>
      http.request<WebPageResponse<TRecord>>({
        method: 'POST',
        path: `${modulePath}/query`,
        body: request,
      }),
    view: (id) => http.request<TRecord>({ path: `${modulePath}/view/${encodeURIComponent(id)}` }),
    insert: async (record) =>
      normalizeRecordMutationResponse(
        await http.request<TRecord | WebActionResultEnvelope<TRecord>>({
          method: 'POST',
          path: `${modulePath}/insert`,
          body: record,
        }),
      ),
    update: async (id, record) =>
      normalizeRecordMutationResponse(
        await http.request<TRecord | WebActionResultEnvelope<TRecord>>({
          method: 'POST',
          path: `${modulePath}/update/${encodeURIComponent(id)}`,
          body: record,
        }),
      ),
    delete: async (id, request) =>
      normalizeCountMutationResponse(
        await http.request<StaticCountMutationResult>({
          method: 'POST',
          path: `${modulePath}/delete/${encodeURIComponent(id)}`,
          body: request,
        }),
      ),
    enable: async (id, request) =>
      normalizeCountMutationResponse(
        await http.request<StaticCountMutationResult>({
          method: 'POST',
          path: `${modulePath}/enable/${encodeURIComponent(id)}`,
          body: request,
        }),
      ),
    disable: async (id, request) =>
      normalizeCountMutationResponse(
        await http.request<StaticCountMutationResult>({
          method: 'POST',
          path: `${modulePath}/disable/${encodeURIComponent(id)}`,
          body: request,
        }),
      ),
  };
}

export function createModuleTreeClient<TRecord>(
  http: HttpClient,
  options: { moduleAlias: string },
): ModuleTreeClient<TRecord> {
  return createStaticResourceTreeClient(http, modulePathOf(options.moduleAlias));
}

export function createNavigatorReferenceTreeClient<TRecord>(
  http: HttpClient,
  options: { moduleAlias: string },
): ModuleTreeClient<TRecord> {
  const normal = createNavigatorReferenceCrudClient<TRecord>(http, options);
  const modulePath = modulePathOf(options.moduleAlias);
  return {
    ...normal,
    tree: (request) =>
      http.request<WebListResponse<WebTreeNode<TRecord>>>({
        method: 'POST',
        path: `${modulePath}/navigator/reference/tree/query`,
        body: request,
      }),
    treeFlat: () => Promise.reject(new Error('Navigator reference tree does not expose flat traversal')),
    subtree: () => Promise.reject(new Error('Navigator reference tree does not expose subtree traversal')),
    sort: () => Promise.reject(new Error('Navigator reference tree is read-only')),
  };
}

export function createStaticResourceTreeClient<TRecord>(
  http: HttpClient,
  resourcePath: string,
): ModuleTreeClient<TRecord> {
  const modulePath = modulePathOf(resourcePath);
  const crud = createStaticResourceCrudClient<TRecord>(http, modulePath);
  return {
    ...crud,
    tree: (request) =>
      request
        ? http.request<WebListResponse<WebTreeNode<TRecord>>>({
            method: 'POST',
            path: `${modulePath}/tree/query`,
            body: request,
          })
        : http.request<WebListResponse<WebTreeNode<TRecord>>>({
            path: `${modulePath}/tree`,
          }),
    treeFlat: (options) => {
      const rootId = options?.rootId;
      const path = rootId ? `${modulePath}/tree/${encodeURIComponent(rootId)}` : `${modulePath}/tree`;
      return http.request<WebListResponse<TRecord>>({
        path,
        query: {
          flat: true,
          includeSelf: options?.includeSelf,
        },
      });
    },
    subtree: (id, query) =>
      http.request<WebListResponse<WebTreeNode<TRecord>>>({
        path: `${modulePath}/tree/${encodeURIComponent(id)}`,
        query,
      }),
    sort: async (id, request) =>
      normalizeCountMutationResponse(
        await http.request<StaticCountMutationResult>({
          method: 'POST',
          path: `${modulePath}/sort/${encodeURIComponent(id)}`,
          body: request,
        }),
      ),
  };
}

/** @deprecated Use ModuleCrudClient. Remove after static-prefixed consumer imports migrate. */
export type StaticModuleCrudClient<TRecord> = ModuleCrudClient<TRecord>;
/** @deprecated Use ModuleTreeClient. Remove after static-prefixed consumer imports migrate. */
export type StaticModuleTreeClient<TRecord> = ModuleTreeClient<TRecord>;
/** @deprecated Use createModuleCrudClient. Remove after static-prefixed consumer imports migrate. */
export const createStaticModuleCrudClient = createModuleCrudClient;
/** @deprecated Use createModuleTreeClient. Remove after static-prefixed consumer imports migrate. */
export const createStaticModuleTreeClient = createModuleTreeClient;

function modulePathOf(moduleAlias: string) {
  const normalized = moduleAlias.trim();
  return normalized.startsWith('/') ? normalized : `/${normalized}`;
}

function normalizeRecordMutationResponse<TRecord>(
  response: TRecord | WebActionResultEnvelope<TRecord>,
): StaticRecordMutationResult<TRecord> {
  if (isWebActionResultEnvelope<TRecord>(response)) {
    const result: StaticRecordMutationResult<TRecord> = {
      record: response.data,
      message: response.message,
      changes: response.changes,
      changeSetId: response.changeSetId,
    };
    if (response.resultType) {
      result.resultType = response.resultType;
    }
    return result;
  }
  return { record: response };
}

function normalizeCountMutationResponse(response: StaticCountMutationResult): StaticCountMutationResult {
  if (isWebActionResultEnvelope<number>(response)) {
    return response;
  }
  if (typeof response === 'number' && Number.isFinite(response)) {
    return response;
  }
  return 0;
}

function isWebActionResultEnvelope<TData>(response: unknown): response is WebActionResultEnvelope<TData> {
  return typeof response === 'object' && response !== null && 'data' in response && 'changeSetId' in response;
}
