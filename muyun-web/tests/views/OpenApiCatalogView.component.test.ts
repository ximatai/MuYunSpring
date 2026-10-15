import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { createLanguageContext, provideLanguageContext, AppError } from '@muyun/web-core';
import { loadOpenApiCatalog } from '@/platform-admin-runtime/moduleOpenApi';
import OpenApiCatalogView from '@/views/OpenApiCatalogView.vue';

vi.mock('@/platform-admin-runtime/moduleOpenApi', () => ({ loadOpenApiCatalog: vi.fn() }));

it('uses scoped API directory labels and safe retained errors without requesting again on language changes', async () => {
  vi.mocked(loadOpenApiCatalog).mockRejectedValueOnce(
    new AppError('私有诊断栈', { code: 'INTERNAL_ERROR', status: 500 }),
  );
  const language = createLanguageContext({ languageTag: 'en' });
  const Harness = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(OpenApiCatalogView);
    },
  });
  const wrapper = mount(Harness);
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('Module API directory');
    expect(wrapper.text()).toContain('A system error prevented the operation from completing');
    expect(wrapper.text()).not.toContain('私有诊断栈');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.text()).toContain('开放模块 API');
    expect(wrapper.text()).toContain('系统异常，操作未完成');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.text()).toContain('Module API directory');
    expect(loadOpenApiCatalog).toHaveBeenCalledOnce();
  } finally {
    wrapper.unmount();
  }
});

it('projects module catalog title resources without translating literals or refetching on a language switch', async () => {
  vi.mocked(loadOpenApiCatalog).mockClear();
  const titleText = {
    source: {
      sourceKind: 'CONFIG' as const,
      sourceId: 'config/module/orders/title',
      sourceVersion: 'v1',
      originalText: '订单模块',
    },
    languages: {
      en: { text: 'Orders', requestedTag: 'en', missing: false, fallback: false },
      'zh-CN': { text: '订单模块', requestedTag: 'zh-CN', missing: false, fallback: false },
    },
  };
  const modules = [
    {
      moduleAlias: 'orders',
      title: 'Orders',
      titleText,
      moduleKind: 'dynamic' as const,
      documentPath: '/orders/openapi',
    },
    {
      moduleAlias: 'literal',
      title: '订单模块',
      moduleKind: 'static' as const,
      documentPath: '/literal/openapi',
    },
  ];
  vi.mocked(loadOpenApiCatalog).mockResolvedValueOnce(modules);
  const language = createLanguageContext({ languageTag: 'en' });
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(OpenApiCatalogView);
      },
    }),
  );
  try {
    await flushPromises();
    expect(wrapper.findAll('strong').map((item) => item.text())).toEqual(['Orders', '订单模块']);
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.findAll('strong').map((item) => item.text())).toEqual(['订单模块', '订单模块']);
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.findAll('strong')[0]!.text()).toBe('Orders');
    expect(loadOpenApiCatalog).toHaveBeenCalledOnce();
    expect(modules[0]!.title).toBe('Orders');
  } finally {
    wrapper.unmount();
  }
});
