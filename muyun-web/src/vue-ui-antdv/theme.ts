import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context';

export interface UiThemeTone {
  base: string;
  hover: string;
  active: string;
  soft: string;
  softText: string;
  border: string;
  focus: string;
  disabled: string;
  disabledText: string;
}

export interface UiSupportTheme {
  page: string;
  canvas: string;
  surface: string;
  elevated: string;
  hover: string;
  border: string;
  borderSubtle: string;
  text: string;
  textBody: string;
  textMuted: string;
  icon: string;
}

export interface UiTheme {
  theme: UiThemeTone;
  brandAccent: UiThemeTone;
  support: UiSupportTheme;
  positive: UiThemeTone;
  warning: UiThemeTone;
  danger: UiThemeTone;
  info: UiThemeTone;
}

export const defaultUiTheme: UiTheme = {
  theme: {
    base: '#0052D9',
    hover: '#266FE8',
    active: '#0034B5',
    soft: '#ECF2FE',
    softText: '#6482C2',
    border: '#E8EEF9',
    focus: '#D4E3FC',
    disabled: '#BBD3FB',
    disabledText: '#4E72C6',
  },
  brandAccent: {
    base: '#F5B700',
    hover: '#F8C639',
    active: '#D99200',
    soft: '#FFF7D9',
    softText: '#9C6500',
    border: '#F7DF9B',
    focus: '#FFE9A6',
    disabled: '#F8DEA0',
    disabledText: '#B98B31',
  },
  support: {
    page: '#FFFFFF',
    canvas: '#F5F7FB',
    surface: '#FFFFFF',
    elevated: '#FBFCFF',
    hover: '#F7F9FC',
    border: '#E6ECF4',
    borderSubtle: '#EEF2F7',
    text: '#171F2A',
    textBody: '#354255',
    textMuted: '#566577',
    icon: '#425266',
  },
  positive: {
    base: '#00A870',
    hover: '#31C48D',
    active: '#00875A',
    soft: '#EDF9F5',
    softText: '#4AAA8B',
    border: '#E3F3EC',
    focus: '#D3F3E7',
    disabled: '#A7E5CF',
    disabledText: '#2C9B77',
  },
  warning: {
    base: '#ED7B2F',
    hover: '#F2995F',
    active: '#D35A21',
    soft: '#FEF3E6',
    softText: '#DE9D63',
    border: '#F7E8D9',
    focus: '#F9E0C7',
    disabled: '#F7C797',
    disabledText: '#D98943',
  },
  danger: {
    base: '#E34D59',
    hover: '#F36D78',
    active: '#C9353F',
    soft: '#FDECEE',
    softText: '#D8848F',
    border: '#F5E5E7',
    focus: '#F9D7D9',
    disabled: '#F8B9BE',
    disabledText: '#CC6974',
  },
  info: {
    base: '#0052D9',
    hover: '#266FE8',
    active: '#0034B5',
    soft: '#ECF2FE',
    softText: '#6482C2',
    border: '#E8EEF9',
    focus: '#D4E3FC',
    disabled: '#BBD3FB',
    disabledText: '#4E72C6',
  },
};

export function cssVariablesOf(theme: UiTheme): Record<`--muyun-${string}`, string> {
  const variables: Record<`--muyun-${string}`, string> = {};
  const roles: Record<string, UiThemeTone | UiSupportTheme> = {
    theme: theme.theme,
    brandAccent: theme.brandAccent,
    support: theme.support,
    positive: theme.positive,
    warning: theme.warning,
    danger: theme.danger,
    info: theme.info,
  };
  for (const [role, tone] of Object.entries(roles)) {
    for (const [name, value] of Object.entries(tone) as Array<[string, string]>) {
      variables[`--muyun-${toKebabCase(role)}-${toKebabCase(name)}`] = value;
    }
  }
  return variables;
}

function toKebabCase(name: string) {
  return name.replace(/[A-Z]/g, (character) => `-${character.toLowerCase()}`);
}

export function antDesignThemeOf(theme: UiTheme): ThemeConfig {
  return {
    token: {
      colorPrimary: theme.theme.base,
      colorInfo: theme.info.base,
      colorSuccess: theme.positive.base,
      colorWarning: theme.warning.base,
      colorError: theme.danger.base,
      colorLink: theme.theme.base,
      colorBgLayout: theme.support.canvas,
      colorBgContainer: theme.support.surface,
      colorBorder: theme.support.border,
      colorText: theme.support.text,
      colorTextSecondary: theme.support.textMuted,
      borderRadius: 4,
    },
  };
}
