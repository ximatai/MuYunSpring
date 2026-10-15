import { expect, it, vi } from 'vitest';
import { createLanguageContext, type HttpClient } from '@muyun/web-core';
import { moduleActivationFeedback, readModuleActivationFeedback } from '@/views/moduleRuntimeActivation';
import type { DynamicRuntimeActivationStatus } from '@muyun/web-contracts';

it('uses explicit language for both direct and headless activation feedback while retaining commit status', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const status = {
    status: 'FAILED',
    desiredRevision: 7,
    installedRevision: 4,
  } as DynamicRuntimeActivationStatus;
  const request = vi.fn(async () => status);
  expect(moduleActivationFeedback(status, language).message).toEqual({
    type: 'WARNING',
    text: 'Module runtime configuration saved. Activation failed.',
  });
  expect(await readModuleActivationFeedback({ request } as HttpClient, 'business.order', language)).toEqual(
    moduleActivationFeedback(status, language),
  );
  language.setLanguage('zh-CN');
  expect(moduleActivationFeedback(status, language).message.text).toBe('模块运行配置已保存，生效失败');
  expect(status).toMatchObject({ status: 'FAILED', desiredRevision: 7, installedRevision: 4 });
  expect(request).toHaveBeenCalledOnce();
});
