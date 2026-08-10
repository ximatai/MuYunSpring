export function compactMenuTopOf(topbarBottom: number, workbenchTop: number): number {
  return Math.max(0, Math.round(topbarBottom - workbenchTop));
}
