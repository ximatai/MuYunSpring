import { assert, it } from 'vitest';
import {
  createOpenApiCatalogPageDescriptor,
  createOpenApiAuthenticatedFetch,
  createModuleOpenApiPageDescriptor,
  isOpenApiCatalogPath,
  isModuleOpenApiPage,
  loadModuleOpenApi,
  moduleAliasFromOpenApiPath,
  openApiBackendBaseUrl,
} from '@/platform-admin-runtime/moduleOpenApi.ts';

it('recognizes the dedicated OpenAPI viewer route for a platform module alias', () => {
  assert.equal(moduleAliasFromOpenApiPath('/openapi/education.teacher'), 'education.teacher');
  assert.equal(moduleAliasFromOpenApiPath('/openapi/iam.user/'), 'iam.user');
  assert.equal(moduleAliasFromOpenApiPath('/openapi/education.teacher/extra'), undefined);
  assert.equal(moduleAliasFromOpenApiPath('/openapi/Teacher'), undefined);
});

it('creates the OpenAPI catalog as a regular workbench route', () => {
  const descriptor = createOpenApiCatalogPageDescriptor();
  if (descriptor.pageType !== 'platform-route') {
    throw new Error('Expected a platform route descriptor.');
  }
  assert.equal(descriptor.target.route, '/openapi');
  assert.equal(descriptor.tabPolicy.identity, 'by-target');
});

it('recognizes the API catalog route independently from a module document route', () => {
  assert.equal(isOpenApiCatalogPath('/openapi'), true);
  assert.equal(isOpenApiCatalogPath('/openapi/'), true);
  assert.equal(isOpenApiCatalogPath('/openapi/education.teacher'), false);
});

it('creates a stable workbench tab descriptor for a module document', () => {
  const descriptor = createModuleOpenApiPageDescriptor('education.teacher', '教师');

  assert.equal(descriptor.title, '教师.OpenAPI');
  if (!isModuleOpenApiPage(descriptor)) {
    throw new Error('Expected a module OpenAPI descriptor.');
  }
  if (!('route' in descriptor.target)) {
    throw new Error('Expected a route target.');
  }
  assert.equal(descriptor.target.route, '/openapi/education.teacher');
  assert.equal(isModuleOpenApiPage(descriptor), true);
});

it('loads a module document from its canonical backend OpenAPI endpoint', async () => {
  const requests: unknown[] = [];
  const client = {
    request: async <T>(options: unknown) => {
      requests.push(options);
      return { openapi: '3.1.1' } as T;
    },
  };

  const document = await loadModuleOpenApi(client, 'education.teacher');

  assert.deepEqual(requests, [{ path: '/education.teacher/openapi' }]);
  assert.equal(document.openapi, '3.1.1');
});

it('forwards the current session only to trusted OpenAPI request origins', async () => {
  const previousFetch = globalThis.fetch;
  const previousWindow = globalThis.window;
  const requests: Array<{ input: RequestInfo | URL; init?: RequestInit }> = [];
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: {
      location: { origin: 'http://127.0.0.1:5173' },
      localStorage: { getItem: () => 'session-token' },
    },
  });
  globalThis.fetch = async (input, init) => {
    requests.push({ input, init });
    return new Response('{}', { status: 200 });
  };

  try {
    const authenticatedFetch = createOpenApiAuthenticatedFetch();
    await authenticatedFetch('/education.teacher/query');
    await authenticatedFetch('https://untrusted.example/records');

    assert.equal(new Headers(requests[0]?.init?.headers).get('Authorization'), 'Bearer session-token');
    assert.equal(new Headers(requests[1]?.init?.headers).get('Authorization'), null);
  } finally {
    globalThis.fetch = previousFetch;
    Object.defineProperty(globalThis, 'window', { configurable: true, value: previousWindow });
  }
});

it('uses the configured backend origin for interactive OpenAPI calls', () => {
  assert.equal(openApiBackendBaseUrl(), undefined);
});

it('keeps the API directory tab original and identity while delivering all registered language resources', async () => {
  const { createLanguageContext, presentationLabel } = await import('@muyun/web-core');
  const descriptor = createOpenApiCatalogPageDescriptor();
  const language = createLanguageContext({ languageTag: 'en' });
  const target = descriptor.target;
  assert.equal(descriptor.title, '开放模块 API');
  assert.equal(presentationLabel(descriptor.title!, descriptor.titleText, language), 'Module API directory');
  language.setLanguage('zh-CN');
  assert.equal(presentationLabel(descriptor.title!, descriptor.titleText, language), '开放模块 API');
  assert.equal(descriptor.target, target);
});

it('uses owning application resources for an extended locale without changing selection', async () => {
  const { createLanguageContext, presentationLabel } = await import('@muyun/web-core');
  const language = createLanguageContext({ languageTag: 'en' });
  language.setCatalog({
    ...language.catalog.value,
    languages: [
      ...language.catalog.value.languages,
      { tag: 'de', nativeName: 'Deutsch', direction: 'ltr', fallbackTag: 'en' },
    ],
  });
  const key = 'platform.screen.OpenApiCatalogView.module-api-directory';
  language.registerResources('de', { [key]: 'Modul-API-Verzeichnis' });
  const revision = language.revision.value;
  const selectionRevision = language.selectionRevision.value;
  const descriptor = createOpenApiCatalogPageDescriptor(language);
  assert.equal(language.languageTag.value, 'en');
  assert.equal(language.revision.value, revision);
  assert.equal(language.selectionRevision.value, selectionRevision);
  const target = descriptor.target;
  language.setLanguage('de');
  assert.equal(presentationLabel(descriptor.title!, descriptor.titleText, language), language.t(key));
  assert.equal(presentationLabel(descriptor.title!, descriptor.titleText, language), 'Modul-API-Verzeichnis');
  language.setLanguage('en');
  assert.equal(presentationLabel(descriptor.title!, descriptor.titleText, language), 'Module API directory');
  assert.equal(descriptor.target, target);
});
