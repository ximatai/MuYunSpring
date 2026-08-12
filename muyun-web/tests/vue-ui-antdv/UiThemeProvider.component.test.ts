import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import UiThemeProvider from '@/vue-ui-antdv/components/UiThemeProvider.vue';
import { defaultUiTheme } from '@/vue-ui-antdv/theme';

describe('UiThemeProvider', () => {
  it('scopes semantic variables to its own subtree instead of mutating the document root', () => {
    const original = document.documentElement.style.getPropertyValue('--muyun-theme-base');
    const wrapper = mount(UiThemeProvider, {
      props: { theme: { ...defaultUiTheme, theme: { ...defaultUiTheme.theme, base: '#5B43D6' } } },
      slots: { default: '<span class="theme-child">内容</span>' },
    });

    expect(wrapper.get('.ui-theme-provider').attributes('style')).toContain('--muyun-theme-base: #5B43D6');
    expect(document.documentElement.style.getPropertyValue('--muyun-theme-base')).toBe(original);

    wrapper.unmount();
    expect(document.documentElement.style.getPropertyValue('--muyun-theme-base')).toBe(original);
  });

  it('sets global variables for teleport surfaces and restores them after unmounting', () => {
    const name = '--muyun-theme-base';
    const original = document.documentElement.style.getPropertyValue(name);
    const wrapper = mount(UiThemeProvider, {
      props: {
        scope: 'global',
        theme: { ...defaultUiTheme, theme: { ...defaultUiTheme.theme, base: '#5B43D6' } },
      },
    });

    expect(document.documentElement.style.getPropertyValue(name)).toBe('#5B43D6');

    wrapper.unmount();
    expect(document.documentElement.style.getPropertyValue(name)).toBe(original);
  });
});
