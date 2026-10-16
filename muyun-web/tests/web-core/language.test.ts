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
  context.setLanguage('de');
  expect(context.languageTag.value).toBe('en');
  context.setCatalog({
    ...catalog,
    languages: [...catalog.languages, { tag: 'de', nativeName: 'Deutsch', direction: 'ltr' }],
  });
  expect(context.languageTag.value).toBe('de');
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
      ...context.catalog.value.languages.map((language) =>
        language.tag === 'fr' ? { ...language, enabled: true } : language,
      ),
    ],
  });
  context.registerResources('fr', { 'test.greeting': 'Bonjour {name}' });
  context.setLanguage('fr-CA');
  expect(context.t('test.greeting', '', { name: 'Ada' })).toBe('Bonjour Ada');
  expect(context.t('platform.language.label')).toBe('Langue de l’interface');
  expect(context.resolve('platform.screen.WorkflowAdministrationView.reset-approval')).toMatchObject({
    text: 'Reset business approval',
    resourceTag: 'en',
    fallback: true,
  });
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

it('keeps three concurrent consumer request scopes independent through completion and later selections', async () => {
  const tags = ['zh-CN', 'en', 'fr'];
  const pending: { options: HttpRequestOptions; complete: (value: unknown) => void }[] = [];
  const transport = {
    request: (options: HttpRequestOptions) => new Promise((complete) => pending.push({ options, complete })),
  };
  const contexts = tags.map((tag) => {
    const context = createLanguageContext();
    context.setCatalog({
      ...context.catalog.value,
      languages: context.catalog.value.languages.map((entry) =>
        entry.tag === 'fr' ? { ...entry, enabled: true } : entry,
      ),
    });
    context.setLanguage(tag);
    return context;
  });
  const clients = contexts.map((context) =>
    withLanguageContext(transport as unknown as import('@/web-core/http').HttpClient, context),
  );
  const requests = clients.map((client, index) =>
    client.request({
      path: '/records',
      headers: { 'X-MuYun-Tenant-Id': `tenant-${index}`, Authorization: `Bearer fake-${index}` },
    }),
  );
  contexts[2].setLanguage('en');
  for (let index = pending.length - 1; index >= 0; index--) {
    expect(pending[index].options.headers).toMatchObject({
      'Accept-Language': tags[index],
      'X-MuYun-Tenant-Id': `tenant-${index}`,
      Authorization: `Bearer fake-${index}`,
    });
    pending[index].complete({ original: `用户原文-${index}` });
  }
  expect(await Promise.all(requests)).toEqual(tags.map((_, index) => ({ original: `用户原文-${index}` })));
  expect(contexts.map((context) => context.languageTag.value)).toEqual(['zh-CN', 'en', 'en']);
  const next = clients[2].request({ path: '/next' });
  expect(pending[3].options.headers?.['Accept-Language']).toBe('en');
  pending[3].complete(undefined);
  await next;
});

it('resolves disabled resources without changing selection or its revisions', () => {
  const context = createLanguageContext({ languageTag: 'en' });
  const key = 'test.disabled-label';
  context.registerResources('fr', { [key]: 'Valeur {name}' });
  const revision = context.revision.value;
  const selectionRevision = context.selectionRevision.value;
  expect(context.resolveForLanguage('fr', key, 'unused', { name: 'Ada' })).toMatchObject({
    text: 'Valeur Ada',
    requestedTag: 'fr',
    resourceTag: 'fr',
    fallback: false,
    missing: false,
  });
  expect(
    context.resolveForLanguage('fr', 'platform.screen.WorkflowAdministrationView.reset-approval'),
  ).toMatchObject({
    text: 'Reset business approval',
    resourceTag: 'en',
    fallback: true,
  });
  expect(() => context.resolveForLanguage('de', key)).toThrow('Unregistered fallback language');
  expect(context.languageTag.value).toBe('en');
  expect(context.revision.value).toBe(revision);
  expect(context.selectionRevision.value).toBe(selectionRevision);
});

it('keeps English quantity labels grammatical for zero, one and multiple results', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  for (const count of [0, 1, 2]) {
    expect(language.t('platform.query.applied-filters', '', { count })).toBe(`Filters applied: ${count}`);
    expect(language.t('platform.form.max-length', '', { label: 'Name', count })).toBe(
      `Character limit for Name: ${count}`,
    );
    expect(language.t('platform.workflow-config.target-count', '', { count })).toBe(` (targets: ${count})`);
    expect(language.t('platform.security.logout-countdown', '', { seconds: count })).toBe(
      `Returning to sign-in (seconds remaining: ${count})`,
    );
    expect(language.t('platform.rules.trial-detail-count', '', { count })).toBe(
      `· Details after trial (rows: ${count})`,
    );
    expect(
      language.t('platform.screen.ConstructionPlanCard.value-value-items', '', {
        value1: 'Plan',
        value2: count,
      }),
    ).toBe(`Plan · Items: ${count}`);
  }
});
