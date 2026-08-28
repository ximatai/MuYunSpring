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

/** Shared geometry for collapsible micro-list tabs and their left rail. */
export const MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT = {
  railWidth: 36,
  tabPaddingBlockStart: 8,
  tabPaddingBlockEnd: 4,
  titleFontSize: 15,
  titleLineHeight: 18,
  /** Visual glyph size; its accessible click target remains 22px. */
  iconSize: 14,
  iconHitArea: 22,
  tabContentGap: 8,
  tabStackGap: 8,
} as const;

export function collapsedExplorerTabHeight(title: string): number {
  const layout = MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT;
  return (
    layout.tabPaddingBlockStart +
    layout.tabPaddingBlockEnd +
    Math.max(1, Array.from(title).length) * layout.titleLineHeight +
    layout.tabContentGap +
    layout.iconHitArea
  );
}

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
