import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import type * as Consumer from '../../../src/consumer';

let sdk: typeof Consumer;
beforeAll(() => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run through npm run verify:consumer.');
  sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
});
afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  window.history.replaceState({}, '', '/');
});

it('assembles a custom shell with public login, callback handoff and personal binding', async () => {
  window.history.replaceState({}, '', '/?tenant=tenant-a');
  const requests: Array<{ path: string; init?: RequestInit }> = [];
  const start = (state: string) => ({
    transactionId: state,
    state,
    browserProof: `proof-${state}`,
    authorizationUrl: 'https://login.welink.huaweicloud.com/sso/oauth2/sns_authorize',
    expiresAt: new Date(Date.now() + 180000).toISOString(),
    qrCode: {
      providerType: 'welink',
      scriptUrl: 'invalid',
      authorizationUrl: 'invalid',
      clientId: 'client',
      redirectUri: 'callback',
      state,
      scope: 'snsapi_login',
      messageOrigin: 'invalid',
    },
  });
  const profile: Consumer.ExternalProvisioningProfile = {
    displayName: 'Applicant',
    tenantId: 'tenant-a',
    departmentIds: ['sales'],
    placements: ['Org / Sales'],
  };
  const provider = { providerId: 'welink', title: 'WeLink', providerType: 'welink' };
  const login = {
    token: 'host-session',
    tokenType: 'Bearer',
    issuedAt: '2026-10-24T00:00:00Z',
    currentUser: { userId: 'alice', tenantId: 'tenant-a', system: false },
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      requests.push({ path, init });
      if (path.endsWith('/login-context')) return Response.json({ externalLoginMethods: [provider] });
      if (path.endsWith('/welink/login')) return Response.json(start('login-state'));
      if (path.endsWith('/welink/complete'))
        return Response.json({
          status: 'confirmationRequired',
          purpose: 'login',
          confirmation: profile,
        });
      if (path.endsWith('/welink/confirm'))
        return Response.json({ status: 'succeeded', purpose: 'login', login });
      if (path.endsWith('/external/identities'))
        return Response.json({ bindings: [], bindableProviders: [provider] });
      if (path.endsWith('/welink/bind')) return Response.json(start('bind-state'));
      throw new Error(`Unexpected request ${path}`);
    }),
  );
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  });
  await router.push('/?tenant=tenant-a');
  const http = sdk.createBackendHttpClient({ withAuth: false });
  const client = sdk.createExternalLoginClient(http);
  const session = Vue.ref<Consumer.LoginResult>();
  let host!: Consumer.ExternalLoginHost;
  const Shell = Vue.defineComponent({
    setup() {
      host = sdk.useExternalLoginHost({
        client,
        router,
        signedIn: () => Boolean(session.value),
        onAuthenticated: (value) => {
          session.value = value;
        },
      });
      Vue.onMounted(() => void host.completeReturn());
      return () =>
        host.confirmation.value
          ? Vue.h(sdk.ExternalProvisioningConfirmation, {
              confirmation: host.confirmation.value,
              busy: host.confirming.value,
              error: host.error.value,
              onConfirm: host.confirmProvisioning,
              onCancel: host.cancelProvisioning,
            })
          : session.value
            ? Vue.h(sdk.ExternalIdentityBindings, { client, token: session.value.token })
            : Vue.h(sdk.LoginView, {
                authClient: sdk.createAuthClient(http),
                loginContextClient: sdk.createLoginContextClient(http),
                externalLoginClient: host.client,
                error: host.error.value,
              });
    },
  });
  let wrapper = mount(Shell);
  try {
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('WeLink'))!
      .trigger('click');
    await flushPromises();
    expect(requests.find(({ path }) => path.endsWith('/welink/login'))).toBeDefined();
    expect(wrapper.findComponent({ name: 'WeLinkQrCode' }).exists()).toBe(true);
    // Simulate provider navigation and a fresh host mount: no private storage contract is copied.
    wrapper.unmount();
    await router.replace('/?state=login-state&code=provider-code');
    wrapper = mount(Shell);
    await flushPromises();
    expect(session.value).toBeUndefined();
    expect(wrapper.text()).toContain('Org / Sales');
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('确认并登录'))!
      .trigger('click');
    await flushPromises();
    expect(session.value).toEqual(login);
    expect(
      JSON.parse(String(requests.find(({ path }) => path.endsWith('/welink/confirm'))?.init?.body)),
    ).toEqual({
      state: 'login-state',
      browserProof: 'proof-login-state',
    });
    const completion = requests.find(({ path }) => path.endsWith('/welink/complete'))!;
    expect(JSON.parse(String(completion.init?.body))).toEqual({
      state: 'login-state',
      browserProof: 'proof-login-state',
      parameters: { state: 'login-state', code: 'provider-code' },
    });
    expect(window.location.search).toBe('?_muyunTenantId=tenant-a');
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('去绑定'))!
      .trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'WeLinkQrCode' }).exists()).toBe(true);
    expect(requests.find(({ path }) => path.endsWith('/welink/bind'))?.init?.headers).toMatchObject({
      Authorization: 'Bearer host-session',
    });
    expect(sdk.CurrentUserProfileDialog).toBeDefined();
  } finally {
    wrapper.unmount();
  }
});
