/**
 * Stable width tiers for platform drawers.
 *
 * Business surfaces should express the space their content needs instead of
 * coupling themselves to a pixel value. New width needs are added here as a
 * platform decision, rather than introduced as local numbers.
 */
export const uiDrawerWidths = {
  /** Lightweight property or auxiliary settings. */
  compact: 420,
  /** A small single-purpose form or configuration. */
  narrow: 480,
  /** The normal record detail and form drawer. */
  standard: 520,
  /** A configuration surface with a dense table or several form groups. */
  wide: 760,
  /** A large relationship management or editing surface. */
  extraWide: 960,
} as const;

export type UiDrawerWidth = keyof typeof uiDrawerWidths;
export function resolveUiDrawerWidth(width: UiDrawerWidth): number {
  return uiDrawerWidths[width];
}

export function isUiDrawerWidth(width: unknown): width is UiDrawerWidth {
  return typeof width === 'string' && Object.hasOwn(uiDrawerWidths, width);
}
