/**
 * Shared transport shapes for semantic identity-object pickers.  The concrete
 * user and employee components deliberately keep separate ID aliases and
 * provider names, so a form cannot accidentally treat an employee as a login
 * account merely because both IDs are strings.
 */
export interface IdentityPickerCandidate<TId extends string> {
  id: TId;
  title: string;
  subtitle?: string;
  disabled?: boolean;
  unavailable?: boolean;
}

export interface IdentityPickerPageRequest {
  /** Provider-defined search text. The shared panel does not prescribe searchable fields. */
  keyword: string;
  pageNum: number;
  pageSize: number;
}

export interface IdentityPickerPage<TId extends string> {
  records: IdentityPickerCandidate<TId>[];
  total: number;
}

export type IdentityPickerPageSearch<TId extends string> = (
  request: IdentityPickerPageRequest,
) => Promise<IdentityPickerPage<TId>>;

/** Resolves persisted IDs through the same authorized candidate scope as the page search. */
export type IdentityPickerResolver<TId extends string> = (
  ids: TId[],
) => Promise<IdentityPickerCandidate<TId>[]>;
