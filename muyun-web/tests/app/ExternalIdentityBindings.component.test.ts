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

  it('stays hidden when the deployment does not enable federation', async () => {
    const client = externalLoginClient({
      identities: vi
        .fn()
        .mockRejectedValue(new AppError('not found', { code: platformErrorCodes.httpError, status: 404 })),
    });
    const wrapper = mount(ExternalIdentityBindings, { props: { client, token: 'token-1' } });
    await flushPromises();

    expect(wrapper.find('.external-bindings').exists()).toBe(false);
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
