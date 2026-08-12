import { describe, expect, it } from 'vitest';
import {
  antDesignThemeOf,
  cssVariablesOf,
  defaultUiTheme,
  defaultUiThemeSkinId,
  uiThemeSkinById,
  uiThemeSkins,
  type UiTheme,
} from '@/vue-ui-antdv/theme';

describe('UiTheme', () => {
  it('exposes semantic CSS variables from one theme input', () => {
    const variables = cssVariablesOf(defaultUiTheme);

    expect(variables['--muyun-theme-base']).toBe('#0052D9');
    expect(variables['--muyun-brand-accent-on-base']).toBe('#171F2A');
    expect(variables['--muyun-brand-accent-base']).toBe('#F5B700');
    expect(variables['--muyun-support-surface']).toBe('#FFFFFF');
    expect(variables['--muyun-support-disabled']).toBe('#EEF1F5');
    expect(variables['--muyun-positive-focus']).toBe('#D3F3E7');
    expect(variables['--muyun-danger-border']).toBe('#F5E5E7');
  });

  it('allows a future palette adjustment without changing semantic consumers', () => {
    const theme: UiTheme = {
      ...defaultUiTheme,
      theme: { ...defaultUiTheme.theme, base: '#5B43D6', hover: '#725FEB' },
      brandAccent: { ...defaultUiTheme.brandAccent, base: '#E2A800' },
    };

    const variables = cssVariablesOf(theme);
    const antTheme = antDesignThemeOf(theme);

    expect(variables['--muyun-theme-base']).toBe('#5B43D6');
    expect(variables['--muyun-theme-hover']).toBe('#725FEB');
    expect(variables['--muyun-brand-accent-base']).toBe('#E2A800');
    expect(antTheme.token?.colorPrimary).toBe('#5B43D6');
    expect(antTheme.token?.colorSuccess).toBe(defaultUiTheme.positive.base);
  });

  it('maps semantic status roles to the Ant Design adapter', () => {
    const tokens = antDesignThemeOf(defaultUiTheme).token;

    expect(tokens?.colorPrimaryHover).toBe(defaultUiTheme.theme.hover);
    expect(tokens?.colorPrimaryActive).toBe(defaultUiTheme.theme.active);
    expect(tokens?.controlOutline).toBe(defaultUiTheme.theme.focus);
    expect(tokens?.colorSuccessBg).toBe(defaultUiTheme.positive.soft);
    expect(tokens?.colorWarningText).toBe(defaultUiTheme.warning.softText);
    expect(tokens?.colorInfo).toBe(defaultUiTheme.info.base);
    expect(tokens?.colorSuccess).toBe(defaultUiTheme.positive.base);
    expect(tokens?.colorWarning).toBe(defaultUiTheme.warning.base);
    expect(tokens?.colorError).toBe(defaultUiTheme.danger.base);
    expect(tokens?.colorBgContainerDisabled).toBe(defaultUiTheme.support.disabled);
    expect(tokens?.colorTextDisabled).toBe(defaultUiTheme.support.disabledText);
    expect(tokens?.controlItemBgActiveDisabled).toBe(defaultUiTheme.support.disabled);
  });

  it('keeps every semantic soft surface readable by normal text', () => {
    uiThemeSkins.forEach((skin) => {
      const tones = [
        skin.theme.theme,
        skin.theme.brandAccent,
        skin.theme.positive,
        skin.theme.warning,
        skin.theme.danger,
        skin.theme.info,
      ];
      tones.forEach((tone) => expect(contrastRatio(tone.soft, tone.softText)).toBeGreaterThanOrEqual(4.5));
    });
  });

  it('keeps every emphasis base readable by its on-base foreground', () => {
    uiThemeSkins.forEach((skin) => {
      const tones = [
        skin.theme.theme,
        skin.theme.brandAccent,
        skin.theme.positive,
        skin.theme.warning,
        skin.theme.danger,
        skin.theme.info,
      ];
      tones.forEach((tone) => expect(contrastRatio(tone.base, tone.onBase)).toBeGreaterThanOrEqual(4.5));
    });
  });

  it('provides four complete built-in skins with a stable fallback', () => {
    expect(uiThemeSkins.map((skin) => skin.id)).toEqual([
      'light-blue',
      'light-amber',
      'dark-navy',
      'dark-graphite',
    ]);
    expect(uiThemeSkinById(defaultUiThemeSkinId).id).toBe(defaultUiThemeSkinId);
    expect(uiThemeSkinById('retired-custom-palette').id).toBe(defaultUiThemeSkinId);
    expect(cssVariablesOf(uiThemeSkinById('light-amber').theme)['--muyun-brand-accent-on-base']).toBe(
      '#FFFFFF',
    );

    uiThemeSkins.forEach((skin) => {
      const variables = cssVariablesOf(skin.theme);
      expect(variables['--muyun-theme-base']).toBeTruthy();
      expect(variables['--muyun-support-surface']).toBeTruthy();
      expect(variables['--muyun-danger-soft-text']).toBeTruthy();
      expect(antDesignThemeOf(skin.theme).algorithm).toBeTruthy();
    });
  });

  it('maps light and dark skins to their matching Ant appearance algorithm', () => {
    expect(antDesignThemeOf(uiThemeSkinById('light-blue').theme).algorithm).toBe(
      antDesignThemeOf(uiThemeSkinById('light-amber').theme).algorithm,
    );
    expect(antDesignThemeOf(uiThemeSkinById('dark-navy').theme).algorithm).toBe(
      antDesignThemeOf(uiThemeSkinById('dark-graphite').theme).algorithm,
    );
    expect(antDesignThemeOf(uiThemeSkinById('light-blue').theme).algorithm).not.toBe(
      antDesignThemeOf(uiThemeSkinById('dark-navy').theme).algorithm,
    );
  });
});

function contrastRatio(first: string, second: string) {
  const luminance = (hex: string) => {
    const channels = hex.match(/[a-f\d]{2}/gi)?.map((value) => Number.parseInt(value, 16) / 255) ?? [];
    const [red, green, blue] = channels.map((channel) =>
      channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
    );
    return red * 0.2126 + green * 0.7152 + blue * 0.0722;
  };
  const [lighter, darker] = [luminance(first), luminance(second)].sort((left, right) => right - left);
  return (lighter + 0.05) / (darker + 0.05);
}
