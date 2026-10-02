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
    { fieldName: 'amount', operator: 'GT', values: ['10oops'] },
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

const instantFields: RecordQueryListFilterField[] = [
  {
    name: 'createdAt',
    title: 'Created',
    valueType: 'INSTANT',
    operators: ['EQ', 'BETWEEN', 'NULL'],
    sortable: true,
  },
];

it.each([
  ['EQ', ['2026-09-22T00:00:00Z']],
  ['BETWEEN', ['2026-09-22T00:00:00Z', '2026-09-23T00:00:00Z']],
  ['BETWEEN', ['2026-09-22', '2026-09-23']],
  ['NULL', []],
])('preserves server-supported instant condition %s %j', (operator, values) => {
  const parsed = parseRecordQueryListStandardQuery(
    { conditions: [{ fieldName: 'createdAt', operator, values }], sorts: [] },
    instantFields,
  );
  expect(parsed.conditions[0]?.values).toEqual(values);
});

it.each([
  ['EQ', ['2026-09-22T00:00:00+08:00']],
  ['EQ', ['2026-09-22T00:00:00.123Z']],
  ['EQ', ['2026-02-30T00:00:00Z']],
  ['EQ', ['2026-09-22']],
  ['BETWEEN', ['2026-09-22', '2026-09-23T00:00:00Z']],
  ['BETWEEN', ['2026-02-30', '2026-03-01']],
])('rejects unsupported instant condition before mutation: %s %j', (operator, values) => {
  expect(() =>
    parseRecordQueryListStandardQuery(
      { conditions: [{ fieldName: 'createdAt', operator, values }], sorts: [] },
      instantFields,
    ),
  ).toThrow();
});

it('preserves exact numeric text and rejects overflow before changing the query', () => {
  for (const [valueType, value] of [
    ['LONG', '9007199254740993'],
    ['LONG', '-9223372036854775808'],
    ['DECIMAL', '123456789012345.678901'],
  ] as const) {
    const fields = [
      {
        name: 'value',
        title: 'Value',
        valueType,
        operators: ['EQ'] as import('@muyun/web-contracts').QueryOperator[],
        sortable: true,
      },
    ];
    const query = parseRecordQueryListStandardQuery(
      { conditions: [{ fieldName: 'value', operator: 'EQ', values: [value] }], sorts: [] },
      fields,
    );
    expect(query.conditions[0]?.values).toEqual([value]);
  }
  for (const value of ['9223372036854775808', '-9223372036854775809', '1.5', Number('9007199254740993')])
    expect(() =>
      parseRecordQueryListStandardQuery(
        { conditions: [{ fieldName: 'value', operator: 'EQ', values: [value] }], sorts: [] },
        [{ name: 'value', title: 'Value', valueType: 'LONG', operators: ['EQ'], sortable: true }],
      ),
    ).toThrow();
});
