/**
 * A workbench navigation request. The consuming App owns the router and must
 * apply this request with its own `router.push` or `router.replace`.
 */
export interface AppWorkbenchNavigation {
  url: string;
  mode: 'push' | 'replace';
}
