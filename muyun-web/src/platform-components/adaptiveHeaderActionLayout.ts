/**
 * Business-facing action importance. The shell decides whether an action is
 * rendered directly or collected under "更多"; callers never declare a UI
 * placement.
 */
export type HeaderActionLevel = 'primary' | 'standard' | 'secondary';

export interface HeaderActionLayoutItem {
  key: string;
  level?: HeaderActionLevel;
  width: number;
}

export interface HeaderActionLayout {
  directKeys: string[];
  overflowKeys: string[];
}

const collapseOrder: HeaderActionLevel[] = ['secondary', 'standard', 'primary'];
const defaultGap = 8;

/**
 * Keeps actions direct while they fit, then withdraws actions one by one from
 * low to high priority. Within one importance level, declaration order is the
 * business tie-breaker: later actions enter “更多” first. This avoids hiding
 * every standard action merely because one more standard action was added.
 */
export function resolveHeaderActionLayout(
  actions: HeaderActionLayoutItem[],
  availableWidth: number,
  moreWidth: number,
  gap = defaultGap,
): HeaderActionLayout {
  const visible = actions.filter((action) => action.width > 0);
  const totalWidth = (items: HeaderActionLayoutItem[]) =>
    items.reduce((sum, action) => sum + action.width, 0) + Math.max(0, items.length - 1) * gap;
  if (totalWidth(visible) <= availableWidth) {
    return { directKeys: visible.map((action) => action.key), overflowKeys: [] };
  }

  const overflowKeys = new Set<string>();
  for (const level of collapseOrder) {
    const candidates = visible.filter((action) => (action.level ?? 'standard') === level).reverse();
    for (const action of candidates) {
      overflowKeys.add(action.key);
      const direct = visible.filter((item) => !overflowKeys.has(item.key));
      const required = totalWidth(direct) + (direct.length > 0 ? gap : 0) + moreWidth;
      if (required <= availableWidth) {
        return {
          directKeys: direct.map((item) => item.key),
          overflowKeys: visible.filter((item) => overflowKeys.has(item.key)).map((item) => item.key),
        };
      }
    }
  }
  return { directKeys: [], overflowKeys: visible.map((action) => action.key) };
}
