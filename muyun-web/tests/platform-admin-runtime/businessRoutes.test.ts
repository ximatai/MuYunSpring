import { assert, it } from 'vitest';
import {
  platformAdminModuleRoutes,
  platformAdminRoutePrefixes,
  isPlatformAdminRoutePage,
  resolvePlatformAdminRoute,
} from '@/platform-admin-runtime/platformAdminRoutes.ts';
import { pageDescriptorFromUrl } from '@/platform-workbench/menuNavigation.ts';
import type { BusinessRoutePageDescriptor } from '@/web-contracts/index.ts';

it('static business route registry exposes route prefixes for navigation resolution', () => {
  assert.deepEqual(platformAdminRoutePrefixes, [
    '/config/field-specs',
    '/config/field-ui-controls',
    '/config/dictionaries',
    '/config/modules',
    '/config/menus',
    '/platform/security/passwords',
    '/iam/tenants',
    '/iam/organizations',
    '/iam/departments',
    '/iam/employees',
    '/iam/users',
    '/iam/system-users',
    '/iam/roles',
    '/iam/role-authorization',
    '/iam/positions',
  ]);
  assert.deepEqual(platformAdminModuleRoutes, {
    'platform.field_spec': '/config/field-specs',
    'platform.field_ui_control': '/config/field-ui-controls',
    'platform.dictionary_category': '/config/dictionaries',
    'platform.module': '/config/modules',
    'platform.menu_scheme': '/config/menus',
    'iam.password_policy_rule': '/platform/security/passwords',
    'iam.tenant': '/iam/tenants',
    'iam.organization': '/iam/organizations',
    'iam.department': '/iam/departments',
    'iam.employee': '/iam/employees',
    'iam.user': '/iam/users',
    'iam.system_user': '/iam/system-users',
    'iam.role': '/iam/roles',
    'iam.position_category': '/iam/positions',
  });
});

it('role authorization is a direct workbench page and does not replace the role menu route', () => {
  const descriptor = pageDescriptorFromUrl('/iam/role-authorization?roleId=role-1', {
    businessRoutePrefixes: platformAdminRoutePrefixes,
  });
  assert.equal(descriptor.pageType, 'business-route');
  assert.deepEqual(descriptor.target, { route: '/iam/role-authorization', query: { roleId: 'role-1' } });
  assert.equal(platformAdminModuleRoutes['iam.role'], '/iam/roles');
});

it('static business route registry resolves module alias by route', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/organizations' },
    tabPolicy: { identity: 'by-target' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.moduleAlias, 'iam.organization');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves password management module route', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/platform/security/passwords', moduleAlias: 'iam.password_policy_rule' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/platform/security/passwords');
  assert.equal(route?.moduleAlias, 'iam.password_policy_rule');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves password management URL under platform namespace', () => {
  const descriptor = pageDescriptorFromUrl('/platform/security/passwords', {
    businessRoutePrefixes: platformAdminRoutePrefixes,
  });

  assert.equal(descriptor.pageType, 'business-route');
  assert.equal(descriptor.hostType, 'business-route-host');
  assert.deepEqual(descriptor.target, {
    route: '/platform/security/passwords',
    query: undefined,
  });
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves tenant management module route', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/tenants', moduleAlias: 'iam.tenant' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/iam/tenants');
  assert.equal(route?.moduleAlias, 'iam.tenant');
});

it('static business route registry resolves position category as position management entry', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/positions', moduleAlias: 'iam.position_category' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/iam/positions');
  assert.equal(route?.moduleAlias, 'iam.position_category');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves department management module route', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/departments', moduleAlias: 'iam.department' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/iam/departments');
  assert.equal(route?.moduleAlias, 'iam.department');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves employee management module route', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/employees', moduleAlias: 'iam.employee' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/iam/employees');
  assert.equal(route?.moduleAlias, 'iam.employee');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves role management module route', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/roles', moduleAlias: 'iam.role' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/iam/roles');
  assert.equal(route?.moduleAlias, 'iam.role');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves user management module route', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/users', moduleAlias: 'iam.user' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/iam/users');
  assert.equal(route?.moduleAlias, 'iam.user');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves system user management module route', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/system-users', moduleAlias: 'iam.system_user' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/iam/system-users');
  assert.equal(route?.moduleAlias, 'iam.system_user');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves dictionary category as dictionary management entry', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/config/dictionaries', moduleAlias: 'platform.dictionary_category' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/config/dictionaries');
  assert.equal(route?.moduleAlias, 'platform.dictionary_category');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry resolves menu scheme as menu management entry', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/config/menus', moduleAlias: 'platform.menu_scheme' },
    tabPolicy: { identity: 'by-menu' },
  };

  const route = resolvePlatformAdminRoute(descriptor);

  assert.equal(route?.route, '/config/menus');
  assert.equal(route?.moduleAlias, 'platform.menu_scheme');
  assert.equal(isPlatformAdminRoutePage(descriptor), true);
});

it('static business route registry does not capture a business module that reuses a platform route', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/roles', moduleAlias: 'crm.customer' },
    tabPolicy: { identity: 'by-menu' },
  };

  assert.equal(resolvePlatformAdminRoute(descriptor), undefined);
  assert.equal(isPlatformAdminRoutePage(descriptor), false);
});

it('static business route registry prefers explicit route over module alias fallback', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/config/unknown', moduleAlias: 'platform.application' },
    tabPolicy: { identity: 'by-target' },
  };

  assert.equal(resolvePlatformAdminRoute(descriptor), undefined);
  assert.equal(isPlatformAdminRoutePage(descriptor), false);
});

it('static business route registry rejects unregistered business routes', () => {
  const descriptor: BusinessRoutePageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    target: { route: '/iam/accounts' },
    tabPolicy: { identity: 'by-target' },
  };

  assert.equal(resolvePlatformAdminRoute(descriptor), undefined);
  assert.equal(isPlatformAdminRoutePage(descriptor), false);
});

it('static business route registry does not classify unregistered sibling routes as business pages', () => {
  const descriptor = pageDescriptorFromUrl('/iam/users-extra', {
    businessRoutePrefixes: platformAdminRoutePrefixes,
  });

  assert.equal(descriptor.pageType, 'platform-route');
});
