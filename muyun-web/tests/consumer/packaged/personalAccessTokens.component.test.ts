import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import type { PersonalAccessTokenClient } from '../../../src/consumer';

it('lets a custom host expose the platform token dialog through the public package', async () => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run through npm run verify:consumer.');
  const sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
  const client: PersonalAccessTokenClient = {
    capabilities: vi.fn().mockResolvedValue({ canCreate: true }),
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn(),
    revoke: vi.fn(),
    remove: vi.fn(),
  };
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  const session = Vue.ref<string | undefined>('interactive-session');
  let access: ReturnType<typeof sdk.usePersonalAccessTokenAccess>;
  const wrapper = mount(
    Vue.defineComponent({
      setup() {
        access = sdk.usePersonalAccessTokenAccess({ client, sessionToken: () => session.value });
        return () =>
          Vue.h(sdk.PersonalAccessTokenDialog, {
            open: access.available.value,
            client,
            sessionToken: session.value,
          });
      },
    }),
    { attachTo: document.body },
  );
  try {
    await flushPromises();
    expect(access!.available.value).toBe(true);
    expect(client.list).toHaveBeenCalledWith('interactive-session');
    expect(document.body.textContent).toContain('个人访问密钥');
    expect(client.create).not.toHaveBeenCalled();
    session.value = undefined;
    await flushPromises();
    expect(access!.available.value).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it('a standard consumer shell obtains token management by injecting its session once', async () => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run through npm run verify:consumer.');
  const sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  const request = vi.fn(async ({ path }: { path: string }) =>
    path.endsWith('/capabilities') ? { canCreate: true } : path === '/iam.auth/access-tokens' ? [] : {},
  );
  const wrapper = mount(sdk.AppWorkbenchShell, {
    props: {
      startup: {
        session: { currentUser: { userId: 'admin', username: 'admin', system: true } },
        menus: [],
        tabs: [],
      },
      location: '/',
      accountContext: { http: { request }, token: 'consumer-session', preferencePersistence: 'local' },
      accountMenu: {
        hiddenCommands: ['changePassword'],
        contributions: [{ key: 'demo.tools', title: 'Business tools' }],
      },
    },
    attachTo: document.body,
  });
  try {
    await flushPromises();
    const menu = wrapper
      .findAllComponents(sdk.UiDropdown)
      .find((part) => part.props('items').some((item: { key: string }) => item.key === 'accessTokens'))!;
    const keys = menu.props('items').map((item: { key: string }) => item.key);
    expect(keys).toContain('demo.tools');
    expect(keys).not.toContain('changePassword');
    menu.vm.$emit('select', 'demo.tools');
    await flushPromises();
    expect(wrapper.emitted('userCommand')).toEqual([['demo.tools']]);
    menu.vm.$emit('select', 'accessTokens');
    await flushPromises();
    expect(wrapper.findComponent(sdk.PersonalAccessTokenDialog).props('open')).toBe(true);
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/iam.auth/access-tokens',
        headers: { Authorization: 'Bearer consumer-session' },
      }),
    );
    expect(document.body.textContent).toContain('暂无访问密钥');
  } finally {
    wrapper.unmount();
  }
});

it('exposes a reactive credential reader for consumer-owned account hosts', async () => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run through npm run verify:consumer.');
  const sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
  sdk.saveAuthToken('consumer-a');
  let token!: Vue.ComputedRef<string | undefined>;
  const wrapper = mount(
    Vue.defineComponent({
      setup() {
        token = sdk.useEffectiveAuthToken();
        return () => Vue.h('span', token.value);
      },
    }),
  );
  try {
    expect(token.value).toBe('consumer-a');
    window.localStorage.setItem('muyun.auth.token', 'consumer-b');
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'muyun.auth.token', storageArea: window.localStorage }),
    );
    expect(token.value).toBe('consumer-b');
    sdk.clearAuthToken();
    expect(token.value).toBeUndefined();
  } finally {
    wrapper.unmount();
    sdk.clearAuthToken();
  }
});
