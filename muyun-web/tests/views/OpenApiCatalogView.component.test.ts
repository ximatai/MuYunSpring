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
