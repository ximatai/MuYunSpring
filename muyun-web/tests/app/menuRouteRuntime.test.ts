// @vitest-environment jsdom

import { createPinia, setActivePinia } from 'pinia';
import { assert, beforeEach, it, vi } from 'vitest';
import { createRouter, createMemoryHistory } from 'vue-router';
import type { MenuTreeNode } from '@muyun/web-contracts';
import { createMenuRouteRuntime } from '@/app/menuRouteRuntime.ts';

beforeEach(() => {
  setActivePinia(createPinia());
});

it('loads menus and adds pages to the router passed in during creation', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'workbench', component: { template: '<RouterView />' } },
      { path: '/:pathMatch(.*)*', name: 'missing', component: { template: '<div>missing</div>' } },
    ],
  });
  const menus: MenuTreeNode[] = [
    {
      record: {
        id: 'customer',
        schemeId: 'default',
        title: '客户',
        enabled: true,
        entryType: 'module',
        openMode: 'tab',
        moduleAlias: 'crm.customer',
      },
      children: [],
    },
  ];
  const loadMenus = vi.fn(async () => menus);

  const runtime = createMenuRouteRuntime({ router, workbenchRouteName: 'workbench', loadMenus });
  await runtime.ensureMenuRoutes();

  assert.equal(loadMenus.mock.calls.length, 1);
  assert.equal(
    router.getRoutes().some((record) => record.name === 'menu:module:customer'),
    true,
  );
});

it('resolves a direct dynamic URL to its menu route after menu routes are prepared', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'workbench', component: { template: '<RouterView />' } },
      {
        path: '/:applicationAlias/:moduleName',
        name: 'dynamic-module-route',
        component: { template: '<div>dynamic</div>' },
      },
    ],
  });
  const menus: MenuTreeNode[] = [
    {
      record: {
        id: 'tenant',
        schemeId: 'default',
        title: '租户管理',
        enabled: true,
        entryType: 'module',
        openMode: 'tab',
        moduleAlias: 'iam.tenant',
        pageMode: 'LIST',
      },
      children: [],
    },
  ];
  const runtime = createMenuRouteRuntime({
    router,
    workbenchRouteName: 'workbench',
    loadMenus: async () => menus,
  });

  await runtime.ensureMenuRoutes();
  await router.push('/iam/tenant');

  assert.equal(router.currentRoute.value.meta.title, '租户管理');
  assert.equal(router.currentRoute.value.meta.menuId, 'tenant');
});
