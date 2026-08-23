import type { ResolvedPageNavigatorLevelDescriptor } from '@muyun/web-contracts';

/**
 * A tenant user's sole tenant is a fixed request scope, while a system user has no session
 * tenant and may choose from the tenant records allowed by the source REFERENCE action.
 */
export function shouldHideSingleResultNavigator(
  level: ResolvedPageNavigatorLevelDescriptor,
  singleResult: boolean,
  sessionTenantId: string | undefined,
): boolean {
  if (level.singleResultPolicy !== 'AUTO_SELECT_AND_HIDE' || !singleResult) return false;
  return level.sourceScope !== 'CURRENT_TENANT' || sessionTenantId != null;
}
