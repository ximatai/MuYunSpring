import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { presentPlatformError } from '@muyun/platform-components';
import { loadModuleOpenApi, type ModuleOpenApiDocument } from '@/platform-admin-runtime/moduleOpenApi';
import ModuleOpenApiView from '@/views/ModuleOpenApiView.vue';

vi.mock('@scalar/api-reference', () => ({
  ApiReference: {
    name: 'ApiReference',
    props: ['configuration'],
    template: '<section class="api-reference-stub" />',
  },
}));

vi.mock('@/platform-admin-runtime/backendHttp', () => ({
  createBackendHttpClient: vi.fn(),
}));

vi.mock('@muyun/platform-components', () => ({
  presentPlatformError: vi.fn((cause: Error) => cause),
}));

vi.mock('@/platform-admin-runtime/moduleOpenApi', () => ({
  createOpenApiAuthenticatedFetch: vi.fn(() => vi.fn()),
  loadModuleOpenApi: vi.fn(async () => ({
    openapi: '3.1.1',
    info: { title: '平台应用', version: '1.0.0' },
    paths: {},
  })),
  openApiBackendBaseUrl: vi.fn(() => 'http://127.0.0.1:8080'),
}));

describe('ModuleOpenApiView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(loadModuleOpenApi).mockResolvedValue({
      openapi: '3.1.1',
      info: { title: '平台应用', version: '1.0.0' },
      paths: {},
    });
  });

  it('uses the current Scalar developer-tools contract without deprecated toolbar configuration', async () => {
    const wrapper = mount(ModuleOpenApiView, { props: { moduleAlias: 'platform.application' } });
    await flushPromises();
    await vi.dynamicImportSettled();

    const configuration = wrapper.findComponent({ name: 'ApiReference' }).props('configuration') as Record<
      string,
      unknown
    >;
    expect(configuration.showDeveloperTools).toBe('never');
    expect(configuration).not.toHaveProperty('showToolbar');
  });

  it('ignores a stale document-load failure after the route changes to a valid module', async () => {
    let rejectFirst!: (cause: Error) => void;
    let resolveSecond!: (document: ModuleOpenApiDocument) => void;
    vi.mocked(loadModuleOpenApi)
      .mockImplementationOnce(
        () =>
          new Promise((_, reject) => {
            rejectFirst = reject;
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveSecond = resolve;
          }),
      );

    const wrapper = mount(ModuleOpenApiView, { props: { moduleAlias: 'stale.module' } });
    await flushPromises();
    await wrapper.setProps({ moduleAlias: 'platform.application' });
    resolveSecond({ openapi: '3.1.1', info: { title: '平台应用', version: '1.0.0' }, paths: {} });
    await flushPromises();
    await vi.dynamicImportSettled();
    rejectFirst(new Error('请求资源不存在'));
    await flushPromises();

    expect(presentPlatformError).not.toHaveBeenCalled();
    expect(wrapper.findComponent({ name: 'ApiReference' }).props('configuration')).toMatchObject({
      title: '平台应用',
    });
  });

  it('does not request an OpenAPI document before a module alias is available', async () => {
    mount(ModuleOpenApiView, { props: { moduleAlias: ' ' } });
    await flushPromises();

    expect(loadModuleOpenApi).not.toHaveBeenCalled();
  });
});
