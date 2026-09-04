import type {
  SortRequest,
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
  sort(id: string, request: SortRequest): Promise<StaticCountMutationResult>;
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

/**
 * Fixed platform transport for a compiled, one-level detail relation.
 * Relation descriptors contain no business URL: the parent module alias, persisted parent id and
 * server-issued relation code are the only routing facts a surface receives.
 */
export interface ManagedDetailRelationClient<TRecord> {
  query(request?: WebQueryRequest): Promise<WebPageResponse<TRecord>>;
  insert(record: TRecord): Promise<StaticRecordMutationResult<TRecord>>;
  update(id: string, record: TRecord): Promise<StaticRecordMutationResult<TRecord>>;
  delete(id: string, request: RecordActionRequest): Promise<StaticCountMutationResult>;
}

export function createManagedDetailRelationClient<TRecord>(
  http: HttpClient,
  options: { parentModuleAlias: string; parentId: string; relationCode: string },
): ManagedDetailRelationClient<TRecord> {
  const parentPath = modulePathOf(options.parentModuleAlias);
  const relationPath = `${parentPath}/view/${encodeURIComponent(options.parentId)}/relations/${encodeURIComponent(options.relationCode)}`;
  return {
    query: (request) =>
      http
        .request<WebPageResponse<TRecord>>({
          method: 'POST',
          path: `${relationPath}/query`,
          body: request,
        })
        .then(normalizeModulePageResponse),
    insert: async (record) =>
      normalizeRecordMutationResponse(
        await http.request<TRecord | WebActionResultEnvelope<TRecord>>({
          method: 'POST',
          path: `${relationPath}/insert`,
          body: record,
        }),
      ),
    update: async (id, record) =>
      normalizeRecordMutationResponse(
        await http.request<TRecord | WebActionResultEnvelope<TRecord>>({
          method: 'POST',
          path: `${relationPath}/update/${encodeURIComponent(id)}`,
          body: record,
        }),
      ),
    delete: (id, request) =>
      http
        .request<StaticCountMutationResult>({
          method: 'POST',
          path: `${relationPath}/delete/${encodeURIComponent(id)}`,
          body: request,
        })
        .then(normalizeCountMutationResponse),
  };
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
  options: { moduleAlias: string; navigatorReference?: NavigatorReferenceRequestContext },
): ModuleCrudClient<TRecord> {
  const normal = createModuleCrudClient<TRecord>(http, options);
  const modulePath = modulePathOf(options.moduleAlias);
  return {
    ...normal,
    query: (request) =>
      http
        .request<WebPageResponse<TRecord>>({
          method: 'POST',
          path: `${modulePath}/navigator/reference/query`,
          body: navigatorReferenceRequest(request, options.navigatorReference),
        })
        .then(normalizeModulePageResponse),
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
      http
        .request<WebPageResponse<TRecord>>({
          method: 'POST',
          path: `${modulePath}/query`,
          body: request,
        })
        .then(normalizeModulePageResponse),
    view: (id) =>
      http
        .request<TRecord>({ path: `${modulePath}/view/${encodeURIComponent(id)}` })
        .then(normalizeModuleRecord),
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

export function createModuleTreeClient<TRecord>(
  http: HttpClient,
  options: { moduleAlias: string },
): ModuleTreeClient<TRecord> {
  return createStaticResourceTreeClient(http, modulePathOf(options.moduleAlias));
}

export function createNavigatorReferenceTreeClient<TRecord>(
  http: HttpClient,
  options: { moduleAlias: string; navigatorReference?: NavigatorReferenceRequestContext },
): ModuleTreeClient<TRecord> {
  const normal = createNavigatorReferenceCrudClient<TRecord>(http, options);
  const modulePath = modulePathOf(options.moduleAlias);
  return {
    ...normal,
    tree: (request) =>
      http.request<WebListResponse<WebTreeNode<TRecord>>>({
        method: 'POST',
        path: `${modulePath}/navigator/reference/tree/query`,
        body: navigatorReferenceRequest(request, options.navigatorReference),
      }),
    treeFlat: () => Promise.reject(new Error('Navigator reference tree does not expose flat traversal')),
    subtree: () => Promise.reject(new Error('Navigator reference tree does not expose subtree traversal')),
    sort: () => Promise.reject(new Error('Navigator reference tree is read-only')),
  };
}

export interface NavigatorReferenceRequestContext {
  hostModuleAlias: string;
  targetLevelKey: string;
}

function navigatorReferenceRequest(
  request: WebQueryRequest | undefined,
  context: NavigatorReferenceRequestContext | undefined,
): WebQueryRequest | undefined {
  if (!context) return request;
  return {
    ...request,
    navigatorHostModuleAlias: context.hostModuleAlias,
    navigatorTargetLevelKey: context.targetLevelKey,
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

/** @deprecated Use ModuleCrudClient. Retained for published consumer compatibility. */
export type StaticModuleCrudClient<TRecord> = ModuleCrudClient<TRecord>;
/** @deprecated Use ModuleTreeClient. Retained for published consumer compatibility. */
export type StaticModuleTreeClient<TRecord> = ModuleTreeClient<TRecord>;
/** @deprecated Use createModuleCrudClient. Retained for published consumer compatibility. */
export const createStaticModuleCrudClient = createModuleCrudClient;
/** @deprecated Use createModuleTreeClient. Retained for published consumer compatibility. */
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
      record: normalizeModuleRecord(response.data),
      message: response.message,
      changes: response.changes,
      changeSetId: response.changeSetId,
    };
    if (response.resultType) {
      result.resultType = response.resultType;
    }
    return result;
  }
  return { record: normalizeModuleRecord(response) };
}

/**
 * Dynamic records use a typed `{ id, version, values, children }` wire envelope while static
 * records already publish their fields at the root. The shared module client is the only place
 * that should bridge this transport difference: every page surface then consumes one flat record
 * contract, including aggregate children, and submits that same accepted shape back to the
 * dynamic deserializer.
 */
function normalizeModuleRecord<TRecord>(record: TRecord): TRecord {
  if (!isDynamicRecordWire(record)) return record;
  return {
    ...record.values,
    ...normalizeModuleChildren(record.children),
    id: record.id,
    version: record.version,
  } as TRecord;
}

function normalizeModuleChildren(children: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(children).map(([fieldName, records]) => [
      fieldName,
      Array.isArray(records) ? records.map((record) => normalizeModuleRecord(record)) : records,
    ]),
  );
}

/**
 * Normalizes a page response at the source-neutral module transport boundary.
 *
 * <p>Association query contracts use their server-issued route directly instead of the standard
 * module CRUD path, but they still return the same dynamic record envelope.  Keeping this helper
 * public lets those descriptor-driven surfaces reuse the one normalization rule.</p>
 */
export function normalizeModulePageResponse<TRecord>(
  response: WebPageResponse<TRecord>,
): WebPageResponse<TRecord> {
  if (!Array.isArray(response.records)) return response;
  return {
    ...response,
    records: response.records.map(normalizeModuleRecord),
  };
}

function isDynamicRecordWire(value: unknown): value is {
  id: string;
  version: number | undefined;
  values: Record<string, unknown>;
  children: Record<string, unknown>;
} {
  if (!value || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.id === 'string' &&
    typeof record.values === 'object' &&
    record.values !== null &&
    !Array.isArray(record.values) &&
    typeof record.children === 'object' &&
    record.children !== null &&
    !Array.isArray(record.children)
  );
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
