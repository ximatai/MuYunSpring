/**
 * The ways a side panel may be dismissed.  The policy controls which user
 * gestures can initiate a close; a guard decides whether an initiated close
 * may discard the active business session.
 */
export type UiDrawerDismissal = 'explicit' | 'dismissible' | 'guarded';

export type UiDrawerCloseReason = 'close-button' | 'outside';

export type UiDrawerCloseGuard = (reason: UiDrawerCloseReason) => boolean | Promise<boolean>;

export interface UiDrawerDismissalOptions {
  dismissal?: UiDrawerDismissal;
  /** @deprecated Compatibility bridge for callers that have not adopted `dismissal`. */
  closeOnOutside?: boolean;
  beforeClose?: UiDrawerCloseGuard;
}

export function resolveUiDrawerDismissal(options: UiDrawerDismissalOptions): UiDrawerDismissal {
  if (options.dismissal) return options.dismissal;
  return options.closeOnOutside ? 'dismissible' : 'explicit';
}

/** A guarded drawer fails closed until its session owner supplies a guard. */
export function allowsUiDrawerOutsideDismissal(options: UiDrawerDismissalOptions): boolean {
  const dismissal = resolveUiDrawerDismissal(options);
  return dismissal === 'dismissible' || (dismissal === 'guarded' && options.beforeClose != null);
}

export async function mayCloseUiDrawer(
  options: UiDrawerDismissalOptions,
  reason: UiDrawerCloseReason,
): Promise<boolean> {
  if (reason === 'outside' && !allowsUiDrawerOutsideDismissal(options)) return false;
  return (await options.beforeClose?.(reason)) !== false;
}
