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

  it('uses a display message by rule identity while evaluating the original program', () => {
    const display = {
      ...positiveAmount,
      message: 'Amount must be positive',
      program: { ...positiveAmount.program, profile: 'FORM_COMPUTE' as const },
    };
    expect(
      new FormValidationCoordinator([positiveAmount]).validate({ contractAmount: 0 }, [display])?.message,
    ).toBe('Amount must be positive');
    expect(
      new FormValidationCoordinator([positiveAmount]).validate({ contractAmount: 1 }, [display]),
    ).toBeUndefined();
    expect(positiveAmount.message).toBe('合同金额必须大于 0');
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
