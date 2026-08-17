import type {
  RouteQueryValue,
  WebListResponse,
  WebPageResponse,
  WebQueryRequest,
  WebTreeNode,
} from '@muyun/web-contracts';
import type { ModuleContext, StaticCountMutationResult, StaticModuleTreeClient } from '@muyun/web-core';

export interface ScopedTreeModuleContextOptions {
  scopeFieldName: string;
  scopeValue: string | undefined | (() => string | undefined);
  treePath: string;
  sortPath?: string;
  treeQueryParam?: string;
}

/**
 * Gives a tree client declared page-context criteria. An empty context is deliberately fail-closed:
 * a dependent picker must not expose records from an unselected business scope.
 */
export interface QueryScopedTreeModuleContextOptions {
  queryValues:
    | Record<string, RouteQueryValue>
    | undefined
    | (() => Record<string, RouteQueryValue> | undefined);
  treePath: string;
  sortPath?: string;
}

interface TreeScopeClientOptions {
  queryValues: () => Record<string, RouteQueryValue> | undefined;
  treePath: string;
  sortPath?: string;
}

export function createQueryScopedTreeModuleContext<TRecord>(
  context: ModuleContext<TRecord>,
  options: QueryScopedTreeModuleContextOptions,
): ModuleContext<TRecord> {
  return withScopedTreeClient(
    context,
    createTreeScopeClient(context, {
      queryValues: () =>
        typeof options.queryValues === 'function' ? options.queryValues() : options.queryValues,
      treePath: options.treePath,
      sortPath: options.sortPath,
    }),
  );
}

function withScopedTreeClient<TRecord>(
  context: ModuleContext<TRecord>,
  treeClient: StaticModuleTreeClient<TRecord>,
): ModuleContext<TRecord> {
  return {
    ...context,
    abilities: {
      ...context.abilities,
      tree: () => treeClient,
      tryTree: () => (context.abilities.hasTree() ? treeClient : undefined),
    },
  };
}

export function createScopedTreeModuleContext<TRecord>(
  context: ModuleContext<TRecord>,
  options: ScopedTreeModuleContextOptions,
): ModuleContext<TRecord> {
  return withScopedTreeClient(context, createScopedTreeClient(context, options));
}

export function createScopedTreeClient<TRecord>(
  context: ModuleContext<TRecord>,
  options: ScopedTreeModuleContextOptions,
): StaticModuleTreeClient<TRecord> {
  return createTreeScopeClient(context, {
    queryValues: () => scopeQueryParams(options),
    treePath: options.treePath,
    sortPath: options.sortPath,
  });
}

function createTreeScopeClient<TRecord>(
  context: ModuleContext<TRecord>,
  options: TreeScopeClientOptions,
): StaticModuleTreeClient<TRecord> {
  const hasScope = () => Object.keys(options.queryValues() ?? {}).length > 0;
  return {
    ...context.crud,
    query: (request) => {
      const queryValues = options.queryValues();
      if (!hasScope()) {
        return emptyPageResponse<TRecord>(request);
      }
      return context.crud.query({
        ...request,
        conditions: [
          ...(request?.conditions ?? []),
          ...Object.entries(queryValues ?? {}).map(([fieldName, value]) => ({
            fieldName,
            operator: 'EQ' as const,
            values: [String(value)],
          })),
        ],
      });
    },
    tree: () => {
      if (!hasScope()) {
        return emptyTreeResponse<TRecord>();
      }
      return context.http.request<WebListResponse<WebTreeNode<TRecord>>>({
        path: options.treePath,
        query: options.queryValues(),
      });
    },
    treeFlat: (treeOptions) => {
      if (!hasScope()) {
        return emptyListResponse<TRecord>();
      }
      return context.http.request<WebListResponse<TRecord>>({
        path: treePathOf(options.treePath, treeOptions?.rootId),
        query: {
          ...options.queryValues(),
          flat: true,
          includeSelf: treeOptions?.includeSelf,
        },
      });
    },
    subtree: (id, subtreeOptions) => {
      if (!hasScope()) {
        return emptyTreeResponse<TRecord>();
      }
      return context.http.request<WebListResponse<WebTreeNode<TRecord>>>({
        path: treePathOf(options.treePath, id),
        query: {
          ...options.queryValues(),
          ...subtreeOptions,
        },
      });
    },
    sort: (id, request) => {
      if (!hasScope()) {
        return Promise.resolve(0);
      }
      return context.http.request<StaticCountMutationResult>({
        method: 'POST',
        path: `${(options.sortPath ?? `${options.treePath.replace(/\/tree\/?$/, '')}/sort`).replace(
          /\/$/,
          '',
        )}/${encodeURIComponent(id)}`,
        query: options.queryValues(),
        body: request,
      });
    },
  };
}

function scopeQueryParams(options: ScopedTreeModuleContextOptions) {
  const scopeValue = scopeValueOf(options);
  return scopeValue ? { [options.treeQueryParam ?? options.scopeFieldName]: scopeValue } : undefined;
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
