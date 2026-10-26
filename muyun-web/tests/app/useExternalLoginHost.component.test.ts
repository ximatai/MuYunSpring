import { defineComponent } from 'vue';
import { mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { createRouter, createWebHistory } from 'vue-router';
import { useExternalLoginHost, type ExternalLoginHost } from '@/app/useExternalLoginHost';
import { rememberExternalLogin } from '@/app/externalLoginReturn';
import type { ExternalLoginClient } from '@muyun/web-core';

const wrappers: Array<ReturnType<typeof mount>> = [];
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  window.sessionStorage.clear();
  window.history.replaceState({}, '', '/');
});

async function host(
  options: { pending?: boolean; signedIn?: boolean; purpose?: 'login' | 'bind'; path?: string } = {},
) {
  const path = options.path ?? '/';
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path, component: { template: '<div />' } }],
  });
  await router.push(`${path}?state=state&code=code#provider`);
  if (options.pending !== false)
    rememberExternalLogin(
      {
        transactionId: 'tx',
        state: 'state',
        browserProof: 'proof',
        authorizationUrl: 'https://idp.test',
        expiresAt: '',
      },
      { providerId: 'idp', purpose: options.purpose ?? 'login', tenantId: 'tenant-a' },
    );
  const complete = vi.fn();
  const confirm = vi.fn();
  const onAuthenticated = vi.fn();
  let api!: ExternalLoginHost;
  wrappers.push(
    mount(
      defineComponent({
        setup() {
          api = useExternalLoginHost({
            client: { complete, confirm } as unknown as ExternalLoginClient,
            router,
            signedIn: () => options.signedIn ?? false,
            onAuthenticated,
            callbackPath: path,
          });
          return () => null;
        },
      }),
    ),
  );
  return { api, complete, confirm, router, onAuthenticated };
}

it('cleans address and history before completion, coalesces calls, and hands off once', async () => {
  const { api, complete, onAuthenticated, router } = await host();
  const login = { token: 'session-token' };
  complete.mockImplementation(async () => {
    expect(window.location.search).toBe('?_muyunTenantId=tenant-a');
    expect(window.location.hash).toBe('');
    expect(window.history.state.current).not.toContain('code=');
    return { status: 'succeeded', login };
  });
  const first = api.completeReturn();
  expect(api.completeReturn()).toBe(first);
  expect(await first).toBe(true);
  expect(onAuthenticated).toHaveBeenCalledExactlyOnceWith(login);
  expect(complete).toHaveBeenCalledExactlyOnceWith('idp', {
    state: 'state',
    browserProof: 'proof',
    parameters: { state: 'state', code: 'code' },
  });
  expect(await api.completeReturn()).toBe(false);
  // A copied old callback or browser refresh cannot reuse a consumed proof.
  await router.replace('/?state=state&code=code');
  expect(await api.completeReturn()).toBe(false);
  expect(complete).toHaveBeenCalledTimes(1);
  expect(window.location.search).toBe('');
});

it.each(['denied', 'expired'])('reports %s to the signed-out login screen', async (status) => {
  const { api, complete } = await host();
  complete.mockResolvedValue({ status, failure: 'identityNotBound' });
  expect(await api.completeReturn()).toBe(true);
  expect(api.error.value).toContain(status === 'expired' ? '超时' : '尚未绑定');
  expect(window.location.search).toBe('?_muyunTenantId=tenant-a');
});

it('surfaces transport failure and consumes proof before refresh', async () => {
  const { api, complete } = await host();
  complete.mockRejectedValue(new Error('offline'));
  expect(await api.completeReturn()).toBe(true);
  expect(api.error.value).toBeTruthy();
  expect(api.completing.value).toBe(false);
  expect(window.sessionStorage.length).toBe(0);
  expect(await api.completeReturn()).toBe(false);
});

it('ignores unknown returns and supports a custom callback route', async () => {
  const { api, complete } = await host({ pending: false, path: '/auth/return' });
  expect(await api.completeReturn()).toBe(false);
  expect(complete).not.toHaveBeenCalled();
  expect(window.location.pathname).toBe('/auth/return');
  expect(window.location.search).toBe('');
});

