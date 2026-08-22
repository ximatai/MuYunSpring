import { assert, it } from 'vitest';
import {
  canonicalDynamicModulePath,
  createMenuTab,
  dynamicModuleAliasFromPath,
  getMenuNavigationTarget,
  isTabMenuTarget,
  isWindowMenuTarget,
  pageDescriptorFromUrl,
  pageDescriptorToUrl,
  resolvePageDescriptor,
  tabKeyOf,
  tryPageDescriptorFromUrl,
} from '@/platform-workbench/menuNavigation.ts';
import type { PageDescriptor } from '@/web-contracts/index.ts';
import { platformAdminRoutes } from '@/platform-admin-runtime/platformAdminRoutes.ts';

function assertPageType<T extends PageDescriptor['pageType']>(
  descriptor: PageDescriptor,
  pageType: T,
): asserts descriptor is Extract<PageDescriptor, { pageType: T }> {
  assert.equal(descriptor.pageType, pageType);
}

it('resolves a platform module route through the backend alias catalog', () => {
  const descriptor = resolvePageDescriptor(
    {
      menuId: 'metadata',
      menuType: 'route',
      openMode: 'tab',
      route: '/platform/metadata',
    },
    { title: 'Metadata' },
  );

  assertPageType(descriptor, 'platform-route');
  assert.equal(descriptor.openMode, 'workbench-route');
  assert.equal(descriptor.hostType, 'platform-route-host');
  assert.equal(descriptor.title, 'Metadata');
  assert.equal(descriptor.target.route, '/platform/metadata');
  assert.equal(tabKeyOf(descriptor), 'menu:metadata');
  assert.equal(pageDescriptorToUrl(descriptor), '/platform/metadata');
  const roundTrip = pageDescriptorFromUrl(pageDescriptorToUrl(descriptor));
  assert.equal(roundTrip.pageType, 'dynamic-module');
  assert.equal(roundTrip.target.moduleAlias, 'platform.metadata');
  assert.equal(roundTrip.title, undefined);
  assert.equal(roundTrip.tabPolicy.identity, 'by-target');
});

it('delegates a readable module route to the dynamic module host when the route is declared as standard runtime content', () => {
  const options = { dynamicModuleRoutes: { '/iam/organizations': 'iam.organization' } };
  const descriptor = resolvePageDescriptor(
    {
      menuId: 'organization',
      menuType: 'route',
      openMode: 'tab',
      route: '/iam/organizations',
      moduleAlias: 'iam.organization',
    },
    options,
  );

  assertPageType(descriptor, 'dynamic-module');
  assert.equal(descriptor.target.moduleAlias, 'iam.organization');
  assert.equal(descriptor.target.pageMode, 'LIST');

  const restored = pageDescriptorFromUrl('/iam/organizations?menu=organization', options);
  assertPageType(restored, 'dynamic-module');
  assert.equal(restored.target.moduleAlias, 'iam.organization');
  assert.equal(restored.menuId, 'organization');
});

it('resolves the canonical module-management URL through the neutral module host', () => {
  const descriptor = pageDescriptorFromUrl('/platform/module');

  assertPageType(descriptor, 'dynamic-module');
  assert.equal(descriptor.hostType, 'module-page-host');
  assert.equal(descriptor.target.moduleAlias, 'platform.module');
  assert.equal(descriptor.menuId, undefined);
  assert.equal(tabKeyOf(descriptor), 'dynamic-module:platform.module:LIST');
});

it('derives a reversible default catalog path without registering every module by hand', () => {
  assert.equal(canonicalDynamicModulePath('crm.customer_order'), '/crm/customer-order');
  assert.equal(dynamicModuleAliasFromPath('/crm/customer-order'), 'crm.customer_order');
  assert.equal(canonicalDynamicModulePath('platform.field_spec'), '/platform/field-spec');
});

