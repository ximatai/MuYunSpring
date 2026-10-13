import { AppError, platformErrorCodes, createLanguageContext } from '@/web-core';
import {
  missingReferencePickerDependencies,
  referencePickerReadErrorOf,
  referencePickerErrorMessage,
  unsupportedReferencePickerConfiguration,
} from '@/platform-components/referencePickerReadError';
import { describe, expect, it } from 'vitest';

describe('reference picker read errors', () => {
  it('classifies only explicit AppError status and code facts', () => {
    expect(
      referencePickerReadErrorOf(new AppError('没有查看权限', { status: 403 }), '读取失败'),
    ).toMatchObject({
      kind: 'accessDenied',
      retryable: false,
    });
    expect(
      referencePickerReadErrorOf(
        new AppError('引用配置不存在', { code: platformErrorCodes.configMissing }),
        '读取失败',
      ),
    ).toMatchObject({ kind: 'unsupportedConfiguration', retryable: false });
    expect(referencePickerReadErrorOf(new Error('请求超时'), '读取失败')).toMatchObject({
      kind: 'temporaryFailure',
      retryable: true,
    });
  });

  it('leaves 401 to the request authentication lifecycle', () => {
    expect(
      referencePickerReadErrorOf(new AppError('登录已失效', { status: 401 }), '读取失败'),
    ).toBeUndefined();
  });

  it('creates non-retryable source errors only from declared facts', () => {
    expect(missingReferencePickerDependencies(['classId', 'organizationId'])).toMatchObject({
      kind: 'missingDependencies',
      retryable: false,
      missingDependencyFields: ['classId', 'organizationId'],
    });
    expect(unsupportedReferencePickerConfiguration('当前引用来源不支持范围导航')).toMatchObject({
      kind: 'unsupportedConfiguration',
      retryable: false,
    });
  });
});

it('resolves local error identities live while preserving server and business messages', () => {
  const language = createLanguageContext();
  const missing = missingReferencePickerDependencies(['classId']);
  const fallback = referencePickerReadErrorOf(new Error(''), {
    key: 'platform.reference-picker.load-failed',
    original: '引用候选加载失败',
  })!;
  const business = referencePickerReadErrorOf(new Error('业务原始错误'), {
    key: 'platform.reference-picker.load-failed',
    original: '引用候选加载失败',
  })!;
  expect(referencePickerErrorMessage(missing, language)).toBe('请先补齐引用所需的依赖字段');
  expect(referencePickerErrorMessage(fallback, language)).toBe('引用候选加载失败');
  language.setLanguage('en');
  expect(referencePickerErrorMessage(missing, language)).toBe(
    'Complete the fields required by this reference first',
  );
  expect(referencePickerErrorMessage(fallback, language)).toBe('Reference candidates could not be loaded');
  expect(referencePickerErrorMessage(business, language)).toBe('业务原始错误');
  expect(missing.missingDependencyFields).toEqual(['classId']);
  expect(missing.message).toBe('请先补齐引用所需的依赖字段');
});
