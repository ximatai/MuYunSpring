import { theme as antDesignTheme } from 'ant-design-vue';
import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context';

export interface UiThemeTone {
  base: string;
  onBase: string;
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
  disabled: string;
  disabledText: string;
}

export interface UiTheme {
  appearance: 'light' | 'dark';
  theme: UiThemeTone;
  brandAccent: UiThemeTone;
  support: UiSupportTheme;
  positive: UiThemeTone;
  warning: UiThemeTone;
  danger: UiThemeTone;
  info: UiThemeTone;
}

export interface UiThemeSkin {
  id: UiThemeSkinId;
  title: string;
  description: string;
  theme: UiTheme;
}

export type UiThemeSkinId = 'light-blue' | 'light-amber' | 'dark-navy' | 'dark-graphite';

export const defaultUiTheme: UiTheme = {
  appearance: 'light',
  theme: {
    base: '#0062B0',
    onBase: '#FFFFFF',
    hover: '#2D7FBE',
    active: '#004B87',
    soft: '#E8F2FA',
    softText: '#245B88',
    border: '#CFE3F3',
    focus: '#D4EAF8',
    disabled: '#ABD1EC',
    disabledText: '#3F719B',
  },
  brandAccent: {
    base: '#F5BE2C',
    onBase: '#171F2A',
    hover: '#F8CC58',
    active: '#C99515',
    soft: '#FFF7DF',
    softText: '#765400',
    border: '#F5DEA0',
    focus: '#FFEDBB',
    disabled: '#F7DFA1',
    disabledText: '#A77A2A',
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
    disabled: '#EEF1F5',
    disabledText: '#8A96A6',
  },
  positive: {
    base: '#00A870',
    onBase: '#171F2A',
    hover: '#31C48D',
    active: '#00875A',
    soft: '#EDF9F5',
    softText: '#006B47',
    border: '#E3F3EC',
    focus: '#D3F3E7',
    disabled: '#A7E5CF',
    disabledText: '#2C9B77',
  },
  warning: {
    base: '#ED7B2F',
    onBase: '#171F2A',
    hover: '#F2995F',
    active: '#D35A21',
    soft: '#FEF3E6',
    softText: '#8A3B00',
    border: '#F7E8D9',
    focus: '#F9E0C7',
    disabled: '#F7C797',
    disabledText: '#D98943',
  },
  danger: {
    base: '#E34D59',
    onBase: '#000000',
    hover: '#F36D78',
    active: '#C9353F',
    soft: '#FDECEE',
    softText: '#9D1C2C',
    border: '#F5E5E7',
    focus: '#F9D7D9',
    disabled: '#F8B9BE',
    disabledText: '#CC6974',
  },
  info: {
    base: '#0062B0',
    onBase: '#FFFFFF',
    hover: '#2D7FBE',
    active: '#004B87',
    soft: '#E8F2FA',
    softText: '#004B87',
    border: '#CFE3F3',
    focus: '#D4EAF8',
    disabled: '#ABD1EC',
    disabledText: '#3F719B',
  },
};

const lightAmberTheme: UiTheme = {
  ...defaultUiTheme,
  theme: {
    base: '#9C6500',
    onBase: '#FFFFFF',
    hover: '#B97800',
    active: '#7A4D00',
    soft: '#FFF4D6',
    softText: '#704600',
    border: '#F4D99B',
    focus: '#FFE8AD',
    disabled: '#EDD49D',
    disabledText: '#9D7A37',
  },
  brandAccent: {
    ...defaultUiTheme.brandAccent,
    base: '#0062B0',
    onBase: '#FFFFFF',
    hover: '#2D7FBE',
    active: '#004B87',
    soft: '#E8F2FA',
    softText: '#245B88',
    border: '#CFE3F3',
    focus: '#D4EAF8',
    disabled: '#ABD1EC',
    disabledText: '#3F719B',
  },
  support: {
    ...defaultUiTheme.support,
    canvas: '#FAF7F1',
    surface: '#FFFEFB',
    elevated: '#FFFDF8',
    hover: '#FAF5EC',
    border: '#ECE3D6',
    borderSubtle: '#F3EDE4',
    textBody: '#4D4232',
    textMuted: '#756958',
    icon: '#645846',
    disabled: '#F3EFE8',
    disabledText: '#968B7C',
  },
};

