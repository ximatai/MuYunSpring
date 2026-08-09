import { assert, it } from 'vitest';
import { createScopedResourceTreeModuleContext } from '@/platform-components/scopedResourceTreeModuleContext.ts';
import type { MenuRecord } from '@/web-contracts/index.ts';
import type { HttpRequestOptions } from '@/web-core/http.ts';
import type { ModuleContext } from '@/web-core/module/moduleContext.ts';

it('scoped resource tree context targets nested menu resource paths', async () => {
  const context = createFakeModuleContext();
  const scoped = createScopedResourceTreeModuleContext<MenuRecord, MenuRecord>(context, {
    resourcePath: '/platform.menu-scheme/platform.menu_scheme.admin/menus',
  });

  await scoped.abilities.tree().tree();
  await scoped.abilities.tree().treeFlat({ rootId: 'menu/1', includeSelf: false });
  await scoped.abilities.crud().insert({ id: 'menu-1', schemeId: 'scheme-1', title: '菜单' });

  assert.deepEqual(context.requests, [
    { path: '/platform.menu-scheme/platform.menu_scheme.admin/menus/tree' },
    {
      path: '/platform.menu-scheme/platform.menu_scheme.admin/menus/tree/menu%2F1',
      query: { flat: true, includeSelf: false },
    },
    {
      method: 'POST',
      path: '/platform.menu-scheme/platform.menu_scheme.admin/menus/insert',
      body: { id: 'menu-1', schemeId: 'scheme-1', title: '菜单' },
    },
  ]);
});

it('scoped resource tree context returns empty tree before resource path is available', async () => {
  const context = createFakeModuleContext();
  const scoped = createScopedResourceTreeModuleContext<MenuRecord, MenuRecord>(context, {
    emptyQueryScopeName: 'platform.menu.empty',
  });

  assert.deepEqual(await scoped.abilities.tree().tree(), { records: [] });
  assert.deepEqual(await scoped.abilities.tree().treeFlat(), { records: [] });
  assert.deepEqual(context.requests, []);
});

function createFakeModuleContext() {
  const requests: HttpRequestOptions[] = [];
  const crud = {
    querySchema: async () => ({ fields: [] }),
    query: async () => ({ records: [], total: 0, pageNum: 1, pageSize: 10, pages: 0, totalKnown: true }),
    view: async (id: string) => ({ id, schemeId: 'scheme-1', title: id }),
    insert: async (record: MenuRecord) => ({ record }),
    update: async (_id: string, record: MenuRecord) => ({ record }),
    delete: async () => 1,
    enable: async () => 1,
    disable: async () => 1,
  };
  const context = {
    moduleAlias: 'platform.menu',
    http: {
      request: async <TResponse>(request: HttpRequestOptions): Promise<TResponse> => {
        requests.push(request);
        if (request.path.endsWith('/insert')) {
          return { record: request.body } as TResponse;
        }
        return { records: [] } as TResponse;
      },
    },
    crud,
    runtime: {
      ready: Promise.resolve(),
      refresh: async () => undefined,
      snapshot: () => undefined,
      action: () => undefined,
      can: () => true,
      hasAbility: () => true,
    },
    abilities: {
      crud: () => crud,
      tree: () => crud,
      enable: () => crud,
      tryCrud: () => crud,
      tryTree: () => crud,
      tryEnable: () => crud,
      has: () => true,
      hasCrud: () => true,
      hasTree: () => true,
      hasEnable: () => true,
    },
    action: () => undefined,
    can: () => true,
    requests,
  };
  return context as unknown as ModuleContext<MenuRecord> & { requests: HttpRequestOptions[] };
}
