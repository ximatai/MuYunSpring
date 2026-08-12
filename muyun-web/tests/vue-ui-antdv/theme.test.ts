import { describe, expect, it } from 'vitest';
import { antDesignThemeOf, cssVariablesOf, defaultUiTheme, type UiTheme } from '@/vue-ui-antdv/theme';

describe('UiTheme', () => {
  it('exposes semantic CSS variables from one theme input', () => {
    const variables = cssVariablesOf(defaultUiTheme);

    expect(variables['--muyun-theme-base']).toBe('#0052D9');
    expect(variables['--muyun-brand-accent-base']).toBe('#F5B700');
    expect(variables['--muyun-support-surface']).toBe('#FFFFFF');
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
  });

  it('keeps every semantic soft surface readable by normal text', () => {
    const tones = [
      defaultUiTheme.theme,
      defaultUiTheme.brandAccent,
      defaultUiTheme.positive,
      defaultUiTheme.warning,
      defaultUiTheme.danger,
      defaultUiTheme.info,
    ];

    tones.forEach((tone) => expect(contrastRatio(tone.soft, tone.softText)).toBeGreaterThanOrEqual(4.5));
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
