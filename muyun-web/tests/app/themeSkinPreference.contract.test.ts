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
    failure: 'save-failed',
  });
  expect(store.set).toHaveBeenNthCalledWith(1, themeSkinPreferenceKey, 'dark-navy', { persistence: 'local' });
  expect(store.set).toHaveBeenNthCalledWith(2, themeSkinPreferenceKey, 'dark-navy', {
    persistence: 'backend',
  });
  expect(store.set).toHaveBeenNthCalledWith(3, themeSkinPreferenceKey, 'light-blue', {
    persistence: 'local',
  });
});

it('does not persist a previous account selection to the new account after a local write yields', async () => {
  const store = preferenceStore();
  let current = true;
  vi.mocked(store.set).mockImplementationOnce(async () => {
    current = false;
  });
  await saveThemeSkinPreference(store, 'dark-navy', 'light-blue', () => current);
  expect(store.set).toHaveBeenCalledTimes(1);
  expect(store.set).toHaveBeenCalledWith(themeSkinPreferenceKey, 'dark-navy', { persistence: 'local' });
});
it('does not roll back a new account preference after an old account save fails', async () => {
  const store = preferenceStore();
  let current = true;
  vi.mocked(store.set)
    .mockResolvedValueOnce(undefined)
    .mockImplementationOnce(async () => {
      current = false;
      throw new Error('old failure');
    });
  await saveThemeSkinPreference(store, 'dark-navy', 'light-blue', () => current);
  expect(store.set).toHaveBeenCalledTimes(2);
});
it('does not mirror a late preference restore into the current account', async () => {
  const store = preferenceStore();
  let current = true;
  vi.mocked(store.restore).mockImplementation(async () => {
    current = false;
    return 'dark-navy';
  });
  await expect(restoreThemeSkinPreference(store, 'light-blue', () => current)).resolves.toBe('light-blue');
  expect(store.set).not.toHaveBeenCalled();
});
