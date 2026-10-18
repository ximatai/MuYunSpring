import { expect, it, vi } from 'vitest';
import { createLanguageContext } from '@/web-core/language';
import { businessContentLanguageContext } from '@/web-core/businessContentLanguage';
import { createLanguagePreferenceController } from '@/web-core/languagePreference';
import type { UserPreferenceStore } from '@/web-core/userPreferences';

it('shares reading intent within one interface scope and preserves explicit reading language across interface changes', () => {
  const language = createLanguageContext();
  const reading = businessContentLanguageContext(language);
  expect(businessContentLanguageContext(language)).toBe(reading);
  expect(reading.languageTag.value).toBe('zh-CN');
  language.setLanguage('en');
  expect(reading.languageTag.value).toBe('en');
  reading.setLanguage('de');
  language.setLanguage('zh-CN');
  expect(reading.languageTag.value).toBe('de');
  expect(language.languageTag.value).toBe('zh-CN');
  expect(businessContentLanguageContext(createLanguageContext()).languageTag.value).toBe('zh-CN');
  reading.setLanguage();
  expect(reading.languageTag.value).toBe('zh-CN');
});

it('restores and removes a separate account preference without writing the interface preference', async () => {
  const language = createLanguageContext();
  const reading = businessContentLanguageContext(language);
  const set = vi.fn(async () => undefined);
  const remove = vi.fn(async () => undefined);
  const store = {
    get: () => undefined,
    restore: async () => 'de',
    set,
    remove,
  } as unknown as UserPreferenceStore;
  const preferences = createLanguagePreferenceController(reading, store, 'business-content.language');
  await preferences.restore('user-a');
  expect(reading.languageTag.value).toBe('de');
  expect(language.languageTag.value).toBe('zh-CN');
  await preferences.save('en');
  expect(set).toHaveBeenCalledWith('business-content.language', 'en', { persistence: 'backend' });
  await preferences.save();
  expect(remove).toHaveBeenCalledWith('business-content.language', { persistence: 'backend' });
  language.setLanguage('en');
  expect(reading.requestedLanguageTag.value).toBeUndefined();
  expect(reading.languageTag.value).toBe('en');
});

it('does not let a delayed reading preference cross account or override current reading intent', async () => {
  const reading = businessContentLanguageContext(createLanguageContext());
  const callbacks: ((tag: string) => void)[] = [];
  const store = {
    get: () => undefined,
    restore: () => new Promise<string>((done) => callbacks.push(done)),
  } as unknown as UserPreferenceStore;
  const preferences = createLanguagePreferenceController(reading, store, 'business-content.language');
  const first = preferences.restore('a');
  const second = preferences.restore('b');
  reading.setLanguage('de');
  callbacks[0]!('en');
  callbacks[1]!('zh-CN');
  await Promise.all([first, second]);
  expect(reading.languageTag.value).toBe('de');
});

it.each([undefined, 'de'])(
  'retains an explicit repeated reading selection while an account restore is pending: %s',
  async (selection) => {
    const reading = businessContentLanguageContext(createLanguageContext());
    reading.setLanguage(selection);
    let complete!: (tag: string) => void;
    const store = {
      get: () => selection,
      restore: () => new Promise<string>((done) => (complete = done)),
    } as unknown as UserPreferenceStore;
    const pending = createLanguagePreferenceController(reading, store, 'business-content.language').restore(
      'a',
    );
    reading.setLanguage(selection);
    complete('en');
    await pending;
    expect(reading.requestedLanguageTag.value).toBe(selection);
    expect(reading.languageTag.value).toBe(selection ?? 'zh-CN');
  },
);
