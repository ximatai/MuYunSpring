import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import App from '@/App.vue';
import { router } from '@/app/router';
import type { ExternalLoginClient } from '@/web-core/clients';

const loadStartup = vi.hoisted(() => vi.fn());
const complete = vi.hoisted(() => vi.fn());
const confirm = vi.hoisted(() => vi.fn());

vi.mock('@/app/appWorkbenchStartup', () => ({
  usesMockStartup: () => false,
  loadAppWorkbenchStartupState: loadStartup,
}));

vi.mock('@muyun/web-core', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/web-core')>()),
  createExternalLoginClient: (): ExternalLoginClient => ({
    startLogin: vi.fn(),
    startBind: vi.fn(),
    complete,
    confirm,
    identities: vi.fn(),
    unbind: vi.fn(),
  }),
}));

describe('App external login return', () => {
  afterEach(async () => {
    window.sessionStorage.clear();
    window.localStorage.clear();
    complete.mockReset();
    confirm.mockReset();
    loadStartup.mockReset();
    await router.replace('/');
  });

  it('requires an explicit confirmation and sends only this browser transaction proof', async () => {
    remember({
      providerId: 'welink',
      state: 'join',
      browserProof: 'proof',
      purpose: 'login',
      tenantId: 'tenant',
    });
    complete.mockResolvedValue({
      status: 'confirmationRequired',
      purpose: 'login',
      confirmation: {
        displayName: 'New Person',
        tenantId: 'tenant',
        departmentIds: ['sales-id'],
        placements: ['Organization / Sales'],
      },
    });
    const wrapper = await mountAt('/?state=join&code=provider-code');
    expect(wrapper.text()).toContain('New Person');
    expect(wrapper.text()).toContain('Organization / Sales');
    expect(wrapper.text()).not.toContain('sales-id');
    expect(confirm).not.toHaveBeenCalled();
    confirm.mockResolvedValue({ status: 'denied', purpose: 'login', failure: 'admissionRejected' });
    await wrapper.findComponent({ name: 'ExternalProvisioningConfirmation' }).vm.$emit('confirm');
    await flushPromises();
    expect(confirm).toHaveBeenCalledWith('welink', { state: 'join', browserProof: 'proof' });
    expect(wrapper.text()).toContain('准入');
    wrapper.unmount();
  });

  it('drops a provider return this browser did not start and clears it from the address', async () => {
    const wrapper = await mountAt('/?state=attacker-state&code=victim-code');

    expect(complete).not.toHaveBeenCalled();
    expect(router.currentRoute.value.query).toEqual({});
    wrapper.unmount();
  });

  it('shows a denied sign-in on the tenant login page without keeping provider parameters', async () => {
    remember({
      providerId: 'welink',
      state: 's-1',
      browserProof: 'proof-1',
      purpose: 'login',
      tenantId: 'tenant-a',
    });
    complete.mockResolvedValue({ status: 'denied', purpose: 'login', failure: 'identityNotBound' });

    const wrapper = await mountAt('/?state=s-1&code=c-1');

    expect(complete).toHaveBeenCalledWith('welink', {
      state: 's-1',
      browserProof: 'proof-1',
      parameters: { state: 's-1', code: 'c-1' },
    });
    expect(router.currentRoute.value.query).toEqual({ _muyunTenantId: 'tenant-a' });
    expect(wrapper.find('.login-error').text()).toContain('尚未绑定');
    wrapper.unmount();
  });

  it('signs in with the completed external session', async () => {
    remember({
      providerId: 'welink',
      state: 's-2',
      browserProof: 'proof-2',
      purpose: 'login',
      tenantId: 'tenant-a',
    });
    complete.mockResolvedValue({
      status: 'succeeded',
      purpose: 'login',
      login: {
        token: 'external-token',
        tokenType: 'Bearer',
        issuedAt: '2026-10-24T00:00:00Z',
        currentUser: { userId: 'alice', tenantId: 'tenant-a', system: false },
      },
    });
    loadStartup.mockResolvedValue({
      session: { currentUser: { userId: 'alice', tenantId: 'tenant-a', system: false } },
      menus: [],
      tabs: [],
    });

    const wrapper = await mountAt('/?state=s-2&code=c-2');

    expect(loadStartup).toHaveBeenCalled();
    expect(wrapper.find('.login-panel').exists()).toBe(false);
    wrapper.unmount();
  });
});

async function mountAt(path: string) {
  const pinia = createPinia();
  setActivePinia(pinia);
  await router.replace(path);
  const wrapper = mount(App, { global: { plugins: [pinia, router] } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

function remember(entry: Record<string, string>) {
  window.sessionStorage.setItem('muyun.externalLogin.pending', JSON.stringify([entry]));
}
