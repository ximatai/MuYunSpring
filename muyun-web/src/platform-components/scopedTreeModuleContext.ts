import type { WebListResponse, WebPageResponse, WebQueryRequest, WebTreeNode } from '@muyun/web-contracts';
import type { ModuleContext, StaticCountMutationResult, StaticModuleTreeClient } from '@muyun/web-core';

export interface ScopedTreeModuleContextOptions {
  scopeFieldName: string;
  scopeValue: string | undefined | (() => string | undefined);
  treePath: string;
  sortPath?: string;
  treeQueryParam?: string;
}

export function createScopedTreeModuleContext<TRecord>(
  context: ModuleContext<TRecord>,
  options: ScopedTreeModuleContextOptions,
): ModuleContext<TRecord> {
  const treeClient = createScopedTreeClient(context, options);
  return {
    ...context,
    abilities: {
      ...context.abilities,
      tree: () => treeClient,
      tryTree: () => (context.abilities.hasTree() ? treeClient : undefined),
    },
  };
}

export function createScopedTreeClient<TRecord>(
  context: ModuleContext<TRecord>,
  options: ScopedTreeModuleContextOptions,
): StaticModuleTreeClient<TRecord> {
  return {
    ...context.crud,
    query: (request) => {
      if (!scopeValueOf(options)) {
        return emptyPageResponse<TRecord>(request);
      }
      return context.crud.query(scopedQuery(request, options));
    },
    tree: () => {
      if (!scopeValueOf(options)) {
        return emptyTreeResponse<TRecord>();
      }
      return context.http.request<WebListResponse<WebTreeNode<TRecord>>>({
        path: options.treePath,
        query: scopeQueryParams(options),
      });
    },
    treeFlat: (treeOptions) => {
      if (!scopeValueOf(options)) {
        return emptyListResponse<TRecord>();
      }
      return context.http.request<WebListResponse<TRecord>>({
        path: treePathOf(options.treePath, treeOptions?.rootId),
        query: {
          ...scopeQueryParams(options),
          flat: true,
          includeSelf: treeOptions?.includeSelf,
        },
      });
    },
    subtree: (id, subtreeOptions) => {
      if (!scopeValueOf(options)) {
        return emptyTreeResponse<TRecord>();
      }
      return context.http.request<WebListResponse<WebTreeNode<TRecord>>>({
        path: treePathOf(options.treePath, id),
        query: {
          ...scopeQueryParams(options),
          ...subtreeOptions,
        },
      });
    },
    sort: (id, request) => {
      if (!scopeValueOf(options)) {
        return Promise.resolve(0);
      }
      return context.http.request<StaticCountMutationResult>({
        method: 'POST',
        path: `${(options.sortPath ?? `${options.treePath.replace(/\/tree\/?$/, '')}/sort`).replace(
          /\/$/,
          '',
        )}/${encodeURIComponent(id)}`,
        query: scopeQueryParams(options),
        body: request,
      });
    },
  };
}

function scopedQuery(request: WebQueryRequest | undefined, options: ScopedTreeModuleContextOptions) {
  const scopeValue = scopeValueOf(options);
  if (!scopeValue) {
    return request;
  }
  return {
    ...request,
    conditions: [
      ...(request?.conditions ?? []),
      { fieldName: options.scopeFieldName, operator: 'EQ', values: [scopeValue] },
    ],
  };
}

function scopeQueryParams(options: ScopedTreeModuleContextOptions) {
  return { [options.treeQueryParam ?? options.scopeFieldName]: scopeValueOf(options) };
}

function scopeValueOf(options: ScopedTreeModuleContextOptions) {
  return typeof options.scopeValue === 'function' ? options.scopeValue() : options.scopeValue;
}

function treePathOf(treePath: string, rootId: string | undefined) {
  const normalized = treePath.replace(/\/$/, '');
  return rootId ? `${normalized}/${encodeURIComponent(rootId)}` : normalized;
}

async function emptyTreeResponse<TRecord>(): Promise<WebListResponse<WebTreeNode<TRecord>>> {
  return { records: [] };
}

async function emptyListResponse<TRecord>(): Promise<WebListResponse<TRecord>> {
  return { records: [] };
}

async function emptyPageResponse<TRecord>(request?: WebQueryRequest): Promise<WebPageResponse<TRecord>> {
  const page = request?.page;
  return {
    records: [],
    total: 0,
    pageNum: page?.pageNum ?? 1,
    pageSize: page?.pageSize ?? 20,
    pages: 0,
    totalKnown: true,
  };
}
