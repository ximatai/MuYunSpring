import { describe, expect, it } from 'vitest';
import {
  readonlyReferenceDisplay,
  readonlyReferenceDisplayItems,
} from '@/platform-components/readonlyReferenceDisplay';

describe('readonly reference display', () => {
  it('prefers an explicit ONE projection while retaining unavailable and missing IDs safely', () => {
    const reference = { cardinality: 'ONE' as const };
    expect(readonlyReferenceDisplay(reference, 'user-1', '张三')).toBe('张三');
    expect(
      readonlyReferenceDisplay(reference, 'user-1', {
        id: 'user-1',
        title: '张三',
        alias: 'zhangsan',
        unavailable: true,
      }),
    ).toBe('张三 (zhangsan)（不可用）');
    expect(readonlyReferenceDisplay(reference, 'user-1', { id: 'user-2', title: '李四' })).toBe('user-1');
  });

  it('matches MANY summaries by persisted ID and preserves order, partial failures and cleared values', () => {
    const reference = { cardinality: 'MANY' as const };
    expect(
      readonlyReferenceDisplay(
        reference,
        ['b', 'a', 'missing'],
        [
          { id: 'a', title: '甲' },
          { id: 'b', title: '乙', unavailable: true },
          { id: 'extra', title: '不应展示' },
        ],
      ),
    ).toBe('乙（不可用）、甲、missing');
    expect(readonlyReferenceDisplay(reference, ['a', 'b'], ['甲'])).toBe('a、b');
    expect(readonlyReferenceDisplay(reference, '["b","a"]', ['乙', '甲'])).toBe('乙、甲');
    expect(readonlyReferenceDisplay(reference, [], [{ id: 'a', title: '旧摘要' }])).toBe('');
  });
});

it('retains per-ID browse eligibility without treating unavailable or missing projections as targets', () => {
  expect(
    readonlyReferenceDisplayItems(
      { cardinality: 'MANY' },
      ['b', 'a', 'missing'],
      [
        { id: 'a', title: '甲' },
        { id: 'b', title: '乙', unavailable: true },
      ],
    ),
  ).toEqual([
    { id: 'b', label: '乙（不可用）', browseable: false },
    { id: 'a', label: '甲', browseable: true },
    { id: 'missing', label: 'missing', browseable: false },
  ]);
});

it('allows a delivered title identical to the ID while keeping an ID-only summary neutral', () => {
  expect(
    readonlyReferenceDisplayItems({ cardinality: 'ONE' }, 'A001', { id: 'A001', title: 'A001' }),
  ).toEqual([{ id: 'A001', label: 'A001', browseable: true }]);
  expect(readonlyReferenceDisplayItems({ cardinality: 'ONE' }, 'A001', { id: 'A001' })).toEqual([
    { id: 'A001', label: 'A001', browseable: false },
  ]);
});