it('keeps dynamic URL state public and rejects the retired technical route', () => {
  const descriptor = resolvePageDescriptor({
    menuId: 'customer-module',
    menuType: 'module',
    openMode: 'tab',
    moduleAlias: 'crm.customer',
    pageMode: 'FORM',
    query: { InstanceKey: 'internal-instance', recordId: 'customer-1' },
  });

  assert.equal(pageDescriptorToUrl(descriptor), '/crm/customer?mode=form&recordId=customer-1');
  assert.equal(tryPageDescriptorFromUrl('/platform/dynamic/crm.customer/list'), undefined);
  assert.equal(
    tryPageDescriptorFromUrl('/crm/customer?InstanceKey=old&recordId=customer-1')?.params?.InstanceKey,
    undefined,
  );
});

it('getMenuNavigationTarget ignores disabled menus', () => {
  const target = getMenuNavigationTarget({
    id: 'disabled-runtime',
    schemeId: 'default',
    title: 'Disabled Runtime',
    entryType: 'module',
    openMode: 'tab',
    moduleAlias: 'platform.runtime',
    enabled: false,
  });

  assert.equal(target, undefined);
});

it('getMenuNavigationTarget requires explicit open mode for navigation menus', () => {
  const target = getMenuNavigationTarget({
    id: 'missing-open-mode',
    schemeId: 'default',
    title: 'Missing Open Mode',
    moduleAlias: 'crm.customer',
    externalUrl: '/crm/customer/list',
  });

  assert.equal(target, undefined);
});

it('resolvePageDescriptor keeps path, routeName, and pageKey available for offline business routes', () => {
  const pathDescriptor = resolvePageDescriptor(
    {
      menuId: 'customer-list',
      menuType: 'route',
      openMode: 'tab',
      route: '/crm/customer/list',
    },
    { businessRoutePrefixes: ['/crm'] },
  );
  const routeNameDescriptor = resolvePageDescriptor(
    {
      menuId: 'customer-name',
      menuType: 'route',
      openMode: 'tab',
      route: 'crm.customer.list',
    },
    { businessRouteNames: ['crm.customer.list'] },
  );
  const pageKeyDescriptor = resolvePageDescriptor(
    {
      menuId: 'customer-page',
      menuType: 'route',
      openMode: 'tab',
      route: 'customerList',
    },
    { businessPageKeys: ['customerList'] },
  );

  assertPageType(pathDescriptor, 'business-route');
  assert.equal(pathDescriptor.hostType, 'business-route-host');
  assert.equal(pathDescriptor.target.route, '/crm/customer/list');
  assertPageType(routeNameDescriptor, 'business-route');
  assert.equal(routeNameDescriptor.target.routeName, 'crm.customer.list');
  assertPageType(pageKeyDescriptor, 'business-route');
  assert.equal(pageKeyDescriptor.target.pageKey, 'customerList');
});

it('resolves a module menu without a static page through the dynamic module host', () => {
  const descriptor = resolvePageDescriptor({
    menuId: 'module-management',
    menuType: 'module',
    openMode: 'tab',
    moduleAlias: 'platform.module',
  });

  assertPageType(descriptor, 'dynamic-module');
  assert.equal(descriptor.target.moduleAlias, 'platform.module');
  assert.equal(pageDescriptorToUrl(descriptor), '/platform/module');
  const restored = pageDescriptorFromUrl('/platform/module');
  assert.equal(restored.pageType, 'dynamic-module');
});

it('keeps dynamic menu titles out of shared URLs because recipients resolve their own menu facts', () => {
  const descriptor = resolvePageDescriptor(
    {
      menuId: 'tenant',
      menuType: 'module',
      openMode: 'tab',
      moduleAlias: 'iam.tenant',
      pageMode: 'LIST',
    },
    { title: '租户管理' },
  );

  assertPageType(descriptor, 'dynamic-module');
  assert.equal(pageDescriptorToUrl(descriptor), '/iam/tenant');
  assert.equal(pageDescriptorFromUrl(pageDescriptorToUrl(descriptor)).title, undefined);
});

it('every static business route explicitly classifies its page layout', () => {
  assert.ok(platformAdminRoutes.every((route) => route.layout === 'flow' || route.layout === 'workspace'));
});

it('role management is a constrained workspace page', () => {
  const descriptor = resolvePageDescriptor(
    {
      menuId: 'roles',
      menuType: 'route',
      openMode: 'tab',
      route: '/iam/roles',
      moduleAlias: 'iam.role',
    },
    {
      businessRoutePrefixes: ['/iam/roles'],
      businessRouteLayouts: { '/iam/roles': 'workspace' },
    },
  );

  assert.equal(descriptor.layout, 'workspace');
});

