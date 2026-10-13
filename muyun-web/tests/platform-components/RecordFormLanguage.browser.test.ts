import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { createLanguageContext } from '@muyun/web-core';
import UiLanguageProvider from '@/vue-ui-antdv/components/UiLanguageProvider.vue';
import RecordFormGrid from '@/platform-components/RecordFormGrid.vue';
import RecordFieldLabel from '@/platform-components/RecordFieldLabel.vue';
import UiInput from '@/vue-ui-antdv/components/UiInput.vue';
import UiButton from '@/vue-ui-antdv/components/UiButton.vue';
import '@/styles.css';

it.each([992, 390])(
  'keeps long third-language labels and clickable controls inside a %s-pixel viewport without losing drafts',
  async (width) => {
    await page.viewport(width, 820);
    const language = createLanguageContext();
    language.setCatalog({
      ...language.catalog.value,
      languages: language.catalog.value.languages.map((entry) =>
        entry.tag === 'fr' ? { ...entry, enabled: true } : entry,
      ),
    });
    language.registerResources('zh-CN', {
      'acceptance.field': '实施字段',
      'acceptance.neighbor': '相邻字段',
    });
    language.registerResources('fr', {
      'acceptance.field':
        'Identifiantadministratifinternationaldel’établissementresponsabledeladéclaration et référence du dossier',
      'acceptance.neighbor': 'Nom du responsable de la déclaration',
    });
    language.setLanguage('fr');
    const draft = ref('用户未保存的原文');
    const clicks = ref(0);
    const Form = defineComponent({
      setup() {
        return () =>
          h(UiLanguageProvider, { context: language }, () =>
            h('section', { style: 'width: min(649px, calc(100vw - 32px)); margin: 16px' }, [
              h(RecordFormGrid, {}, () => [
                h('label', { 'data-test-field': 'long' }, [
                  h(RecordFieldLabel, { required: true }, () => language.t('acceptance.field')),
                  h(UiInput, {
                    value: draft.value,
                    'onUpdate:value': (value: string) => (draft.value = value),
                  }),
                ]),
                h('label', { 'data-test-field': 'neighbor' }, [
                  h(RecordFieldLabel, {}, () => language.t('acceptance.neighbor')),
                  h(UiInput, { value: '稳定业务原值' }),
                ]),
              ]),
              h(UiButton, { onClick: () => clicks.value++ }, () => language.t('platform.action.save')),
            ]),
          );
      },
    });
    const wrapper = mount(Form, { attachTo: document.body });
    try {
      await nextTick();
      const input = wrapper.get('input').element;
      const field = wrapper.get('[data-test-field="long"]').element;
      const label = wrapper.get('[data-test-field="long"] .record-field-label').element;
      const box = field.getBoundingClientRect();
      expect(label.getBoundingClientRect().right).toBeLessThanOrEqual(box.right + 1);
      expect(label.scrollWidth).toBeLessThanOrEqual(field.clientWidth + 1);
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width + 1);
      expect(wrapper.find('[aria-label="Obligatoire"]').exists()).toBe(true);
      await page.elementLocator(input).fill('中文草稿保持原值');
      await page.getByRole('button', { name: 'Enregistrer', exact: true }).click();
      expect(clicks.value).toBe(1);
      language.setLanguage('zh-CN');
      await nextTick();
      expect(wrapper.get('input').element).toBe(input);
      expect((input as HTMLInputElement).value).toBe('中文草稿保持原值');
      expect(draft.value).toBe('中文草稿保持原值');
      await page.elementLocator(wrapper.get('button').element).click();
      expect(clicks.value).toBe(2);
    } finally {
      wrapper.unmount();
    }
  },
);
