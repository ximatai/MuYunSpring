import type { MenuTab } from '@muyun/web-contracts';
import type { UserPreferenceStore } from '@muyun/web-core';

export const lockedTabPreferenceKey = 'workbench.locked-tabs';

export async function restoreLockedTabPreference(store: UserPreferenceStore): Promise<MenuTab[]> {
  const restored = await store.restore(lockedTabPreferenceKey, [], { persistence: 'backend' });
  return normalizeLockedTabs(restored);
}

export async function saveLockedTabPreference(store: UserPreferenceStore, tabs: MenuTab[]): Promise<void> {
  await store.set(lockedTabPreferenceKey, tabs, { persistence: 'backend' });
}

export function normalizeLockedTabs(value: unknown): MenuTab[] {
  if (!Array.isArray(value)) return [];
  const keys = new Set<string>();
  return value.flatMap((candidate) => {
    if (!isMenuTab(candidate) || keys.has(candidate.key)) return [];
    keys.add(candidate.key);
    return [candidate];
  });
}

function isMenuTab(value: unknown): value is MenuTab {
  const tab = value as MenuTab;
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof tab.key === 'string' &&
    typeof tab.title === 'string' &&
    (isPageDescriptor(tab.pageDescriptor) || isMenuTarget(tab.target))
  );
}

function isPageDescriptor(value: unknown): boolean {
  if (!isRecord(value) || !isRecord(value.target) || !isRecord(value.tabPolicy)) return false;
  if (!['by-menu', 'by-target', 'by-params'].includes(String(value.tabPolicy.identity))) return false;
  if (value.pageType === 'platform-route')
    return (
      value.openMode === 'workbench-route' &&
      value.hostType === 'platform-route-host' &&
      isRouteTarget(value.target)
    );
  if (value.pageType === 'business-route')
    return (
      value.openMode === 'workbench-route' &&
      value.hostType === 'business-route-host' &&
      isRouteTarget(value.target)
    );
  if (value.pageType === 'dynamic-module')
    return (
      value.openMode === 'dynamic-runner' &&
      (value.hostType === 'module-page-host' || value.hostType === 'dynamic-module-host') &&
      typeof value.target.moduleAlias === 'string'
    );
  if (value.pageType === 'remote-url')
    return (
      (value.openMode === 'iframe' || value.openMode === 'new-window') &&
      value.hostType === 'external-page-host' &&
      typeof value.target.url === 'string'
    );
  return (
    value.pageType === 'external-link' &&
    value.openMode === 'new-window' &&
    value.hostType === 'external-page-host' &&
    typeof value.target.url === 'string'
  );
}

function isRouteTarget(value: Record<string, unknown>): boolean {
  return (
    typeof value.route === 'string' ||
    typeof value.routeName === 'string' ||
    typeof value.pageKey === 'string'
  );
}

function isMenuTarget(value: unknown): boolean {
  if (!isRecord(value) || typeof value.menuId !== 'string' || value.openMode !== 'tab') return false;
  if (value.menuType === 'module') return typeof value.moduleAlias === 'string';
  if (value.menuType === 'route') return typeof value.route === 'string';
  return value.menuType === 'link' && typeof value.externalUrl === 'string';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
