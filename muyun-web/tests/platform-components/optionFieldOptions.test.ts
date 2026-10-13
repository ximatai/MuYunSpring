import { expect, it } from 'vitest';
import { createLanguageContext } from '@muyun/web-core';
import type { OptionItemDescriptor, TextPresentation } from '@muyun/web-contracts';
import { optionItemsToOptions, optionItemsToTree } from '@/platform-components/optionFieldOptions';
import { resolveRecordDetailDisplayValue } from '@/platform-components/recordDetailFieldModel';
import type { RecordFormFieldState } from '@/platform-components/recordFormFieldModel';
import { resolveRecordQueryListDisplayValue } from '@/platform-components/recordQueryListColumnModel';

it('uses each consumer language for option labels and saved title companions while preserving raw codes and titles', () => {
  const titleText: TextPresentation = {
    source: {
      sourceKind: 'CONFIG',
      sourceId: 'dictionary/item',
      sourceVersion: 'v1',
      originalText: '已参加',
    },
    languages: {
      en: { text: 'Attended', requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false },
      'zh-CN': { text: '已参加', requestedTag: 'zh-CN', missing: true, fallback: true },
    },
  };
  const items: OptionItemDescriptor[] = [
    { code: 'ATTENDED', title: '已参加', enabled: false, parentCode: 'ROOT', titleText },
  ];
  const english = createLanguageContext({ languageTag: 'en' });
  const chinese = createLanguageContext({ languageTag: 'zh-CN' });
  expect(optionItemsToOptions(items, english)).toEqual([
    { value: 'ATTENDED', label: 'Attended', disabled: true },
  ]);
  expect(optionItemsToTree(items, chinese)[0]?.title).toBe('已参加');
  const record = { attendance: 'ATTENDED', attendanceTitle: '已参加', opinion: '张三的原文意见' };
  const field = {
    fieldName: 'attendance',
    controlType: 'select',
    hasOption: true,
    optionTitleField: 'attendanceTitle',
  } as RecordFormFieldState;
  expect(resolveRecordDetailDisplayValue(field, record, { optionItems: items, language: english })).toBe(
    'Attended',
  );
  expect(
    resolveRecordQueryListDisplayValue(
      record,
      { key: 'attendance', title: 'Status', titleField: 'attendanceTitle', optionItems: items },
      {},
      { language: english },
    ),
  ).toBe('Attended');
  expect(resolveRecordDetailDisplayValue(field, record, { optionItems: items, language: chinese })).toBe(
    '已参加',
  );
  english.setLanguage('zh-CN');
  expect(optionItemsToOptions(items, english)[0]?.label).toBe('已参加');
  expect(record).toEqual({ attendance: 'ATTENDED', attendanceTitle: '已参加', opinion: '张三的原文意见' });
  expect(items[0]?.title).toBe('已参加');
});
