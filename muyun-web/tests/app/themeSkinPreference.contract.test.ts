import { expect, it, vi } from 'vitest';
import type { UserPreferenceStore } from '@muyun/web-core';
import {
  restoreThemeSkinPreference,
  saveThemeSkinPreference,
  themeSkinPreferenceKey,
} from '@/app/themeSkinPreference';

function preferenceStore(): UserPreferenceStore {
  return {
    get: vi.fn(),
    restore: vi.fn(),
    set: vi.fn(),
    remove: vi.fn(),
  };
}

it('restores the account skin and mirrors the normalized value locally', async () => {
  const store = preferenceStore();
  vi.mocked(store.restore).mockResolvedValue('dark-navy');

  await expect(restoreThemeSkinPreference(store, 'light-blue')).resolves.toBe('dark-navy');
  expect(store.restore).toHaveBeenCalledWith(themeSkinPreferenceKey, 'light-blue', {
    persistence: 'backend',
  });
  expect(store.set).toHaveBeenCalledWith(themeSkinPreferenceKey, 'dark-navy', { persistence: 'local' });
});

it('rolls back the local skin when account persistence fails', async () => {
  const store = preferenceStore();
  vi.mocked(store.set)
    .mockResolvedValueOnce(undefined)
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce(undefined);

  await expect(saveThemeSkinPreference(store, 'dark-navy', 'light-blue')).resolves.toEqual({
    skinId: 'light-blue',
    error: '皮肤保存失败，已恢复为之前的设置。',
  });
  expect(store.set).toHaveBeenNthCalledWith(1, themeSkinPreferenceKey, 'dark-navy', { persistence: 'local' });
  expect(store.set).toHaveBeenNthCalledWith(2, themeSkinPreferenceKey, 'dark-navy', {
    persistence: 'backend',
  });
  expect(store.set).toHaveBeenNthCalledWith(3, themeSkinPreferenceKey, 'light-blue', {
    persistence: 'local',
  });
});
