import { expect, it, vi } from 'vitest';
import { AppError, createLanguageContext, type ModuleContext } from '@/web-core';
import { useModulePageBootstrap } from '@/dynamic-page-runtime/composables/useModulePageBootstrap';
import type { QueryListRecord } from '@/platform-components';

it('reprojects a failed entry read in the owning language without retrying or leaking server diagnostics', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const cause = new AppError('private database diagnostic', { code: 'INTERNAL_ERROR', status: 500 });
  const request = vi.fn().mockRejectedValue(cause);
  const context = {
    moduleAlias: 'crm.customer',
    http: { request },
  } as unknown as ModuleContext<QueryListRecord>;
  const entry = useModulePageBootstrap(context, () => 'menu-one', language);
  await entry.loadPageBootstrap();
  expect(entry.pageBootstrapError.value).toBe('A system error prevented the operation from completing');
  language.setLanguage('zh-CN');
  expect(entry.pageBootstrapError.value).toBe('系统异常，操作未完成');
  expect(request).toHaveBeenCalledOnce();
  expect(cause.message).toBe('private database diagnostic');
  expect(entry.pageBootstrap.value).toBeUndefined();
});
