import { describe, expect, it } from 'vitest';
import { FormValidationCoordinator } from '@/dynamic-page-runtime/formValidationCoordinator';
import type { FormulaProgram, ResolvedFormValidationRuleDescriptor } from '@/web-contracts';

const positiveAmount: ResolvedFormValidationRuleDescriptor = {
  code: 'contractAmountPositive',
  message: '合同金额必须大于 0',
  targetField: 'contractAmount',
  inputFields: ['contractAmount'],
  program: {
    schemaVersion: 1,
    profile: 'FORM_VALIDATION',
    referencedFields: ['contractAmount'],
    root: {
      kind: 'BINARY',
      operator: '>',
      arguments: [
        { kind: 'FIELD', field: 'contractAmount', arguments: [] },
        { kind: 'VALUE', value: 0, arguments: [] },
      ],
    },
  },
};

describe('FormValidationCoordinator', () => {
  it('returns the server-configured message and target before a failing mutation', () => {
    expect(new FormValidationCoordinator([positiveAmount]).validate({ contractAmount: 0 })).toEqual({
      code: 'contractAmountPositive',
      message: '合同金额必须大于 0',
      targetField: 'contractAmount',
    });
    expect(new FormValidationCoordinator([positiveAmount]).validate({ contractAmount: 1 })).toBeUndefined();
  });

  it('fails open for malformed descriptors so the server remains the authority', () => {
    const malformed: ResolvedFormValidationRuleDescriptor = {
      ...positiveAmount,
      program: { ...positiveAmount.program, profile: 'FORM_COMPUTE' },
    };
    expect(new FormValidationCoordinator([malformed]).validate({ contractAmount: 0 })).toBeUndefined();
  });
});

export const formValidationProgram = positiveAmount.program as FormulaProgram;
