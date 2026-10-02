import {
  createOperationConfirmation,
  type OperationProposal,
  type OperationConfirmation,
} from './operationConfirmation';
export { OperationRejectedError as AssistantOperationRejectedError } from './operationConfirmation';
export type { OperationConfirmationState as AssistantConfirmationState } from './operationConfirmation';

/** Assistant-only explanation and continuation decorate a platform command. */
export interface AssistantOperationProposal extends OperationProposal {
  modelSummary?: string;
  continuation?: { message: string; isCurrent(): boolean };
}
export interface AssistantOperationConfirmation extends OperationConfirmation {
  readonly modelSummary: string;
  takeContinuation(): string | undefined;
}
export function createAssistantOperationConfirmation(
  proposal: AssistantOperationProposal,
  scopeIsCurrent: () => boolean,
  now: () => number = Date.now,
): AssistantOperationConfirmation {
  const confirmation = createOperationConfirmation(proposal, scopeIsCurrent, now);
  const continuation = proposal.continuation && { ...proposal.continuation };
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
    confirm: confirmation.confirm,
    check: confirmation.check,
    cancel: confirmation.cancel,
    modelSummary: proposal.modelSummary ?? '有一项操作等待用户确认，尚未执行。',
    takeContinuation() {
      if (taken || confirmation.state !== 'succeeded' || !scopeIsCurrent() || !continuation?.isCurrent())
        return undefined;
      taken = true;
      return continuation.message;
    },
  };
}
