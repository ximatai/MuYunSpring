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
) {
  if (session.presenceStatus === 'online') {
    return '使用中';
  }
  if (session.presenceStatus === 'idle') {
    return '闲置';
  }
  if (session.presenceStatus === 'offline') {
    return '离线';
  }
  return session.presenceStatusTitle || '离线';
}

export function userSessionBrowserTitle(session: UserSessionView) {
  return session.loginUserAgent || session.loginIp || session.id;
}

export function userSessionTerminalTitle(session: UserSessionView) {
  const terminal = session.terminalTypeTitle || '其他终端';
  const platform = session.platformTypeTitle;
  return platform ? `${terminal} / ${platform}` : terminal;
}

export function userSessionPresenceDescription(session: UserSessionView) {
  return `${userSessionPresenceTitle(session)}，实时连接数 ${session.connectionCount ?? 0}`;
}
