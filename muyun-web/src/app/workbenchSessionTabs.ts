import type { MenuTab, PageDescriptor } from '@muyun/web-contracts';

const storageKey = 'muyun.workbench.session-tabs.v1';

export interface WorkbenchSessionTabsSnapshot {
  version: 1;
  userId: string;
  tabs: MenuTab[];
  activeTabKey?: string;
}

type SessionStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/**
 * Browser-session persistence keeps the workbench's tab shell across reloads.
 * It deliberately excludes page drafts and cached component instances.
 */
export function restoreWorkbenchSessionTabs(
  userId: string,
  storage: SessionStorage | undefined = sessionStorageOf(),
): WorkbenchSessionTabsSnapshot | undefined {
  if (!storage) return undefined;
  try {
    const raw = storage.getItem(storageKey);
    if (!raw) return undefined;
    const snapshot = JSON.parse(raw) as Partial<WorkbenchSessionTabsSnapshot>;
    if (
      snapshot.version !== 1 ||
      snapshot.userId !== userId ||
      !Array.isArray(snapshot.tabs) ||
      (snapshot.activeTabKey !== undefined && typeof snapshot.activeTabKey !== 'string')
    ) {
      storage.removeItem(storageKey);
      return undefined;
    }
    return {
      version: 1,
      userId,
      tabs: normalizePersistedWorkbenchTabs(snapshot.tabs),
      activeTabKey: snapshot.activeTabKey,
    };
  } catch {
    clearWorkbenchSessionTabs(storage);
    return undefined;
  }
}

export function saveWorkbenchSessionTabs(
  input: {
    userId: string;
    tabs: MenuTab[];
    activeTabKey?: string;
  },
  storage: SessionStorage | undefined = sessionStorageOf(),
) {
  if (!storage) return;
  try {
    const snapshot: WorkbenchSessionTabsSnapshot = { version: 1, ...input };
    storage.setItem(storageKey, JSON.stringify(snapshot));
  } catch {
    // The workbench remains usable when browser session storage is unavailable.
  }
}

export function clearWorkbenchSessionTabs(storage: SessionStorage | undefined = sessionStorageOf()) {
  try {
    storage?.removeItem(storageKey);
  } catch {
    // Storage can be unavailable in restrictive browser contexts.
  }
}

/** Keeps client-side persistence from becoming an untyped startup input. */
export function normalizePersistedWorkbenchTabs(value: unknown): MenuTab[] {
  if (!Array.isArray(value)) return [];
  const keys = new Set<string>();
  return value.flatMap((candidate) => {
    if (!isPersistedMenuTab(candidate) || keys.has(candidate.key)) return [];
    keys.add(candidate.key);
    return [candidate];
  });
}

function isPersistedMenuTab(value: unknown): value is MenuTab {
  const tab = value as MenuTab;
  return (
    isRecord(value) &&
    typeof tab.key === 'string' &&
    typeof tab.title === 'string' &&
    (isPageDescriptor(tab.pageDescriptor) || isMenuTarget(tab.target))
  );
}

function isPageDescriptor(value: unknown): value is PageDescriptor {
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

function isRouteTarget(value: Record<string, unknown>) {
  return (
    typeof value.route === 'string' ||
    typeof value.routeName === 'string' ||
    typeof value.pageKey === 'string'
  );
}

function isMenuTarget(value: unknown) {
  if (!isRecord(value) || typeof value.menuId !== 'string' || value.openMode !== 'tab') return false;
  if (value.menuType === 'module') return typeof value.moduleAlias === 'string';
  if (value.menuType === 'route') return typeof value.route === 'string';
  return value.menuType === 'link' && typeof value.externalUrl === 'string';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function sessionStorageOf(): Storage | undefined {
  if (typeof window === 'undefined') return undefined;
  try {
    return window.sessionStorage;
  } catch {
    return undefined;
  }
}
