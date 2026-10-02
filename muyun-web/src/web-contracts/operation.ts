/** Platform operation presentation; never interpreted from model prose. */
export interface OperationPresentation {
  title: string;
  lines: string[];
  /** Secondary, fully reviewable details; never used as an execution payload. */
  details?: { title: string; lines: string[] };
}

/** A bounded read-only pointer, never an executable command or authority. */
export type OperationReceiptReference =
  | { kind: 'record-save'; moduleAlias: string; requestId: string; tenantId?: string; menuId?: string }
  | { kind: 'child-metadata'; moduleAlias: string; relationId: string; requestId: string }
  | { kind: 'page-publication'; variantId: string; revisionId: string; contentDigest: string };
