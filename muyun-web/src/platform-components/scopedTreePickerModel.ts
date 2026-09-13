/**
 * An authorized, source-owned record projection for a lazily loaded tree selector.
 * The component deliberately does not infer a parent, tenant, or module from this shape.
 */
export interface ScopedTreePickerCandidate {
  id: string;
  title: string;
  /** A provider may return a path, code, or scope summary to disambiguate equal titles. */
  subtitle?: string;
  /** False keeps the item expandable and asks the provider for children when it is expanded. */
  isLeaf?: boolean;
  disabled?: boolean;
  unavailable?: boolean;
}

export interface ScopedTreePickerRootRequest {
  /** Provider-defined search text. An empty string requests the current scope roots. */
  keyword: string;
  signal: AbortSignal;
}

export interface ScopedTreePickerChildrenRequest extends ScopedTreePickerRootRequest {
  parent: ScopedTreePickerCandidate;
  /** Opaque continuation supplied by an earlier child response when the provider supports paging. */
  cursor?: string;
}

export interface ScopedTreePickerPage {
  records: ScopedTreePickerCandidate[];
  hasMore?: boolean;
  nextCursor?: string;
}

/**
 * A source-owned delivery boundary. The caller may use a static reference resolver, a dedicated
 * candidate endpoint, or another authorized source; this component has no module-path knowledge.
 */
export interface ScopedTreePickerProvider {
  loadRoot: (request: ScopedTreePickerRootRequest) => Promise<ScopedTreePickerPage>;
  loadChildren: (request: ScopedTreePickerChildrenRequest) => Promise<ScopedTreePickerPage>;
  /** Resolves persisted IDs through the same authorization and effective-scope rule. */
  resolve: (ids: string[]) => Promise<ScopedTreePickerCandidate[]>;
}

export interface ScopedTreePickerConfig {
  title?: string;
  placeholder?: string;
  searchPlaceholder?: string;
  provider: ScopedTreePickerProvider;
}

export function scopedTreePickerValue(value: string | undefined) {
  return value?.trim() || undefined;
}
