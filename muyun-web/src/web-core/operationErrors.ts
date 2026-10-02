/** A rejected editor operation with safe, bounded feedback for callers. */
export class OperationUsageError extends Error {
  constructor(
    message: string,
    readonly code:
      | 'CAPABILITY_USAGE_INVALID'
      | 'CANDIDATE_AMBIGUOUS'
      | 'CANDIDATE_EXPIRED'
      | 'PRECONDITION_FAILED' = 'CAPABILITY_USAGE_INVALID',
  ) {
    super(message);
    this.name = 'OperationUsageError';
  }
}
