import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { FormulaRuntime } from '../../src/formula/FormulaRuntime';
import type { FormulaProgram } from '../../src/web-contracts';

type GoldenVector = {
  id: string;
  profile: FormulaProgram['profile'];
  targetValueType?: import('../../src/web-contracts').ViewFieldValueType;
  record: Record<string, unknown>;
  expected: boolean | Record<string, unknown>;
  program: FormulaProgram;
};

const vectors = JSON.parse(
  readFileSync(new URL('../../../contracts/formula-program-golden-vectors.json', import.meta.url), 'utf8'),
) as { vectors: GoldenVector[] };
const runtime = new FormulaRuntime();

describe('FormulaProgram cross-engine golden vectors', () => {
  it('executes the JSON programs compiled and round-tripped by FormulaEngine', () => {
    for (const vector of vectors.vectors) {
      if (vector.profile === 'WEB_UI') {
        expect(runtime.evaluateWebUi(vector.program, vector.record), vector.id).toBe(vector.expected);
        continue;
      }
      expect(
        runtime.evaluateFormCompute(vector.program, vector.record, vector.targetValueType),
        vector.id,
      ).toEqual({
        patch: vector.expected,
        changedFields: ['amount'],
      });
    }
  });

  it('fails closed for unsupported issued programs and non-finite literals', () => {
    const valid = vectors.vectors[0].program;
    expect(runtime.evaluateWebUi({ ...valid, schemaVersion: 2 }, {})).toBe(false);
    expect(runtime.evaluateWebUi({ ...valid, profile: 'SERVER_RULE' as never }, {})).toBe(false);
    expect(
      runtime.evaluateWebUi(
        {
          ...valid,
          root: { kind: 'UNKNOWN' as never, arguments: [] },
        },
        {},
      ),
    ).toBe(false);
    expect(
      runtime.evaluateWebUi(
        {
          ...valid,
          root: {
            kind: 'UNARY',
            operator: '!',
            arguments: [{ kind: 'UNKNOWN' as never, arguments: [] }],
          },
        },
        {},
      ),
    ).toBe(false);
    expect(
      runtime.evaluateWebUi(
        {
          ...valid,
          root: {
            kind: 'BINARY',
            operator: '!=',
            arguments: [
              { kind: 'UNKNOWN' as never, arguments: [] },
              { kind: 'VALUE', value: 'x', arguments: [] },
            ],
          },
        },
        {},
      ),
    ).toBe(false);
    expect(runtime.evaluateWebUi({ ...valid, root: { kind: 'VALUE', value: true, arguments: [] } }, {})).toBe(
      false,
    );
    expect(
      runtime.evaluateWebUi(
        {
          ...valid,
          root: {
            kind: 'BINARY',
            operator: '==',
            arguments: [
              { kind: 'FIELD', field: 'status', operator: '!', arguments: [] },
              { kind: 'VALUE', value: 'active', arguments: [] },
            ],
          },
        },
        { status: 'active' },
      ),
    ).toBe(false);
    expect(
      runtime.evaluateWebUi(
        {
          ...valid,
          root: {
            kind: 'BINARY',
            operator: '==',
            arguments: [
              { kind: 'FIELD', field: 'status', arguments: [] },
              { kind: 'VALUE', value: 'x'.repeat(129), arguments: [] },
            ],
          },
        },
        { status: 'active' },
      ),
    ).toBe(false);
    expect(
      runtime.evaluateWebUi(
        {
          ...valid,
          root: { kind: 'VALUE', value: Number.POSITIVE_INFINITY, arguments: [] },
        },
        {},
      ),
    ).toBe(false);

    const formCompute = vectors.vectors.find((vector) => vector.profile === 'FORM_COMPUTE')!.program;
    expect(
      runtime.evaluateFormCompute(
        { ...formCompute, root: { kind: 'FUNCTION', operator: 'NOW', arguments: [] } },
        { amount: 0 },
        'DECIMAL',
      ),
    ).toEqual({ patch: {}, changedFields: [] });

    const nonFinite = vectors.vectors.find(
      (vector) => vector.id === 'form-compute-non-finite-text-normalizes-number',
    )!;
    expect(
      runtime.evaluateFormCompute(nonFinite.program, { amount: null, source: 'NaN' }, 'DECIMAL'),
    ).toEqual({
      patch: { amount: 1 },
      changedFields: ['amount'],
    });
  });
});
