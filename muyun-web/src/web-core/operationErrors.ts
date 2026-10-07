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

/** A staged draft was rejected before publication; no draft or display state was changed. */
export class DraftUpdateRejectedError extends OperationUsageError {
  constructor(failure: OperationUsageError) {
    super(failure.message, failure.code);
    this.name = 'DraftUpdateRejectedError';
  }
}
