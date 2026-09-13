import type {
  IdentityPickerCandidate,
  IdentityPickerPage,
  IdentityPickerPageRequest,
  IdentityPickerPageSearch,
  IdentityPickerResolver,
} from './identityPickerModel';

/** A user selection always persists an IAM user account ID, never an employee ID. */
export type UserAccountId = string;

/**
 * An authorized display projection returned by the selection provider.
 * `subtitle` can contain tenant, organization, or other context needed to disambiguate names.
 */
export type UserPickerCandidate = IdentityPickerCandidate<UserAccountId>;
export type UserPickerPageRequest = IdentityPickerPageRequest;
export type UserPickerPage = IdentityPickerPage<UserAccountId>;
export type UserPickerPageSearch = IdentityPickerPageSearch<UserAccountId>;
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
