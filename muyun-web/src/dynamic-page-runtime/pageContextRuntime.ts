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
    const value = sourceValues[binding.source]?.[binding.sourceKey];
    if (value != null) values[binding.targetKey] = value;
  }
  return Object.keys(values).length === 0 ? undefined : values;
}
