import type {
  IdentityPickerCandidate,
  IdentityPickerPage,
  IdentityPickerPageRequest,
  IdentityPickerPageSearch,
  IdentityPickerResolver,
} from './identityPickerModel';

/** A personnel master-data identity; it is intentionally distinct from a user account ID. */
export type EmployeeId = string;

export type EmployeePickerCandidate = IdentityPickerCandidate<EmployeeId>;
export type EmployeePickerPageRequest = IdentityPickerPageRequest;
export type EmployeePickerPage = IdentityPickerPage<EmployeeId>;
export type EmployeePickerPageSearch = IdentityPickerPageSearch<EmployeeId>;
export type EmployeePickerResolver = IdentityPickerResolver<EmployeeId>;

/**
 * Source-owned employee delivery. Callers supply the authorized candidate
 * scope; this UI boundary does not read IAM modules or infer tenant scope.
 */
export interface EmployeePickerConfig {
  title?: string;
  placeholder?: string;
  maxSelection?: number;
  searchPage: EmployeePickerPageSearch;
  resolveEmployees: EmployeePickerResolver;
}
