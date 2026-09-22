import { describe, expect, it } from 'vitest';
import { parseRecordQueryListStandardQuery } from '@/platform-components/recordQueryListStandardQuery';
import type { RecordQueryListFilterField } from '@/platform-components/recordQueryListQueryController';
const fields: RecordQueryListFilterField[] = [
  {
    name: 'amount',
    title: '金额',
    valueType: 'DECIMAL',
    operators: ['GT', 'BETWEEN', 'NULL'],
    sortable: true,
  },
  {
    name: 'status',
    title: '状态',
    valueType: 'STRING',
    operators: ['EQ', 'IN'],
    sortable: false,
    options: ['OPEN', 'CLOSED'],
  },
  { name: 'date', title: '日期', valueType: 'DATE', operators: ['EQ'], sortable: true },
];
describe('standard query validation', () => {
  it('normalizes declared conditions and sorts without coercing values', () => {
    expect(
      parseRecordQueryListStandardQuery(
        {
          conditions: [{ fieldName: 'amount', operator: 'BETWEEN', values: [10, 20] }],
          sorts: [{ field: 'amount', desc: true }],
        },
        fields,
      ),
    ).toEqual({
      conditions: [{ kind: 'CONDITION', fieldName: 'amount', operator: 'BETWEEN', values: [10, 20] }],
      sorts: [{ field: 'amount', desc: true }],
    });
  });
  it.each([
    { fieldName: 'secret', operator: 'EQ', values: ['value'] },
    { fieldName: 'amount', operator: 'EQ', values: [10] },
    { fieldName: 'amount', operator: 'GT', values: ['10'] },
    { fieldName: 'amount', operator: 'BETWEEN', values: [10] },
    { fieldName: 'amount', operator: 'NULL', values: [10] },
    { fieldName: 'status', operator: 'EQ', values: ['UNKNOWN'] },
    { fieldName: 'date', operator: 'EQ', values: ['2026-02-30'] },
  ])('rejects invalid condition %j', (condition) => {
    expect(() => parseRecordQueryListStandardQuery({ conditions: [condition], sorts: [] }, fields)).toThrow();
  });
  it('rejects undeclared and repeated sorts', () => {
    for (const sorts of [
      [{ field: 'status', desc: false }],
      [
        { field: 'amount', desc: false },
        { field: 'amount', desc: true },
      ],
    ])
      expect(() => parseRecordQueryListStandardQuery({ conditions: [], sorts }, fields)).toThrow();
  });
});
