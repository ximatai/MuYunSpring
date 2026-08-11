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

export type WorkbenchMenuPresentation = 'compact' | 'expanded';

export function effectiveWorkbenchMenuPresentation(
  preferredPresentation: WorkbenchMenuPresentation,
  narrowViewport: boolean,
): WorkbenchMenuPresentation {
  return narrowViewport ? 'compact' : preferredPresentation;
}
