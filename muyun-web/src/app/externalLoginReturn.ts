import type { LanguageContext } from '@muyun/web-core';
import type { ExternalLoginCompletion, ExternalLoginPurpose, ExternalLoginStart } from '@muyun/web-contracts';

const STORAGE_KEY = 'muyun.externalLogin.pending';

/** A transaction this browser started and may complete when the provider returns it to the workbench. */
export interface PendingExternalLogin {
  providerId: string;
  state: string;
  browserProof: string;
  purpose: ExternalLoginPurpose;
  /** Locked tenant of the sign-in entry, restored on the address after the provider round trip. */
  tenantId?: string;
}

/** A provider return to the workbench, matched against this browser's pending transactions. */
export interface ExternalLoginReturn {
  /** Absent when this browser did not start the transaction; the return must then be ignored. */
  pending?: PendingExternalLogin;
  /** Every provider parameter on the address, forwarded unchanged for server-side verification. */
  parameters: Record<string, string>;
}

/**
 * Keeps the state and proof of a started transaction in session storage. Only this browser tab session can
 * complete the transaction; neither value is ever placed in a URL by the workbench.
 */
export function rememberExternalLogin(
  start: ExternalLoginStart,
  target: { providerId: string; purpose: ExternalLoginPurpose; tenantId?: string },
  storage: Storage | undefined = sessionStorageOrUndefined(),
) {
  const pending = readPending(storage).filter((entry) => entry.state !== start.state);
  pending.push({
    providerId: target.providerId,
    state: start.state,
    browserProof: start.browserProof,
    purpose: target.purpose,
    tenantId: target.tenantId || undefined,
  });
  writePending(storage, pending.slice(-5));
}

/** Sends the browser to the provider authorization page; the page itself is outside the workbench. */
export function navigateToExternalAuthorization(authorizationUrl: string) {
  window.location.assign(authorizationUrl);
}

/**
 * Reads a provider return from the workbench root query and forgets the matching pending transaction.
 * Returns `undefined` when the address is not a provider return.
 */
export function takeExternalLoginReturn(
  query: Record<string, unknown>,
  storage: Storage | undefined = sessionStorageOrUndefined(),
): ExternalLoginReturn | undefined {
  const parameters: Record<string, string> = {};
  for (const [key, value] of Object.entries(query)) {
    const text = Array.isArray(value) ? value[0] : value;
    if (typeof text === 'string' && !key.startsWith('_muyun')) {
      parameters[key] = text;
    }
  }
  const state = parameters.state?.trim();
  if (!state) {
    return undefined;
  }
  const pending = readPending(storage);
  writePending(
    storage,
    pending.filter((entry) => entry.state !== state),
  );
  return { pending: pending.find((entry) => entry.state === state), parameters };
}

/** User-facing explanation of a completion that did not succeed. */
export function externalLoginFailureMessage(completion: ExternalLoginCompletion, language: LanguageContext) {
  if (completion.status === 'expired') {
    return language.t('platform.external-login.authorization-expired', '授权已超时，请重新发起');
  }
  switch (completion.failure) {
    case 'identityNotBound':
      return language.t(
        'platform.external-login.identity-not-bound',
        '该外部账号尚未绑定本系统账号，请先用账号密码登录，在个人信息中绑定后再使用',
      );
    case 'identityConflict':
      return language.t('platform.external-login.identity-conflict', '该外部账号已绑定其他账号');
    case 'alreadyBound':
      return language.t('platform.external-login.already-bound', '当前账号已绑定该身份源');
    case 'enterpriseMismatch':
      return language.t('platform.external-login.enterprise-mismatch', '该外部账号不属于允许的企业');
    case 'providerUnavailable':
      return language.t('platform.external-login.provider-unavailable', '身份源暂时不可用，请稍后重试');
    case 'internalError':
      return language.t('platform.external-login.internal-error', '平台处理失败，请稍后重试');
    default:
      return language.t('platform.external-login.authorization-failed', '外部授权未通过，请重试或联系管理员');
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
    // Storage may be unavailable; the transaction then simply cannot be completed.
  }
}

function isPending(value: unknown): value is PendingExternalLogin {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const entry = value as Record<string, unknown>;
  return (
    typeof entry.providerId === 'string' &&
    typeof entry.state === 'string' &&
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
