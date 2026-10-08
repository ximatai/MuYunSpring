import { expect, it } from 'vitest';
import { businessNotificationPageDescriptor } from '@/app/businessNotificationNavigation';
import { openDirectTab } from '@/app/workbenchStartup';
import {
  platformAdminModuleRoutes,
  platformAdminRouteLayouts,
  platformAdminRoutePrefixes,
  resolvePlatformAdminRoute,
} from '@/platform-admin-runtime/platformAdminRoutes';
import { pageDescriptorToUrl } from '@/platform-workbench/menuNavigation';
import type { WebBusinessNotificationNavigateAction } from '@muyun/web-contracts';

const options = {
  businessModuleRoutes: platformAdminModuleRoutes,
  businessRouteLayouts: platformAdminRouteLayouts,
  businessRoutePrefixes: platformAdminRoutePrefixes,
};
const action = (
  values: Partial<WebBusinessNotificationNavigateAction> = {},
): WebBusinessNotificationNavigateAction => ({
  kind: 'navigate',
  key: 'todo',
  label: '查看待办',
  moduleAlias: 'iam.workflow_workbench',
  pageMode: 'LIST',
  dismissOnSuccess: false,
  ...values,
});

it('opens the notification module intent through its registered approval workbench, preserving tenant query and tab policy', () => {
  const menus = [
    {
      record: { id: 'group', schemeId: 'admin', title: '工作入口' },
      children: [
        {
          record: {
            id: 'todo-menu',
            schemeId: 'admin',
            title: '我的待办入口',
            enabled: true,
            entryType: 'route' as const,
            openMode: 'tab' as const,
            moduleAlias: 'iam.workflow_workbench',
            route: platformAdminModuleRoutes['iam.workflow_workbench'],
          },
          children: [],
        },
      ],
    },
  ];
  const descriptor = businessNotificationPageDescriptor(
    action({ query: { tenantId: 'demo' } }),
    options,
    menus,
  );
  expect(descriptor.pageType).toBe('business-route');
  if (descriptor.pageType !== 'business-route') throw new Error('expected registered static route');
  expect(resolvePlatformAdminRoute(descriptor)?.moduleAlias).toBe('iam.workflow_workbench');
  expect(descriptor.hostType).toBe('business-route-host');
  expect(descriptor.layout).toBe('workspace');
  expect(descriptor.tabPolicy).toEqual({ identity: 'by-target', closable: true, cacheable: true });
  expect(descriptor.menuId).toBeUndefined();
  expect(descriptor.title).toBe('我的待办入口');
  const opened = openDirectTab([], descriptor, options);
  expect(opened.tabs[0]!.title).toBe('我的待办入口');
  const url = new URL(opened.tabs[0]!.fullPath!, 'https://example.test');
  expect(url.pathname).toBe('/workflow/workbench');
  expect(url.searchParams.get('tenantId')).toBe('demo');
});

it('resolves all registered module routes without a notification-specific route catalog', () => {
  for (const [moduleAlias, route] of Object.entries(platformAdminModuleRoutes)) {
    const descriptor = businessNotificationPageDescriptor(action({ moduleAlias }), options);
    expect(descriptor.pageType).toBe('business-route');
    expect(pageDescriptorToUrl(descriptor, options)).toBe(route);
  }
});

it('preserves ordinary dynamic detail navigation, record parameters and distinct record tabs', () => {
  const notification = action({
    moduleAlias: 'crm.customer',
    pageMode: 'DETAIL',
    recordId: 'r/1',
    query: { tenantId: 'demo', recordId: 'stale' },
  });
  const descriptor = businessNotificationPageDescriptor(notification, options);
  expect(descriptor).toMatchObject({
    pageType: 'dynamic-module',
    hostType: 'module-page-host',
    target: { moduleAlias: 'crm.customer', pageMode: 'DETAIL' },
    params: { tenantId: 'demo', recordId: 'r/1' },
    tabPolicy: { identity: 'by-params', closable: true, cacheable: true },
  });
  const url = new URL(pageDescriptorToUrl(descriptor, options), 'https://example.test');
  expect(url.pathname).toBe('/crm/customer');
  expect(url.searchParams.get('mode')).toBe('detail');
  expect(url.searchParams.get('recordId')).toBe('r/1');
  const opened = openDirectTab([], descriptor, options);
  expect(openDirectTab(opened.tabs, descriptor, options).created).toBe(false);
  const next = businessNotificationPageDescriptor({ ...notification, recordId: 'r/2' }, options);
  expect(openDirectTab(opened.tabs, next, options).tabs).toHaveLength(2);
});

it('preserves record and query parameters for dedicated static pages too', () => {
  const descriptor = businessNotificationPageDescriptor(
    action({ moduleAlias: 'platform.runtime_log', recordId: 'log-1', query: { tenantId: 'demo' } }),
    options,
  );
  expect(descriptor.params).toEqual({ recordId: 'log-1', tenantId: 'demo' });
  expect(descriptor.tabPolicy.identity).toBe('by-params');
  const url = new URL(pageDescriptorToUrl(descriptor, options), 'https://example.test');
  expect(url.pathname).toBe('/platform/logs/runtime');
  expect(url.searchParams.get('recordId')).toBe('log-1');
  expect(url.searchParams.get('tenantId')).toBe('demo');
});
