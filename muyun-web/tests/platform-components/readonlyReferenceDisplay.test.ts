import { createLanguageContext } from '@/web-core';
import { describe, expect, it } from 'vitest';
import {
  readonlyReferenceDisplay,
  readonlyReferenceDisplayItems,
  readonlyReferenceNameProjection,
  readonlyReferenceSummaryOf,
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

it('uses one identity-aware reference projection for localized display and name-only assistant reads', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const reference = { cardinality: 'MANY' as const };
  expect(readonlyReferenceDisplay(reference, ['a', 'b'], ['Alice', 'Bob'], language)).toBe('Alice, Bob');
  expect(readonlyReferenceNameProjection(reference, ['a', 'b'], [], language)).toEqual({ selected: true });
  expect(readonlyReferenceNameProjection(reference, '[]', undefined, language)).toEqual({ selected: false });
  expect(
    readonlyReferenceNameProjection(reference, ['a', 'private'], [{ id: 'a', title: 'Alice' }], language),
  ).toEqual({ selected: true, display: 'Alice, Selected (name unavailable)' });
  expect(
    readonlyReferenceNameProjection({ cardinality: 'ONE' }, 'A001', { id: 'A001', title: 'A001' }, language),
  ).toEqual({ selected: true, display: 'A001' });
  expect(
    readonlyReferenceNameProjection(
      { cardinality: 'ONE' },
      'A001',
      { id: 'another', title: 'wrong' },
      language,
    ),
  ).toEqual({ selected: true });
});

it('projects delivered summary names while preserving order, identity and unavailability', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const summary = {
    id: 'text',
    title: '文本',
    alias: 'text',
    unavailable: true,
    $texts: {
      title: {
        source: {
          sourceKind: 'CONFIG',
          sourceId: 'config/record/platform.field_ui_control/text/title',
          sourceVersion: 'v1',
          originalText: '文本',
        },
        languages: {
          en: { text: 'Text', requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false },
        },
      },
    },
  };
  expect(
    readonlyReferenceDisplayItems({ cardinality: 'MANY' }, ['text', 'missing'], [summary], language),
  ).toEqual([
    { id: 'text', label: 'Text (text) (unavailable)', browseable: false },
    { id: 'missing', label: 'missing', browseable: false },
  ]);
  expect(readonlyReferenceNameProjection({ cardinality: 'ONE' }, 'text', summary, language).display).toBe(
    'Text (text) (unavailable)',
  );
  language.setLanguage('zh-CN');
  expect(readonlyReferenceDisplay({ cardinality: 'ONE' }, 'text', summary, language)).toBe(
    '文本 (text)（不可用）',
  );
  language.setLanguage('en');
  expect(readonlyReferenceDisplay({ cardinality: 'ONE' }, 'other', summary, language)).toBe('other');
  expect(
    readonlyReferenceDisplay({ cardinality: 'ONE' }, 'text', { ...summary, title: '人工改名' }, language),
  ).toBe('人工改名 (text) (unavailable)');
  expect(summary.title).toBe('文本');
});

it('projects scalar companion resources without changing raw identity or summaries', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const record = {
    role: 'role-1',
    roleTitle: '租户管理员',
    $texts: {
      roleTitle: {
        source: {
          sourceKind: 'CONFIG',
          sourceId: 'role/title',
          sourceVersion: 'v1',
          originalText: '租户管理员',
        },
        languages: {
          en: {
            text: 'Tenant administrator',
            requestedTag: 'en',
            resourceTag: 'en',
            missing: false,
            fallback: false,
          },
        },
      },
    },
  };
  const before = JSON.stringify(record);
  for (const [tag, expected] of [
    ['en', 'Tenant administrator'],
    ['zh-CN', '租户管理员'],
    ['en', 'Tenant administrator'],
  ] as const) {
    language.setLanguage(tag);
    const summary = readonlyReferenceSummaryOf(record, 'roleTitle', language);
    expect(readonlyReferenceDisplayItems({ cardinality: 'ONE' }, record.role, summary, language)).toEqual([
      { id: 'role-1', label: expected, browseable: true },
    ]);
    expect(
      readonlyReferenceNameProjection({ cardinality: 'ONE' }, record.role, summary, language).display,
    ).toBe(expected);
  }
  expect(readonlyReferenceSummaryOf({ ...record, roleTitle: '人工改名' }, 'roleTitle', language)).toBe(
    '人工改名',
  );
  const structured = { id: 'role-1', title: '租户管理员' };
  expect(readonlyReferenceSummaryOf({ roleTitle: structured }, 'roleTitle', language)).toBe(structured);
  expect(readonlyReferenceSummaryOf(record, undefined, language)).toBeUndefined();
  expect(JSON.stringify(record)).toBe(before);
});
