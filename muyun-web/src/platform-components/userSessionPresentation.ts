import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import type { UserSessionView } from '@muyun/web-contracts';

/**
 * Stable presentation rules for the IAM online-session capability.
 *
 * Both tenant users and system accounts are backed by the same user-session
 * resource. Keeping these labels here prevents management pages from
 * gradually developing different interpretations of the same session facts.
 */
export function userSessionPresenceTitle(
  session: Pick<UserSessionView, 'presenceStatus' | 'presenceStatusTitle'>,
  language: LanguageContext = platformLanguageContext,
) {
  if (session.presenceStatus === 'online') {
    return language.t('platform.sessions.presence.online', '使用中');
  }
  if (session.presenceStatus === 'idle') {
    return language.t('platform.sessions.presence.idle', '闲置');
  }
  if (session.presenceStatus === 'offline') {
    return language.t('platform.sessions.presence.offline', '离线');
  }
  return session.presenceStatusTitle || language.t('platform.sessions.presence.offline', '离线');
}

export function userSessionBrowserTitle(session: UserSessionView) {
  return session.loginUserAgent || session.loginIp || session.id;
}

const terminalTitles: Readonly<Record<string, string>> = {
  desktopWeb: 'Web 桌面端',
  mobileWeb: 'Web 移动端',
  tabletWeb: 'Web 平板端',
  desktopApp: '桌面客户端',
  mobileApp: '移动客户端',
  other: '其他终端',
};

export function userSessionTerminalTitle(
  session: UserSessionView,
  language: LanguageContext = platformLanguageContext,
) {
  const original = terminalTitles[session.terminalType ?? ''];
  const terminal = original
    ? language.t(`platform.sessions.terminal.${session.terminalType}`, original)
    : session.terminalTypeTitle || language.t('platform.sessions.terminal.other', '其他终端');
  const platform =
    session.platformType === 'other'
      ? language.t('platform.sessions.platform.other', '其他系统')
      : session.platformTypeTitle;
  return platform ? `${terminal} / ${platform}` : terminal;
}

export function userSessionPresenceDescription(
  session: UserSessionView,
  language: LanguageContext = platformLanguageContext,
) {
  return language.t('platform.sessions.presence.description', '{status}，实时连接数 {count}', {
    status: userSessionPresenceTitle(session, language),
    count: session.connectionCount ?? 0,
  });
}
