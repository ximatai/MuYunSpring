import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { clearAuthToken, saveAuthToken, useEffectiveAuthToken } from '@/platform-admin-runtime/authSession';

afterEach(() => {
  clearAuthToken();
  vi.restoreAllMocks();
});

function host(fallback?: string) {
  let token!: ReturnType<typeof useEffectiveAuthToken>;
  const wrapper = mount(
    defineComponent({
      setup() {
        token = useEffectiveAuthToken(fallback);
        return () => h('span', token.value);
      },
    }),
  );
  return { wrapper, token: () => token.value };
}

it('updates all reactive readers synchronously on same-tab writes, preserving env fallback', () => {
  clearAuthToken();
  const a = host(' env-token ');
  const b = host();
  try {
    expect(a.token()).toBe('env-token');
    expect(b.token()).toBeUndefined();
    saveAuthToken(' session-a ');
    expect(a.token()).toBe('session-a');
    expect(b.token()).toBe('session-a');
    clearAuthToken();
    expect(a.token()).toBe('env-token');
    expect(b.token()).toBeUndefined();
  } finally {
    a.wrapper.unmount();
    b.wrapper.unmount();
  }
});

it.each(['storage', 'focus'] as const)('refreshes cross-tab switches and logout on %s', (event) => {
  saveAuthToken('session-a');
  const f = host();
  const notify = () =>
    window.dispatchEvent(
      event === 'focus'
        ? new Event('focus')
        : new StorageEvent('storage', { key: 'muyun.auth.token', storageArea: window.localStorage }),
    );
  try {
    expect(f.token()).toBe('session-a');
    window.localStorage.setItem('muyun.auth.token', 'session-b');
    notify();
    expect(f.token()).toBe('session-b');
    window.localStorage.removeItem('muyun.auth.token');
    notify();
    expect(f.token()).toBeUndefined();
  } finally {
    f.wrapper.unmount();
  }
});

it('handles storage.clear, ignores other storage and removes browser listeners with its owner', () => {
  saveAuthToken('session-a');
  const remove = vi.spyOn(window, 'removeEventListener');
  const f = host();
  expect(f.token()).toBe('session-a');
  window.localStorage.removeItem('muyun.auth.token');
  window.dispatchEvent(new StorageEvent('storage', { key: 'unrelated', storageArea: window.localStorage }));
  window.dispatchEvent(
    new StorageEvent('storage', { key: 'muyun.auth.token', storageArea: window.sessionStorage }),
  );
  expect(f.token()).toBe('session-a');
  window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: window.localStorage }));
  expect(f.token()).toBeUndefined();
  f.wrapper.unmount();
  expect(remove).toHaveBeenCalledWith('storage', expect.any(Function));
  expect(remove).toHaveBeenCalledWith('focus', expect.any(Function));
  // A disposed reader no longer subscribes to browser invalidation.
  window.localStorage.setItem('muyun.auth.token', 'after-disposal');
  window.dispatchEvent(new Event('focus'));
  expect(f.token()).toBeUndefined();
});
