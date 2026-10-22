import { expect, it } from 'vitest';
import { createLanguageContext, codeOperationErrorMessage } from '@/web-core';
import type { TextPresentation } from '@/web-contracts';
import { metadataChangeSetIssueMessage } from '@/views/metadataModelChangeSetClient';

it('consumes backend-only diagnostic resources in either language without changing diagnostic facts', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const original = 'Field spec requires existing type: {value1}';
  const key = 'platform.rejection.field-spec.field-spec-requires-existing-type';
  const args = { value1: 'retired_spec' };
  const source = codeOperationErrorMessage(language, key, original, args).source;
  const messageText: TextPresentation = {
    source,
    languages: {
      en: {
        text: 'Field spec requires existing type: retired_spec',
        requestedTag: 'en',
        resourceTag: 'en',
        missing: false,
        fallback: false,
      },
      'zh-CN': {
        text: '字段规格不存在：retired_spec',
        requestedTag: 'zh-CN',
        resourceTag: 'zh-CN',
        missing: false,
        fallback: false,
      },
    },
  };
  const issue = {
    severity: 'ERROR',
    code: 'INVALID_FIELD_DRAFT',
    subject: 'amount',
    message: 'Field spec requires existing type: retired_spec',
    textSource: source,
    messageArgs: args,
    messageText,
  };
  expect(metadataChangeSetIssueMessage(issue, language)).toBe(issue.message);
  language.setLanguage('zh-CN');
  expect(language.resolve(key).missing).toBe(true);
  expect(metadataChangeSetIssueMessage(issue, language)).toBe('字段规格不存在：retired_spec');
  expect(issue.subject).toBe('amount');
  expect(issue.code).toBe('INVALID_FIELD_DRAFT');
  expect(issue.message).toBe('Field spec requires existing type: retired_spec');
  expect(issue.messageArgs).toEqual(args);
});

it('keeps diagnostics without a verified server presentation as original text', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const issue = {
    severity: 'ERROR',
    code: 'INVALID_FIELD_DRAFT',
    subject: 'new',
    message: 'external {diagnostic}',
  };
  expect(metadataChangeSetIssueMessage(issue, language)).toBe(issue.message);
  const source = codeOperationErrorMessage(language, 'platform.form.required', '请填写{label}').source;
  expect(metadataChangeSetIssueMessage({ ...issue, textSource: source }, language)).toBe(issue.message);
  expect(metadataChangeSetIssueMessage({ ...issue, messageText: null }, language)).toBe(issue.message);
});
