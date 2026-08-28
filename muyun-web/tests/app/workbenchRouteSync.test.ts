import { assert, it } from 'vitest';
import {
  isCurrentWorkbenchNavigation,
  shouldRestoreWorkbenchFromRoute,
  workbenchRouteCommitFor,
  workbenchRouteWriteFor,
} from '@/app/workbenchRouteSync.ts';
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
    url: tab.fullPath,
    mode: 'push',
  });
});

it('does not create a duplicate history entry for the active tab URL', () => {
  assert.equal(workbenchRouteWriteFor(state, tab.fullPath!, 'push'), undefined);
});

it('restores workbench state for browser navigation but not self-written routes', () => {
  assert.equal(shouldRestoreWorkbenchFromRoute('/crm/customers', undefined), true);
  assert.equal(
    shouldRestoreWorkbenchFromRoute('/crm/customers', { url: '/crm/customers', revision: 1 }),
    false,
  );
  assert.equal(shouldRestoreWorkbenchFromRoute('/openapi', undefined), true);
});

it('recognizes a newer same-url intent as distinct from an in-flight route write', () => {
  // A starts at /a, B begins an asynchronous /b push, then the user returns
  // to A before that push commits. The eventual /b commit must be reconciled,
  // not allowed to render B's route with A's runtime state.
  const inFlight = { url: '/b', revision: 4 };
  const latest = { url: '/a', revision: 5 };

  assert.equal(isCurrentWorkbenchNavigation(inFlight, latest), false);
  assert.equal(workbenchRouteCommitFor('/b', inFlight, latest), 'reconcile');
  assert.equal(workbenchRouteCommitFor('/a', latest, latest), 'commit');
});
