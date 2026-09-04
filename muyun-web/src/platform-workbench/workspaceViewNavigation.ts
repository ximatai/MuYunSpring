import type { RouteQueryValue } from '@muyun/web-contracts';
import { useWorkspaceViewHost } from './workspaceViewHost';

/**
 * Updates navigation state owned by the current workspace view. The workbench
 * replaces the current page descriptor, so this does not open another tab or
 * add a browser-history entry.
 */
export function useWorkspaceViewNavigation() {
  const host = useWorkspaceViewHost();
  return {
    replaceQuery(query: Record<string, RouteQueryValue | undefined>) {
      host?.replaceQuery(query);
    },
  };
}
