import type { OperationProposal } from '@muyun/web-core';
import type {
  BusinessRuleProposal,
  BusinessRulePreview,
  BusinessRuleTrialResult,
} from './businessRuleGovernance';
export interface BusinessRuleTrialInput {
  sampleValues: Record<string, unknown>;
  sampleChildren: Record<string, Record<string, unknown>[]>;
}
export interface BusinessRuleEditor {
  summary(): { moduleAlias: string; title?: string; editable: boolean };
  catalog(section: string): unknown[];
  revise(rule: BusinessRuleProposal): void;
  preview(signal: AbortSignal): Promise<BusinessRulePreview>;
  trial(input: BusinessRuleTrialInput, signal: AbortSignal): Promise<BusinessRuleTrialResult>;
  prepareConfirmation(signal: AbortSignal): Promise<OperationProposal>;
}
