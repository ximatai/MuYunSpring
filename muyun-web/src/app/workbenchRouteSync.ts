import type { WorkbenchStartupState } from '@muyun/web-contracts';
import { activeTabUrlOf } from './workbenchStartup';

export type WorkbenchRouteWriteMode = 'push' | 'replace';

export interface WorkbenchRouteWrite {
  url: string;
  mode: WorkbenchRouteWriteMode;
}

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
  pendingWorkbenchNavigation: string | undefined,
): boolean {
  return pendingWorkbenchNavigation !== url;
}
