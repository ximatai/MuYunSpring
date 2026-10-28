import { platformLanguageContext, type LanguageContext } from './language';
import type { OperationReceiptReference, OperationResult, OperationPresentation } from '@muyun/web-contracts';
import {
  createOperationConfirmation,
  restoreOperationConfirmation,
  type OperationProposal,
  type OperationConfirmation,
} from './operationConfirmation';
export { OperationRejectedError as AssistantOperationRejectedError } from './operationConfirmation';
export type { OperationConfirmationState as AssistantConfirmationState } from './operationConfirmation';

/** Assistant-only explanation and continuation decorate a platform command. */
export interface AssistantOperationProposal<
  TResult extends OperationResult = OperationPresentation,
> extends OperationProposal<TResult> {
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
  proposal: AssistantOperationProposal<OperationResult>,
  scopeIsCurrent: () => boolean,
  now: () => number = Date.now,
  language: LanguageContext = platformLanguageContext,
  receiptScopeIsCurrent: () => boolean = scopeIsCurrent,
): AssistantOperationConfirmation {
  const confirmation = createOperationConfirmation(
    proposal,
    scopeIsCurrent,
    now,
    language,
    receiptScopeIsCurrent,
  );
  const modelSummary = proposal.modelSummary;
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
    get modelSummary() {
      return (
        modelSummary ??
        language.t(
          'platform.screen.assistantConfirmation.an-operation-is-awaiting-user-confirmation-and-has-not-been',
          '有一项操作等待用户确认，尚未执行。',
        )
      );
    },
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
  lookup: () => Promise<OperationResult | undefined>,
  scopeIsCurrent: () => boolean,
  language: LanguageContext = platformLanguageContext,
): AssistantOperationConfirmation {
  const confirmation = restoreOperationConfirmation(
    { presentation: { title: '', lines: [] }, lookup },
    scopeIsCurrent,
    language,
  );
  return {
    receiptReference: structuredClone(reference),
    get modelSummary() {
      return language.t(
        'platform.screen.assistantConfirmation.the-historical-result-needs-verification-it-can-only-be-queried',
        '历史操作结果待核实，只能查询，不能重新提交。',
      );
    },
    confirmLabel: '',
    get presentation() {
      return {
        title: language.t(
          'platform.screen.assistantConfirmation.verify-historical-operation',
          '核实历史操作',
        ),
        lines: [
          language.t(
            'platform.screen.assistantConfirmation.query-within-the-original-identity-and-business-scope-checking-current',
            '请在原身份与业务范围内查询；重新校验当前权限。未查询到不表示操作未提交。',
          ),
        ],
      };
    },
    get state() {
      return confirmation.state;
    },
    get result() {
      return confirmation.result;
    },
    confirm: async () => {},
    cancel() {},
    takeContinuation: () => undefined,
    check: confirmation.check,
  };
}
