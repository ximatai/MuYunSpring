import { defineComponent, h, nextTick } from 'vue';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ThemeSkinPreferencesDialog from '@/app/ThemeSkinPreferencesDialog.vue';
import { uiThemeSkins } from '@/vue-ui-antdv/theme';

describe('ThemeSkinPreferencesDialog', () => {
  it('shows every built-in skin and emits the selected stable ID', async () => {
    const wrapper = mount(ThemeSkinPreferencesDialog, {
      props: { open: true, skins: uiThemeSkins, activeSkinId: 'light-blue' },
      global: { stubs: { UiModal: { template: '<div><slot /></div>' } } },
    });

    const cards = wrapper.findAll('[role="radio"]');
    expect(cards).toHaveLength(4);
    expect(cards[0].attributes('aria-checked')).toBe('true');

    await cards[2].trigger('click');
    expect(wrapper.emitted('select')).toEqual([['dark-navy']]);
  });

  it('uses roving tab focus and selects the adjacent skin with an arrow key', async () => {
    const wrapper = mount(ThemeSkinPreferencesDialog, {
      attachTo: document.body,
      props: { open: true, skins: uiThemeSkins, activeSkinId: 'light-blue' },
      global: { stubs: { UiModal: { template: '<div><slot /></div>' } } },
    });
    const cards = wrapper.findAll<HTMLElement>('[role="radio"]');

    expect(cards.map((card) => card.attributes('tabindex'))).toEqual(['0', '-1', '-1', '-1']);
    await cards[0].trigger('keydown', { key: 'ArrowRight' });

    expect(wrapper.emitted('select')).toEqual([['light-amber']]);
    expect(document.activeElement).toBe(cards[1].element);
    wrapper.unmount();
  });
});

it('uses the consumer language for skin labels without changing selection, IDs or theme facts', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const original = JSON.stringify(uiThemeSkins);
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () =>
        h(ThemeSkinPreferencesDialog, { open: true, skins: uiThemeSkins, activeSkinId: 'light-blue' });
    },
  });
  const wrapper = mount(Host, { global: { stubs: { UiModal: { template: '<div><slot /></div>' } } } });
  try {
    expect(wrapper.text()).toContain('Engineering blue');
    expect(wrapper.text()).not.toContain('工程蓝');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.text()).toContain('工程蓝');
    const cards = wrapper.findAll('[role="radio"]');
    expect(cards[0].attributes('aria-checked')).toBe('true');
    await cards[2].trigger('click');
    expect(wrapper.findComponent(ThemeSkinPreferencesDialog).emitted('select')).toEqual([['dark-navy']]);
    expect(JSON.stringify(uiThemeSkins)).toBe(original);
  } finally {
    wrapper.unmount();
  }
});
