import { expect, it } from 'vitest';
import { createLanguageContext, codeOperationErrorMessage } from '@/web-core';
import { metadataChangeSetIssueMessage } from '@/views/metadataModelChangeSetClient';

it('uses the same declared diagnostic in both languages without changing subject, code or literal arguments', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const original = '字段“{title}”已有 {count} 条业务数据；目标字段规格不在当前规格声明的数据安全转换范围内。';
  const text = codeOperationErrorMessage(
    language,
    'platform.metadata.diagnostic.field-spec-change-with-data',
    original,
    { title: 'Cost {count}', count: 7 },
  );
  const issue = {
    severity: 'ERROR',
    code: 'FIELD_SPEC_CHANGE_WITH_DATA',
    subject: 'amount',
    message: 'original diagnostic',
    textSource: text.source,
    messageArgs: text.args,
  };
  expect(metadataChangeSetIssueMessage(issue, language)).toContain(
    'Cost {count}” already has 7 business records',
  );
  language.setLanguage('zh-CN');
  expect(metadataChangeSetIssueMessage(issue, language)).toContain('Cost {count}”已有 7 条');
  expect(issue.subject).toBe('amount');
  expect(issue.message).toBe('original diagnostic');
});

it('keeps undeclared, external and mismatched-version diagnostics as original text', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const issue = {
    severity: 'ERROR',
    code: 'INVALID_FIELD_DRAFT',
    subject: 'new',
    message: 'external {diagnostic}',
  };
  expect(metadataChangeSetIssueMessage(issue, language)).toBe(issue.message);
  const source = codeOperationErrorMessage(
    language,
    'platform.metadata.diagnostic.stale-field-version',
    '字段版本已变化，请重新载入后预检。',
  ).source;
  expect(
    metadataChangeSetIssueMessage({ ...issue, textSource: { ...source, sourceVersion: 'stale' } }, language),
  ).toBe(issue.message);
  expect(
    metadataChangeSetIssueMessage({ ...issue, textSource: { ...source, sourceKind: 'CONFIG' } }, language),
  ).toBe(issue.message);
});