it('continues ordinary session startup after binding, without session handoff', async () => {
  const { api, complete, onAuthenticated } = await host({ purpose: 'bind', signedIn: true });
  complete.mockResolvedValue({ status: 'succeeded', binding: { providerTitle: 'WeLink' } });
  expect(await api.completeReturn()).toBe(false);
  expect(onAuthenticated).not.toHaveBeenCalled();
  expect(window.location.search).toBe('');
});

it('preserves transactions on ordinary routes and completes them only at the callback', async () => {
  const { api, complete, router } = await host();
  router.addRoute({ path: '/business', component: { template: '<div />' } });
  await router.replace('/business?state=state&code=business-code');
  expect(await api.completeReturn()).toBe(false);
  expect(complete).not.toHaveBeenCalled();
  expect(window.sessionStorage.length).toBe(1);
  await router.replace('/?state=state&code=provider-code');
  complete.mockResolvedValue({ status: 'succeeded', login: { token: 'session' } });
  expect(await api.completeReturn()).toBe(true);
});

it('continues an existing session after a denied bind', async () => {
  const { api, complete, onAuthenticated } = await host({ purpose: 'bind', signedIn: true });
  complete.mockResolvedValue({ status: 'denied', failure: 'identityConflict' });
  expect(await api.completeReturn()).toBe(false);
  expect(api.error.value).toContain('其他账号');
  expect(onAuthenticated).not.toHaveBeenCalled();
});

const confirmation = {
  displayName: 'Applicant',
  tenantId: 'tenant-a',
  departmentIds: ['sales'],
  placements: ['Org / Sales'],
};

it('confirms through the public host once and hands off the session without exposing proof', async () => {
  const { api, complete, confirm, onAuthenticated } = await host();
  complete.mockResolvedValue({ status: 'confirmationRequired', confirmation });
  expect(await api.completeReturn()).toBe(true);
  expect(api.confirmation.value).toEqual(confirmation);
  expect(onAuthenticated).not.toHaveBeenCalled();
  let release!: (value: unknown) => void;
  confirm.mockImplementation(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const first = api.confirmProvisioning();
  await api.confirmProvisioning();
  api.cancelProvisioning();
  expect(api.confirmation.value).toBeDefined();
  expect(api.confirming.value).toBe(true);
  const login = { token: 'new-session' };
  release({ status: 'succeeded', login });
  await first;
  expect(confirm).toHaveBeenCalledExactlyOnceWith('idp', { state: 'state', browserProof: 'proof' });
  expect(onAuthenticated).toHaveBeenCalledExactlyOnceWith(login);
  expect(api.confirmation.value).toBeUndefined();
  expect(api.confirming.value).toBe(false);
});

it('keeps confirmation after admission failure and allows cancel without creating an account', async () => {
  const { api, complete, confirm, onAuthenticated } = await host();
  complete.mockResolvedValue({ status: 'confirmationRequired', confirmation });
  await api.completeReturn();
  confirm.mockResolvedValue({ status: 'denied', failure: 'admissionRejected' });
  await api.confirmProvisioning();
  expect(api.error.value).toContain('准入');
  expect(api.confirmation.value).toEqual(confirmation);
  expect(onAuthenticated).not.toHaveBeenCalled();
  api.cancelProvisioning();
  await api.confirmProvisioning();
  expect(confirm).toHaveBeenCalledTimes(1);
  expect(api.confirmation.value).toBeUndefined();
  expect(api.error.value).toBeUndefined();
});

it.each(['reviewPending', 'reviewRejected'])(
  'reports %s without session handoff or confirmation',
  async (status) => {
    const { api, complete, confirm, onAuthenticated } = await host();
    complete.mockResolvedValue({ status });
    expect(await api.completeReturn()).toBe(true);
    expect(api.error.value).toContain(status === 'reviewPending' ? '审核通过' : '拒绝');
    expect(api.confirmation.value).toBeUndefined();
    expect(confirm).not.toHaveBeenCalled();
    expect(onAuthenticated).not.toHaveBeenCalled();
  },
);
