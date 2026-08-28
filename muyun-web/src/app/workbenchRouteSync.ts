import type { WorkbenchStartupState } from '@muyun/web-contracts';
import { activeTabUrlOf } from './workbenchStartup';

export type WorkbenchRouteWriteMode = 'push' | 'replace';

export interface WorkbenchRouteWrite {
  url: string;
  mode: WorkbenchRouteWriteMode;
}

/** A route write belongs to an explicit workbench intent, not merely a URL. */
export interface WorkbenchNavigationIntent {
  url: string;
  revision: number;
}

export type WorkbenchRouteCommit = 'commit' | 'reconcile' | 'restore';

/**
 * Keeps browser history decisions at the workbench boundary: user navigation is
 * navigable history, while state normalization only replaces the current entry.
 */
export function workbenchRouteWriteFor(
  state: WorkbenchStartupState,
  currentUrl: string,
  mode: WorkbenchRouteWriteMode,
): WorkbenchRouteWrite | undefined {
  const url = activeTabUrlOf(state) ?? '/';
  return url === currentUrl ? undefined : { url, mode };
}

/** A route written from the workbench already has matching in-memory tab state. */
export function shouldRestoreWorkbenchFromRoute(
  url: string,
  pendingWorkbenchNavigation: WorkbenchNavigationIntent | undefined,
): boolean {
  return pendingWorkbenchNavigation?.url !== url;
}

export function isCurrentWorkbenchNavigation(
  navigation: WorkbenchNavigationIntent,
  current: WorkbenchNavigationIntent,
): boolean {
  return navigation.revision === current.revision;
}

/** Classifies a committed route without confusing URL equality for tab identity. */
export function workbenchRouteCommitFor(
  url: string,
  pending: WorkbenchNavigationIntent | undefined,
  latest: WorkbenchNavigationIntent | undefined,
): WorkbenchRouteCommit {
  if (pending?.url !== url) return 'restore';
  return latest && !isCurrentWorkbenchNavigation(pending, latest) ? 'reconcile' : 'commit';
}
