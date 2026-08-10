import { assert, it } from 'vitest';
import { shouldRestoreWorkbenchFromRoute, workbenchRouteWriteFor } from '@/app/workbenchRouteSync.ts';
import { openDirectTab } from '@/app/workbenchStartup.ts';

const descriptor = {
  pageType: 'business-route' as const,
  openMode: 'workbench-route' as const,
  hostType: 'business-route-host' as const,
  title: '客户管理',
  target: { route: '/crm/customers' },
  tabPolicy: { identity: 'by-target' as const },
};

const tab = openDirectTab([], descriptor).tabs[0];
const state = {
  session: { currentUser: { userId: 'user-1', system: false } },
  menus: [],
  tabs: [tab],
  activeTabKey: tab.key,
};

it('writes user tab navigation as a browser history entry', () => {
  assert.deepEqual(workbenchRouteWriteFor(state, '/platform/metadata', 'push'), {
    url: '/crm/customers?_muyunTitle=%E5%AE%A2%E6%88%B7%E7%AE%A1%E7%90%86',
    mode: 'push',
  });
});

it('does not create a duplicate history entry for the active tab URL', () => {
  assert.equal(
    workbenchRouteWriteFor(state, '/crm/customers?_muyunTitle=%E5%AE%A2%E6%88%B7%E7%AE%A1%E7%90%86', 'push'),
    undefined,
  );
});

it('restores workbench state for browser navigation but not self-written or special routes', () => {
  assert.equal(shouldRestoreWorkbenchFromRoute('/crm/customers', undefined, false), true);
  assert.equal(shouldRestoreWorkbenchFromRoute('/crm/customers', '/crm/customers', false), false);
  assert.equal(shouldRestoreWorkbenchFromRoute('/openapi', undefined, true), false);
});
