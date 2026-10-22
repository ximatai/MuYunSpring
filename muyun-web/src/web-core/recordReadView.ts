import type {
  QuerySchema,
  ResolvedModuleUiDescriptor,
  WebPageResponse,
  WebQueryRequest,
} from '@muyun/web-contracts';

export interface RecordReadRequestOptions {
  signal: AbortSignal;
  contentLanguage?: string;
}

/**
 * A business-owned, authorized read projection. It declares no write capability and needs no
 * entity table or ModuleContext. Query merging, stable ordering and pagination belong to the server.
 */
export interface RecordReadView<TRecord extends Record<string, unknown> = Record<string, unknown>> {
  sourceId: string;
  viewCode: string;
  /** Change on authentication, tenant or authorization/range changes; this key grants no access. */
  scopeKey: string;
  descriptor: ResolvedModuleUiDescriptor;
  /** Authorized query capabilities; absent for legacy adapters that only support quick search. */
  querySchema?: QuerySchema;
  /** Stable within this source, including the origin when multiple sources share record IDs. */
  recordKey(record: TRecord): string;
  query(request: WebQueryRequest, options: RecordReadRequestOptions): Promise<WebPageResponse<TRecord>>;
  /** Reauthorize detail reads on the server. Omit when this projection has no detail surface. */
  detail?: {
    load(record: TRecord, options: RecordReadRequestOptions): Promise<Record<string, unknown>>;
    title(record: TRecord): string;
  };
}

/** User-adjustable query input, never an authorization or mandatory business-scope declaration. */
export type RecordReadQuery = Omit<WebQueryRequest, 'page' | 'quickSearch' | 'unpaged'>;
