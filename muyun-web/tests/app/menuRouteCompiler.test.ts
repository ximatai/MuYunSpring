import { assert, it, vi } from 'vitest';
import type { Component } from 'vue';
import type { MenuRecord, MenuTreeNode } from '@muyun/web-contracts';
import { validateAndCompileMenuRoutes, type RouteConfigurationIssueCode } from '@/app/menuRouteCompiler.ts';
import type { RoutePageLoader, StaticRouteDefinition } from '@/app/staticRouteDefinitions.ts';

const staticDefinition: StaticRouteDefinition = {
  route: '/iam/organizations',
  moduleAlias: 'iam.organization',
  componentPath: '/src/views/OrganizationManagementView.vue',
  layout: 'workspace',
};

function loaderTable(): Record<string, RoutePageLoader> {
  return {
    [staticDefinition.componentPath]: vi.fn(async () => ({}) as Component),
  };
}

function menu(overrides: Partial<MenuRecord>): MenuRecord {
  return { id: 'menu-1', schemeId: 'default', title: '菜单入口', enabled: true, ...overrides };
}

function issueCodes(result: ReturnType<typeof validateAndCompileMenuRoutes>): RouteConfigurationIssueCode[] {
  return result.issues.map((issue) => issue.code);
}

it('compiles each supported explicit menu entry without loading the Vue component', () => {
  const loaders = loaderTable();
  const staticLoader = loaders[staticDefinition.componentPath]!;
  const result = validateAndCompileMenuRoutes(
    [
      {
        record: menu({ id: 'dynamic', entryType: 'module', openMode: 'tab', moduleAlias: 'crm.customer' }),
        children: [],
      },
      {
        record: menu({
          id: 'static',
          entryType: 'route',
          openMode: 'tab',
          moduleAlias: 'iam.organization',
          route: staticDefinition.route,
        }),
        children: [],
      },
      {
        record: menu({
          id: 'external',
          entryType: 'link',
          openMode: 'tab',
          moduleAlias: 'ops.report',
          externalUrl: 'https://bi.example.test/report',
        }),
        children: [],
      },
      {
        record: menu({
          id: 'window',
          entryType: 'link',
          openMode: 'window',
          moduleAlias: 'ops.report',
          externalUrl: 'https://bi.example.test/window',
        }),
        children: [],
      },
    ] satisfies MenuTreeNode[],
    [staticDefinition],
    loaders,
  );

  assert.deepEqual(result.issues, []);
  assert.equal(result.validRoutes.length, 3);
  assert.equal(result.validRoutes[0]?.path, '/platform/dynamic/crm.customer/list');
  assert.equal(result.validRoutes[1]?.name, 'static:iam-organizations');
  assert.equal(result.validRoutes[2]?.path, '/platform/external/external');
  assert.deepEqual(
    result.windowMenus.map((item) => item.id),
    ['window'],
  );
  assert.equal(vi.mocked(staticLoader).mock.calls.length, 0, '编译阶段不得执行 Vue 懒加载函数');
});

it('reports missing entry type, static module mismatches, and missing components with a fixable source', () => {
  const result = validateAndCompileMenuRoutes(
    [
      menu({ id: 'missing-type', openMode: 'tab', moduleAlias: 'crm.customer' }),
      menu({
        id: 'wrong-module',
        entryType: 'route',
        openMode: 'tab',
        moduleAlias: 'crm.department',
        route: staticDefinition.route,
      }),
    ],
    [{ ...staticDefinition, componentPath: '/src/views/MissingView.vue' }],
    {},
  );

  assert.deepEqual(issueCodes(result), [
    'COMPONENT_NOT_FOUND',
    'MENU_ENTRY_TYPE_MISSING',
    'MODULE_ALIAS_MISMATCH',
  ]);
  assert.equal(result.issues[1]?.menuId, 'missing-type');
  assert.equal(result.issues[2]?.expected?.moduleAlias, 'iam.organization');
});

it('rejects every conflicting menu that claims the same URL but permits an exact shared definition', () => {
  const shared = menu({
    id: 'shared-a',
    entryType: 'route',
    openMode: 'tab',
    moduleAlias: 'iam.organization',
    route: staticDefinition.route,
  });
  const sharedCopy = { ...shared, id: 'shared-b' };
  const sharedResult = validateAndCompileMenuRoutes([shared, sharedCopy], [staticDefinition], loaderTable());
  assert.equal(sharedResult.validRoutes.length, 1);
  assert.deepEqual(issueCodes(sharedResult), []);

  const collisionDefinition: StaticRouteDefinition = {
    ...staticDefinition,
    route: '/platform/dynamic/crm.customer/list',
    moduleAlias: 'crm.customer',
  };
  const conflict = menu({
    id: 'conflict',
    entryType: 'route',
    openMode: 'tab',
    moduleAlias: 'crm.customer',
    route: collisionDefinition.route,
  });
  const module = menu({ id: 'dynamic', entryType: 'module', openMode: 'tab', moduleAlias: 'crm.customer' });
  const conflicted = validateAndCompileMenuRoutes([conflict, module], [collisionDefinition], loaderTable());
  assert.equal(conflicted.validRoutes.length, 0);
  assert.deepEqual(issueCodes(conflicted), ['ROUTE_CONFLICT', 'ROUTE_CONFLICT']);

  const dynamicA = menu({
    id: 'dynamic-a',
    entryType: 'module',
    openMode: 'tab',
    moduleAlias: 'crm.customer',
  });
  const dynamicB = { ...dynamicA, id: 'dynamic-b', pageMode: 'LIST' as const };
  const same = validateAndCompileMenuRoutes([dynamicA, dynamicB], [staticDefinition], loaderTable());
  assert.equal(same.validRoutes.length, 1);
  assert.deepEqual(issueCodes(same), []);
});

it('registers a non-menu user detail branch with the same verified user module route', () => {
  const userList: StaticRouteDefinition = {
    route: '/iam/users',
    moduleAlias: 'iam.user',
    componentPath: '/src/views/UserManagementView.vue',
    layout: 'workspace',
  };
  const userDetail: StaticRouteDefinition = { ...userList, route: '/iam/users/:userId', menuEntry: false };
  const loaders: Record<string, RoutePageLoader> = {
    [userList.componentPath]: vi.fn(async () => ({}) as Component),
  };

  const result = validateAndCompileMenuRoutes(
    [menu({ entryType: 'route', openMode: 'tab', moduleAlias: 'iam.user', route: userList.route })],
    [userList, userDetail],
    loaders,
  );

  assert.deepEqual(result.issues, []);
  assert.deepEqual(
    result.validRoutes.map((route) => route.path),
    ['/iam/users', '/iam/users/:userId'],
  );
});
