export function compactMenuTopOf(topbarBottom: number, workbenchTop: number): number {
  return Math.max(0, Math.round(topbarBottom - workbenchTop));
}

export function floatingPanelTopOf(
  anchorViewportTop: number,
  panelHeight: number,
  viewportHeight: number,
  shellViewportTop: number,
  margin = 8,
): number {
  const minimumViewportTop = margin;
  const maximumViewportTop = Math.max(minimumViewportTop, viewportHeight - panelHeight - margin);
  const viewportTop = Math.min(Math.max(anchorViewportTop, minimumViewportTop), maximumViewportTop);
  return Math.round(viewportTop - shellViewportTop);
}

interface FloatingMenuPanelRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

interface FloatingMenuPanelAnchor {
  left: number;
  top: number;
  height: number;
}

interface CompactMenuPanelBounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

interface CompactMenuAnchorBounds {
  left: number;
  top: number;
  right: number;
}

interface CompactMenuFlyoutBounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/**
 * Builds one continuous outline for a side-menu trigger and its adjoining flyout.
 * The flyout must begin at the trigger's right edge and never below its top edge.
 */
export function floatingMenuPanelOutlinePath(
  panel: FloatingMenuPanelRect,
  anchor: FloatingMenuPanelAnchor,
  anchorRadius = 5,
  panelRadius = 8,
): string {
  const panelRight = panel.left + panel.width;
  const panelBottom = panel.top + panel.height;
  const anchorBottom = anchor.top + anchor.height;

  return [
    `M ${panel.left} ${panel.top}`,
    `H ${panelRight - panelRadius}`,
    `Q ${panelRight} ${panel.top} ${panelRight} ${panel.top + panelRadius}`,
    `V ${panelBottom - panelRadius}`,
    `Q ${panelRight} ${panelBottom} ${panelRight - panelRadius} ${panelBottom}`,
    `H ${panel.left}`,
    `V ${anchorBottom}`,
    `H ${anchor.left + anchorRadius}`,
    `Q ${anchor.left} ${anchorBottom} ${anchor.left} ${anchorBottom - anchorRadius}`,
    `V ${anchor.top + anchorRadius}`,
    `Q ${anchor.left} ${anchor.top} ${anchor.left + anchorRadius} ${anchor.top}`,
    `H ${panel.left}`,
    `V ${panel.top}`,
  ].join(' ');
}

/** Builds the shared outline of the compact-menu trigger and its dropdown panel. */
export function compactMenuPanelOutlinePath(
  panel: CompactMenuPanelBounds,
  anchor: CompactMenuAnchorBounds,
  anchorRadius = 5,
  panelRadius = 4,
  flyout?: CompactMenuFlyoutBounds,
): string {
  // The adjacent menu surfaces can differ by one pixel because one side owns the border.
  // Treat that seam as shared so the visual card never falls back to two separate outlines.
  const joinsPanel =
    flyout &&
    Math.abs(flyout.left - panel.right) <= 1 &&
    flyout.right > flyout.left &&
    flyout.bottom > flyout.top &&
    flyout.top >= panel.top;
  if (joinsPanel) {
    const flyoutRadius = 8;
    const flyoutExtendsBelowPanel = flyout.bottom > panel.bottom;
    return [
      `M ${anchor.left + anchorRadius} ${anchor.top}`,
      `H ${anchor.right - anchorRadius}`,
      `Q ${anchor.right} ${anchor.top} ${anchor.right} ${anchor.top + anchorRadius}`,
      `V ${panel.top}`,
      `H ${panel.right}`,
      `V ${flyout.top}`,
      `H ${flyout.right - flyoutRadius}`,
      `Q ${flyout.right} ${flyout.top} ${flyout.right} ${flyout.top + flyoutRadius}`,
      `V ${flyout.bottom - flyoutRadius}`,
      `Q ${flyout.right} ${flyout.bottom} ${flyout.right - flyoutRadius} ${flyout.bottom}`,
      `H ${panel.right}`,
      ...(flyoutExtendsBelowPanel
        ? [`V ${panel.bottom}`, `H ${panel.left + panelRadius}`]
        : [
            `V ${panel.bottom - panelRadius}`,
            `Q ${panel.right} ${panel.bottom} ${panel.right - panelRadius} ${panel.bottom}`,
            `H ${panel.left + panelRadius}`,
          ]),
      `Q ${panel.left} ${panel.bottom} ${panel.left} ${panel.bottom - panelRadius}`,
      `V ${panel.top + panelRadius}`,
      `Q ${panel.left} ${panel.top} ${panel.left + panelRadius} ${panel.top}`,
      `H ${anchor.left}`,
      `V ${anchor.top + anchorRadius}`,
      `Q ${anchor.left} ${anchor.top} ${anchor.left + anchorRadius} ${anchor.top}`,
    ].join(' ');
  }
  return [
    `M ${anchor.left + anchorRadius} ${anchor.top}`,
    `H ${anchor.right - anchorRadius}`,
    `Q ${anchor.right} ${anchor.top} ${anchor.right} ${anchor.top + anchorRadius}`,
    `V ${panel.top}`,
    `H ${panel.right - panelRadius}`,
    `Q ${panel.right} ${panel.top} ${panel.right} ${panel.top + panelRadius}`,
    `V ${panel.bottom - panelRadius}`,
    `Q ${panel.right} ${panel.bottom} ${panel.right - panelRadius} ${panel.bottom}`,
    `H ${panel.left + panelRadius}`,
    `Q ${panel.left} ${panel.bottom} ${panel.left} ${panel.bottom - panelRadius}`,
    `V ${panel.top + panelRadius}`,
    `Q ${panel.left} ${panel.top} ${panel.left + panelRadius} ${panel.top}`,
    `H ${anchor.left}`,
    `V ${anchor.top + anchorRadius}`,
    `Q ${anchor.left} ${anchor.top} ${anchor.left + anchorRadius} ${anchor.top}`,
  ].join(' ');
}

export type WorkbenchMenuPresentation = 'compact' | 'expanded';

export function effectiveWorkbenchMenuPresentation(
  preferredPresentation: WorkbenchMenuPresentation,
  narrowViewport: boolean,
): WorkbenchMenuPresentation {
  return narrowViewport ? 'compact' : preferredPresentation;
}
