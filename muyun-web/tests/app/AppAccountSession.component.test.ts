import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createPinia } from 'pinia';
import App from '@/App.vue';
import { router } from '@/app/router';
import Workbench from '@/platform-workbench/Workbench.vue';
import WorkbenchAccountCenter from '@/app/WorkbenchAccountCenter.vue';
import ChangeOwnPasswordDialog from '@/app/ChangeOwnPasswordDialog.vue';
import { clearAuthToken, saveAuthToken } from '@/platform-admin-runtime/authSession';

const request = vi.hoisted(() => vi.fn());
vi.mock('@/platform-admin-runtime/backendHttp', () => ({ createBackendHttpClient: () => ({ request }) }));
vi.mock('@/app/appWorkbenchStartup', () => ({
  usesMockStartup: () => true,
  loadAppWorkbenchStartupState: async () => ({
    session: { currentUser: { userId: 'a', system: true } },
    menus: [],
    tabs: [],
  }),
}));
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
afterEach(() => {
  clearAuthToken();
  request.mockReset();
  vi.unstubAllGlobals();
});

it.each(['storage', 'focus'] as const)(
  'clears account secrets and password drafts when another tab changes the session (%s)',
  async (event) => {
    request.mockImplementation(async ({ path, method }: { path: string; method?: string }) => {
      if (path.endsWith('/capabilities')) return { canCreate: true };
      if (path === '/iam.auth/access-tokens')
        return method === 'POST'
          ? {
              token: 'one-time-test-secret',
              accessToken: {
                id: 'key-a',
                name: 'tool',
                displayPrefix: 'prefix',
                createdAt: '2026-10-27T00:00:00Z',
                expiresAt: '2030-01-01T00:00:00Z',
              },
            }
          : [];
      return {};
    });
    saveAuthToken('session-a');
    await router.replace('/');
    const wrapper = mount(App, {
      global: {
        plugins: [createPinia(), router],
        stubs: {
          Workbench: true,
          BusinessNotificationPanel: true,
          UiModal: { props: ['open'], template: '<div v-if="open"><slot /><slot name="operation" /></div>' },
        },
      },
    });
    const notify = () =>
      window.dispatchEvent(
        event === 'focus'
          ? new Event('focus')
          : new StorageEvent('storage', { key: 'muyun.auth.token', storageArea: window.localStorage }),
      );
    try {
      await flushPromises();
      const workbench = wrapper.findComponent(Workbench);
      workbench.vm.$emit('userCommand', 'accessTokens');
      await flushPromises();
      await wrapper.get('input').setValue('tool');
      await wrapper.get('form').trigger('submit');
      await flushPromises();
      expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('one-time-test-secret');
      window.localStorage.setItem('muyun.auth.token', 'session-b');
      notify();
      await flushPromises();
      expect(wrapper.findComponent(WorkbenchAccountCenter).props('context')?.token).toBe('session-b');
      expect(wrapper.find('textarea').exists()).toBe(false);
      workbench.vm.$emit('userCommand', 'changePassword');
      await flushPromises();
      const password = wrapper.findComponent(ChangeOwnPasswordDialog);
      password.vm.$emit('update:newPassword', 'private-test-draft');
      await flushPromises();
      expect(password.props('newPassword')).toBe('private-test-draft');
      window.localStorage.removeItem('muyun.auth.token');
      notify();
      await flushPromises();
      expect(wrapper.findComponent(WorkbenchAccountCenter).props('context')?.token).toBeUndefined();
      expect(password.props('open')).toBe(false);
      expect(password.props('newPassword')).toBe('');
    } finally {
      wrapper.unmount();
    }
  },
);
