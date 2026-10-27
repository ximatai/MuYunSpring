import { computed, defineComponent, h, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import WorkbenchAccountCenter from '@/app/WorkbenchAccountCenter.vue';
import Workbench from '@/platform-workbench/Workbench.vue';
import { UiLanguageProvider, type UiDropdownItem } from '@muyun/vue-ui-antdv';
import { createLanguageContext, type HttpClient } from '@muyun/web-core';
import '@/vue-ui-antdv/styles.css';
import '@/styles.css';

it('a custom host gets a usable standard account menu and dialog without feature-specific wiring', async () => {
  await page.viewport(1063, 804);
  const token = ref('session-a');
  const selected = ref('');
  const logout = vi.fn();
  const language = createLanguageContext({ languageTag: 'zh-CN' });
  const request = vi.fn(async (input: { path: string; headers?: Record<string, string> }) =>
    input.path.endsWith('/capabilities')
      ? { canCreate: input.headers?.Authorization === 'Bearer session-a' }
      : input.path === '/iam.auth/access-tokens'
        ? []
        : {},
  );
  const context = computed(() => ({
    http: { request } as HttpClient,
    token: token.value,
    preferencePersistence: 'local' as const,
    currentUser: { userId: 'admin', username: 'admin', system: true },
  }));
  const startup = { session: { currentUser: context.value.currentUser }, menus: [], tabs: [] };
  type Slot = {
    items?: UiDropdownItem[];
    select: (key: string) => Promise<void>;
    refresh: () => Promise<void>;
  };
  const root = defineComponent({
    setup: () => () =>
      h(
        UiLanguageProvider,
        { context: language },
        {
          default: () => [
            h(
              WorkbenchAccountCenter,
              {
                context: context.value,
                menu: {
                  hiddenCommands: ['changePassword'],
                  contributions: [{ key: 'business.help', title: '业务帮助' }],
                },
                beforeCommand: ({ key }: { key: string }) => key !== 'logout',
                onLogout: logout,
                onUserCommand: (key: string) => {
                  selected.value = key;
                },
              },
              {
                default: (account: Slot) =>
                  h(Workbench, {
                    startup,
                    managedAccountMenuItems: account.items,
                    onUserCommand: (key: string) => {
                      void account.select(key);
                    },
                    onAccountMenuOpen: () => {
                      void account.refresh();
                    },
                  }),
              },
            ),
            h('p', { 'data-testid': 'selected-command' }, selected.value),
          ],
        },
      ),
  });
  const wrapper = mount(root, {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  try {
    await flushPromises();
    expect(wrapper.findAllComponents({ name: 'WorkbenchLanguageSettings' })).toHaveLength(1);
    await expect.element(page.getByRole('button', { name: '账号菜单：admin', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '账号菜单：admin', exact: true }).click();
    await expect.element(page.getByRole('menuitem', { name: '个人访问密钥', exact: true })).toBeVisible();
    expect(document.body.textContent).not.toContain('修改密码');
    await page.getByRole('menuitem', { name: '个人访问密钥', exact: true }).click();
    await expect.element(page.getByText('暂无访问密钥', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: /关\s*闭/, exact: true }).click();
    await flushPromises();
    expect(wrapper.findComponent({ name: 'PersonalAccessTokenDialog' }).props('open')).toBe(false);
    await page.getByRole('button', { name: '账号菜单：admin', exact: true }).click();
    await expect.element(page.getByRole('menuitem', { name: '业务帮助', exact: true })).toBeVisible();
    await page.getByRole('menuitem', { name: '业务帮助', exact: true }).click();
    await expect.element(page.getByTestId('selected-command')).toHaveTextContent('business.help');
    await page.getByRole('button', { name: '账号菜单：admin', exact: true }).click();
    await expect.element(page.getByRole('menuitem', { name: '退出登录', exact: true })).toBeVisible();
    await page.getByRole('menuitem', { name: '退出登录', exact: true }).click();
    expect(logout).not.toHaveBeenCalled();
    token.value = 'session-b';
    await flushPromises();
    await page.getByRole('button', { name: '账号菜单：admin', exact: true }).click();
    await expect
      .poll(
        () =>
          [...document.querySelectorAll('[role="menuitem"]')].filter(
            (item) => item.textContent?.trim() === '个人访问密钥',
          ).length,
      )
      .toBe(0);
  } finally {
    wrapper.unmount();
  }
}, 30000);
