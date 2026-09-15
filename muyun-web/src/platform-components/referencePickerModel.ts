/**
 * Source-neutral candidate contract for records selected through an authorized
 * reference source. IDs are the only persisted value; projections are display
 * data and affectPatch remains an explicit, host-owned extension.
 */
export type ReferencePickerId = string;

/** Whether the compact entry still represents the persisted reference selection. */
export interface ReferencePickerValidity {
  valid: boolean;
  status: 'ready' | 'editing' | 'resolving' | 'unmatched' | 'error';
  message?: string;
}

export interface ReferencePickerSourceIdentity {
  targetModuleAlias: string;
  source: {
    kind: 'sourceField' | 'businessPurpose' | 'targetReference';
    id: string;
  };
  /** A source-controlled authorization/range revision when it is available. */
  authorizationScope?: string;
}

export interface ReferencePickerCandidate {
  id: ReferencePickerId;
  title: string;
  subtitle?: string;
  disabled?: boolean;
  unavailable?: boolean;
  projections?: Readonly<Record<string, unknown>>;
  affectPatch?: Readonly<Record<string, unknown>>;
}

export interface ReferencePickerAxisSelection {
  axisId: string;
  itemId: string;
}

export interface ReferencePickerBrowseScope {
  selections: readonly ReferencePickerAxisSelection[];
}

export interface ReferencePickerNavigationItem {
  id: string;
  title: string;
  disabled?: boolean;
}

/** A declared browse axis. The picker clears dependent selections on an upstream change. */
export interface ReferencePickerNavigationAxis {
  id: string;
  title: string;
  dependsOn?: readonly string[];
  items: readonly ReferencePickerNavigationItem[];
}

export interface ReferencePickerPageRequest {
  keyword: string;
  pageNum: number;
  pageSize: number;
  scope: ReferencePickerBrowseScope;
}

export interface ReferencePickerPage<TCandidate extends ReferencePickerCandidate = ReferencePickerCandidate> {
  records: TCandidate[];
  total: number;
  navigation?: readonly ReferencePickerNavigationAxis[];
}

export interface ReferencePickerProvider<
  TCandidate extends ReferencePickerCandidate = ReferencePickerCandidate,
> {
  identity: ReferencePickerSourceIdentity;
  searchPage(request: ReferencePickerPageRequest): Promise<ReferencePickerPage<TCandidate>>;
  /** Resolves persisted IDs under the source's authorized historical-display scope. */
  resolve(ids: ReferencePickerId[]): Promise<TCandidate[]>;
}

export interface ReferencePickerColumn {
  key: string;
  title: string;
  width?: number;
}

/** Compiled page/query configuration for a standard reference interaction. */
export interface ReferencePickerConfig {
  provider: ReferencePickerProvider;
  mode?: 'dialog' | 'dropdown';
  columns?: readonly ReferencePickerColumn[];
  title?: string;
  placeholder?: string;
  searchPlaceholder?: string;
  maxSelection?: number;
  reloadKey?: string | number;
}

export interface ReferencePickerSelectionSummary {
  id: ReferencePickerId;
  title: string;
  subtitle?: string;
  unavailable?: boolean;
}

export function normalizeReferencePickerIds(
  value: ReferencePickerId | readonly ReferencePickerId[] | undefined,
  multiple: boolean,
): ReferencePickerId[] {
  const raw = Array.isArray(value) ? value : value ? [value] : [];
  const unique = [...new Set(raw.filter(Boolean))];
  return multiple ? unique : unique.slice(0, 1);
}

export function referencePickerValue(
  ids: readonly ReferencePickerId[],
  multiple: boolean,
): ReferencePickerId | ReferencePickerId[] | undefined {
  return multiple ? [...ids] : ids[0];
}

export function referencePickerSummary(candidate: ReferencePickerCandidate): ReferencePickerSelectionSummary {
  return {
    id: candidate.id,
    title: candidate.title,
    subtitle: candidate.subtitle,
    unavailable: candidate.unavailable,
  };
}
