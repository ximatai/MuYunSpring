import type { OperationReceiptReference, OperationPresentation } from '@muyun/web-contracts';
import {
  createOperationConfirmation,
  type OperationProposal,
  type OperationConfirmation,
  type OperationConfirmationState,
} from './operationConfirmation';
export { OperationRejectedError as AssistantOperationRejectedError } from './operationConfirmation';
export type { OperationConfirmationState as AssistantConfirmationState } from './operationConfirmation';

/** Assistant-only explanation and continuation decorate a platform command. */
export interface AssistantOperationProposal extends OperationProposal {
  modelSummary?: string;
  continuation?: { message: string; readOnly?: boolean; isCurrent(): boolean };
}
export interface AssistantOperationConfirmation extends OperationConfirmation {
  readonly modelSummary: string;
  readonly receiptReference?: OperationReceiptReference;
  readonly continuationReadOnly?: boolean;
  readonly continuationIsCurrent?: () => boolean;
  takeContinuation(): string | undefined;
}
export function createAssistantOperationConfirmation(
  proposal: AssistantOperationProposal,
  scopeIsCurrent: () => boolean,
  now: () => number = Date.now,
): AssistantOperationConfirmation {
  const confirmation = createOperationConfirmation(proposal, scopeIsCurrent, now);
  const continuation = proposal.continuation && { ...proposal.continuation };
  const continuationIsCurrent = () =>
    confirmation.state === 'succeeded' && scopeIsCurrent() && continuation?.isCurrent() === true;
  let taken = false;
  return {
    get confirmLabel() {
      return confirmation.confirmLabel;
    },
    get presentation() {
      return confirmation.presentation;
    },
    get state() {
      return confirmation.state;
    },
    get result() {
      return confirmation.result;
    },
    receiptReference: proposal.receiptReference && structuredClone(proposal.receiptReference),
    confirm: confirmation.confirm,
    check: confirmation.check,
    cancel: confirmation.cancel,
    modelSummary: proposal.modelSummary ?? '有一项操作等待用户确认，尚未执行。',
    continuationReadOnly: continuation?.readOnly === true,
    continuationIsCurrent,
    takeContinuation() {
      if (taken || !continuationIsCurrent()) return undefined;
      taken = true;
      return continuation!.message;
    },
  };
}

/** Recovered results have no execute path, expiry renewal, or automatic continuation. */
export function restoreAssistantOperationReceipt(
  reference: OperationReceiptReference,
  lookup: () => Promise<OperationPresentation | undefined>,
  scopeIsCurrent: () => boolean,
): AssistantOperationConfirmation {
  let state: OperationConfirmationState = 'unknown';
  let result: OperationPresentation | undefined;
  let pending: Promise<void> | undefined;
  return {
    receiptReference: structuredClone(reference),
    modelSummary: '历史操作结果待核实，只能查询，不能重新提交。',
    confirmLabel: '',
    presentation: {
      title: '核实历史操作',
      lines: ['请在原身份与业务范围内查询；重新校验当前权限。未查询到不表示操作未提交。'],
    },
    get state() {
      return state;
    },
    get result() {
      return result;
    },
    confirm: async () => {},
    cancel() {},
    takeContinuation: () => undefined,
    check() {
      if (pending) return pending;
      if (state !== 'unknown' || !scopeIsCurrent()) return Promise.resolve();
      state = 'checking';
      pending = Promise.resolve().then(async () => {
        try {
          if (!scopeIsCurrent()) return;
          const found = await lookup();
          if (scopeIsCurrent() && found) {
            result = found;
            state = 'succeeded';
          }
        } catch {
          /* Missing access or a failed query does not prove a failed write. */
        } finally {
          if (state === 'checking') state = 'unknown';
          pending = undefined;
        }
      });
      return pending;
    },
  };
}
