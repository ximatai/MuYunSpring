import { expect, it, vi } from 'vitest';
import { codeOperationErrorMessage, createLanguageContext, type ModuleContext } from '@muyun/web-core';
import {
  useFlatCrudManagementState,
  type StaticCrudRecord,
} from '@/platform-components/staticCrudManagementState';

vi.mock('@/platform-components/platformErrorFeedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/platform-components/platformErrorFeedback')>()),
  presentPlatformMessage: vi.fn(),
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
