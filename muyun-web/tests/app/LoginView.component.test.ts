import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import LoginView from '@/app/LoginView.vue';
import type { AuthClient } from '@/web-core/clients';

describe('LoginView', () => {
  afterEach(() => {
    window.history.replaceState({}, '', '/');
  });

  it('uses an initial consumer username without requiring consumer-owned login markup', () => {
    const wrapper = mount(LoginView, {
      props: {
        authClient: authClient(),
        initialUsername: 'xcmg',
      },
    });

    expect((wrapper.find('input[autocomplete="username"]').element as HTMLInputElement).value).toBe('xcmg');
  });

  it('uses locked tenant branding from the platform login context', async () => {
    window.history.replaceState({}, '', '/?tenant=tenant-a');
    const wrapper = mount(LoginView, {
      props: { authClient: authClient() },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('租户 A');
    expect(wrapper.text()).toContain('租户专属工作台');
    expect(wrapper.text()).not.toContain('租户：tenant-a');
    expect(wrapper.find('input[placeholder="留空进入系统工作区"]').exists()).toBe(false);
    expect(wrapper.find('.login-brand > .login-logo').exists()).toBe(true);
  });
});

function authClient(): AuthClient {
  return {
    login: vi.fn(),
    loginContext: vi.fn().mockResolvedValue({
      tenantId: 'tenant-a',
      branding: {
        lightLogo: 'data:image/png;base64,bGlnaHQ=',
        title: '租户 A',
        subtitle: '租户专属工作台',
      },
    }),
    changeOwnPassword: vi.fn(),
    currentProfile: vi.fn(),
    updateCurrentProfile: vi.fn(),
    logout: vi.fn(),
  };
}
