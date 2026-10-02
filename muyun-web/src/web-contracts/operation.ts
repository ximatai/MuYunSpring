/** Platform operation presentation; never interpreted from model prose. */
export interface OperationPresentation {
  title: string;
  lines: string[];
  /** Secondary, fully reviewable details; never used as an execution payload. */
  details?: { title: string; lines: string[] };
}
