import type { AssistantResultPresentation } from '@muyun/web-contracts';

/** Created only by a trusted capability adapter, never decoded from model output. */
export interface AssistantOperationProposal {
  presentation: AssistantResultPresentation;
  /** Explicit model-safe explanation; human review content is never a model input. */
  modelSummary?: string;
  /** Trusted post-receipt planning only; never another execution approval. */
  continuation?: { message: string; isCurrent(): boolean };
  confirmLabel?: string;
  expiresAt: number;
  isCurrent(): boolean;
  /** Submits the captured payload using the same durable request identity on every attempt. */
  execute(): Promise<AssistantResultPresentation>;
  /** A missing receipt is not permission to automatically resubmit. */
  lookup(): Promise<AssistantResultPresentation | undefined>;
}

export type AssistantConfirmationState =
  | 'pending'
  | 'executing'
  | 'checking'
  | 'succeeded'
  | 'unknown'
  | 'cancelled'
  | 'expired'
  | 'rejected';

/** Adapter proof that no persistent operation was accepted (validation or authorization rejection). */
export class AssistantOperationRejectedError extends Error {}

export interface AssistantOperationConfirmation {
  readonly confirmLabel: string;
  readonly presentation: AssistantResultPresentation;
  readonly modelSummary: string;
  readonly state: AssistantConfirmationState;
  readonly result: AssistantResultPresentation | undefined;
  confirm(): Promise<void>;
  check(): Promise<void>;
  cancel(): void;
  takeContinuation(): string | undefined;
}

/** Human-only execution handle: it is never registered as a model-callable capability. */
export function createAssistantOperationConfirmation(
  proposal: AssistantOperationProposal,
  scopeIsCurrent: () => boolean,
  now: () => number = Date.now,
): AssistantOperationConfirmation {
  let state: AssistantConfirmationState = 'pending';
  let continuationTaken = false;
  const continuation = proposal.continuation && { ...proposal.continuation };
  let result: AssistantResultPresentation | undefined;
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
        if (kind === 'execute' && error instanceof AssistantOperationRejectedError) {
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
    modelSummary: proposal.modelSummary ?? '有一项操作等待用户确认，尚未执行。',
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
    takeContinuation() {
      if (continuationTaken || state !== 'succeeded' || !scopeIsCurrent() || !continuation?.isCurrent())
        return undefined;
      continuationTaken = true;
      return continuation.message;
    },
    confirm: () => run('execute'),
    check: () => run('lookup'),
    cancel() {
      if (state === 'pending' || state === 'rejected') state = 'cancelled';
    },
  };
}
