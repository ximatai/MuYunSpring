/* eslint-disable vue/one-component-per-file -- Local host fixtures cover credential ownership changes. */
import { defineComponent, h, ref, watch } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { usePersonalAccessTokenAccess } from '@/app/usePersonalAccessTokenAccess';
import type { PersonalAccessTokenClient } from '@/web-core';
import PersonalAccessTokenDialog from '@/app/PersonalAccessTokenDialog.vue';
import { storedAuthToken } from '@/platform-admin-runtime/authSession';

beforeEach(() => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockReturnValue({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

it('fails closed, refreshes grants and rejects stale decisions after account changes or disposal', async () => {
  const session = ref<string>();
  let resolveOld!: (value: { canCreate: boolean }) => void;
  const capabilities = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    )
    .mockResolvedValueOnce({ canCreate: false })
    .mockResolvedValueOnce({ canCreate: true })
    .mockRejectedValueOnce(new Error('offline'));
  const client: PersonalAccessTokenClient = {
    capabilities,
    list: vi.fn(),
    create: vi.fn(),
    remove: vi.fn(),
    revoke: vi.fn(),
  };
  let access!: ReturnType<typeof usePersonalAccessTokenAccess>;
  const wrapper = mount(
    defineComponent({
      setup() {
        access = usePersonalAccessTokenAccess({ client, sessionToken: () => session.value });
        return () => h('div');
      },
    }),
  );
  expect(access.available.value).toBe(false);
  expect(capabilities).not.toHaveBeenCalled();
  session.value = 'old-session';
  session.value = 'new-session';
  await flushPromises();
  resolveOld({ canCreate: true });
  await flushPromises();
  expect(access.available.value).toBe(false);
  await access.refresh();
  expect(capabilities).toHaveBeenLastCalledWith('new-session');
  expect(access.available.value).toBe(true);
  window.dispatchEvent(new Event('focus'));
  await flushPromises();
  expect(access.available.value).toBe(false);
  let resolveDisposed!: (value: { canCreate: boolean }) => void;
  capabilities.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveDisposed = resolve;
      }),
  );
  const pending = access.refresh();
  wrapper.unmount();
  resolveDisposed({ canCreate: true });
  await pending;
  expect(access.available.value).toBe(false);
});

it.each(['focus', 'storage'] as const)(
  'clears the previous account secret on %s even when both accounts can create',
  async (trigger) => {
    window.localStorage.setItem('muyun.auth.token', 'session-a');
    let resolveNext!: (value: { canCreate: boolean }) => void;
    const capabilities = vi.fn((session: string) =>
      session === 'session-a'
        ? Promise.resolve({ canCreate: true })
        : new Promise<{ canCreate: boolean }>((resolve) => {
            resolveNext = resolve;
          }),
    );
    const client: PersonalAccessTokenClient = {
      capabilities,
      list: vi.fn().mockResolvedValue([]),
      create: vi.fn().mockResolvedValue({
        token: 'first-account-secret',
        accessToken: {
          id: 'key-a',
          name: 'tool',
          displayPrefix: 'prefix',
          createdAt: '2026-10-27T00:00:00Z',
          expiresAt: '2030-01-01T00:00:00Z',
        },
      }),
      remove: vi.fn(),
      revoke: vi.fn(),
    };
    let access!: ReturnType<typeof usePersonalAccessTokenAccess>;
    const wrapper = mount(
      defineComponent({
        setup() {
          const open = ref(true);
          access = usePersonalAccessTokenAccess({ client, sessionToken: storedAuthToken });
          watch(
            [access.sessionToken, access.available],
            ([session, available], [previous]) => {
              if (session !== previous || !available) open.value = false;
            },
            { flush: 'sync' },
          );
          return () =>
            h(PersonalAccessTokenDialog, {
              open: open.value,
              client,
              sessionToken: access.sessionToken.value,
            });
        },
      }),
      { global: { stubs: { UiModal: { template: '<div><slot /></div>' } } } },
    );
    try {
      await flushPromises();
      expect(access.available.value).toBe(true);
      await wrapper.get('input').setValue('tool');
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('first-account-secret');
      window.localStorage.setItem('muyun.auth.token', 'session-b');
      window.dispatchEvent(
        trigger === 'focus'
          ? new Event('focus')
          : new StorageEvent('storage', {
              key: 'muyun.auth.token',
              storageArea: window.localStorage,
              oldValue: 'session-a',
              newValue: 'session-b',
            }),
      );
      expect(access.sessionToken.value).toBe('session-b');
      expect(access.available.value).toBe(false);
      await flushPromises();
      expect(wrapper.find('textarea').exists()).toBe(false);
      expect(wrapper.findComponent(PersonalAccessTokenDialog).props('open')).toBe(false);
      resolveNext({ canCreate: true });
      await flushPromises();
      expect(access.available.value).toBe(true);
      expect(wrapper.findComponent(PersonalAccessTokenDialog).props('open')).toBe(false);
      expect(wrapper.find('textarea').exists()).toBe(false);
      expect(client.create).toHaveBeenCalledExactlyOnceWith('session-a', { name: 'tool', validDays: 90 });
    } finally {
      wrapper.unmount();
      window.localStorage.clear();
    }
  },
);

it('observes cross-tab logout and ignores unrelated storage updates, then removes its listeners', async () => {
  window.localStorage.setItem('muyun.auth.token', 'session-a');
  const capabilities = vi.fn().mockResolvedValue({ canCreate: true });
  const client: PersonalAccessTokenClient = {
    capabilities,
    list: vi.fn(),
    create: vi.fn(),
    remove: vi.fn(),
    revoke: vi.fn(),
  };
  let access!: ReturnType<typeof usePersonalAccessTokenAccess>;
  const wrapper = mount(
    defineComponent({
      setup() {
        access = usePersonalAccessTokenAccess({ client, sessionToken: storedAuthToken });
        return () => h('div');
      },
    }),
  );
  let mounted = true;
  try {
    await flushPromises();
    expect(access.sessionToken.value).toBe('session-a');
    window.localStorage.setItem('preference', 'dark');
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'preference', storageArea: window.localStorage }),
    );
    await flushPromises();
    expect(capabilities).toHaveBeenCalledTimes(1);
    expect(access.available.value).toBe(true);
    window.localStorage.clear();
    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: window.localStorage }));
    expect(access.sessionToken.value).toBeUndefined();
    expect(access.available.value).toBe(false);
    wrapper.unmount();
    mounted = false;
    window.localStorage.setItem('muyun.auth.token', 'session-b');
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'muyun.auth.token', storageArea: window.localStorage }),
    );
    window.dispatchEvent(new Event('focus'));
    await flushPromises();
    expect(capabilities).toHaveBeenCalledTimes(1);
  } finally {
    if (mounted) wrapper.unmount();
    window.localStorage.clear();
  }
});
