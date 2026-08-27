import type { QuerySchema, WebListResponse, WebPageResponse, WebTreeNode } from '@muyun/web-contracts';
import type { ModuleContext, ModuleTreeClient } from '@muyun/web-core';

export interface StaticTreeResourceModuleContextOptions<TRecord> {
  client?: ModuleTreeClient<TRecord>;
  emptyQueryScopeName?: string;
}

export function createStaticTreeResourceModuleContext<TRecord, TContextRecord = unknown>(
  context: ModuleContext<TContextRecord>,
  options: StaticTreeResourceModuleContextOptions<TRecord> = {},
): ModuleContext<TRecord> {
  const treeClient = options.client ?? createEmptyStaticTreeClient<TRecord>(options.emptyQueryScopeName);
  return {
    ...context,
    crud: treeClient,
    abilities: {
      ...context.abilities,
      crud: () => treeClient,
      tree: () => treeClient,
      enable: () => treeClient,
      tryCrud: () => treeClient,
      tryTree: () => (context.abilities.hasTree() === false ? undefined : treeClient),
      tryEnable: () => treeClient,
    },
  };
}

export function createEmptyStaticTreeClient<TRecord>(scopeName = 'static.empty'): ModuleTreeClient<TRecord> {
  return {
    querySchema: async () => emptyQuerySchema(scopeName),
    query: async () => emptyPage<TRecord>(),
    view: async () => ({}) as TRecord,
    insert: async (record) => ({ record }),
    update: async (_id, record) => ({ record }),
    delete: async () => 0,
    enable: async () => 0,
    disable: async () => 0,
    tree: async () => emptyTree<TRecord>(),
    treeFlat: async () => emptyList<TRecord>(),
    subtree: async () => emptyTree<TRecord>(),
    sort: async () => 0,
  };
}

function emptyQuerySchema(scopeName: string): QuerySchema {
  return {
    scopeName,
    quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
    fields: [],
    externalCriteria: [],
    defaultSorts: [],
  };
}

function emptyPage<TRecord>(): WebPageResponse<TRecord> {
  return {
    records: [],
    total: 0,
    pageNum: 1,
    pageSize: 20,
    pages: 0,
    totalKnown: true,
  };
}

function emptyTree<TRecord>(): WebListResponse<WebTreeNode<TRecord>> {
  return { records: [] };
}

function emptyList<TRecord>(): WebListResponse<TRecord> {
  return { records: [] };
}
