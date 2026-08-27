import type { ResolvedPageContextBindingDescriptor } from '@muyun/web-contracts';

export type PageContextSourceValues = Partial<
  Record<ResolvedPageContextBindingDescriptor['source'], Record<string, unknown>>
>;

/** Returns context fields that must be supplied by the browser for a query target. */
export function externalPageContextCriteriaKeys(
  bindings: readonly ResolvedPageContextBindingDescriptor[],
  target: ResolvedPageContextBindingDescriptor['target'],
  targetNavigatorLevelKey?: string,
): string[] {
  return bindings
    .filter(
      (binding) =>
        binding.target === target &&
        binding.source !== 'SESSION' &&
        (target !== 'NAVIGATOR_QUERY' ||
          targetNavigatorLevelKey == null ||
          binding.targetNavigatorLevelKey === targetNavigatorLevelKey),
    )
    .map((binding) => binding.targetKey);
}

/** Browser-provided navigator keys that must be selected before a page list may query. */
export function requiredNavigatorListScopeCriteriaKeys(
  bindings: readonly ResolvedPageContextBindingDescriptor[],
): string[] {
  return bindings
    .filter(
      (binding) =>
        binding.source === 'NAVIGATOR' &&
        binding.target === 'LIST_QUERY' &&
        binding.navigatorListQueryMode !== 'OPTIONAL_FILTER',
    )
    .map((binding) => binding.targetKey);
}

/** Resolves declared context flows without coupling a target to navigator UI state. */
export function resolvePageContextTargetValues(
  bindings: readonly ResolvedPageContextBindingDescriptor[],
  target: ResolvedPageContextBindingDescriptor['target'],
  sourceValues: PageContextSourceValues,
  targetNavigatorLevelKey?: string,
): Record<string, unknown> | undefined {
  const values: Record<string, unknown> = {};
  for (const binding of bindings) {
    if (
      binding.target !== target ||
      (target === 'NAVIGATOR_QUERY' && binding.targetNavigatorLevelKey !== targetNavigatorLevelKey)
    )
      continue;
    // A resolved selection is an opaque browser key. Its server response is a field map, so
    // consume the declared target field rather than pretending the selection kind is a value.
    const value =
      binding.source === 'RESOLVED_SELECTION'
        ? sourceValues.RESOLVED_SELECTION?.[binding.targetKey]
        : sourceValues[binding.source]?.[binding.sourceKey];
    if (value != null) values[binding.targetKey] = value;
  }
  return Object.keys(values).length === 0 ? undefined : values;
}

/**
 * Query scopes are values, not refresh signals. Preserve the prior object when
 * a render reconstructs the same flat criteria so consumers do not reload just
 * because their parent component rendered for an unrelated state transition.
 */
export function reuseEquivalentQueryValues(
  previous: Record<string, unknown> | undefined,
  next: Record<string, unknown> | undefined,
): Record<string, unknown> | undefined {
  if (previous === next || (!previous && !next)) return previous;
  if (!previous || !next) return next;
  const previousKeys = Object.keys(previous);
  const nextKeys = Object.keys(next);
  if (
    previousKeys.length !== nextKeys.length ||
    previousKeys.some(
      (key) => !Object.prototype.hasOwnProperty.call(next, key) || !Object.is(previous[key], next[key]),
    )
  ) {
    return next;
  }
  return previous;
}
