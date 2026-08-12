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

export function floatingMenuPanelOutlinePath(
  panel: FloatingMenuPanelRect,
  anchor?: FloatingMenuPanelAnchor,
  anchorRadius = 5,
  panelRadius = 8,
): string {
  const panelRight = panel.left + panel.width;
  const panelBottom = panel.top + panel.height;
  const path = [
    `M ${panel.left} ${panel.top}`,
    `H ${panelRight - panelRadius}`,
    `Q ${panelRight} ${panel.top} ${panelRight} ${panel.top + panelRadius}`,
    `V ${panelBottom - panelRadius}`,
    `Q ${panelRight} ${panelBottom} ${panelRight - panelRadius} ${panelBottom}`,
    `H ${panel.left}`,
  ];
  if (!anchor) {
    return path.join(' ');
  }

  const anchorBottom = anchor.top + anchor.height;
  return [
    ...path,
    `V ${anchorBottom}`,
    `H ${anchor.left + anchorRadius}`,
    `Q ${anchor.left} ${anchorBottom} ${anchor.left} ${anchorBottom - anchorRadius}`,
    `V ${anchor.top + anchorRadius}`,
    `Q ${anchor.left} ${anchor.top} ${anchor.left + anchorRadius} ${anchor.top}`,
    `H ${panel.left}`,
    `V ${panel.top}`,
  ].join(' ');
}

export type WorkbenchMenuPresentation = 'compact' | 'expanded';

export function effectiveWorkbenchMenuPresentation(
  preferredPresentation: WorkbenchMenuPresentation,
  narrowViewport: boolean,
): WorkbenchMenuPresentation {
  return narrowViewport ? 'compact' : preferredPresentation;
}
