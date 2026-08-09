import { assert, it } from 'vitest';
import { createScopedTreeModuleContext } from '@/platform-components/scopedTreeModuleContext.ts';
import type { WebQueryRequest } from '@/web-contracts/index.ts';
import type { HttpRequestOptions } from '@/web-core/http.ts';
import type { ModuleContext } from '@/web-core/module/moduleContext.ts';

interface DepartmentRecord {
  id: string;
  organizationId?: string;
  title: string;
}

it('scoped tree context appends scope condition to query requests', async () => {
  const context = createFakeModuleContext();
  const scopedContext = createScopedTreeModuleContext(context, {
    scopeFieldName: 'organizationId',
    scopeValue: 'org-1',
    treePath: '/iam.department/tree',
    sortPath: '/iam.department/sort',
  });

  await scopedContext.abilities.tree().query({
    conditions: [{ fieldName: 'title', operator: 'LIKE', values: ['研发'] }],
  });

  assert.deepEqual(context.queries[0], {
    conditions: [
      { fieldName: 'title', operator: 'LIKE', values: ['研发'] },
      { fieldName: 'organizationId', operator: 'EQ', values: ['org-1'] },
    ],
  });
});

it('scoped tree context returns empty tree data before scope is selected', async () => {
  const context = createFakeModuleContext();
  const scopedContext = createScopedTreeModuleContext(context, {
    scopeFieldName: 'organizationId',
    scopeValue: undefined,
    treePath: '/iam.department/tree',
  });

  assert.deepEqual(await scopedContext.abilities.tree().tree(), { records: [] });
  assert.deepEqual(await scopedContext.abilities.tree().treeFlat(), { records: [] });
  assert.deepEqual(context.requests, []);
});

it('scoped tree context sends scoped tree flat and sort requests through platform paths', async () => {
  const context = createFakeModuleContext();
  const scopedContext = createScopedTreeModuleContext(context, {
    scopeFieldName: 'organizationId',
    scopeValue: 'org-1',
    treePath: '/iam.department/tree',
    sortPath: '/iam.department/sort',
  });

  await scopedContext.abilities.tree().treeFlat({ rootId: 'dept/1', includeSelf: true });
  await scopedContext.abilities.tree().subtree('dept/2', { includeSelf: false });
  await scopedContext.abilities.tree().sort('dept/1', { nextId: 'dept-0' });

  assert.deepEqual(context.requests, [
    {
      path: '/iam.department/tree/dept%2F1',
      query: { organizationId: 'org-1', flat: true, includeSelf: true },
    },
    {
      path: '/iam.department/tree/dept%2F2',
      query: { organizationId: 'org-1', includeSelf: false },
    },
    {
      method: 'POST',
      path: '/iam.department/sort/dept%2F1',
      query: { organizationId: 'org-1' },
      body: { nextId: 'dept-0' },
    },
  ]);
});

it('scoped tree context preserves runtime tree ability availability', () => {
  const unavailableContext = createFakeModuleContext({ hasTree: false });
  const scopedContext = createScopedTreeModuleContext(unavailableContext, {
    scopeFieldName: 'organizationId',
    scopeValue: 'org-1',
    treePath: '/iam.department/tree',
  });

  assert.equal(scopedContext.abilities.hasTree(), false);
  assert.equal(scopedContext.abilities.tryTree(), undefined);
});

function createFakeModuleContext(options: { hasTree?: boolean } = {}) {
  const requests: HttpRequestOptions[] = [];
  const queries: Array<WebQueryRequest | undefined> = [];
  const hasTree = options.hasTree ?? true;
  const crud = {
    querySchema: async () => ({ fields: [] }),
    query: async (request?: WebQueryRequest) => {
      queries.push(request);
      return { records: [], total: 0, pageNum: 1, pageSize: 10, pages: 0, totalKnown: true };
    },
    view: async (id: string) => ({ id, title: id }),
    insert: async (record: DepartmentRecord) => ({ record }),
    update: async (_id: string, record: DepartmentRecord) => ({ record }),
    delete: async () => 1,
    enable: async () => 1,
    disable: async () => 1,
  };
  const context = {
    moduleAlias: 'iam.department',
    http: {
      request: async <TResponse>(request: HttpRequestOptions): Promise<TResponse> => {
        requests.push(request);
        return { records: [] } as TResponse;
      },
    },
    crud,
    runtime: {
      ready: Promise.resolve(),
      refresh: async () => undefined,
      snapshot: () => undefined,
      action: () => undefined,
      can: () => undefined,
      hasAbility: () => hasTree,
    },
    abilities: {
      crud: () => crud,
      tree: () => {
        if (!hasTree) {
          throw new Error('tree unavailable');
        }
        return crud;
      },
      enable: () => crud,
      tryCrud: () => crud,
      tryTree: () => (hasTree ? crud : undefined),
      tryEnable: () => crud,
      has: () => hasTree,
      hasCrud: () => true,
      hasTree: () => hasTree,
      hasEnable: () => true,
    },
    action: () => undefined,
    can: () => undefined,
    requests,
    queries,
  };
  return context as unknown as ModuleContext<DepartmentRecord> & {
    requests: HttpRequestOptions[];
    queries: Array<WebQueryRequest | undefined>;
  };
}
