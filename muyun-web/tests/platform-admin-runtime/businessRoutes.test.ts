import { assert, it } from 'vitest';
import {
  platformAdminModuleRoutes,
  platformAdminRoutePrefixes,
  isPlatformAdminRoutePage,
  resolvePlatformAdminRoute,
} from '@/platform-admin-runtime/platformAdminRoutes.ts';
import { validateAndCompileMenuRoutes } from '@/app/menuRouteCompiler.ts';
import { routePageLoaders, staticRouteDefinitions } from '@/app/staticRouteDefinitions.ts';
import { pageDescriptorFromUrl, pageDescriptorToUrl } from '@/platform-workbench/menuNavigation.ts';
import type { BusinessRoutePageDescriptor } from '@/web-contracts/index.ts';

it('uses backend application and module aliases for every public static route', () => {
  assert.deepEqual(platformAdminRoutePrefixes, [
    '/_platform/workspace',
    '/iam/role',
    '/iam/role/authorization',
  ]);
  assert.deepEqual(platformAdminModuleRoutes, {
    'iam.role': '/iam/role',
  });
});

it('accepts every backend-published static menu route in the frontend registry', () => {
  const menus = Object.entries(platformAdminModuleRoutes).map(([moduleAlias, route]) => ({
    id: `menu:${moduleAlias}`,
    schemeId: 'platform',
    title: moduleAlias,
    enabled: true,
    entryType: 'route' as const,
    openMode: 'tab' as const,
    moduleAlias,
    route,
  }));

  const result = validateAndCompileMenuRoutes(menus, staticRouteDefinitions, routePageLoaders);

  assert.deepEqual(result.issues, []);
  assert.deepEqual(
    menus.map((menu) => menu.route).sort(),
    result.validRoutes
      .filter((route) => menus.some((menu) => menu.route === route.path))
      .map((route) => route.path)
      .sort(),
  );
});

it('resolves direct static workbench pages without replacing their backend module identity', () => {
  const descriptor = pageDescriptorFromUrl('/iam/role/authorization?roleId=role-1', {
    businessRoutePrefixes: platformAdminRoutePrefixes,
  });
  assert.equal(descriptor.pageType, 'business-route');
  assert.deepEqual(descriptor.target, { route: '/iam/role/authorization', query: { roleId: 'role-1' } });
  assert.equal(platformAdminModuleRoutes['iam.role'], '/iam/role');
});

it('derives standard dynamic module routes without a frontend override catalog', () => {
  const cases = [
    ['/platform/module', 'platform.module'],
    ['/platform/field-spec', 'platform.field_spec'],
    ['/platform/field-ui-control', 'platform.field_ui_control'],
    ['/iam/tenant', 'iam.tenant'],
    ['/iam/password-policy-rule', 'iam.password_policy_rule'],
  ] as const;

  for (const [url, moduleAlias] of cases) {
    const descriptor = pageDescriptorFromUrl(url);
    assert.equal(descriptor.pageType, 'dynamic-module');
    assert.equal(descriptor.target.moduleAlias, moduleAlias);
    assert.equal(pageDescriptorToUrl(descriptor), url);
  }
});

it('resolves a static page only when both route and backend module match', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/role', moduleAlias: 'iam.role' },
    tabPolicy: { identity: 'by-menu' },
  };
  assert.equal(resolvePlatformAdminRoute(descriptor)?.route, '/iam/role');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
  assert.equal(
    resolvePlatformAdminRoute({ ...descriptor, target: { route: '/iam/role', moduleAlias: 'crm.customer' } }),
    undefined,
  );
});
