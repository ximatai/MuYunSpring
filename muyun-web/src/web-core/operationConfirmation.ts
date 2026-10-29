import { platformLanguageContext, type LanguageContext } from './language';
import { resolveOperationErrorMessage, type OperationErrorMessage } from './operationErrors';
import type { OperationPresentation, OperationReceiptReference, OperationResult } from '@muyun/web-contracts';

/** Read-only recovery of a domain-owned durable command identity. No payload or write callback is restored. */
export interface OperationRecovery<TResult extends OperationResult = OperationResult> {
  presentation: OperationPresentation;
  /** Reauthorize and map authoritative domain facts; missing or inaccessible does not mean failed. */
  lookup(): Promise<TResult | undefined>;
}

/** Captured platform command. The caller owns the candidate and execution scope. */
export interface OperationProposal<
  TResult extends OperationResult = OperationPresentation,
> extends OperationRecovery<TResult> {
  receiptReference?: OperationReceiptReference;
  confirmLabel?: string;
  expiresAt: number;
  isCurrent(): boolean;
  /** Submits the captured payload using the same durable request identity on every attempt. */
  execute(): Promise<TResult>;
}

export type OperationConfirmationState =
  | 'pending'
  | 'executing'
  | 'checking'
  | 'processing'
  | 'failed'
  | 'succeeded'
  | 'unknown'
  | 'cancelled'
  | 'expired'
  | 'rejected';

/** Adapter proof that no persistent operation was accepted (validation or authorization rejection). */
export class OperationRejectedError extends Error {
  readonly textMessage?: OperationErrorMessage;
  constructor(message: string | OperationErrorMessage = '', options?: ErrorOptions) {
    super(typeof message === 'string' ? message : message.message, options);
    this.textMessage =
      typeof message === 'string'
        ? undefined
        : Object.freeze({
            ...message,
            source: Object.freeze({ ...message.source }),
            args: Object.freeze({ ...message.args }),
          });
  }
}

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
  proposal: OperationProposal<OperationResult>,
  scopeIsCurrent: () => boolean,
  now: () => number = Date.now,
  language: LanguageContext = platformLanguageContext,
  receiptScopeIsCurrent: () => boolean = scopeIsCurrent,
): OperationConfirmation {
  return createConfirmation(proposal, scopeIsCurrent, now, language, proposal, receiptScopeIsCurrent);
}

/** Restores only the read path; confirm/cancel cannot execute or discard an already submitted command. */
export function restoreOperationConfirmation(
  recovery: OperationRecovery,
  scopeIsCurrent: () => boolean,
  language: LanguageContext = platformLanguageContext,
): OperationConfirmation {
  return createConfirmation(recovery, scopeIsCurrent, Date.now, language);
}

function createConfirmation(
  operation: OperationRecovery,
  scopeIsCurrent: () => boolean,
  now: () => number,
  language: LanguageContext,
  proposal?: OperationProposal<OperationResult>,
  receiptScopeIsCurrent: () => boolean = scopeIsCurrent,
): OperationConfirmation {
  let state: OperationConfirmationState = proposal ? 'pending' : 'unknown';
  let result: OperationPresentation | undefined;
  let rejectedFailure: OperationRejectedError | undefined;
  const confirmLabel = proposal?.confirmLabel;
  let pending: Promise<void> | undefined;
  const presentation = structuredClone(operation.presentation);

  function valid() {
    return scopeIsCurrent() && proposal?.isCurrent() === true && now() < proposal.expiresAt;
  }

  function run(kind: 'execute' | 'lookup') {
    if (pending) return pending;
    if (kind === 'execute') {
      if (!proposal) return Promise.resolve();
      if (state !== 'pending' && state !== 'rejected') return Promise.resolve();
      if (!valid()) {
        state = 'expired';
        return Promise.resolve();
      }
    } else if (!['unknown', 'processing'].includes(state) || !receiptScopeIsCurrent())
      return Promise.resolve();
    state = kind === 'execute' ? 'executing' : 'checking';
    result = undefined;
    rejectedFailure = undefined;
    // Capture state before scheduling; two clicks must share one request.
    pending = Promise.resolve().then(async () => {
      try {
        if (kind === 'execute' && !valid()) {
          state = 'expired';
          return;
        }
        if (kind === 'lookup' && !receiptScopeIsCurrent()) {
          state = 'unknown';
          return;
        }
        const receipt = await (kind === 'execute' ? proposal!.execute() : operation.lookup());
        // A response from the previous identity/scope must never enter the current UI.
        if (!receiptScopeIsCurrent()) {
          state = 'unknown';
          return;
        }
        if (receipt) {
          if ('status' in receipt) {
            if (!['processing', 'succeeded', 'failed'].includes(receipt.status))
              throw new Error('Invalid operation receipt status');
            result = structuredClone(receipt.presentation);
            state = receipt.status;
          } else {
            result = structuredClone(receipt);
            state = 'succeeded';
          }
        } else state = 'unknown';
      } catch (error) {
        if (receiptScopeIsCurrent() && kind === 'execute' && error instanceof OperationRejectedError) {
          rejectedFailure = new OperationRejectedError(error.textMessage ?? error.message);
          result = {
            title: language.t('platform.operation.not-submitted', '操作未提交'),
            lines: [resolveOperationErrorMessage(error, language)],
          };
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
    get confirmLabel() {
      return !proposal ? '' : (confirmLabel ?? language.t('platform.operation.confirm-save', '确认保存'));
    },
    get presentation() {
      return structuredClone(presentation);
    },
    get state() {
      if ((state === 'pending' || state === 'rejected') && !valid()) state = 'expired';
      return state;
    },
    get result() {
      return receiptScopeIsCurrent() && result
        ? {
            ...structuredClone(result),
            ...(rejectedFailure
              ? {
                  title: language.t('platform.operation.not-submitted', '操作未提交'),
                  lines: [resolveOperationErrorMessage(rejectedFailure, language)],
                }
              : {}),
          }
        : undefined;
    },
    confirm: () => run('execute'),
    check: () => run('lookup'),
    cancel() {
      if (state === 'pending' || state === 'rejected') state = 'cancelled';
    },
  };
}
