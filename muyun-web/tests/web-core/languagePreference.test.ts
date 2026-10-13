import { expect, it, vi } from 'vitest';
import { createLanguageContext } from '@/web-core/language';
import { createLanguagePreferenceController } from '@/web-core/languagePreference';
import type { UserPreferenceStore } from '@/web-core/userPreferences';

it('does not let delayed account restores overwrite a fresh selection or another account', async () => {
  const context = createLanguageContext();
  const pending: ((value: string) => void)[] = [];
  const set = vi.fn(async () => undefined);
  const store = {
    get: () => 'zh-CN',
    restore: () => new Promise<string>((resolve) => pending.push(resolve)),
    set,
  } as unknown as UserPreferenceStore;
  const preferences = createLanguagePreferenceController(context, store);
  const first = preferences.restore('user-a');
  await preferences.save('en');
  pending[0]!('zh-CN');
  await first;
  expect(context.languageTag.value).toBe('en');
  const old = preferences.restore('user-a');
  const next = preferences.restore('user-b');
  pending[1]!('en');
  await old;
  expect(context.languageTag.value).toBe('zh-CN');
  pending[2]!('zh-CN');
  await next;
  expect(set).toHaveBeenCalledWith('interface.language', 'en', { persistence: 'backend' });
  const external = preferences.restore('user-b');
  context.setLanguage('en');
  pending[3]!('zh-CN');
  await external;
  expect(context.languageTag.value).toBe('en');
});

it('serializes saves, rolls back failed persistence and never sends a queued write to a changed account', async () => {
  const context = createLanguageContext();
  let complete!: () => void;
  const set = vi.fn().mockImplementationOnce(() => new Promise<void>((resolve) => (complete = resolve)));
  const store = { get: () => 'zh-CN', restore: async () => 'zh-CN', set } as unknown as UserPreferenceStore;
  const preferences = createLanguagePreferenceController(context, store);
  await preferences.restore('user-a');
  const first = preferences.save('en');
  await Promise.resolve();
  await Promise.resolve();
  const queued = preferences.save('zh-CN');
  await preferences.restore('user-b');
  complete();
  await first;
  await queued;
  expect(set).toHaveBeenCalledTimes(1);
  set.mockRejectedValueOnce(new Error('backend unavailable'));
  await expect(preferences.save('en')).rejects.toThrow('backend unavailable');
  expect(context.languageTag.value).toBe('zh-CN');
  preferences.dispose();
});
