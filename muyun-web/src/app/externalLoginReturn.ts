import type { LanguageContext } from '@muyun/web-core';
import type { ExternalLoginPurpose, ExternalLoginRedemption, ExternalLoginStart } from '@muyun/web-contracts';

/** Query parameter the platform callback appends when it returns the browser to the workbench. */
export const EXTERNAL_LOGIN_RETURN_PARAMETER = '_muyunExternalLogin';
const STORAGE_KEY = 'muyun.externalLogin.pending';

export interface PendingExternalLogin {
  transactionId: string;
  browserProof: string;
  purpose: ExternalLoginPurpose;
}

/**
 * Keeps the browser proof of a started transaction in session storage, so only this browser tab session can
 * redeem the result after the provider round trip. The proof never leaves storage except in the redeem body.
 */
export function rememberExternalLogin(
  start: ExternalLoginStart,
  purpose: ExternalLoginPurpose,
  storage: Storage | undefined = sessionStorageOrUndefined(),
) {
  const pending = readPending(storage).filter((entry) => entry.transactionId !== start.transactionId);
  pending.push({ transactionId: start.transactionId, browserProof: start.browserProof, purpose });
  writePending(storage, pending.slice(-5));
}

/** Sends the browser to the provider authorization page; the page itself is outside the workbench. */
export function navigateToExternalAuthorization(authorizationUrl: string) {
  window.location.assign(authorizationUrl);
}

/** Returns and forgets the pending transaction the current URL returns for, if this browser started it. */
export function takeReturningExternalLogin(
  search: string = currentSearch(),
  storage: Storage | undefined = sessionStorageOrUndefined(),
): PendingExternalLogin | undefined {
  const transactionId = new URLSearchParams(search).get(EXTERNAL_LOGIN_RETURN_PARAMETER)?.trim();
  if (!transactionId) {
    return undefined;
  }
  const pending = readPending(storage);
  const match = pending.find((entry) => entry.transactionId === transactionId);
  writePending(
    storage,
    pending.filter((entry) => entry.transactionId !== transactionId),
  );
  return match;
}

/** Whether the URL carries a return from the platform callback, matched or not. */
export function hasExternalLoginReturn(search: string = currentSearch()) {
  return Boolean(new URLSearchParams(search).get(EXTERNAL_LOGIN_RETURN_PARAMETER));
}

/** Removes the return parameter while keeping the rest of the address, including a locked tenant. */
export function withoutExternalLoginReturn(href: string) {
  const url = new URL(href);
  url.searchParams.delete(EXTERNAL_LOGIN_RETURN_PARAMETER);
  return url.toString();
}

export function stripExternalLoginReturnFromLocation() {
  if (typeof window === 'undefined' || !hasExternalLoginReturn(window.location.search)) {
    return;
  }
  window.history.replaceState(window.history.state, '', withoutExternalLoginReturn(window.location.href));
}

/** User-facing explanation of a redemption that did not succeed. */
export function externalLoginFailureMessage(redemption: ExternalLoginRedemption, language: LanguageContext) {
  if (redemption.status === 'expired' || redemption.status === 'pending') {
    return language.t('platform.screen.ExternalLogin.authorization-expired', '授权已超时，请重新发起');
  }
  switch (redemption.failureCode) {
    case 'IDENTITY_NOT_BOUND':
      return language.t(
        'platform.screen.ExternalLogin.identity-not-bound',
        '该外部账号尚未绑定本系统账号，请先用账号密码登录，在个人信息中绑定后再使用',
      );
    case 'IDENTITY_CONFLICT':
      return language.t('platform.screen.ExternalLogin.identity-conflict', '该外部账号已绑定其他账号');
    case 'ALREADY_BOUND':
      return language.t('platform.screen.ExternalLogin.already-bound', '当前账号已绑定该身份源');
    case 'ENTERPRISE_MISMATCH':
      return language.t('platform.screen.ExternalLogin.enterprise-mismatch', '该外部账号不属于允许的企业');
    case 'PROVIDER_UNAVAILABLE':
      return language.t('platform.screen.ExternalLogin.provider-unavailable', '身份源暂时不可用，请稍后重试');
    default:
      return language.t(
        'platform.screen.ExternalLogin.authorization-failed',
        '外部授权未通过，请重试或联系管理员',
      );
  }
}

function readPending(storage: Storage | undefined): PendingExternalLogin[] {
  try {
    const parsed: unknown = JSON.parse(storage?.getItem(STORAGE_KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter(isPending) : [];
  } catch {
    return [];
  }
}

function writePending(storage: Storage | undefined, pending: PendingExternalLogin[]) {
  try {
    if (pending.length === 0) {
      storage?.removeItem(STORAGE_KEY);
    } else {
      storage?.setItem(STORAGE_KEY, JSON.stringify(pending));
    }
  } catch {
    // Storage may be unavailable; the transaction then simply cannot be redeemed.
  }
}

function isPending(value: unknown): value is PendingExternalLogin {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const entry = value as Record<string, unknown>;
  return (
    typeof entry.transactionId === 'string' &&
    typeof entry.browserProof === 'string' &&
    (entry.purpose === 'login' || entry.purpose === 'bind')
  );
}

function sessionStorageOrUndefined(): Storage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.sessionStorage;
  } catch {
    return undefined;
  }
}

function currentSearch() {
  return typeof window === 'undefined' ? '' : window.location.search;
}
