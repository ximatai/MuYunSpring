import type { OperationPresentation, OperationReceiptReference } from '@muyun/web-contracts';

/** Captured platform command. The caller owns the candidate and execution scope. */
export interface OperationProposal {
  receiptReference?: OperationReceiptReference;
  presentation: OperationPresentation;
  confirmLabel?: string;
  expiresAt: number;
  isCurrent(): boolean;
  /** Submits the captured payload using the same durable request identity on every attempt. */
  execute(): Promise<OperationPresentation>;
  /** A missing receipt is not permission to automatically resubmit. */
  lookup(): Promise<OperationPresentation | undefined>;
}

export type OperationConfirmationState =
  | 'pending'
  | 'executing'
  | 'checking'
  | 'succeeded'
  | 'unknown'
  | 'cancelled'
  | 'expired'
  | 'rejected';

/** Adapter proof that no persistent operation was accepted (validation or authorization rejection). */
export class OperationRejectedError extends Error {}

export interface OperationConfirmation {
  readonly confirmLabel: string;
  readonly presentation: OperationPresentation;
  readonly state: OperationConfirmationState;
  readonly result: OperationPresentation | undefined;
  confirm(): Promise<void>;
  check(): Promise<void>;
  cancel(): void;
}

/** Explicit confirmation, duplicate-click protection and read-only recovery of uncertain writes. */
export function createOperationConfirmation(
  proposal: OperationProposal,
  scopeIsCurrent: () => boolean,
  now: () => number = Date.now,
): OperationConfirmation {
  let state: OperationConfirmationState = 'pending';
  let result: OperationPresentation | undefined;
  let pending: Promise<void> | undefined;
  const presentation = structuredClone(proposal.presentation);

  function valid() {
    return scopeIsCurrent() && proposal.isCurrent() && now() < proposal.expiresAt;
  }

  function run(kind: 'execute' | 'lookup') {
    if (pending) return pending;
    if (kind === 'execute') {
      if (state !== 'pending' && state !== 'rejected') return Promise.resolve();
      if (!valid()) {
        state = 'expired';
        return Promise.resolve();
      }
    } else if (state !== 'unknown' || !scopeIsCurrent()) return Promise.resolve();
    state = kind === 'execute' ? 'executing' : 'checking';
    result = undefined;
    // Capture state before scheduling; two clicks must share one request.
    pending = Promise.resolve().then(async () => {
      try {
        if (kind === 'execute' && !valid()) {
          state = 'expired';
          return;
        }
        if (kind === 'lookup' && !scopeIsCurrent()) {
          state = 'unknown';
          return;
        }
        const receipt = await (kind === 'execute' ? proposal.execute() : proposal.lookup());
        if (receipt) {
          result = structuredClone(receipt);
          state = 'succeeded';
        } else state = 'unknown';
      } catch (error) {
        if (kind === 'execute' && error instanceof OperationRejectedError) {
          result = { title: '操作未提交', lines: [error.message] };
          state = 'rejected';
          return;
        }
        // A rejected transport does not prove that the transaction failed.
        state = 'unknown';
      } finally {
        pending = undefined;
      }
    });
    return pending;
  }

  return {
    confirmLabel: proposal.confirmLabel ?? '确认保存',
    get presentation() {
      return structuredClone(presentation);
    },
    get state() {
      if ((state === 'pending' || state === 'rejected') && !valid()) state = 'expired';
      return state;
    },
    get result() {
      return result && structuredClone(result);
    },
    confirm: () => run('execute'),
    check: () => run('lookup'),
    cancel() {
      if (state === 'pending' || state === 'rejected') state = 'cancelled';
    },
  };
}
