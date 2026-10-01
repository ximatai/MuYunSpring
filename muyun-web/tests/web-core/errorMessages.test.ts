import { expect, it } from 'vitest';
import { AppError, userFacingErrorMessage, resolveGlobalErrorPresentation } from '@/web-core/errors';

it.each([
  ['AI_PROVIDER_AUTHENTICATION_FAILED', 502, '模型连接鉴权失败'],
  ['AI_PROVIDER_RATE_LIMITED', 429, '模型服务限制了本次请求'],
  ['AI_PROVIDER_UNAVAILABLE', 503, '模型服务暂时不可用'],
  ['AI_PROVIDER_REQUEST_REJECTED', 502, '模型服务拒绝了本次请求'],
  ['AI_MODEL_TIMEOUT', 504, '等待模型回复超时'],
  ['AI_MODEL_CONNECTION_FAILED', 502, '模型连接失败'],
  ['AI_MODEL_INCOMPLETE_RESPONSE', 502, '模型回复在完成前断开'],
  ['AI_MODEL_INTERRUPTED', 503, '模型请求已中断'],
  ['AI_MODEL_CALL_FAILED', 502, '模型调用未能完成'],
] as const)(
  'presents the fixed model explanation instead of private diagnostics: %s',
  (code, status, text) => {
    const error = new AppError('private provider body', { code, status });
    expect(userFacingErrorMessage(error)).toContain(text);
    expect(userFacingErrorMessage(error)).not.toMatch(/当前页面|当前需求|保存|待确认/);
    const presentation = resolveGlobalErrorPresentation(error);
    expect(presentation.message).toContain(text);
    expect(presentation.message).not.toContain('private provider body');
    expect(presentation.slot).not.toBe('redirect-login');
  },
);

it('keeps unrecognized server failures masked and describes network failures without guessing their cause', () => {
  expect(
    userFacingErrorMessage(new AppError('private body', { code: 'AI_UNKNOWN_FAILURE', status: 502 })),
  ).toBe('系统异常，操作未完成');
  const network = userFacingErrorMessage(new AppError('Network request failed', { code: 'NETWORK_ERROR' }));
  expect(network).toContain('暂时无法连接服务');
  expect(network).not.toContain('未启动');
});