it('system user management is a constrained workspace page', () => {
  const descriptor = resolvePageDescriptor(
    {
      menuId: 'system-users',
      menuType: 'route',
      openMode: 'tab',
      route: '/iam/system-users',
      moduleAlias: 'iam.system_user',
    },
    {
      businessRoutePrefixes: ['/iam/system-users'],
      businessRouteLayouts: { '/iam/system-users': 'workspace' },
    },
  );

  assert.equal(descriptor.layout, 'workspace');
});

it('user management is a constrained workspace page', () => {
  const descriptor = resolvePageDescriptor(
    {
      menuId: 'users',
      menuType: 'route',
      openMode: 'tab',
      route: '/iam/users',
      moduleAlias: 'iam.user',
    },
    {
      businessRoutePrefixes: ['/iam/users'],
      businessRouteLayouts: { '/iam/users': 'workspace' },
    },
  );

  assert.equal(descriptor.layout, 'workspace');
});

it('resolvePageDescriptor carries route menu module alias for business module context', () => {
  const target = getMenuNavigationTarget({
    id: 'organization',
    schemeId: 'default',
    title: '组织管理',
    entryType: 'route',
    openMode: 'tab',
    route: '/iam/organizations',
    moduleAlias: 'iam.organization',
  });

  assert.ok(target);
  const descriptor = resolvePageDescriptor(target, { businessRoutePrefixes: ['/iam'] });

  assertPageType(descriptor, 'business-route');
  assert.equal(descriptor.target.route, '/iam/organizations');
  assert.equal(descriptor.target.moduleAlias, 'iam.organization');
});

it('resolvePageDescriptor lets registered business routes under platform namespace override broad platform prefix', () => {
  const target = getMenuNavigationTarget({
    id: 'passwords',
    schemeId: 'default',
    title: '密码管理',
    entryType: 'route',
    openMode: 'tab',
    route: '/platform/security/passwords',
    moduleAlias: 'iam.password_policy_rule',
  });

  assert.ok(target);
  const descriptor = resolvePageDescriptor(target, {
    businessRoutePrefixes: ['/platform/security/passwords'],
  });

  assertPageType(descriptor, 'business-route');
  assert.equal(descriptor.hostType, 'business-route-host');
  assert.equal(descriptor.target.route, '/platform/security/passwords');
  assert.equal(descriptor.target.moduleAlias, 'iam.password_policy_rule');
});

it('resolvePageDescriptor resolves MODULE targets as dynamic module descriptors', () => {
  const descriptor = resolvePageDescriptor({
    menuId: 'customer-module',
    menuType: 'module',
    openMode: 'tab',
    moduleAlias: 'crm.customer',
    pageMode: 'LIST',
    defaultUiConfigId: 'customer-list-v1',
    defaultQueryTemplateId: 'customer-query-v1',
    entryParamsJson: '{"source":"menu"}',
    query: { recordId: 'customer-1' },
  });

  assertPageType(descriptor, 'dynamic-module');
  assert.equal(descriptor.openMode, 'dynamic-runner');
  assert.equal(descriptor.hostType, 'module-page-host');
  assert.equal(descriptor.target.moduleAlias, 'crm.customer');
  assert.equal(descriptor.target.pageMode, 'LIST');
  assert.equal(descriptor.entryParamsJson, '{"source":"menu"}');
  assert.equal(pageDescriptorToUrl(descriptor), '/crm/customer?recordId=customer-1');
  const roundTrip = pageDescriptorFromUrl(pageDescriptorToUrl(descriptor));
  assert.equal(roundTrip.params?.recordId, 'customer-1');
  assert.equal(roundTrip.menuId, undefined);
  assert.equal(roundTrip.tabPolicy.identity, 'by-target');
});

