import { assert, it } from 'vitest';
import {
  createQueryScopedTreeModuleContext,
  createScopedTreeModuleContext,
} from '@/platform-components/scopedTreeModuleContext.ts';
import type { RouteQueryValue, WebQueryRequest } from '@/web-contracts/index.ts';
import type { HttpRequestOptions } from '@/web-core/http.ts';
import type { RecordQueryRequestOptions } from '@/web-core/module/staticModuleClient.ts';
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

it('preserves reading intent when it binds a query to its parent scope', async () => {
  const context = createFakeModuleContext();
  const scoped = createScopedTreeModuleContext(context, {
    scopeFieldName: 'organizationId',
    scopeValue: 'org-1',
    treePath: '/iam.department/tree',
  });
  await scoped.abilities.tree().query({}, { contentLanguage: 'de' });
  assert.deepEqual(context.queryOptions[0], { contentLanguage: 'de' });
  assert.deepEqual(context.queries[0]?.conditions, [
    { fieldName: 'organizationId', operator: 'EQ', values: ['org-1'] },
  ]);
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
  assert.deepEqual(await scopedContext.abilities.tree().query(), {
    records: [],
    total: 0,
    pageNum: 1,
    pageSize: 20,
    pages: 0,
    totalKnown: true,
  });
  assert.deepEqual(await scopedContext.abilities.tree().subtree('department-1'), { records: [] });
  assert.equal(await scopedContext.abilities.tree().sort('department-1', {}), 0);
  assert.deepEqual(context.queries, []);
  assert.deepEqual(context.requests, []);
});

it('scoped tree context resolves scope values lazily', async () => {
  const context = createFakeModuleContext();
  const selectedOrganization = { id: undefined as string | undefined };
  const scopedContext = createScopedTreeModuleContext(context, {
    scopeFieldName: 'organizationId',
    scopeValue: () => selectedOrganization.id,
    treePath: '/iam.department/tree',
  });

  assert.deepEqual(await scopedContext.abilities.tree().tree(), { records: [] });
  selectedOrganization.id = 'org-2';
  await scopedContext.abilities.tree().tree();

  assert.deepEqual(context.requests, [
    {
      path: '/iam.department/tree',
      query: { organizationId: 'org-2' },
      headers: { 'X-MuYun-Content-Languages': 'zh-CN,en,de' },
    },
  ]);
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
      headers: { 'X-MuYun-Content-Languages': 'zh-CN,en,de' },
      query: { organizationId: 'org-1', flat: true, includeSelf: true },
    },
    {
      path: '/iam.department/tree/dept%2F2',
      headers: { 'X-MuYun-Content-Languages': 'zh-CN,en,de' },
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

it('picker query context scopes tree requests and stays empty before its navigator value exists', async () => {
  const context = createFakeModuleContext();
  const queryValues = { value: undefined as Record<string, RouteQueryValue> | undefined };
  const scopedContext = createQueryScopedTreeModuleContext(context, {
    queryValues: () => queryValues.value,
    treePath: '/iam.department/tree',
  });

  assert.deepEqual(await scopedContext.abilities.tree().tree(), { records: [] });
  assert.deepEqual(await scopedContext.crud.query(), {
    records: [],
    total: 0,
    pageNum: 1,
    pageSize: 20,
    pages: 0,
    totalKnown: true,
  });
  assert.deepEqual(context.queries, []);
  queryValues.value = { organizationId: 'org-1' };
  await scopedContext.abilities.tree().tree();
  await scopedContext.crud.query();

  assert.deepEqual(context.requests, [
    {
      path: '/iam.department/tree',
      query: { organizationId: 'org-1' },
      headers: { 'X-MuYun-Content-Languages': 'zh-CN,en,de' },
    },
  ]);
  assert.deepEqual(context.queries, [
    { conditions: [{ fieldName: 'organizationId', operator: 'EQ', values: ['org-1'] }] },
  ]);
});

function createFakeModuleContext(options: { hasTree?: boolean } = {}) {
  const requests: HttpRequestOptions[] = [];
  const queries: Array<WebQueryRequest | undefined> = [];
  const queryOptions: Array<RecordQueryRequestOptions | undefined> = [];
  const hasTree = options.hasTree ?? true;
  const crud = {
    querySchema: async () => ({ fields: [] }),
    query: async (request?: WebQueryRequest, options?: RecordQueryRequestOptions) => {
      queries.push(request);
      queryOptions.push(options);
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
    queryOptions,
  };
  return context as unknown as ModuleContext<DepartmentRecord> & {
    requests: HttpRequestOptions[];
    queries: Array<WebQueryRequest | undefined>;
    queryOptions: Array<RecordQueryRequestOptions | undefined>;
  };
}

it('normalizes dynamic scoped tree records at every depth without moving reading labels into raw fields', async () => {
  const context = createFakeModuleContext();
  const texts = { title: { originalText: 'original', readings: { de: 'Gerät' } } };
  const record = { id: 'one', version: 1, values: { title: 'original' }, children: {}, $contentTexts: texts };
  context.http.request = async (request: HttpRequestOptions) =>
    (request.query?.flat
      ? { records: [record] }
      : { records: [{ record, children: [{ record: { ...record, id: 'two' }, children: [] }] }] }) as never;
  const client = createScopedTreeModuleContext<Record<string, unknown>>(context as never, {
    scopeFieldName: 'organizationId',
    scopeValue: 'org-1',
    treePath: '/test.category/tree',
  }).abilities.tree();
  const tree = await client.tree();
  assert.deepEqual(tree.records[0]?.record, {
    id: 'one',
    version: 1,
    title: 'original',
    $contentTexts: texts,
  });
  assert.deepEqual(tree.records[0]?.children[0]?.record, {
    id: 'two',
    version: 1,
    title: 'original',
    $contentTexts: texts,
  });
  assert.deepEqual((await client.treeFlat()).records[0], {
    id: 'one',
    version: 1,
    title: 'original',
    $contentTexts: texts,
  });
});
