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
