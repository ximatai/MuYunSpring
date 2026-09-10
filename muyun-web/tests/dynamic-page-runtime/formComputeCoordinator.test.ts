import { describe, expect, it } from 'vitest';
import { FormComputeCoordinator } from '@/dynamic-page-runtime/formComputeCoordinator';
import type { FormulaNode, FormulaProgram, ResolvedFormComputeRuleDescriptor } from '@muyun/web-contracts';

describe('FormComputeCoordinator', () => {
  it('initializes constant computations and their dependents once when creating a form', () => {
    const coordinator = new FormComputeCoordinator([
      rule('amount', ['quantity', 'price'], binary('*', field('quantity'), field('price'))),
      rule('quantity', [], value(2)),
    ]);
    const draft = { price: 5 };
    expect(coordinator.applyOnCreate(draft)).toEqual({ price: 5, quantity: 2, amount: 10 });
    expect(draft).toEqual({ price: 5 });
    expect(coordinator.applyAfterChange({ price: 6, quantity: 2, amount: 10 }, ['price'])).toEqual({
      price: 6,
      quantity: 2,
      amount: 12,
    });
  });

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

  it('orders a diamond by formula reads when a consumer omits upstream fields from its triggers', () => {
    const coordinator = new FormComputeCoordinator([
      rule('left', ['source'], binary('+', field('source'), value(1))),
      rule('total', ['source'], binary('+', field('left'), field('right'))),
      rule('right', ['source'], binary('*', field('source'), value(2))),
    ]);

    expect(coordinator.applyAfterChange({ source: 3, left: 0, right: 0, total: 0 }, ['source'])).toEqual({
      source: 3,
      left: 4,
      right: 6,
      total: 10,
    });
  });

  it('evaluates a batched input change against the complete draft', () => {
    const coordinator = new FormComputeCoordinator([
      rule('amount', ['quantity', 'unitPrice'], binary('*', field('quantity'), field('unitPrice'))),
    ]);

    expect(
      coordinator.applyAfterChange({ quantity: 4, unitPrice: 5, amount: 0 }, ['quantity', 'unitPrice']),
    ).toEqual({
      quantity: 4,
      unitPrice: 5,
      amount: 20,
    });
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

  it('does not calculate constant rules or a computed target changed by the user', () => {
    const coordinator = new FormComputeCoordinator([
      rule('constant', [], value(1)),
      rule('amount', ['quantity'], field('quantity')),
    ]);

    const draft = { quantity: 3, amount: 99, constant: 0 };
    expect(coordinator.applyAfterChange(draft, ['amount'])).toBe(draft);
    expect(coordinator.applyAfterChange(draft, ['quantity'])).toEqual({
      quantity: 3,
      amount: 3,
      constant: 0,
    });
  });

  it.each([
    [
      'duplicate rule code',
      [rule('left', ['source'], value(1), 'duplicate'), rule('right', ['source'], value(2), 'duplicate')],
    ],
    [
      'duplicate target field',
      [rule('target', ['source'], value(1), 'first'), rule('target', ['source'], value(2), 'second')],
    ],
    ['cyclic dependencies', [rule('a', ['b'], field('b'), 'set-a'), rule('b', ['a'], field('a'), 'set-b')]],
  ])('fails closed without partial writes for %s', (_reason, rules) => {
    const draft = { source: 3, a: 0, b: 0, left: 0, right: 0, target: 0 };
    expect(new FormComputeCoordinator(rules).applyAfterChange(draft, ['source', 'b'])).toBe(draft);
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
