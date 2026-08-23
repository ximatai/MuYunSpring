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
});
