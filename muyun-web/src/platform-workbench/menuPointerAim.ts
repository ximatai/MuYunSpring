export interface MenuPointerPosition {
  x: number;
  y: number;
}

export interface MenuPanelEdge {
  left: number;
  top: number;
  bottom: number;
}

/** Keeps the active menu item stable while the pointer crosses towards its flyout panel. */
export function isPointerHeadingToMenuPanel(
  pointer: MenuPointerPosition,
  origin: MenuPointerPosition,
  panel: MenuPanelEdge,
): boolean {
  if (pointer.x < origin.x) {
    return false;
  }

  return isPointInsideTriangle(
    pointer,
    origin,
    { x: panel.left + 12, y: panel.top - 8 },
    { x: panel.left + 12, y: panel.bottom + 8 },
  );
}

function isPointInsideTriangle(
  point: MenuPointerPosition,
  first: MenuPointerPosition,
  second: MenuPointerPosition,
  third: MenuPointerPosition,
): boolean {
  const firstCross = crossProduct(point, first, second);
  const secondCross = crossProduct(point, second, third);
  const thirdCross = crossProduct(point, third, first);
  const hasNegative = firstCross < 0 || secondCross < 0 || thirdCross < 0;
  const hasPositive = firstCross > 0 || secondCross > 0 || thirdCross > 0;
  return !(hasNegative && hasPositive);
}

function crossProduct(
  point: MenuPointerPosition,
  lineStart: MenuPointerPosition,
  lineEnd: MenuPointerPosition,
): number {
  return (
    (point.y - lineStart.y) * (lineEnd.x - lineStart.x) - (point.x - lineStart.x) * (lineEnd.y - lineStart.y)
  );
}
