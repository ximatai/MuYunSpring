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
});
