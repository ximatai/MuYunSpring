import type { RouteQueryValue } from '@muyun/web-contracts';

/**
 * Controlled module-page entries may nominate one navigator source record.
 * These are framework query keys, deliberately separate from a module's
 * business query fields and from the navigator REFERENCE transport.
 */
export const NAVIGATOR_ENTRY_MODULE_ALIAS_QUERY_KEY = '_muyunNavigatorModuleAlias';
export const NAVIGATOR_ENTRY_RECORD_ID_QUERY_KEY = '_muyunNavigatorRecordId';

export interface NavigatorEntrySelection {
  moduleAlias: string;
  recordId: string;
  identity: string;
}

/**
 * Accept only the complete scalar pair. A malformed public address must leave
 * the page in its normal, unselected state rather than guessing a scope.
 */
export function navigatorEntrySelectionOf(
  params: Record<string, RouteQueryValue> | undefined,
): NavigatorEntrySelection | undefined {
  const moduleAlias = scalarQueryValue(params?.[NAVIGATOR_ENTRY_MODULE_ALIAS_QUERY_KEY]);
  const recordId = scalarQueryValue(params?.[NAVIGATOR_ENTRY_RECORD_ID_QUERY_KEY]);
  if (!moduleAlias || !recordId) return undefined;
  return {
    moduleAlias,
    recordId,
    identity: `${moduleAlias}\u0000${recordId}`,
  };
}

function scalarQueryValue(value: RouteQueryValue | undefined): string | undefined {
  if (Array.isArray(value) || value == null) return undefined;
  const normalized = String(value).trim();
  return normalized || undefined;
}
