/**
 * Shared geometry for the platform management workspace.
 *
 * Keep responsive promotion independent from each page's DSL: descriptors say
 * whether a page has a detail surface; the runtime decides whether that surface
 * can share the available workspace with its list and navigators.
 */
export const MANAGEMENT_WORKSPACE_LAYOUT = {
  explorerWidth: 280,
  listMinWidth: 720,
  detailMinWidth: 560,
  columnGap: 12,
} as const;

export function listDetailWorkspaceMinWidth(explorerCount: number): number {
  const normalizedExplorerCount = Math.max(0, Math.trunc(explorerCount));
  const { explorerWidth, listMinWidth, detailMinWidth, columnGap } = MANAGEMENT_WORKSPACE_LAYOUT;
  return (
    normalizedExplorerCount * explorerWidth +
    listMinWidth +
    detailMinWidth +
    normalizedExplorerCount * columnGap +
    columnGap
  );
}
