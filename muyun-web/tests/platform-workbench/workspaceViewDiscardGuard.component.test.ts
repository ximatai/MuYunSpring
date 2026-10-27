import { afterEach, expect, it, vi } from 'vitest';
import { createLanguageContext } from '@/web-core';
import { confirmDiscardWorkspaceViewState } from '@/platform-workbench/workspaceViewDiscardGuard';
import {
  clearWorkspaceViewUnsavedState,
  registerWorkspaceViewUnsavedState,
} from '@/platform-workbench/workspaceViewUnsavedState';

const feedback = vi.hoisted(() => ({ confirm: vi.fn(), error: vi.fn() }));
vi.mock('@muyun/vue-ui-antdv', () => ({ confirmAction: feedback.confirm, showErrorMessage: feedback.error }));
const key = 'guard-test-page';
afterEach(() => {
  clearWorkspaceViewUnsavedState(key);
  vi.clearAllMocks();
});

it('keeps close protection active as its localized source and dialog text change', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  let busy = false;
  const release = registerWorkspaceViewUnsavedState(
    key,
    'Record details',
    () => true,
    () => busy,
    () => language.t('platform.screen.useModulePageSession.record-details', '记录详情'),
  );
  let settle!: (value: boolean) => void;
  feedback.confirm.mockImplementation(
    () =>
      new Promise<boolean>((resolve) => {
        settle = resolve;
      }),
  );
  const result = confirmDiscardWorkspaceViewState([key], 'close', language);
  const [options, scope] = feedback.confirm.mock.calls[0]!;
  expect(scope).toBe(language);
  expect(options.content).toContain('"Record details" has unsaved changes');
  language.setLanguage('zh-CN');
  expect(options.presentation()).toMatchObject({ title: '关闭标签', okText: '关闭' });
  expect(options.presentation().content).toContain('“记录详情”存在未保存的更改');
  busy = true;
  settle(true);
  expect(await result).toBe(false);
  expect(feedback.error).toHaveBeenLastCalledWith('“记录详情”正在处理操作，请完成后再关闭。');
  release();
  expect(await confirmDiscardWorkspaceViewState([key], 'close', language)).toBe(true);
  expect(feedback.confirm).toHaveBeenCalledOnce();
});

it('uses sign-out wording for draft confirmation and busy-operation feedback', async () => {
  const language = createLanguageContext({ languageTag: 'zh-CN' });
  let busy = false;
  registerWorkspaceViewUnsavedState(
    key,
    '记录详情',
    () => true,
    () => busy,
  );
  feedback.confirm.mockResolvedValue(true);
  expect(await confirmDiscardWorkspaceViewState([key], 'logout', language)).toBe(true);
  const [options] = feedback.confirm.mock.calls[0]!;
  expect(options).toMatchObject({ title: '退出登录', okText: '退出登录' });
  expect(options.content).toContain('退出登录后将丢失');
  language.setLanguage('en');
  expect(options.presentation()).toMatchObject({ title: 'Sign out', okText: 'Sign out' });
  busy = true;
  language.setLanguage('zh-CN');
  expect(await confirmDiscardWorkspaceViewState([key], 'logout', language)).toBe(false);
  expect(feedback.error).toHaveBeenLastCalledWith('“记录详情”正在处理操作，请完成后再退出登录。');
  expect(feedback.confirm).toHaveBeenCalledOnce();
});
