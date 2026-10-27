import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ExternalIdentityBindings from '@/app/ExternalIdentityBindings.vue';
import { navigateToExternalAuthorization } from '@/app/externalLoginReturn';
import type { ExternalLoginClient } from '@/web-core/clients';
import { AppError, platformErrorCodes } from '@/web-core/errors';

vi.mock('@/app/externalLoginReturn', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/app/externalLoginReturn')>()),
  navigateToExternalAuthorization: vi.fn(),
}));

describe('ExternalIdentityBindings', () => {
  afterEach(() => {
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
    const client = externalLoginClient({ startBind: vi.fn().mockResolvedValue(start) });
    const wrapper = mount(ExternalIdentityBindings, {
      props: { client, token: 'token-1' },
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
    const button = wrapper.findAll('button').find((candidate) => candidate.text().includes('去绑定'));
    await button!.trigger('click');
    await flushPromises();
    expect(wrapper.find('.qr-close').exists()).toBe(true);
    expect(navigateToExternalAuthorization).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem('muyun.externalLogin.pending')).toContain('private-proof');
    await wrapper.get('.qr-close').trigger('click');
    expect(window.sessionStorage.getItem('muyun.externalLogin.pending')).toBeNull();
    wrapper.unmount();
  });

  it.each([404, 405])('stays hidden when federation has no GET endpoint (%s)', async (status) => {
    const client = externalLoginClient({
      identities: vi
        .fn()
        .mockRejectedValue(new AppError('not found', { code: platformErrorCodes.httpError, status })),
    });
    const wrapper = mount(ExternalIdentityBindings, { props: { client, token: 'token-1' } });
    await flushPromises();

    expect(wrapper.find('.external-bindings').exists()).toBe(false);
  });

  it('keeps genuine federation failures visible', async () => {
    const client = externalLoginClient({
      identities: vi.fn().mockRejectedValue(new AppError('身份服务暂不可用', { status: 503 })),
    });
    const wrapper = mount(ExternalIdentityBindings, { props: { client, token: 'token-1' } });
    await flushPromises();
    expect(wrapper.get('.external-bindings-error').text()).not.toBe('');
    wrapper.unmount();
  });

  it('lists bindings and starts a bind transaction for an unbound provider', async () => {
    const client = externalLoginClient();
    const wrapper = mount(ExternalIdentityBindings, { props: { client, token: 'token-1' } });
    await flushPromises();

    expect(wrapper.text()).toContain('emp-001');
    const bind = wrapper.findAll('button').find((button) => button.text().includes('去绑定'));
    await bind!.trigger('click');
    await flushPromises();

    expect(client.startBind).toHaveBeenCalledWith('mock_idp', 'token-1');
    expect(navigateToExternalAuthorization).toHaveBeenCalledWith('https://idp.test/authorize?state=s');
    expect(window.sessionStorage.getItem('muyun.externalLogin.pending')).toContain('"purpose":"bind"');
  });
});

function externalLoginClient(overrides: Partial<ExternalLoginClient> = {}): ExternalLoginClient {
  return {
    startLogin: vi.fn(),
    startBind: vi.fn().mockResolvedValue({
      transactionId: 'tx-1',
      state: 's',
      authorizationUrl: 'https://idp.test/authorize?state=s',
      browserProof: 'proof-1',
      expiresAt: '2026-10-24T00:03:00Z',
    }),
    complete: vi.fn(),
    confirm: vi.fn(),
    identities: vi.fn().mockResolvedValue({
      bindings: [
        {
          id: 'binding-1',
          providerId: 'welink',
          providerTitle: 'WeLink',
          externalUserId: 'emp-001',
          boundAt: '2026-10-24T00:00:00Z',
        },
      ],
      bindableProviders: [{ providerId: 'mock_idp', title: '模拟身份源', providerType: 'mock' }],
    }),
    unbind: vi.fn(),
    ...overrides,
  };
}
