import { nextTick, ref } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import { createLanguageContext, resolveOperationErrorMessage, OperationUsageError } from '@muyun/web-core';
import { waitForConfigurationEditor } from '@/views/configurationEditorNavigation';

afterEach(() => vi.useRealTimers());

it('waits for a lazy editor to become visible before resuming the conversation', async () => {
  const visible = ref(false);
  const resumed = vi.fn();
  const pending = waitForConfigurationEditor(() => visible.value).then(resumed);
  await nextTick();
  expect(resumed).not.toHaveBeenCalled();
  visible.value = true;
  await pending;
  expect(resumed).toHaveBeenCalledOnce();
});

it('does not leave a conversation waiting indefinitely for a closed or failed page', async () => {
  vi.useFakeTimers();
  const pending = waitForConfigurationEditor(() => false);
  const rejected = expect(pending).rejects.toThrow('配置页面尚未就绪');
  await vi.advanceTimersByTimeAsync(15_000);
  await rejected;
  const controller = new AbortController();
  const cancelled = waitForConfigurationEditor(() => false, controller.signal);
  controller.abort();
  await expect(cancelled).rejects.toMatchObject({ name: 'AbortError' });
  expect(vi.getTimerCount()).toBe(0);
});

it('retains the owner-language timeout source without changing navigation or abort contracts', async () => {
  vi.useFakeTimers();
  const language = createLanguageContext({ languageTag: 'en' });
  const failure = waitForConfigurationEditor(() => false, undefined, language).catch((error) => error);
  await vi.advanceTimersByTimeAsync(15_000);
  const error = await failure;
  expect(error).toBeInstanceOf(OperationUsageError);
  expect(error.message).toBe('The configuration page is not ready. Reopen it before continuing.');
  language.setLanguage('zh-CN');
  expect(resolveOperationErrorMessage(error, language)).toBe('配置页面尚未就绪，请重新打开后继续。');
});
