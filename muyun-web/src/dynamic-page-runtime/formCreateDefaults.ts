import type { ResolvedViewDescriptor } from '@muyun/web-contracts';

/** Seed only new drafts. Explicit context values (including false, zero and null) take precedence. */
export function formCreateDefaults(
  view: ResolvedViewDescriptor | undefined,
  initial: Record<string, unknown> = {},
): Record<string, unknown> {
  return { ...JSON.parse(JSON.stringify(view?.createDefaults ?? {})), ...initial };
}
