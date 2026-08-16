import type { ResolvedPageContextBindingDescriptor } from '@muyun/web-contracts';

export type PageContextSourceValues = Partial<Record<ResolvedPageContextBindingDescriptor['source'], Record<string, unknown>>>;

/** Resolves declared context flows without coupling a target to navigator UI state. */
export function resolvePageContextTargetValues(
  bindings: ResolvedPageContextBindingDescriptor[],
  target: ResolvedPageContextBindingDescriptor['target'],
  sourceValues: PageContextSourceValues,
  targetNavigatorLevelKey?: string,
): Record<string, unknown> | undefined {
  const values: Record<string, unknown> = {};
  for (const binding of bindings) {
    if (binding.target !== target
      || (target === 'NAVIGATOR_QUERY' && binding.targetNavigatorLevelKey !== targetNavigatorLevelKey)) continue;
    const value = sourceValues[binding.source]?.[binding.sourceKey];
    if (value != null) values[binding.targetKey] = value;
  }
  return Object.keys(values).length === 0 ? undefined : values;
}