const darkNavyTheme: UiTheme = {
  appearance: 'dark',
  theme: {
    base: '#5B8CFF',
    onBase: '#0F172A',
    hover: '#7BA3FF',
    active: '#3B6FE0',
    soft: '#1C315A',
    softText: '#BFD4FF',
    border: '#335A9E',
    focus: '#28477A',
    disabled: '#29446F',
    disabledText: '#88A6D8',
  },
  brandAccent: {
    base: '#F5C542',
    onBase: '#111827',
    hover: '#FFD76A',
    active: '#D9A300',
    soft: '#493C16',
    softText: '#FFE6A1',
    border: '#735E1D',
    focus: '#5D4B19',
    disabled: '#65501B',
    disabledText: '#CDB46D',
  },
  support: {
    page: '#0F172A',
    canvas: '#111827',
    surface: '#172033',
    elevated: '#1E293B',
    hover: '#263449',
    border: '#334155',
    borderSubtle: '#263449',
    text: '#F8FAFC',
    textBody: '#D8E1EA',
    textMuted: '#A8B6C7',
    icon: '#94A3B8',
    disabled: '#202A3A',
    disabledText: '#748399',
  },
  positive: {
    base: '#42C998',
    onBase: '#0F172A',
    hover: '#6FDCB4',
    active: '#1A9A6F',
    soft: '#103D32',
    softText: '#A4EBCF',
    border: '#236B54',
    focus: '#1B513F',
    disabled: '#245342',
    disabledText: '#7EBAA3',
  },
  warning: {
    base: '#F2A65A',
    onBase: '#0F172A',
    hover: '#F7BD80',
    active: '#CE7831',
    soft: '#4A2E17',
    softText: '#FFD2A2',
    border: '#79502A',
    focus: '#5E3C20',
    disabled: '#674422',
    disabledText: '#D9A574',
  },
  danger: {
    base: '#F06B76',
    onBase: '#0F172A',
    hover: '#F58D95',
    active: '#CF4350',
    soft: '#4A2027',
    softText: '#FFC1C7',
    border: '#793842',
    focus: '#5D2931',
    disabled: '#653039',
    disabledText: '#D69AA0',
  },
  info: {
    base: '#6EA8FF',
    onBase: '#0F172A',
    hover: '#91BEFF',
    active: '#4B82D9',
    soft: '#1B315A',
    softText: '#C4D9FF',
    border: '#365B9E',
    focus: '#28477A',
    disabled: '#29446F',
    disabledText: '#91AED9',
  },
};

const darkGraphiteTheme: UiTheme = {
  ...darkNavyTheme,
  theme: {
    base: '#6EA8FF',
    onBase: '#111315',
    hover: '#91BEFF',
    active: '#4B82D9',
    soft: '#233554',
    softText: '#C9DDFF',
    border: '#426497',
    focus: '#304B72',
    disabled: '#324866',
    disabledText: '#94ADD1',
  },
  brandAccent: {
    ...darkNavyTheme.brandAccent,
    base: '#E8B949',
    onBase: '#111315',
    hover: '#F4CD6C',
    active: '#C9911F',
    soft: '#4B3B19',
    softText: '#FFE5A5',
    border: '#725B25',
    focus: '#5B481F',
    disabled: '#625024',
    disabledText: '#C8AF75',
  },
  support: {
    page: '#111315',
    canvas: '#17191C',
    surface: '#202328',
    elevated: '#292D33',
    hover: '#32373E',
    border: '#3C434C',
    borderSubtle: '#30353B',
    text: '#F4F6F8',
    textBody: '#D5D9DE',
    textMuted: '#AAB2BC',
    icon: '#96A0AA',
    disabled: '#2A2E34',
    disabledText: '#7E8792',
  },
};

export const uiThemeSkins: readonly UiThemeSkin[] = [
  { id: 'light-blue', title: '工程蓝', description: '清晰、稳定的默认亮色工作台', theme: defaultUiTheme },
  {
    id: 'light-amber',
    title: '暖琥珀',
    description: '更温暖的亮色表面与琥珀交互焦点',
    theme: lightAmberTheme,
  },
  {
    id: 'dark-navy',
    title: '深海军蓝',
    description: '低照度下保持工程蓝识别的深色方案',
    theme: darkNavyTheme,
  },
  {
    id: 'dark-graphite',
    title: '石墨深灰',
    description: '高对比、克制的制造控制台深色方案',
    theme: darkGraphiteTheme,
  },
];

export const defaultUiThemeSkinId: UiThemeSkinId = 'light-blue';

export function uiThemeSkinById(value: unknown): UiThemeSkin {
  return uiThemeSkins.find((skin) => skin.id === value) ?? uiThemeSkins[0];
}

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
    algorithm: theme.appearance === 'dark' ? antDesignTheme.darkAlgorithm : antDesignTheme.defaultAlgorithm,
    token: {
      ...antToneTokens('Primary', theme.theme),
      ...antToneTokens('Info', theme.info),
      ...antToneTokens('Success', theme.positive),
      ...antToneTokens('Warning', theme.warning),
      ...antToneTokens('Error', theme.danger),
      colorLink: theme.theme.base,
      colorLinkHover: theme.theme.hover,
      colorLinkActive: theme.theme.active,
      colorBgLayout: theme.support.canvas,
      colorBgContainer: theme.support.surface,
      colorBgContainerDisabled: theme.support.disabled,
      colorBorder: theme.support.border,
      colorText: theme.support.text,
      colorTextSecondary: theme.support.textMuted,
      colorTextDisabled: theme.support.disabledText,
      controlOutline: theme.theme.focus,
      controlItemBgHover: theme.support.hover,
      controlItemBgActive: theme.theme.soft,
      controlItemBgActiveHover: theme.theme.focus,
      controlItemBgActiveDisabled: theme.support.disabled,
      borderRadius: 4,
      fontSize: 13,
      controlHeight: 30,
      controlHeightSM: 22,
      controlHeightLG: 38,
    },
  };
}

function antToneTokens(prefix: 'Primary' | 'Info' | 'Success' | 'Warning' | 'Error', tone: UiThemeTone) {
  return {
    [`color${prefix}`]: tone.base,
    [`color${prefix}Bg`]: tone.soft,
    [`color${prefix}BgHover`]: tone.focus,
    [`color${prefix}Border`]: tone.border,
    [`color${prefix}BorderHover`]: tone.hover,
    [`color${prefix}Hover`]: tone.hover,
    [`color${prefix}Active`]: tone.active,
    [`color${prefix}Text`]: tone.softText,
    [`color${prefix}TextHover`]: tone.hover,
    [`color${prefix}TextActive`]: tone.active,
  };
}
