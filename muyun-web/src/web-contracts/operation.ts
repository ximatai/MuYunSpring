/** Platform operation presentation; never interpreted from model prose. */
export interface OperationPresentation {
  title: string;
  lines: string[];
  /** Secondary, fully reviewable details; never used as an execution payload. */
  details?: { title: string; lines: string[] };
}

/** Authoritative command observation. Acceptance is not completion; failure never authorizes replay. */
export interface OperationReceipt {
  status: 'processing' | 'succeeded' | 'failed';
  presentation: OperationPresentation;
}

/** Existing immediate-success adapters may continue returning a presentation. */
export type OperationResult = OperationPresentation | OperationReceipt;

/** A bounded read-only pointer, never an executable command or authority. */
export type OperationReceiptReference =
  | {
      kind: 'record-save';
      /** Independent record context for a receipt lookup; never an authorization grant. */
      recordId?: string;
      moduleAlias: string;
      requestId: string;
      tenantId?: string;
      menuId?: string;
      pageContext?: Record<string, string>;
      pageSelection?: { kind: string; key: string };
    }
  | { kind: 'child-metadata'; moduleAlias: string; relationId: string; requestId: string }
  | { kind: 'page-publication'; variantId: string; revisionId: string; contentDigest: string };