it('resolvePageDescriptor resolves unconfigured MODULE targets through the standard runner', () => {
  const descriptor = resolvePageDescriptor(
    {
      menuId: 'platform.menu.module.platform.application',
      menuType: 'module',
      openMode: 'tab',
      moduleAlias: 'platform.application',
    },
    {
      title: '应用管理',
      businessModuleRoutes: {
        'iam.tenant': '/iam/tenants',
      },
    },
  );

  assertPageType(descriptor, 'dynamic-module');
  assert.equal(descriptor.hostType, 'module-page-host');
  assert.equal(descriptor.title, '应用管理');
  assert.equal(descriptor.target.moduleAlias, 'platform.application');
  assert.equal(tabKeyOf(descriptor), 'menu:platform.menu.module.platform.application');
});

it('resolvePageDescriptor resolves tenant MODULE target as business route', () => {
  const descriptor = resolvePageDescriptor(
    {
      menuId: 'platform.menu.module.iam.tenant',
      menuType: 'module',
      openMode: 'tab',
      moduleAlias: 'iam.tenant',
    },
    {
      title: '租户管理',
      businessModuleRoutes: {
        'iam.tenant': '/iam/tenants',
      },
    },
  );

  assertPageType(descriptor, 'business-route');
  assert.equal(descriptor.target.route, '/iam/tenants');
  assert.equal(descriptor.target.moduleAlias, 'iam.tenant');
});

it('resolvePageDescriptor resolves LINK targets by open mode', () => {
  const iframeDescriptor = resolvePageDescriptor({
    menuId: 'crm-online',
    menuType: 'link',
    openMode: 'tab',
    externalUrl: '/crm/customer/list',
    moduleAlias: 'crm.customer',
  });
  const newWindowDescriptor = resolvePageDescriptor({
    menuId: 'external-bi',
    menuType: 'link',
    openMode: 'window',
    externalUrl: 'https://bi.example.com/report',
  });

  assert.equal(iframeDescriptor.pageType, 'remote-url');
  assert.equal(iframeDescriptor.openMode, 'iframe');
  assert.equal(iframeDescriptor.hostType, 'external-page-host');
  assert.equal(iframeDescriptor.target.moduleAlias, 'crm.customer');
  assert.equal(tabKeyOf(iframeDescriptor), 'menu:crm-online');
  assert.equal(
    pageDescriptorToUrl(iframeDescriptor),
    '/_platform/external?mode=iframe&url=%2Fcrm%2Fcustomer%2Flist',
  );
  assert.equal(newWindowDescriptor.pageType, 'external-link');
  assert.equal(newWindowDescriptor.openMode, 'new-window');
  assert.equal(newWindowDescriptor.hostType, 'external-page-host');
  assert.equal(tabKeyOf(newWindowDescriptor), 'menu:external-bi');
  assert.equal(
    pageDescriptorToUrl(newWindowDescriptor),
    '/_platform/external?mode=new-window&url=https%3A%2F%2Fbi.example.com%2Freport',
  );
});

it('getMenuNavigationTarget carries link module alias for module-first menu entries', () => {
  const target = getMenuNavigationTarget({
    id: 'external-bi',
    schemeId: 'default',
    title: 'External BI',
    entryType: 'link' as const,
    openMode: 'window',
    externalUrl: 'https://bi.example.com/report',
    moduleAlias: 'ops.report',
  });

  assert.ok(target);
  assert.equal(target.menuType, 'link');
  assert.equal(target.moduleAlias, 'ops.report');

  const descriptor = resolvePageDescriptor(target);
  assert.equal(descriptor.pageType, 'external-link');
  assert.equal(descriptor.target.moduleAlias, 'ops.report');
});

it('pageDescriptorToUrl keeps new-window external links on workbench-owned URLs', () => {
  const descriptor = resolvePageDescriptor({
    menuId: 'external-bi',
    menuType: 'link',
    openMode: 'window',
    externalUrl: 'https://bi.example.com/report',
  });

  assert.equal(
    pageDescriptorToUrl(descriptor),
    '/_platform/external?mode=new-window&url=https%3A%2F%2Fbi.example.com%2Freport',
  );

  const restored = pageDescriptorFromUrl(pageDescriptorToUrl(descriptor));
  assertPageType(restored, 'external-link');
  assert.equal(restored.openMode, 'new-window');
  assert.equal(restored.target.url, 'https://bi.example.com/report');
  assert.equal(restored.menuId, undefined);
  assert.equal(restored.tabPolicy.identity, 'by-target');
});

