import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { expect, it } from 'vitest';
import { ConfigProvider } from 'ant-design-vue';
import { createLanguageContext } from '@/web-core/language';
import UiLanguageProvider from '@/vue-ui-antdv/components/UiLanguageProvider.vue';

it('switches control locale and document language while retaining an unsaved child form', async () => {
  const context = createLanguageContext();
  const Form = defineComponent({
    setup() {
      const draft = ref('原文草稿');
      return () =>
        h('input', {
          value: draft.value,
          onInput: (event: Event) => (draft.value = (event.target as HTMLInputElement).value),
        });
    },
  });
  const original = document.documentElement.lang;
  const wrapper = mount(UiLanguageProvider, {
    props: { context, scope: 'global' },
    slots: { default: () => h(Form) },
  });
  await wrapper.get('input').setValue('用户未保存的内容');
  context.setLanguage('en');
  await nextTick();
  expect(wrapper.findComponent(ConfigProvider).props('locale')?.locale).toBe('en');
  expect(document.documentElement.lang).toBe('en');
  expect((wrapper.get('input').element as HTMLInputElement).value).toBe('用户未保存的内容');
  wrapper.unmount();
  expect(document.documentElement.lang).toBe(original);
});

it('restores the remaining document scope when a newer global provider is removed', () => {
  const original = document.documentElement.lang;
  const first = mount(UiLanguageProvider, { props: { context: createLanguageContext(), scope: 'global' } });
  const second = mount(UiLanguageProvider, {
    props: { context: createLanguageContext({ languageTag: 'en' }), scope: 'global' },
  });
  expect(document.documentElement.lang).toBe('en');
  second.unmount();
  expect(document.documentElement.lang).toBe('zh-CN');
  first.unmount();
  expect(document.documentElement.lang).toBe(original);
});

it('applies the selected locale to a real control through the nested theme provider', async () => {
  const { Select } = await import('ant-design-vue');
  const { default: UiThemeProvider } = await import('@/vue-ui-antdv/components/UiThemeProvider.vue');
  const context = createLanguageContext();
  const wrapper = mount(UiLanguageProvider, {
    props: { context },
    slots: { default: () => h(UiThemeProvider, {}, () => h(Select, { open: true, options: [] })) },
    attachTo: document.body,
  });
  context.setLanguage('en');
  await nextTick();
  expect(document.querySelector('.ant-select-dropdown')?.textContent).toContain('No data');
  wrapper.unmount();
});

it('activates the deployed third locale for real controls and retains English fallback diagnostics', async () => {
  const { Select } = await import('ant-design-vue');
  const context = createLanguageContext();
  context.setCatalog({
    ...context.catalog.value,
    languages: context.catalog.value.languages.map((language) =>
      language.tag === 'fr' ? { ...language, enabled: true } : language,
    ),
  });
  context.setLanguage('fr-CA');
  const wrapper = mount(UiLanguageProvider, {
    props: { context },
    slots: { default: () => h(Select, { open: true, options: [] }) },
    attachTo: document.body,
  });
  try {
    await nextTick();
    expect(wrapper.findComponent(ConfigProvider).props('locale')?.locale).toBe('fr');
    expect(document.querySelector('.ant-select-dropdown')?.textContent).toContain('Aucune donnée');
    expect(context.t('platform.language.label')).toBe('Langue de l’interface');
    expect(context.resolve('platform.screen.WorkflowAdministrationView.reset-approval')).toMatchObject({
      resourceTag: 'en',
      fallback: true,
    });
  } finally {
    wrapper.unmount();
  }
});
