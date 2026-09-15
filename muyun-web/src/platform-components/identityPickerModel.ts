import type { ReferencePickerCandidate } from './referencePickerModel';

/** Identity aliases retain their distinct IDs while using the common display contract. */
export interface IdentityPickerCandidate<TId extends string> extends ReferencePickerCandidate {
  id: TId;
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
