import { describe, expect, it } from 'vitest';
import { FormComputeCoordinator } from '@/dynamic-page-runtime/formComputeCoordinator';
import type { FormulaNode, FormulaProgram, ResolvedFormComputeRuleDescriptor } from '@muyun/web-contracts';

describe('FormComputeCoordinator', () => {
  it('propagates declared trigger chains in descriptor order without mutating the input draft', () => {
    const draft = { quantity: 3, unitPrice: 5, amount: 0, label: '' };
    const coordinator = new FormComputeCoordinator([
      rule('amount', ['quantity', 'unitPrice'], binary('*', field('quantity'), field('unitPrice'))),
      rule('label', ['amount'], binary('+', value('金额：'), field('amount'))),
    ]);

    const result = coordinator.applyAfterChange({ ...draft, quantity: 4 }, ['quantity']);

    expect(result).toEqual({ quantity: 4, unitPrice: 5, amount: 20, label: '金额：20' });
    expect(draft).toEqual({ quantity: 3, unitPrice: 5, amount: 0, label: '' });
  });

  it('does nothing for a form without rules or a change outside every trigger', () => {
    const draft = { source: 2, target: 0 };
    expect(new FormComputeCoordinator(undefined).applyAfterChange(draft, ['source'])).toBe(draft);
    expect(
      new FormComputeCoordinator([rule('target', ['source'], field('source'))]).applyAfterChange(draft, [
        'other',
      ]),
    ).toBe(draft);
  });

  it('runs each rule at most once so cyclic descriptors cannot repeatedly overwrite the draft', () => {
    const coordinator = new FormComputeCoordinator([
      rule('a', ['b'], value(1), 'set-a'),
      rule('b', ['a'], value(2), 'set-b'),
    ]);

    expect(coordinator.applyAfterChange({ a: 0, b: 0 }, ['b'])).toEqual({ a: 1, b: 2 });
  });

  it('fails closed for an unexpected program without discarding the user field update', () => {
    const invalid: FormulaProgram = {
      schemaVersion: 999,
      profile: 'FORM_COMPUTE',
      root: assign('target', value(99)),
      referencedFields: [],
    };
    const result = new FormComputeCoordinator([
      {
        code: 'invalid',
        program: invalid,
        targetField: 'target',
        targetValueType: 'DECIMAL',
        triggerFields: ['source'],
        writePolicy: 'ALWAYS',
      },
    ]).applyAfterChange({ source: 3, target: 0 }, ['source']);

    expect(result).toEqual({ source: 3, target: 0 });
  });
});

function rule(
  targetField: string,
  triggerFields: string[],
  expression: FormulaNode,
  code = targetField,
): ResolvedFormComputeRuleDescriptor {
  return {
    code,
    program: {
      schemaVersion: 1,
      profile: 'FORM_COMPUTE',
      root: assign(targetField, expression),
      referencedFields: [],
    },
    targetField,
    targetValueType: targetField === 'label' ? 'STRING' : 'DECIMAL',
    triggerFields,
    writePolicy: 'ALWAYS',
  };
}

function assign(fieldName: string, expression: FormulaNode): FormulaNode {
  return { kind: 'ASSIGN', operator: '=', arguments: [field(fieldName), expression] };
}

function binary(operator: string, left: FormulaNode, right: FormulaNode): FormulaNode {
  return { kind: 'BINARY', operator, arguments: [left, right] };
}

function field(fieldName: string): FormulaNode {
  return { kind: 'FIELD', field: fieldName, arguments: [] };
}

function value(input: string | number): FormulaNode {
  return { kind: 'VALUE', value: input, arguments: [] };
}
