/**
 * Business-facing action importance. The shell decides whether an action is
 * rendered directly or collected under "更多". Control adjacency is a separate
 * constraint and does not promote a business operation's importance.
 */
export type HeaderActionLevel = 'primary' | 'standard' | 'secondary';

export interface HeaderActionLayoutItem {
  key: string;
  level?: HeaderActionLevel;
  width: number;
  keepDirect?: boolean;
  placement?: 'after-primary';
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
  const measured = actions.filter((action) => action.width > 0);
  const companions = measured.filter((action) => action.placement === 'after-primary');
  const visible = measured
    .filter((action) => action.placement !== 'after-primary')
    .sort((left, right) => Number(right.level === 'primary') - Number(left.level === 'primary'));
  const primaryIndex = visible.findIndex((action) => action.level === 'primary');
  visible.splice(primaryIndex < 0 ? visible.length : primaryIndex + 1, 0, ...companions);
  const retained = new Set(visible.filter((action) => action.keepDirect).map((action) => action.key));
  if (companions.some((action) => action.keepDirect) && primaryIndex >= 0) {
    retained.add(visible[primaryIndex].key);
  }
  const totalWidth = (items: HeaderActionLayoutItem[]) =>
    items.reduce((sum, action) => sum + action.width, 0) + Math.max(0, items.length - 1) * gap;
  if (totalWidth(visible) <= availableWidth) {
    return { directKeys: visible.map((action) => action.key), overflowKeys: [] };
  }

  const overflowKeys = new Set<string>();
  for (const level of collapseOrder) {
    const candidates = visible
      .filter((action) => !retained.has(action.key) && (action.level ?? 'standard') === level)
      .reverse();
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
  // Retained controls and their primary anchor wrap in the shell at narrow widths.
  return {
    directKeys: visible.filter((action) => retained.has(action.key)).map((action) => action.key),
    overflowKeys: visible.filter((action) => !retained.has(action.key)).map((action) => action.key),
  };
}
