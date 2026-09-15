import type { IdentityPickerCandidate, IdentityPickerResolver } from './identityPickerModel';

/** A user selection always persists an IAM user account ID, never an employee ID. */
export type UserAccountId = string;

/** An authorized user-account projection returned by the selection provider. */
export interface UserPickerCandidate extends IdentityPickerCandidate<UserAccountId> {
  /** Account snapshot or current account, as defined by the source. */
  account?: string;
  employeeName?: string;
  organizationId?: string;
  organizationName?: string;
  departmentId?: string;
  departmentName?: string;
}
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
