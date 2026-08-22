import { assert, it } from 'vitest';
import {
  platformAdminModuleRoutes,
  platformAdminRoutePrefixes,
  isPlatformAdminRoutePage,
  resolvePlatformAdminRoute,
} from '@/platform-admin-runtime/platformAdminRoutes.ts';
import { pageDescriptorFromUrl, pageDescriptorToUrl } from '@/platform-workbench/menuNavigation.ts';
import type { BusinessRoutePageDescriptor } from '@/web-contracts/index.ts';

it('uses backend application and module aliases for every public static route', () => {
  assert.deepEqual(platformAdminRoutePrefixes, [
    '/_platform/workspace',
    '/platform/dictionary-category',
    '/platform/menu-scheme',
    '/iam/employee',
    '/iam/user',
    '/iam/user/form',
    '/iam/user/form/:userId',
    '/iam/system-user',
    '/iam/role',
    '/iam/role/authorization',
  ]);
  assert.deepEqual(platformAdminModuleRoutes, {
    'platform.dictionary_category': '/platform/dictionary-category',
    'platform.menu_scheme': '/platform/menu-scheme',
    'iam.employee': '/iam/employee',
    'iam.user': '/iam/user',
    'iam.system_user': '/iam/system-user',
    'iam.role': '/iam/role',
  });
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
    target: { route: '/iam/user', moduleAlias: 'iam.user' },
    tabPolicy: { identity: 'by-menu' },
  };
  assert.equal(resolvePlatformAdminRoute(descriptor)?.route, '/iam/user');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
  assert.equal(
    resolvePlatformAdminRoute({ ...descriptor, target: { route: '/iam/user', moduleAlias: 'crm.customer' } }),
    undefined,
  );
});
