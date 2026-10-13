import { expect, it, vi } from 'vitest';
import { createLanguageContext } from '@/web-core/language';
import { createLanguagePreferenceController } from '@/web-core/languagePreference';
import type { UserPreferenceStore } from '@/web-core/userPreferences';

it.each([true, false])(
  'retains extended account preference when catalog arrives first: %s',
  async (catalogFirst) => {
    const context = createLanguageContext();
    let complete!: (value: string) => void;
    const store = {
      get: () => undefined,
      restore: () => new Promise<string>((resolve) => (complete = resolve)),
    } as unknown as UserPreferenceStore;
    const restore = createLanguagePreferenceController(context, store).restore('user-a');
    const catalog = {
      defaultTag: 'en',
      languages: [
        ...context.catalog.value.languages,
        { tag: 'fr', nativeName: 'Français', direction: 'ltr' as const },
      ],
    };
    if (catalogFirst) context.setCatalog(catalog);
    complete('fr');
    await restore;
    if (!catalogFirst) context.setCatalog(catalog);
    expect(context.languageTag.value).toBe('fr');
  },
);

it('does not turn absent preference into an explicit initial default', async () => {
  const context = createLanguageContext();
  const store = { get: () => undefined, restore: async () => undefined } as unknown as UserPreferenceStore;
  await createLanguagePreferenceController(context, store).restore();
  context.setCatalog({ ...context.catalog.value, defaultTag: 'en' });
  expect(context.languageTag.value).toBe('en');
});

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

it('ignores stale restore errors after a direct user selection', async () => {
  const context = createLanguageContext();
  let fail!: (reason: unknown) => void;
  const store = {
    get: () => undefined,
    restore: () =>
      new Promise<string>((resolve, reject) => {
        void resolve;
        fail = reject;
      }),
  } as unknown as UserPreferenceStore;
  const pending = createLanguagePreferenceController(context, store).restore('user-a');
  context.setLanguage('en');
  fail(new Error('stale failure'));
  await expect(pending).resolves.toBeUndefined();
  expect(context.languageTag.value).toBe('en');
});

it.each([true, false])(
  'failed saves preserve default intent and respect direct subsequent selections: %s',
  async (directSelection) => {
    const context = createLanguageContext();
    let fail!: (reason: unknown) => void;
    const store = {
      set: () =>
        new Promise<void>((resolve, reject) => {
          void resolve;
          fail = reject;
        }),
    } as unknown as UserPreferenceStore;
    const pending = createLanguagePreferenceController(context, store).save('en');
    await Promise.resolve();
    await Promise.resolve();
    if (directSelection) context.setLanguage('en-US');
    fail(new Error('persistence failed'));
    if (directSelection) {
      await expect(pending).resolves.toBeUndefined();
      expect(context.requestedLanguageTag.value).toBe('en-US');
    } else {
      await expect(pending).rejects.toThrow('persistence failed');
      expect(context.requestedLanguageTag.value).toBeUndefined();
      context.setCatalog({ ...context.catalog.value, defaultTag: 'en' });
      expect(context.languageTag.value).toBe('en');
    }
  },
);
