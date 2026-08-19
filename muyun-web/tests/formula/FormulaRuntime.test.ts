import { describe, expect, it } from 'vitest';
import { FormulaRuntime } from '../../src/formula/FormulaRuntime';
import type { FormulaProgram, ViewFieldValueType } from '../../src/web-contracts';

const runtime = new FormulaRuntime();

describe('FormulaRuntime FORM_COMPUTE', () => {
  it('evaluates a deterministic assignment locally without mutating the draft', () => {
    const draft = { amount: 0, quantity: '3', unitPrice: 4, enabled: 'false' };
    const result = evaluateCompute(
      program(
        assign(
          field('amount'),
          binary(
            '+',
            binary('*', field('quantity'), field('unitPrice')),
            binary('&&', field('enabled'), value(2)),
          ),
        ),
      ),
      draft,
    );

    expect(result).toEqual({ patch: { amount: 12 }, changedFields: ['amount'] });
    expect(draft).toEqual({ amount: 0, quantity: '3', unitPrice: 4, enabled: 'false' });
    expect(Object.isFrozen(result)).toBe(true);
    expect(Object.isFrozen(result.patch)).toBe(true);
    expect(Object.isFrozen(result.changedFields)).toBe(true);
  });

  it('aligns empty values, loose equality, IN and zero division with FormulaEngine semantics', () => {
    const draft = { amount: null, priority: '2', divisor: 0 };

    expect(
      evaluateCompute(program(assign(field('amount'), binary('/', value(9), field('divisor')))), draft),
    ).toEqual({ patch: { amount: 0 }, changedFields: ['amount'] });
    expect(
      evaluateCompute(
        program(assign(field('amount'), functionNode('IN', field('priority'), value(2), value('3')))),
        draft,
        'BOOLEAN',
      ),
    ).toEqual({ patch: { amount: true }, changedFields: ['amount'] });
    expect(
      evaluateCompute(
        program(assign(field('amount'), functionNode('ISNULL', field('amount')))),
        draft,
        'BOOLEAN',
      ),
    ).toEqual({ patch: { amount: true }, changedFields: ['amount'] });
    expect(
      evaluateCompute(
        program(assign(field('amount'), binary('>', value('z'), value('aa')))),
        draft,
        'BOOLEAN',
      ),
    ).toEqual({ patch: { amount: true }, changedFields: ['amount'] });
    expect(
      evaluateCompute(
        program(assign(field('amount'), binary('==', field('hexText'), value(16)))),
        { amount: null, hexText: '0x10' },
        'BOOLEAN',
      ),
    ).toEqual({ patch: { amount: false }, changedFields: ['amount'] });
    expect(
      evaluateCompute(
        program(assign(field('amount'), binary('==', field('nanText'), value('x')))),
        { amount: null, nanText: 'NaN' },
        'BOOLEAN',
      ),
    ).toEqual({ patch: { amount: true }, changedFields: ['amount'] });
  });

  it('fails closed for malformed, unsupported and unchanged programs', () => {
    expect(evaluateCompute(program(assign(field('amount'), value(1))), { amount: 1 })).toEqual({
      patch: {},
      changedFields: [],
    });
    expect(
      evaluateCompute({ ...program(assign(field('amount'), value(1))), schemaVersion: 2 }, { amount: 0 }),
    ).toEqual({ patch: {}, changedFields: [] });
    expect(
      evaluateCompute(
        { ...program(assign(field('amount'), value(1))), root: functionNode('NOW') },
        { amount: 0 },
      ),
    ).toEqual({ patch: {}, changedFields: [] });
  });

  it('normalizes target writes with the same typed semantics as the server', () => {
    expect(
      runtime.evaluateFormCompute(
        program(assign(field('code'), binary('+', value(1), value(1)))),
        {},
        'STRING',
      ),
    ).toEqual({ patch: { code: '2.0' }, changedFields: ['code'] });
    expect(
      runtime.evaluateFormCompute(
        program(assign(field('code'), binary('+', value(1), value(1)))),
        { code: '2' },
        'STRING',
      ),
    ).toEqual({ patch: { code: '2.0' }, changedFields: ['code'] });
    expect(runtime.evaluateFormCompute(program(assign(field('enabled'), value(1))), {}, 'BOOLEAN')).toEqual({
      patch: { enabled: true },
      changedFields: ['enabled'],
    });
    expect(runtime.evaluateFormCompute(program(assign(field('count'), value(2.5))), {}, 'INTEGER')).toEqual({
      patch: {},
      changedFields: [],
    });
    expect(
      runtime.evaluateFormCompute(program(assign(field('payload'), value('value'))), {}, 'JSON'),
    ).toEqual({ patch: {}, changedFields: [] });
  });
});

function evaluateCompute(
  programValue: FormulaProgram,
  draft: Record<string, unknown>,
  targetValueType: ViewFieldValueType = 'DECIMAL',
) {
  return runtime.evaluateFormCompute(programValue, draft, targetValueType);
}

function program(root: FormulaProgram['root']): FormulaProgram {
  return { schemaVersion: 1, profile: 'FORM_COMPUTE', root, referencedFields: [] };
}

function assign(target: FormulaProgram['root'], expression: FormulaProgram['root']): FormulaProgram['root'] {
  return { kind: 'ASSIGN', operator: '=', arguments: [target, expression] };
}

function field(name: string): FormulaProgram['root'] {
  return { kind: 'FIELD', field: name, arguments: [] };
}

function value(input: string | number | boolean | null): FormulaProgram['root'] {
  return { kind: 'VALUE', value: input, arguments: [] };
}

function binary(
  operator: string,
  left: FormulaProgram['root'],
  right: FormulaProgram['root'],
): FormulaProgram['root'] {
  return { kind: 'BINARY', operator, arguments: [left, right] };
}

function functionNode(operator: string, ...arguments_: FormulaProgram['root'][]): FormulaProgram['root'] {
  return { kind: 'FUNCTION', operator, arguments: arguments_ };
}
