import { describe, expect, it } from 'vitest';
import { RelationFormComputeCoordinator } from '@/dynamic-page-runtime/relationFormComputeCoordinator';

describe('RelationFormComputeCoordinator', () => {
  it('immediately clears the same field on every other row when its guard is true', () => {
    const rows = [
      { key: 'a', primaryPosition: true },
      { key: 'b', primaryPosition: true },
      { key: 'c', primaryPosition: false },
    ];
    const result = new RelationFormComputeCoordinator([
      {
        code: 'primaryPositionExclusive',
        targetField: 'primaryPosition',
        targetValueType: 'BOOLEAN',
        triggerFields: ['primaryPosition'],
        program: {
          schemaVersion: 1,
          profile: 'FORM_COMPUTE',
          referencedFields: ['primaryPosition'],
          root: {
            kind: 'ASSIGN',
            operator: '=',
            arguments: [
              { kind: 'OTHERS', field: 'positions.primaryPosition', arguments: [] },
              { kind: 'VALUE', value: false, arguments: [] },
              { kind: 'FIELD', field: 'primaryPosition', arguments: [] },
            ],
          },
        },
      },
    ]).applyAfterChange(rows, 'b', (row) => row.key, 'primaryPosition');

    expect(result).toEqual([
      { key: 'a', primaryPosition: false },
      { key: 'b', primaryPosition: true },
      { key: 'c', primaryPosition: false },
    ]);
    expect(rows[0].primaryPosition).toBe(true);
  });

  it('does not clear sibling rows when the changed row turns the field off', () => {
    const rows = [
      { key: 'a', primaryPosition: true },
      { key: 'b', primaryPosition: false },
    ];
    const result = new RelationFormComputeCoordinator([
      {
        code: 'primaryPositionExclusive',
        targetField: 'primaryPosition',
        targetValueType: 'BOOLEAN',
        triggerFields: ['primaryPosition'],
        program: {
          schemaVersion: 1,
          profile: 'FORM_COMPUTE',
          referencedFields: ['primaryPosition'],
          root: {
            kind: 'ASSIGN',
            operator: '=',
            arguments: [
              { kind: 'OTHERS', field: 'positions.primaryPosition', arguments: [] },
              { kind: 'VALUE', value: false, arguments: [] },
              { kind: 'FIELD', field: 'primaryPosition', arguments: [] },
            ],
          },
        },
      },
    ]).applyAfterChange(rows, 'b', (row) => row.key, 'primaryPosition');

    expect(result).toEqual(rows);
  });

  it('normalizes a relation formula result using its compiled target value type', () => {
    const rows = [
      { key: 'a', activate: false, rank: 0 },
      { key: 'b', activate: true, rank: 0 },
    ];
    const result = new RelationFormComputeCoordinator([
      {
        code: 'setOtherRanks',
        targetField: 'rank',
        targetValueType: 'INTEGER',
        triggerFields: ['activate'],
        program: {
          schemaVersion: 1,
          profile: 'FORM_COMPUTE',
          referencedFields: ['activate'],
          root: {
            kind: 'ASSIGN',
            operator: '=',
            arguments: [
              { kind: 'OTHERS', field: 'positions.rank', arguments: [] },
              { kind: 'VALUE', value: 1, arguments: [] },
              { kind: 'FIELD', field: 'activate', arguments: [] },
            ],
          },
        },
      },
    ]).applyAfterChange(rows, 'b', (row) => row.key, 'activate');

    expect(result).toEqual([
      { key: 'a', activate: false, rank: 1 },
      { key: 'b', activate: true, rank: 0 },
    ]);
  });
});
