import type { IdentityPickerCandidate, IdentityPickerResolver } from './identityPickerModel';

/** A user selection always persists an IAM user account ID, never an employee ID. */
export type UserAccountId = string;

/**
 * An authorized display projection returned by the selection provider.
 * `subtitle` can contain tenant, organization, or other context needed to disambiguate names.
 */
export type UserPickerCandidate = IdentityPickerCandidate<UserAccountId>;
export interface UserPickerNavigationScope {
  tenantId?: string;
  organizationId?: string;
  departmentId?: string;
}

/** A source-provided progressive browse axis. IDs remain source-owned and are never inferred by the picker. */
export interface UserPickerNavigationItem {
  id: string;
  title: string;
  tenantId?: string;
  /** Parent organization for a department item, when the source has that hierarchy. */
  organizationId?: string;
}

export interface UserPickerNavigation {
  showTenantNavigation: boolean;
  tenants: UserPickerNavigationItem[];
  organizations: UserPickerNavigationItem[];
  departments: UserPickerNavigationItem[];
}

export interface UserPickerPage {
  records: UserPickerCandidate[];
  total: number;
  navigation?: UserPickerNavigation;
}

export interface UserPickerPageRequest {
  keyword: string;
  pageNum: number;
  pageSize: number;
  scope?: UserPickerNavigationScope;
}

export type UserPickerPageSearch = (request: UserPickerPageRequest) => Promise<UserPickerPage>;
export type UserPickerResolver = IdentityPickerResolver<UserAccountId>;

/**
 * A source-owned user-selection delivery contract. The picker consumes it without knowing
 * whether candidates come from a form reference, an authorization surface, or a log stream.
 */
export interface UserPickerConfig {
  title?: string;
  placeholder?: string;
  maxSelection?: number;
  searchPage: UserPickerPageSearch;
  resolveUsers: UserPickerResolver;
}

export function normalizeUserAccountIds(
  value: UserAccountId | readonly UserAccountId[] | undefined,
  multiple: boolean,
): UserAccountId[] {
  const raw = Array.isArray(value) ? value : value ? [value] : [];
  const unique = [...new Set(raw.filter(Boolean))];
  return multiple ? unique : unique.slice(0, 1);
}

export function userPickerValue(
  ids: readonly UserAccountId[],
  multiple: boolean,
): UserAccountId | UserAccountId[] | undefined {
  return multiple ? [...ids] : ids[0];
}
