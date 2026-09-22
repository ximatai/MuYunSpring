import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import LoginView from '@/app/LoginView.vue';
import type { AuthClient, LoginContextClient } from '@/web-core/clients';
import { AppError, platformErrorCodes } from '@/web-core/errors';
import type { TenantBranding } from '@/web-contracts';

describe('LoginView', () => {
  afterEach(() => {
    window.history.replaceState({}, '', '/');
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

    expect(wrapper.text()).toContain('无法访问后端，请检查服务状态或站点授权配置');
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
): LoginContextClient {
  return {
    loginContext: vi.fn().mockResolvedValue({
      tenantId: 'tenant-a',
      branding,
    }),
  };
}
