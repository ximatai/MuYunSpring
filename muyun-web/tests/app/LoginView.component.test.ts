import { defineComponent, h } from 'vue';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import LoginView from '@/app/LoginView.vue';
import { navigateToExternalAuthorization } from '@/app/externalLoginReturn';

vi.mock('@/app/externalLoginReturn', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/app/externalLoginReturn')>()),
  navigateToExternalAuthorization: vi.fn(),
}));
import type { AuthClient, ExternalLoginClient, LoginContextClient } from '@/web-core/clients';
import { AppError, platformErrorCodes } from '@/web-core/errors';
import type { ExternalLoginMethod, TenantBranding } from '@/web-contracts';

describe('LoginView', () => {
  it('does not round a one-minute lock up to 61 seconds at a fractional monotonic timestamp', async () => {
    const clock = vi.spyOn(performance, 'now').mockReturnValue(7407.40734);
    const wrapper = mount(LoginView, {
      props: {
        authClient: authClient({
          login: vi.fn().mockRejectedValue(
            new AppError('locked', {
              code: platformErrorCodes.loginBadCredentials,
              details: {
                loginFailure: { serverTime: '2026-11-11T00:00:00Z', lockedUntil: '2026-11-11T00:01:00Z' },
              },
            }),
          ),
        }),
      },
    });
    try {
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      expect(wrapper.text()).toContain('1:00 后可重试');
      expect(wrapper.text()).not.toContain('1:01 后可重试');
    } finally {
      wrapper.unmount();
      clock.mockRestore();
    }
  });

  it('does not restart the countdown when a pending login fails after unmount', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'performance'] });
    let reject!: (cause: unknown) => void;
    const client = authClient({
      login: vi.fn(
        () =>
          new Promise<import('@/web-contracts').LoginResult>((_resolve, rejectLogin) => {
            reject = rejectLogin;
          }),
      ),
    });
    const wrapper = mount(LoginView, { props: { authClient: client } });
    try {
      await wrapper.get('form').trigger('submit');
      wrapper.unmount();
      reject(
        new AppError('locked', {
          code: platformErrorCodes.loginBadCredentials,
          details: {
            loginFailure: {
              serverTime: '2026-11-11T00:00:00Z',
              lockedUntil: '2026-11-11T00:01:00Z',
            },
          },
        }),
      );
      await flushPromises();
      expect(vi.getTimerCount()).toBe(0);
      expect(client.login).toHaveBeenCalledTimes(1);
    } finally {
      if (wrapper.exists()) wrapper.unmount();
      vi.useRealTimers();
    }
  });

  it('restores the ordinary panel when the received deadline is already expired', async () => {
    const client = authClient({
      login: vi.fn().mockRejectedValue(
        new AppError('locked', {
          code: platformErrorCodes.loginBadCredentials,
          details: {
            loginFailure: {
              serverTime: '2026-11-11T00:01:00Z',
              lockedUntil: '2026-11-11T00:01:00Z',
            },
          },
        }),
      ),
    });
    const wrapper = mount(LoginView, { props: { authClient: client } });
    try {
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      expect(wrapper.find('.login-error').exists()).toBe(false);
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined();
      expect(client.login).toHaveBeenCalledTimes(1);
    } finally {
      wrapper.unmount();
    }
  });

  it.each([false, true])(
    'clears a server lock at expiry without retrying (triggered=%s)',
    async (lockTriggered) => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'performance'] });
      const client = authClient({
        login: vi.fn().mockRejectedValue(
          new AppError('private reason', {
            code: platformErrorCodes.loginBadCredentials,
            status: 401,
            details: {
              loginFailure: {
                serverTime: '2026-11-11T00:00:00Z',
                lockedUntil: '2026-11-11T00:01:01Z',
                lockTriggered,
              },
            },
          }),
        ),
      });
      const wrapper = mount(LoginView, { props: { authClient: client } });
      try {
        await wrapper.get('form').trigger('submit');
        await flushPromises();
        expect(wrapper.text()).toContain(lockTriggered ? '连续登录失败，账号已临时锁定' : '账号暂时锁定');
        expect(wrapper.text()).toContain('1:01 后可重试');
        expect(wrapper.text()).toContain('可重试时间：');
        expect(wrapper.text()).not.toContain('用户名或密码错误');
        expect(wrapper.text()).not.toContain('private reason');
        expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined();
        await wrapper.get('form').trigger('submit');
        expect(client.login).toHaveBeenCalledTimes(1);
        await vi.advanceTimersByTimeAsync(1000);
        expect(wrapper.text()).toContain('1:00 后可重试');
        await vi.advanceTimersByTimeAsync(60000);
        expect(wrapper.find('.login-error').exists()).toBe(false);
        expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined();
        expect(client.login).toHaveBeenCalledTimes(1);
        expect(vi.getTimerCount()).toBe(0);
      } finally {
        wrapper.unmount();
        vi.useRealTimers();
      }
    },
  );

  it('shows server attempt guidance and resets lock feedback when switching accounts', async () => {
    const client = authClient({
      login: vi
        .fn()
        .mockRejectedValueOnce(
          new AppError('bad credentials', {
            code: platformErrorCodes.loginBadCredentials,
            details: { loginFailure: { remainingAttempts: 2 } },
          }),
        )
        .mockRejectedValueOnce(
          new AppError('locked', {
            code: platformErrorCodes.loginBadCredentials,
            details: {
              loginFailure: { serverTime: '2026-11-11T00:00:00Z', lockedUntil: '2026-11-11T00:01:00Z' },
            },
          }),
        ),
    });
    const wrapper = mount(LoginView, { props: { authClient: client } });
    try {
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      expect(wrapper.text()).toContain('还可尝试 2 次，之后将临时锁定');
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined();
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      expect(wrapper.text()).toContain('账号暂时锁定');
      await wrapper.get('input[autocomplete="username"]').setValue('another-account');
      expect(wrapper.find('.login-error').exists()).toBe(false);
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined();
    } finally {
      wrapper.unmount();
    }
  });

  it.each([
    { lockedUntil: 'invalid', serverTime: '2026-11-11T00:00:00Z' },
    { remainingAttempts: 0 },
    { remainingAttempts: '2' },
    { remainingAttempts: 1.5 },
  ])('uses ordinary failure guidance for malformed facts %j', async (loginFailure) => {
    const client = authClient({
      login: vi.fn().mockRejectedValue(
        new AppError('private', {
          code: platformErrorCodes.loginBadCredentials,
          details: { loginFailure },
        }),
      ),
    });
    const wrapper = mount(LoginView, { props: { authClient: client } });
    try {
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      expect(wrapper.text()).toContain('用户名或密码错误，请检查后重试');
      expect(wrapper.text()).not.toContain('锁定');
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined();
    } finally {
      wrapper.unmount();
    }
  });

  it('ignores a delayed lock response after the account has changed', async () => {
    let reject!: (cause: unknown) => void;
    const client = authClient({
      login: vi.fn(
        () =>
          new Promise<import('@/web-contracts').LoginResult>((_resolve, rejectLogin) => {
            reject = rejectLogin;
          }),
      ),
    });
    const wrapper = mount(LoginView, { props: { authClient: client } });
    try {
      await wrapper.get('form').trigger('submit');
      await wrapper.get('input[autocomplete="username"]').setValue('another-account');
      reject(
        new AppError('locked', {
          code: platformErrorCodes.loginBadCredentials,
          details: {
            loginFailure: { serverTime: '2026-11-11T00:00:00Z', lockedUntil: '2026-11-11T00:01:00Z' },
          },
        }),
      );
      await flushPromises();
      expect(wrapper.find('.login-error').exists()).toBe(false);
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined();
    } finally {
      wrapper.unmount();
    }
  });

  it('reveals and hides the password without clearing it or submitting the login form', async () => {
    const client = authClient({ login: vi.fn().mockResolvedValue({ token: 'test-token' }) });
    const wrapper = mount(LoginView, { props: { authClient: client, initialUsername: 'xcmg' } });
    try {
      const password = wrapper.get('input[autocomplete="current-password"]');
      await password.setValue('test-password');
      expect(password.attributes('type')).toBe('password');
      await wrapper.get('button[aria-label="显示密码"]').trigger('click');
      expect(password.attributes('type')).toBe('text');
      expect((password.element as HTMLInputElement).value).toBe('test-password');
      expect(client.login).not.toHaveBeenCalled();
      await wrapper.get('button[aria-label="隐藏密码"]').trigger('click');
      expect(password.attributes('type')).toBe('password');
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      expect(client.login).toHaveBeenCalledExactlyOnceWith({
        tenantId: '',
        username: 'xcmg',
        password: 'test-password',
      });
      expect(wrapper.emitted('authenticated')).toEqual([[{ token: 'test-token' }]]);
    } finally {
      wrapper.unmount();
    }
  });

  it('starts the password change masked and toggles each field independently without submitting', async () => {
    const client = authClient({
      login: vi
        .fn()
        .mockResolvedValueOnce({ token: 'pending-token', passwordChangeRequired: true })
        .mockResolvedValueOnce({ token: 'authenticated-token' }),
      changeOwnPassword: vi.fn().mockResolvedValue(undefined),
    });
    const wrapper = mount(LoginView, { props: { authClient: client, initialUsername: 'xcmg' } });
    try {
      await wrapper.get('input[autocomplete="current-password"]').setValue('old-password');
      await wrapper.get('button[aria-label="显示密码"]').trigger('click');
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      const fields = wrapper.findAll('input[autocomplete="new-password"]');
      expect(fields).toHaveLength(2);
      expect(fields.map((field) => field.attributes('type'))).toEqual(['password', 'password']);
      await fields[0]!.setValue('new-password');
      await fields[1]!.setValue('new-password');
      await wrapper.findAll('button[aria-label="显示密码"]')[0]!.trigger('click');
      expect(fields.map((field) => field.attributes('type'))).toEqual(['text', 'password']);
      await wrapper.get('button[aria-label="显示密码"]').trigger('click');
      expect(fields.map((field) => field.attributes('type'))).toEqual(['text', 'text']);
      await wrapper.findAll('button[aria-label="隐藏密码"]')[0]!.trigger('click');
      expect(fields.map((field) => field.attributes('type'))).toEqual(['password', 'text']);
      expect(client.changeOwnPassword).not.toHaveBeenCalled();
      expect(client.login).toHaveBeenCalledTimes(1);
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      expect(client.changeOwnPassword).toHaveBeenCalledExactlyOnceWith(
        { currentPassword: 'old-password', newPassword: 'new-password' },
        'pending-token',
      );
      expect(client.login).toHaveBeenLastCalledWith({
        tenantId: '',
        username: 'xcmg',
        password: 'new-password',
      });
      expect(wrapper.emitted('authenticated')).toEqual([[{ token: 'authenticated-token' }]]);
    } finally {
      wrapper.unmount();
    }
  });

  afterEach(() => {
    window.history.replaceState({}, '', '/');
    window.sessionStorage.clear();
  });

  it('presents a QR transaction locally and discards its browser proof on close', async () => {
    vi.mocked(navigateToExternalAuthorization).mockClear();
    const start = {
      transactionId: 'qr-tx',
      state: 'qr-state',
      browserProof: 'private-proof',
      authorizationUrl: 'https://login.welink.huaweicloud.com/sso/oauth2/sns_authorize',
      expiresAt: new Date(Date.now() + 180000).toISOString(),
      qrCode: {
        providerType: 'welink',
        scriptUrl: 'official',
        authorizationUrl: 'official',
        clientId: 'client',
        redirectUri: 'callback',
        state: 'qr-state',
        scope: 'snsapi_login',
        messageOrigin: 'official',
      },
    };
    window.history.replaceState({}, '', '/?tenant=tenant-a');
    const client = externalLoginClient({ startLogin: vi.fn().mockResolvedValue(start) });
    const wrapper = mount(LoginView, {
      props: {
        authClient: authClient(),
        loginContextClient: loginContextClient(undefined, [
          { providerId: 'welink', title: 'WeLink', providerType: 'welink' },
        ]),
        externalLoginClient: client,
      },
      global: {
        stubs: {
          WeLinkQrCode: {
            props: ['start'],
            emits: ['close'],
            template: '<button class="qr-close" @click="$emit(\'close\')">Close QR</button>',
          },
        },
      },
    });
    await flushPromises();
    const button = wrapper
      .findAll('button')
      .find((candidate) => candidate.text().includes('使用 WeLink 登录'));
    await button!.trigger('click');
    await flushPromises();
    expect(wrapper.find('.qr-close').exists()).toBe(true);
    expect(navigateToExternalAuthorization).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem('muyun.externalLogin.pending')).toContain('private-proof');
    await wrapper.get('.qr-close').trigger('click');
    expect(window.sessionStorage.getItem('muyun.externalLogin.pending')).toBeNull();
    wrapper.unmount();
  });

  it('offers the tenant external sign-in methods and keeps the browser proof out of the address', async () => {
    window.history.replaceState({}, '', '/?tenant=tenant-a');
    const external = externalLoginClient();
    const wrapper = mount(LoginView, {
      props: {
        authClient: authClient(),
        loginContextClient: loginContextClient(undefined, [
          { providerId: 'welink', title: 'WeLink', providerType: 'welink' },
        ]),
        externalLoginClient: external,
      },
    });
    await flushPromises();

    const button = wrapper
      .findAll('button')
      .find((candidate) => candidate.text().includes('使用 WeLink 登录'));
    expect(button).toBeDefined();
    await button!.trigger('click');
    await flushPromises();

    expect(external.startLogin).toHaveBeenCalledWith('welink', 'tenant-a');
    expect(navigateToExternalAuthorization).toHaveBeenCalledWith('https://idp.test/authorize?state=s');
    expect(JSON.parse(window.sessionStorage.getItem('muyun.externalLogin.pending') ?? '[]')).toEqual([
      { providerId: 'welink', state: 's', browserProof: 'proof-1', purpose: 'login', tenantId: 'tenant-a' },
    ]);
  });

  it('switches labels and an existing failure while preserving credentials and the login contract', async () => {
    const language = createLanguageContext({ languageTag: 'en' });
    const client = authClient({
      login: vi
        .fn()
        .mockRejectedValue(
          new AppError('原始错误', { code: platformErrorCodes.loginBadCredentials, status: 401 }),
        ),
    });
    const Host = defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(LoginView, { authClient: client });
      },
    });
    const wrapper = mount(Host);
    try {
      await flushPromises();
      language.setLanguage('en');
      await flushPromises();
      expect(wrapper.text()).toContain('Platform sign-in');
      expect(wrapper.text()).toContain('Tenant ID');
      await wrapper.find('input[autocomplete="username"]').setValue('user-original');
      await wrapper.find('input[autocomplete="current-password"]').setValue('password-original');
      await wrapper.find('form').trigger('submit');
      await flushPromises();
      expect(wrapper.text()).toContain(
        'Incorrect username or password. Check your credentials and try again.',
      );
      expect(wrapper.text()).not.toContain('locked');
      expect(client.login).toHaveBeenCalledExactlyOnceWith({
        tenantId: '',
        username: 'user-original',
        password: 'password-original',
      });
      language.setLanguage('zh-CN');
      await flushPromises();
      expect(wrapper.text()).toContain('用户名或密码错误，请检查后重试。');
      expect(wrapper.text()).not.toContain('锁定');
      expect(wrapper.text()).not.toContain('原始错误');
      expect(wrapper.text()).toContain('平台登录');
      expect((wrapper.find('input[autocomplete="username"]').element as HTMLInputElement).value).toBe(
        'user-original',
      );
      expect((wrapper.find('input[autocomplete="current-password"]').element as HTMLInputElement).value).toBe(
        'password-original',
      );
      expect(client.login).toHaveBeenCalledTimes(1);
    } finally {
      wrapper.unmount();
    }
  });

  it('uses an initial consumer username without requiring consumer-owned login markup', () => {
    const wrapper = mount(LoginView, {
      props: {
        authClient: authClient(),
        loginContextClient: loginContextClient(),
        initialUsername: 'xcmg',
      },
    });

    expect((wrapper.find('input[autocomplete="username"]').element as HTMLInputElement).value).toBe('xcmg');
  });

  it('uses locked tenant branding from the platform login context', async () => {
    window.history.replaceState({}, '', '/?tenant=tenant-a');
    const wrapper = mount(LoginView, {
      props: { authClient: authClient(), loginContextClient: loginContextClient() },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('租户 A');
    expect(wrapper.text()).toContain('租户专属工作台');
    expect(wrapper.text()).not.toContain('租户：tenant-a');
    expect(wrapper.find('input[placeholder="留空进入系统工作区"]').exists()).toBe(false);
    expect(wrapper.find('.login-brand > .login-logo').exists()).toBe(true);
  });

  it('keeps logo-only branding consistent with the workbench', async () => {
    window.history.replaceState({}, '', '/?tenant=tenant-a');
    const wrapper = mount(LoginView, {
      props: {
        authClient: authClient(),
        loginContextClient: loginContextClient({
          lightLogo: 'data:image/png;base64,bGlnaHQ=',
          mode: 'logoOnly',
          title: '租户 A',
          subtitle: '租户专属工作台',
        }),
      },
    });

    await flushPromises();

    expect(wrapper.find('.login-logo').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('租户 A');
    expect(wrapper.text()).not.toContain('租户专属工作台');
    expect(wrapper.text()).not.toContain('平台登录');
  });

  it('explains a rejected CORS request as missing site authorization', async () => {
    const wrapper = mount(LoginView, {
      props: {
        authClient: authClient({
          login: vi.fn().mockRejectedValue(new AppError('Invalid CORS request', { status: 403 })),
        }),
      },
    });

    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(wrapper.text()).toContain('当前站点未获后端授权，请联系管理员配置访问来源');
    expect(wrapper.text()).not.toContain('服务已启动');
  });

  it('keeps browser-hidden CORS failures distinct from a service-startup diagnosis', async () => {
    const wrapper = mount(LoginView, {
      props: {
        authClient: authClient({
          login: vi
            .fn()
            .mockRejectedValue(
              new AppError('Network request failed', { code: platformErrorCodes.networkError }),
            ),
        }),
      },
    });

    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(wrapper.text()).toContain('暂时无法连接服务，请稍后重试。');
    expect(wrapper.text()).not.toContain('锁定');
  });
});

function authClient(overrides: Partial<AuthClient> = {}): AuthClient {
  return {
    login: vi.fn(),
    changeOwnPassword: vi.fn(),
    currentProfile: vi.fn(),
    updateCurrentProfile: vi.fn(),
    logout: vi.fn(),
    ...overrides,
  };
}

function loginContextClient(
  branding: TenantBranding = {
    lightLogo: 'data:image/png;base64,bGlnaHQ=',
    title: '租户 A',
    subtitle: '租户专属工作台',
  },
  externalLoginMethods: ExternalLoginMethod[] = [],
): LoginContextClient {
  return {
    loginContext: vi.fn().mockResolvedValue({
      tenantId: 'tenant-a',
      branding,
      externalLoginMethods,
    }),
  };
}

function externalLoginClient(overrides: Partial<ExternalLoginClient> = {}): ExternalLoginClient {
  return {
    startLogin: vi.fn().mockResolvedValue({
      transactionId: 'tx-1',
      state: 's',
      authorizationUrl: 'https://idp.test/authorize?state=s',
      browserProof: 'proof-1',
      expiresAt: '2026-10-24T00:03:00Z',
    }),
    startBind: vi.fn(),
    complete: vi.fn(),
    confirm: vi.fn(),
    identities: vi.fn(),
    unbind: vi.fn(),
    ...overrides,
  };
}