it('resolvePageDescriptor uses explicit LINK open mode instead of url shape', () => {
  const iframeDescriptor = resolvePageDescriptor({
    menuId: 'protocol-relative-tab',
    menuType: 'link',
    openMode: 'tab',
    externalUrl: '//bi.example.com/report',
  });
  const newWindowDescriptor = resolvePageDescriptor({
    menuId: 'relative-window',
    menuType: 'link',
    openMode: 'window',
    externalUrl: '/crm/customer/list',
  });

  assert.equal(iframeDescriptor.pageType, 'remote-url');
  assert.equal(iframeDescriptor.openMode, 'iframe');
  assert.equal(newWindowDescriptor.pageType, 'external-link');
  assert.equal(newWindowDescriptor.openMode, 'new-window');
});

it('menu target open mode helpers split tab and window behavior', () => {
  const tabTarget = getMenuNavigationTarget({
    id: 'crm-online',
    schemeId: 'default',
    title: 'CRM Online',
    entryType: 'link',
    openMode: 'tab',
    externalUrl: '/crm/customer/list',
    moduleAlias: 'crm.customer',
  });
  const windowTarget = getMenuNavigationTarget({
    id: 'external-bi',
    schemeId: 'default',
    title: 'External BI',
    entryType: 'link',
    openMode: 'window',
    externalUrl: 'https://bi.example.com/report',
    moduleAlias: 'ops.report',
  });

  assert.ok(tabTarget);
  assert.ok(windowTarget);
  assert.equal(isTabMenuTarget(tabTarget), true);
  assert.equal(isWindowMenuTarget(tabTarget), false);
  assert.equal(isTabMenuTarget(windowTarget), false);
  assert.equal(isWindowMenuTarget(windowTarget), true);
});

it('createMenuTab rejects window menu targets', () => {
  const menu = {
    id: 'external-bi',
    schemeId: 'default',
    title: 'External BI',
    entryType: 'link' as const,
    openMode: 'window' as const,
    externalUrl: 'https://bi.example.com/report',
    moduleAlias: 'ops.report',
  };
  const target = getMenuNavigationTarget(menu);

  assert.ok(target);
  assert.throws(() => createMenuTab(menu, target), /WINDOW menu target cannot be opened/);
});

it('pageDescriptorFromUrl restores readable dynamic, external, and business URLs', () => {
  const dynamicDescriptor = pageDescriptorFromUrl('/crm/customer?recordId=customer-1');
  const externalDescriptor = pageDescriptorFromUrl(
    '/_platform/external?url=%2Fcrm%2Fcustomer%2Flist&mode=iframe',
  );
  const businessDescriptor = pageDescriptorFromUrl('/crm/customer/list?status=active', {
    businessRoutePrefixes: ['/crm'],
  });

  assertPageType(dynamicDescriptor, 'dynamic-module');
  assert.equal(dynamicDescriptor.target.moduleAlias, 'crm.customer');
  assert.equal(dynamicDescriptor.target.pageMode, 'LIST');
  assertPageType(externalDescriptor, 'remote-url');
  assert.equal(externalDescriptor.target.url, '/crm/customer/list');
  assertPageType(businessDescriptor, 'business-route');
  assert.equal(businessDescriptor.target.route, '/crm/customer/list');
  assert.deepEqual(businessDescriptor.params, { status: 'active' });
});

it('pageDescriptorFromUrl restores public dynamic module parameters', () => {
  const descriptor = pageDescriptorFromUrl('/crm/customer?recordId=customer-1');

  assertPageType(descriptor, 'dynamic-module');
  assert.deepEqual(descriptor.params, { recordId: 'customer-1' });
  assert.equal(descriptor.target.defaultUiConfigId, undefined);
});

it('tryPageDescriptorFromUrl rejects invalid workbench-owned URLs', () => {
  assert.equal(tryPageDescriptorFromUrl('/_platform/external'), undefined);
  assert.equal(tryPageDescriptorFromUrl('/platform/dynamic'), undefined);
  assert.equal(tryPageDescriptorFromUrl('/platform/dynamic//list'), undefined);
  assert.equal(tryPageDescriptorFromUrl('/_platform/workspace'), undefined);
  assert.equal(tryPageDescriptorFromUrl('http://['), undefined);
});

