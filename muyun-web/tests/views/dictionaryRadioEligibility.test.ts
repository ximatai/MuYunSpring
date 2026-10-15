import { createLanguageContext } from '@muyun/web-core';
import { describe, expect, it } from 'vitest';
import { dictionaryRadioEligibilityIssue } from '@/views/dictionaryRadioEligibility';

describe('dictionaryRadioEligibilityIssue', () => {
  const radio = { optionSourceType: 'dictionary', optionSelectionMode: 'SINGLE' as const, maxOptions: 3 };

  it('accepts a small, flat single-value dictionary', () => {
    expect(
      dictionaryRadioEligibilityIssue({ ...radio, facts: { enabledCandidateCount: 3, hasHierarchy: false } }),
    ).toBeUndefined();
  });

  it.each([
    [{ ...radio, optionSourceType: 'enum' }, '仅支持数据字典字段'],
    [{ ...radio, optionSelectionMode: 'MULTIPLE' as const }, 'radio 仅支持单值字段'],
    [{ ...radio, loadError: '请求失败' }, '无法读取字典候选：请求失败'],
    [{ ...radio }, '正在读取字典候选'],
    [
      { ...radio, facts: { enabledCandidateCount: 1, hasHierarchy: true } },
      '层级字典不能使用 radio，请改用下拉或弹框',
    ],
    [
      { ...radio, facts: { enabledCandidateCount: 4, hasHierarchy: false } },
      '当前有 4 个启用候选，radio 最多支持 3 个',
    ],
  ])('rejects an ineligible presentation: %s', (input, expected) => {
    expect(dictionaryRadioEligibilityIssue(input)).toBe(expected);
  });
});

it('uses the owning language for radio feedback while retaining the candidate facts and legacy error text', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const facts = { enabledCandidateCount: 5, hasHierarchy: false };
  const input = {
    optionSourceType: 'dictionary',
    optionSelectionMode: 'SINGLE' as const,
    maxOptions: 3,
    facts,
  };
  expect(dictionaryRadioEligibilityIssue(input, language)).toBe(
    'There are 5 enabled options; radio supports at most 3',
  );
  language.setLanguage('zh-CN');
  expect(dictionaryRadioEligibilityIssue(input, language)).toBe('当前有 5 个启用候选，radio 最多支持 3 个');
  expect(facts).toEqual({ enabledCandidateCount: 5, hasHierarchy: false });
  expect(
    dictionaryRadioEligibilityIssue(
      { ...input, loadError: '业务失败原文' },
      createLanguageContext({ languageTag: 'en' }),
    ),
  ).toBe('Unable to read dictionary options: 业务失败原文');
});
