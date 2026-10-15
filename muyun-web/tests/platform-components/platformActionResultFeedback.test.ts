import { expect, it, vi } from 'vitest';
import { createLanguageContext } from '@muyun/web-core';
import { showSuccessMessage } from '@muyun/vue-ui-antdv';
import { handlePlatformActionSuccess } from '@/platform-components/platformActionResultFeedback';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  showSuccessMessage: vi.fn(),
}));

it('localizes the owned success fallback while preserving business messages and result reactions', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const refresh = vi.fn();
  const result = {
    reactions: [{ type: 'refresh-list' }],
    changes: [{ type: 'record-updated', moduleAlias: 'iam.role', recordId: 'original-id' }],
  };
  const before = structuredClone(result);
  const parsed = await handlePlatformActionSuccess(result, {
    language,
    reactionHandlers: { 'refresh-list': refresh },
  });
  expect(showSuccessMessage).toHaveBeenLastCalledWith('Operation succeeded');
  expect(parsed.raw).toBe(result);
  expect(parsed.changes).toEqual(result.changes);
  expect(result).toEqual(before);
  expect(refresh).toHaveBeenCalledOnce();
  language.setLanguage('zh-CN');
  await handlePlatformActionSuccess({}, { language });
  expect(showSuccessMessage).toHaveBeenLastCalledWith('操作成功');
  language.setLanguage('en');
  await handlePlatformActionSuccess({ message: '用户填写的业务成功提示' }, { language });
  expect(showSuccessMessage).toHaveBeenLastCalledWith('用户填写的业务成功提示');
});
