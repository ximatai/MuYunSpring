export interface MenuHoverClickIntent {
  markHoverActivation(menuId: string): void;
  consumeImmediateClick(menuId: string): boolean;
  clear(): void;
}

/**
 * Treats the hover activation immediately preceding a click as the same open intent.
 * A later click remains a deliberate toggle and may close the active menu.
 */
export function createMenuHoverClickIntent(
  gracePeriod: number,
  now: () => number = Date.now,
): MenuHoverClickIntent {
  let activation: { menuId: string; at: number } | undefined;

  return {
    markHoverActivation(menuId) {
      activation = { menuId, at: now() };
    },
    consumeImmediateClick(menuId) {
      const current = activation;
      activation = undefined;
      if (!current || current.menuId !== menuId) {
        return false;
      }
      const elapsed = now() - current.at;
      return elapsed >= 0 && elapsed <= gracePeriod;
    },
    clear() {
      activation = undefined;
    },
  };
}
