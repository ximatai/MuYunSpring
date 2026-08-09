import { assert, it } from 'vitest';
import { AppError, platformErrorCodes } from '@/web-core/index.ts';
import {
  clearAuthToken,
  effectiveAuthToken,
  isAuthenticationRequiredError,
  isPasswordChangeRequiredError,
  saveAuthSessionId,
  saveAuthToken,
  storedAuthSessionId,
} from '@/platform-admin-runtime/authSession.ts';
import { platformMessage } from '@/app/platformMessage.ts';

it('effectiveAuthToken falls back to env token outside browser storage', () => {
  assert.equal(effectiveAuthToken(' env-token '), 'env-token');
});

it('effectiveAuthToken ignores blank env token', () => {
  assert.equal(effectiveAuthToken('   '), undefined);
});

it('authentication recovery message resolves stable codes through the zh-CN dictionary', () => {
  assert.equal(
    platformMessage(platformErrorCodes.authRequired, 'current user context is not available'),
    '登录状态已失效，请重新登录',
  );
  assert.equal(
    platformMessage(platformErrorCodes.authExpired, 'Session expired'),
    '登录会话已过期，请重新登录',
  );
  assert.equal(
    platformMessage(platformErrorCodes.authRequired, 'Session expired', 'en-US'),
    'Session expired',
  );
});

it('auth session storage keeps token and session id together', () => {
  const storage = new Map<string, string>();
  const previousWindow = globalThis.window;
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: {
      localStorage: {
        getItem: (key: string) => storage.get(key) ?? null,
        setItem: (key: string, value: string) => storage.set(key, value),
        removeItem: (key: string) => storage.delete(key),
      },
    },
  });

  try {
    saveAuthToken(' token-1 ');
    saveAuthSessionId(' session-1 ');

    assert.equal(effectiveAuthToken(), 'token-1');
    assert.equal(storedAuthSessionId(), 'session-1');

    clearAuthToken();

    assert.equal(effectiveAuthToken(), undefined);
    assert.equal(storedAuthSessionId(), undefined);
  } finally {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: previousWindow,
    });
  }
});

it('isAuthenticationRequiredError uses backend auth-required code for login recovery', () => {
  assert.equal(
    isAuthenticationRequiredError(
      new AppError('login required', { code: platformErrorCodes.authRequired, status: 401 }),
    ),
    true,
  );
  assert.equal(
    isAuthenticationRequiredError(
      new AppError('password change required', {
        code: platformErrorCodes.passwordChangeRequired,
        status: 403,
      }),
    ),
    false,
  );
  assert.equal(
    isAuthenticationRequiredError(
      new AppError('token expired', { code: platformErrorCodes.authExpired, status: 401 }),
    ),
    true,
  );
  assert.equal(
    isAuthenticationRequiredError(
      new AppError('bad credentials', { code: platformErrorCodes.loginBadCredentials, status: 401 }),
    ),
    false,
  );
  assert.equal(
    isAuthenticationRequiredError(
      new AppError('legacy login required', { code: platformErrorCodes.httpError, status: 401 }),
    ),
    true,
  );
  assert.equal(isAuthenticationRequiredError(new AppError('forbidden', { status: 403 })), false);
  assert.equal(isAuthenticationRequiredError(new AppError('menu scheme missing', { status: 409 })), false);
});

it('isPasswordChangeRequiredError keeps an authenticated session on the change-password path', () => {
  assert.equal(
    isPasswordChangeRequiredError(
      new AppError('password change required', {
        code: platformErrorCodes.passwordChangeRequired,
        status: 403,
      }),
    ),
    true,
  );
  assert.equal(
    isPasswordChangeRequiredError(
      new AppError('login required', { code: platformErrorCodes.authRequired, status: 401 }),
    ),
    false,
  );
});
