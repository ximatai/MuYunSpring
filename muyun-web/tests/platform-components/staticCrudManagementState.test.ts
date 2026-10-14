import { expect, it, vi } from 'vitest';
import {
  codeOperationErrorMessage,
  createLanguageContext,
  platformLanguageContext,
  type ModuleContext,
} from '@muyun/web-core';
import { presentPlatformError } from '@/platform-components/platformErrorFeedback';
import {
  useFlatCrudManagementState,
  type StaticCrudRecord,
} from '@/platform-components/staticCrudManagementState';

vi.mock('@/platform-components/platformErrorFeedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/platform-components/platformErrorFeedback')>()),
  presentPlatformMessage: vi.fn(),
  presentPlatformError: vi.fn(),
}));

it('resolves retained declared validation errors without validating or writing again', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const isValid = vi.fn(() => false);
  const crud = vi.fn();
  const state = useFlatCrudManagementState({
    language,
    context: { can: () => true, abilities: { crud } } as unknown as ModuleContext<StaticCrudRecord>,
    confirmAction: vi.fn(async () => true),
    emptyDraft: () => ({ title: '' }),
    normalizeDraft: (draft) => ({ ...draft }),
    titleOf: (record) => record.title ?? '',
    fallbackTitle: '业务原名',
    createTitle: '业务创建标题',
    recordName: '业务实体',
    deleteTitle: '业务删除标题',
    requiredMessage: codeOperationErrorMessage(
      language,
      'platform.screen.ModuleActionManagementView.the-action-code-is-required',
      '动作编码不能为空',
    ),
    saveDeniedMessage: '业务权限提示',
    createDeniedMessage: '业务权限提示',
    enableDeniedMessage: '业务权限提示',
    deleteDeniedMessage: () => '业务权限提示',
    isValid,
  });
  state.startCreate();
  state.draft.value.title = '未保存业务输入';
  expect(await state.save()).toBe(false);
  expect(state.actionError.value).toBe('The action code is required.');
  const draft = state.draft.value;
  language.setLanguage('zh-CN');
  expect(state.actionError.value).toBe('动作编码不能为空');
  expect(state.draft.value).toBe(draft);
  expect(state.draft.value.title).toBe('未保存业务输入');
  expect(state.mode.value).toBe('create');
  expect(isValid).toHaveBeenCalledOnce();
  expect(crud).not.toHaveBeenCalled();
  language.setLanguage('en');
  expect(state.actionError.value).toBe('The action code is required.');
});

it.each([true, false])(
  'uses the owner language for missing-version feedback (enabled=%s)',
  async (enabled) => {
    const previousLanguage = platformLanguageContext.languageTag.value;
    platformLanguageContext.setLanguage('zh-CN');
    const language = createLanguageContext({ languageTag: 'en' });
    const enable = { enable: vi.fn(), disable: vi.fn() };
    const crud = { view: vi.fn() };
    const state = useFlatCrudManagementState<StaticCrudRecord>({
      language,
      context: {
        can: () => true,
        runtime: { ready: Promise.resolve({}) },
        abilities: { crud: () => crud, enable: () => enable },
      } as unknown as ModuleContext<StaticCrudRecord>,
      confirmAction: vi.fn(async () => true),
      emptyDraft: () => ({}),
      normalizeDraft: (draft) => draft,
      titleOf: (record) => record.title ?? '',
      fallbackTitle: '业务原名',
      createTitle: '业务原名',
      recordName: '业务原名',
      deleteTitle: '业务原名',
      requiredMessage: '业务校验',
      isValid: () => true,
      saveDeniedMessage: '业务权限',
      createDeniedMessage: '业务权限',
      enableDeniedMessage: '业务权限',
      deleteDeniedMessage: () => '业务权限',
    });
    try {
      state.selected.value = { id: 'record-1', title: '业务原名', enabled };
      const record = state.selected.value;
      await state.toggleEnabled();
      const expected = 'The record version is required. Reload before continuing.';
      expect(state.actionError.value).toBe(expected);
      expect(vi.mocked(presentPlatformError).mock.lastCall?.[0]).toMatchObject({ message: expected });
      expect(vi.mocked(presentPlatformError).mock.lastCall?.[1]?.language).toBe(language);
      language.setLanguage('zh-CN');
      expect(state.actionError.value).toBe('记录版本不能为空，请重新加载后再操作');
      expect(state.selected.value).toBe(record);
      expect(enable.enable).not.toHaveBeenCalled();
      expect(enable.disable).not.toHaveBeenCalled();
      expect(crud.view).not.toHaveBeenCalled();
    } finally {
      platformLanguageContext.setLanguage(previousLanguage);
    }
  },
);
