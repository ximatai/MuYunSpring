import { computed, onMounted, onScopeDispose, shallowRef } from 'vue';
import { AppError, platformErrorCodes } from '@muyun/web-core';

const AUTH_TOKEN_STORAGE_KEY = 'muyun.auth.token';
const AUTH_SESSION_ID_STORAGE_KEY = 'muyun.auth.sessionId';
// Storage remains the credential source; this revision only invalidates reactive readers.
const authTokenRevision = shallowRef(0);

/** Reactive counterpart of effectiveAuthToken, with component-scoped browser observation. */
export function useEffectiveAuthToken(envToken?: string) {
  const token = computed(() => {
    void authTokenRevision.value;
    return effectiveAuthToken(envToken);
  });
  const refresh = () => authTokenRevision.value++;
  const onStorage = (event: StorageEvent) => {
    if (
      event.storageArea === window.localStorage &&
      (event.key === null || event.key === AUTH_TOKEN_STORAGE_KEY)
    )
      refresh();
  };
  let mounted = false;
  onMounted(() => {
    mounted = true;
    window.addEventListener('storage', onStorage);
    window.addEventListener('focus', refresh);
    refresh();
  });
  onScopeDispose(() => {
    if (!mounted) return;
    window.removeEventListener('storage', onStorage);
    window.removeEventListener('focus', refresh);
  });
  return token;
}

export function effectiveAuthToken(envToken?: string) {
  return storedAuthToken() ?? normalizeToken(envToken);
}

export function storedAuthToken() {
  if (typeof window === 'undefined') {
    return undefined;
  }
  return normalizeToken(window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY));
}

export function storedAuthSessionId() {
  if (typeof window === 'undefined') {
    return undefined;
  }
  return normalizeToken(window.localStorage.getItem(AUTH_SESSION_ID_STORAGE_KEY));
}

export function saveAuthToken(token: string) {
  if (typeof window === 'undefined') {
    return;
  }
  window.localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, token);
  authTokenRevision.value++;
}

export function saveAuthSessionId(sessionId?: string | null) {
  if (typeof window === 'undefined') {
    return;
  }
  const normalized = normalizeToken(sessionId);
  if (normalized) {
    window.localStorage.setItem(AUTH_SESSION_ID_STORAGE_KEY, normalized);
    return;
  }
  window.localStorage.removeItem(AUTH_SESSION_ID_STORAGE_KEY);
}

export function clearAuthToken() {
  if (typeof window === 'undefined') {
    return;
  }
  window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(AUTH_SESSION_ID_STORAGE_KEY);
  authTokenRevision.value++;
}

export function isAuthenticationRequiredError(cause: unknown) {
  if (!(cause instanceof AppError)) {
    return false;
  }
  return (
    cause.code === platformErrorCodes.authRequired ||
    cause.code === platformErrorCodes.authExpired ||
    (cause.status === 401 && cause.code === platformErrorCodes.httpError)
  );
}

export function isPasswordChangeRequiredError(cause: unknown) {
  return cause instanceof AppError && cause.code === platformErrorCodes.passwordChangeRequired;
}

function normalizeToken(token?: string | null) {
  const value = token?.trim();
  return value ? value : undefined;
}