it('pageDescriptorFromUrl keeps workbench metadata separate from business route query', () => {
  const descriptor = pageDescriptorFromUrl(
    '/crm/customer/list?entryParamsJson=business-value&menuId=business-menu&_muyunEntryParams=%7B%22source%22%3A%22workbench%22%7D&_muyunMenuId=customer-list&_muyunTitle=Customers&title=Business',
    { businessRoutePrefixes: ['/crm'] },
  );

  assertPageType(descriptor, 'business-route');
  assert.equal(descriptor.menuId, 'customer-list');
  assert.equal(descriptor.title, undefined);
  assert.equal(descriptor.entryParamsJson, '{"source":"workbench"}');
  assert.deepEqual(descriptor.target.query, {
    entryParamsJson: 'business-value',
    menuId: 'business-menu',
    title: 'Business',
  });
});

it('pageDescriptorToUrl and pageDescriptorFromUrl preserve routeName and pageKey semantics', () => {
  const routeNameDescriptor = resolvePageDescriptor(
    {
      menuId: 'customer-name',
      menuType: 'route',
      openMode: 'tab',
      route: 'crm.customer.list',
      query: { status: 'active' },
    },
    { businessRouteNames: ['crm.customer.list'], title: 'Customers' },
  );
  const pageKeyDescriptor = resolvePageDescriptor(
    {
      menuId: 'customer-page',
      menuType: 'route',
      openMode: 'tab',
      route: 'customerList',
    },
    { businessPageKeys: ['customerList'] },
  );

  const routeNameRoundTrip = pageDescriptorFromUrl(pageDescriptorToUrl(routeNameDescriptor));
  const pageKeyRoundTrip = pageDescriptorFromUrl(pageDescriptorToUrl(pageKeyDescriptor));

  assertPageType(routeNameRoundTrip, 'business-route');
  assert.equal(routeNameRoundTrip.target.routeName, 'crm.customer.list');
  assert.equal(routeNameRoundTrip.target.query?.status, 'active');
  assert.equal(routeNameRoundTrip.menuId, undefined);
  assert.equal(routeNameRoundTrip.tabPolicy.identity, 'by-target');
  assertPageType(pageKeyRoundTrip, 'business-route');
  assert.equal(pageKeyRoundTrip.target.pageKey, 'customerList');
  assert.equal(pageKeyRoundTrip.menuId, undefined);
  assert.equal(pageKeyRoundTrip.tabPolicy.identity, 'by-target');
});

it('business route prefix matching uses path segment boundaries', () => {
  const businessDescriptor = pageDescriptorFromUrl('/crm/customer/list', {
    businessRoutePrefixes: ['/crm'],
  });
  const platformDescriptor = pageDescriptorFromUrl('/crm-old/customer/list', {
    businessRoutePrefixes: ['/crm'],
  });

  assert.equal(businessDescriptor.pageType, 'business-route');
  assert.equal(platformDescriptor.pageType, 'platform-route');
});

it('tabKeyOf uses menu identity as by-params base when available', () => {
  const descriptor: PageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    menuId: 'customer-list',
    target: { route: '/crm/customer/list' },
    params: { status: 'active', tags: ['vip', 'trial'] },
    tabPolicy: { identity: 'by-params' },
  };

  assert.equal(tabKeyOf(descriptor), 'menu:customer-list:status=active&tags=vip&tags=trial');
});

it('tabKeyOf separates independent instances while the default instance keeps its stable key', () => {
  const defaultDescriptor: PageDescriptor = {
    pageType: 'business-route',
    openMode: 'workbench-route',
    hostType: 'business-route-host',
    menuId: 'customer-list',
    target: { route: '/crm/customer/list' },
    tabPolicy: { identity: 'by-menu' },
  };
  const independentDescriptor: PageDescriptor = {
    ...defaultDescriptor,
    target: { route: '/crm/customer/list', query: { InstanceKey: 'instance-a' } },
  };

  assert.equal(tabKeyOf(defaultDescriptor), 'menu:customer-list');
  assert.equal(tabKeyOf(independentDescriptor), 'menu:customer-list');
  assert.equal(pageDescriptorToUrl(independentDescriptor), '/crm/customer/list');
});
