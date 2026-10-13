import { expect, it, vi } from 'vitest';
import { createLanguageContext, withLanguageContext } from '@/web-core/language';
import type { StreamingHttpClient, HttpRequestOptions } from '@/web-core/http';

it('uses deployment defaults until explicitly selected and retains unsupported selection intent', () => {
  const context = createLanguageContext();
  const catalog = { ...context.catalog.value, defaultTag: 'en' };
  context.setCatalog(catalog);
  expect(context.languageTag.value).toBe('en');
  context.setLanguage('zh-CN');
  context.setCatalog(catalog);
  expect(context.languageTag.value).toBe('zh-CN');
  context.setLanguage('fr');
  expect(context.languageTag.value).toBe('en');
  context.setCatalog({
    ...catalog,
    languages: [...catalog.languages, { tag: 'fr', nativeName: 'Français', direction: 'ltr' }],
  });
  expect(context.languageTag.value).toBe('fr');
  context.setLanguage();
  expect(context.languageTag.value).toBe('en');
});

it('prefers exact tags before regional matches regardless of catalog order', () => {
  const context = createLanguageContext({
    catalog: {
      defaultTag: 'en',
      languages: [
        { tag: 'en-US', nativeName: 'English (US)', direction: 'ltr' },
        { tag: 'en', nativeName: 'English', direction: 'ltr' },
      ],
    },
  });
  context.setLanguage('en');
  expect(context.languageTag.value).toBe('en');
  context.setLanguage('en-US');
  expect(context.languageTag.value).toBe('en-US');
});

it('resolves resources and diagnostic fallbacks without changing separate app contexts', () => {
  const english = createLanguageContext({ languageTag: 'en-US' });
  const chinese = createLanguageContext();
  expect(english.t('platform.language.label')).toBe('Interface language');
  expect(chinese.t('platform.language.label')).toBe('界面语言');
  chinese.registerResources('zh-CN', { 'test.greeting': '你好 {name}，{count} 项' });
  english.registerResources('zh-CN', { 'test.greeting': '你好 {name}，{count} 项' });
  expect(english.resolve('test.greeting', 'unused', { name: '$1\\name', count: 2 })).toMatchObject({
    text: '你好 $1\\name，2 项',
    resourceTag: 'zh-CN',
    fallback: true,
    missing: false,
  });
  expect(english.resolve('unknown.key', '原文 {count}', { count: 2 })).toMatchObject({
    text: '原文 2',
    missing: true,
  });
  english.setLanguage('invalid_tag');
  expect(english.languageTag.value).toBe('zh-CN');
});

it('extends resource registrations and rejects fallback cycles or public text overrides', () => {
  const context = createLanguageContext();
  context.setCatalog({
    ...context.catalog.value,
    languages: [
      ...context.catalog.value.languages,
      { tag: 'fr', nativeName: 'Français', fallbackTag: 'en', direction: 'ltr' },
    ],
  });
  context.registerResources('fr', { 'test.greeting': 'Bonjour {name}' });
  context.setLanguage('fr-CA');
  expect(context.t('test.greeting', '', { name: 'Ada' })).toBe('Bonjour Ada');
  expect(context.t('platform.language.label')).toBe('Interface language');
  expect(context.fallbackLanguageTags.value).toEqual(['fr', 'en', 'zh-CN']);
  expect(() => context.registerResources('en', { 'platform.language.label': 'Tenant wording' })).toThrow(
    'Conflicting',
  );
  expect(() =>
    createLanguageContext({ resources: { en: { 'platform.language.label': 'Override' } } }),
  ).toThrow('Conflicting');
  expect(() =>
    context.setCatalog({
      defaultTag: 'en',
      languages: [{ tag: 'en', nativeName: 'English', direction: 'ltr', fallbackTag: 'en' }],
    }),
  ).toThrow('cycle');
  expect(context.languageTag.value).toBe('fr');
});

it('supplies the current controlled language on JSON and stream requests without altering other headers', async () => {
  const context = createLanguageContext();
  const request = vi.fn<(options: HttpRequestOptions) => Promise<unknown>>().mockResolvedValue(undefined);
  const stream = vi
    .fn<(options: HttpRequestOptions) => Promise<ReadableStream<Uint8Array>>>()
    .mockResolvedValue(new ReadableStream<Uint8Array>());
  const http = withLanguageContext({ request, stream } as unknown as StreamingHttpClient, context);
  const options: HttpRequestOptions = {
    path: '/records',
    headers: { 'accept-language': 'de', 'X-MuYun-Tenant-Id': 'tenant-a' },
  };
  await http.request(options);
  expect(request.mock.calls[0]?.[0]).toMatchObject({
    headers: { 'Accept-Language': 'zh-CN', 'X-MuYun-Tenant-Id': 'tenant-a' },
  });
  expect(options.headers?.['accept-language']).toBe('de');
  context.setLanguage('en');
  await http.stream({ path: '/stream' });
  expect(stream.mock.calls[0]?.[0]).toMatchObject({ headers: { 'Accept-Language': 'en' } });
});

it('excludes disabled locales from selection while retaining their source fallback resources', () => {
  const context = createLanguageContext();
  context.setCatalog({
    defaultTag: 'en',
    languages: [
      { tag: 'en', nativeName: 'English', direction: 'ltr', fallbackTag: 'zh-CN', enabled: true },
      { tag: 'zh-CN', nativeName: '中文', direction: 'ltr', enabled: false },
    ],
  });
  context.setLanguage('zh-CN');
  expect(context.languageTag.value).toBe('en');
  expect(context.fallbackLanguageTags.value).toEqual(['en', 'zh-CN']);
});

it('does not expand a generic language preference to an arbitrary region', () => {
  const context = createLanguageContext({
    catalog: {
      defaultTag: 'zh-CN',
      languages: [
        { tag: 'en-US', nativeName: 'English (US)', direction: 'ltr' },
        { tag: 'zh-CN', nativeName: '中文', direction: 'ltr' },
      ],
    },
  });
  context.setLanguage('en');
  expect(context.languageTag.value).toBe('zh-CN');
  context.setLanguage('en-US-x-private');
  expect(context.languageTag.value).toBe('en-US');
});
