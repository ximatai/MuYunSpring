import type { CurrentUser } from '@muyun/web-contracts';
import type { HttpClient, LanguageContext } from '@muyun/web-core';
import type { UiDropdownItem, UiIconName } from '@muyun/vue-ui-antdv';

export const workbenchAccountCommands = [
  'languageSettings',
  'themeSkin',
  'changePassword',
  'profile',
  'accessTokens',
  'logout',
] as const;
export type WorkbenchAccountCommand = (typeof workbenchAccountCommands)[number];
export interface WorkbenchAccountContribution {
  /** Business namespace, for example mr.expertSeat. Standard commands cannot be replaced. */
  key: `${string}.${string}`;
  title: string;
  iconName?: UiIconName;
  group?: 'preferences' | 'account';
  order?: number;
  visible?: boolean;
  disabled?: boolean;
  danger?: boolean;
}
export interface WorkbenchAccountMenuOptions {
  hiddenCommands?: readonly WorkbenchAccountCommand[];
  contributions?: readonly WorkbenchAccountContribution[];
}
/** Inject once; platform account features continue to use the host's authenticated transport. */
export interface WorkbenchAccountContext {
  http: HttpClient;
  /** Offline/mock hosts may keep appearance preferences local. */
  preferencePersistence?: 'backend' | 'local';
  token?: string;
  currentUser?: CurrentUser;
}
export type WorkbenchAccountCommandGuard = (command: {
  key: string;
  currentUser?: CurrentUser;
}) => boolean | Promise<boolean>;

export function createWorkbenchAccountMenu(
  language: LanguageContext,
  options: WorkbenchAccountMenuOptions = {},
  accessTokensAvailable = false,
): UiDropdownItem[] {
  const keys = new Set<string>();
  const contributions = [...(options.contributions ?? [])];
  for (const item of contributions) {
    if (!/^[a-z][a-zA-Z0-9_-]*\.[a-zA-Z0-9_.-]+$/.test(item.key) || keys.has(item.key))
      throw new Error(`Invalid or duplicate account menu contribution: ${item.key}`);
    if (item.order != null && !Number.isFinite(item.order))
      throw new Error(`Invalid account menu order: ${item.key}`);
    keys.add(item.key);
  }
  contributions.sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
  const extras = (group: 'preferences' | 'account') =>
    contributions
      .filter((item) => (item.group ?? 'account') === group && item.visible !== false)
      .map(({ key, title, iconName, disabled, danger }) => ({ key, title, iconName, disabled, danger }));
  const standard = (
    key: WorkbenchAccountCommand,
    title: string,
    iconName: UiIconName,
    resource: string,
  ): UiDropdownItem[] =>
    options.hiddenCommands?.includes(key)
      ? []
      : [
          {
            key,
            title: language.t(resource, title),
            iconName,
            ...(key === 'logout' ? { danger: true } : {}),
          },
        ];
  return [
    ...standard('languageSettings', '语言设置', 'language', 'platform.content.language-settings'),
    ...standard('themeSkin', '皮肤切换', 'skin', 'platform.screen.Workbench.appearance'),
    ...extras('preferences'),
    ...standard('changePassword', '修改密码', 'lock', 'platform.screen.Workbench.change-password'),
    ...standard('profile', '个人信息', 'user', 'platform.screen.Workbench.my-profile'),
    ...(accessTokensAvailable
      ? standard('accessTokens', '个人访问密钥', 'lock', 'platform.access-tokens.title')
      : []),
    ...extras('account'),
    ...standard('logout', '退出登录', 'power', 'platform.screen.Workbench.sign-out'),
  ];
}
