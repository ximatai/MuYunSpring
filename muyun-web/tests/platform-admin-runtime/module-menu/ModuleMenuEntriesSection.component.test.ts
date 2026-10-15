import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { createLanguageContext, provideLanguageContext } from '@/web-core';
import type { TextPresentation } from '@/web-contracts';
import ModuleMenuEntriesSection from '@/platform-admin-runtime/module-menu/ModuleMenuEntriesSection.vue';

function label(original: string, english: string): TextPresentation {
  return {
    source: {
      sourceKind: 'CONFIG',
      sourceId: `config/menu/${english}/title`,
      sourceVersion: 'v1',
      originalText: original,
    },
    languages: {
      en: { text: english, requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false },
    },
  };
}
it('reprojects full entry paths on language changes without reloading or changing menu facts', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const scheme = {
    id: 'scheme',
    title: '平台超管',
    $texts: { title: label('平台超管', 'Platform administration') },
  };
  const record = {
    id: 'entry',
    title: '字段规格',
    moduleAlias: 'platform.field_spec',
    $texts: { title: label('字段规格', 'Field specifications') },
  };
  const request = vi.fn(async ({ path }: { path: string }) => {
    if (path === '/platform.menu_scheme/query') return { records: [scheme], total: 1 };
    return { records: [{ record, children: [] }] };
  });
  const context = { record: { alias: 'platform.field_spec' }, module: { http: { request } }, refreshKey: 1 };
  const host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(ModuleMenuEntriesSection, { context: context as never });
    },
  });
  const wrapper = mount(host);
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('Platform administration / Field specifications');
    const reads = request.mock.calls.length;
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.text()).toContain('平台超管 / 字段规格');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.text()).toContain('Platform administration / Field specifications');
    expect(request).toHaveBeenCalledTimes(reads);
    expect(record.title).toBe('字段规格');
    expect(scheme.title).toBe('平台超管');
  } finally {
    wrapper.unmount();
  }
});
