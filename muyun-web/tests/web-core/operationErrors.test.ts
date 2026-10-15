import { createHash } from 'node:crypto';
import { expect, it } from 'vitest';
import {
  createLanguageContext,
  codeOperationErrorMessage,
  OperationUsageError,
  AssistantCapabilityUsageError,
  DraftUpdateRejectedError,
  resolveOperationErrorMessage,
} from '@muyun/web-core';

it('retains explicit CODE facts through editor and assistant error adapters while preserving code and snapshot message', () => {
  const chinese = createLanguageContext({ languageTag: 'zh-CN' });
  const english = createLanguageContext({ languageTag: 'en' });
  const argumentsValue = { value1: '业务原值' };
  const text = codeOperationErrorMessage(
    chinese,
    'platform.screen.constructionPlanSession.value-must-be-an-array',
    '{value1} 必须是数组',
    argumentsValue,
  );
  const error = new AssistantCapabilityUsageError(text, 'PRECONDITION_FAILED');
  const wrapped = new DraftUpdateRejectedError(error);
  argumentsValue.value1 = '后续修改';
  expect(error.message).toBe('业务原值 必须是数组');
  expect(wrapped.code).toBe('PRECONDITION_FAILED');
  expect(resolveOperationErrorMessage(wrapped, english)).toBe('业务原值 must be an array');
  expect(resolveOperationErrorMessage(wrapped, chinese)).toBe('业务原值 必须是数组');
  expect(wrapped.textMessage?.source.sourceVersion).toBe(
    'sha256-' + createHash('sha256').update('{value1} 必须是数组', 'utf8').digest('hex'),
  );
  expect(Object.isFrozen(wrapped.textMessage?.source)).toBe(true);
  expect(error).toBeInstanceOf(OperationUsageError);
});
it('preserves legacy and business-authored messages without guessing a text identity', () => {
  const english = createLanguageContext({ languageTag: 'en' });
  const error = new OperationUsageError('用户写入的业务解释');
  expect(resolveOperationErrorMessage(error, english)).toBe('用户写入的业务解释');
  expect(error.textMessage).toBeUndefined();
  expect(error.code).toBe('CAPABILITY_USAGE_INVALID');
});

it('does not borrow CODE resources for configuration or tampered source versions', () => {
  const chinese = createLanguageContext({ languageTag: 'zh-CN' });
  const english = createLanguageContext({ languageTag: 'en' });
  const declared = codeOperationErrorMessage(
    chinese,
    'platform.screen.constructionPlanSession.value-must-be-an-array',
    '{value1} 必须是数组',
    { value1: '用户值' },
  );
  for (const source of [
    { ...declared.source, sourceKind: 'CONFIG' as const },
    { ...declared.source, sourceVersion: 'sha256-' + '0'.repeat(64) },
  ]) {
    const error = new OperationUsageError({ ...declared, source });
    expect(resolveOperationErrorMessage(error, english)).toBe('用户值 必须是数组');
  }
});
