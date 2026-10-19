import { mount, flushPromises } from '@vue/test-utils';
import { h } from 'vue';
import { expect, it } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import WorkbenchPageTitle from '@/platform-workbench/WorkbenchPageTitle.vue';
import Workbench from '@/platform-workbench/Workbench.vue';
import { UiThemeProvider } from '@muyun/vue-ui-antdv';
import { uiThemeSkins } from '@/vue-ui-antdv/theme';
import type { AssistantTurnRequester } from '@muyun/web-core';
import '@/styles.css';

it.each(uiThemeSkins)('keeps global tools aligned and usable from desktop to phone in $id', async (skin) => {
  const wrapper = mount(UiThemeProvider, {
    attachTo: document.body,
    props: { theme: skin.theme, scope: 'global' },
    slots: {
      default: () =>
        h(Workbench, {
          assistantAvailable: true,
          assistantRequestTurn: (() => Promise.resolve()) as unknown as AssistantTurnRequester,
          currentUserEmployeeName: '一位姓名很长的职员用于检查文字省略',
          startup: {
            session: { currentUser: { userId: 'admin', username: 'admin', system: true } },
            menus: [],
            tabs: [{ key: 'applications', title: '应用管理' }],
            activeTabKey: 'applications',
          },
        }),
    },
    global: { stubs: { WorkbenchAssistantPanel: true, WorkbenchMenu: true } },
  });
  try {
    for (const width of [1004, 720, 375, 320]) {
      await page.viewport(width, 804);
      await flushPromises();
      const header = wrapper.get('.app-topbar').element.getBoundingClientRect();
      const tools = wrapper.get('.topbar-actions').element.getBoundingClientRect();
      const identity = wrapper.get('.topbar-identity').element.getBoundingClientRect();
      expect(header.height).toBeLessThanOrEqual(56);
      expect(identity.right).toBeLessThanOrEqual(tools.left + 1);
      expect(tools.right).toBeLessThanOrEqual(width);
      const buttons = ['AI 助手', '账号菜单：admin'].map(
        (label) => wrapper.get(`button[aria-label="${label}"]`).element,
      );
      for (const button of buttons) {
        expect(button.getBoundingClientRect().height).toBe(36);
        expect(button.getBoundingClientRect().width).toBeGreaterThanOrEqual(36);
        expect(button.getBoundingClientRect().top).toBe(buttons[0]!.getBoundingClientRect().top);
      }
      expect(wrapper.get('.app-topbar').text()).toContain('应用管理');
      const title = wrapper.get('.workbench-page-refresh').element.getBoundingClientRect();
      expect(title.width).toBeGreaterThan(32);
      expect(title.right).toBeLessThanOrEqual(tools.left);
      expect(wrapper.findAll('.workbench-page-refresh')).toHaveLength(1);
      if (width <= 480)
        await expect
          .poll(() => getComputedStyle(wrapper.get('.workbench-page-refresh-icon').element).opacity)
          .toBe('1');
      expect(wrapper.get('.tab-strip').text()).toContain('应用管理');
      const account = wrapper.get('.user-button').element.getBoundingClientRect();
      const caret = wrapper.get('.user-caret').element.getBoundingClientRect();
      expect(Math.abs(caret.top + caret.height / 2 - (account.top + account.height / 2))).toBeLessThan(1);
      expect(caret.right).toBeLessThan(account.right);
      expect(caret.width).toBe(12);
      if (width > 480) {
        const userButton = wrapper.get('.user-button').element;
        const style = getComputedStyle(userButton);
        const innerHeight =
          account.height -
          parseFloat(style.paddingTop) -
          parseFloat(style.paddingBottom) -
          parseFloat(style.borderTopWidth) -
          parseFloat(style.borderBottomWidth);
        const metadata = wrapper.get('.user-meta').element.getBoundingClientRect();
        expect(metadata.height).toBeLessThanOrEqual(innerHeight);
        expect(
          Math.abs(metadata.top + metadata.height / 2 - (account.top + account.height / 2)),
        ).toBeLessThan(1);
        const name = wrapper.get('.user-meta small').element;
        expect(name.textContent).toContain('一位姓名很长的职员');
        expect(name.getBoundingClientRect().right).toBeLessThanOrEqual(caret.left);
        expect(name.scrollWidth).toBeGreaterThan(name.clientWidth);
      }
      expect(wrapper.find('.skin-button').exists()).toBe(false);
      expect(getComputedStyle(wrapper.get('.user-button').element).borderTopWidth).toBe('1px');
      expect(getComputedStyle(wrapper.get('.user-button').element).borderTopColor).not.toBe(
        'rgba(0, 0, 0, 0)',
      );
      expect(wrapper.find('button[aria-label="语言设置"]').exists()).toBe(false);
    }
  } finally {
    wrapper.unmount();
  }
});

it('reserves refresh space and makes the same title action discoverable by keyboard and mouse', async () => {
  await page.viewport(1004, 804);
  const wrapper = mount(WorkbenchPageTitle, {
    attachTo: document.body,
    props: { title: '一个需要省略显示的非常长的业务页面标题' },
    attrs: { style: 'width:240px' },
  });
  try {
    const button = wrapper.get('button').element as HTMLButtonElement;
    const icon = wrapper.get('.workbench-page-refresh-icon').element;
    const label = wrapper.get('.workbench-page-title-label').element as HTMLElement;
    await page.elementLocator(document.body).hover();
    await expect.poll(() => getComputedStyle(icon).opacity).toBe('0');
    const before = button.getBoundingClientRect();
    expect(label.scrollWidth).toBeGreaterThan(label.clientWidth);
    await userEvent.keyboard('{Tab}');
    expect(document.activeElement).toBe(button);
    await expect.poll(() => getComputedStyle(icon).opacity).toBe('1');
    await userEvent.keyboard('{Enter}');
    expect(wrapper.emitted('refresh')).toHaveLength(1);
    await page.elementLocator(label).click();
    await page.elementLocator(icon).click();
    expect(wrapper.emitted('refresh')).toHaveLength(3);
    expect(button.getBoundingClientRect().width).toBe(before.width);
    expect(button.getBoundingClientRect().left).toBe(before.left);
  } finally {
    wrapper.unmount();
  }
});

it('opens language preferences through the account menu and closes the shared dialog', async () => {
  await page.viewport(1004, 804);
  const { default: WorkbenchGlobalTools } = await import('@/platform-workbench/WorkbenchGlobalTools.vue');
  const wrapper = mount(WorkbenchGlobalTools, {
    attachTo: document.body,
    global: { stubs: { transition: false, 'transition-group': false } },
    props: { currentUser: { userId: 'admin', system: true }, assistantAvailable: true, assistantOpen: false },
  });
  try {
    await page.elementLocator(wrapper.get('.user-button').element).click();
    await page.getByRole('menuitem', { name: '语言设置', exact: true }).click();
    await expect.element(page.getByRole('dialog')).toBeVisible();
    await expect.element(page.getByText('界面语言', { exact: true })).toBeVisible();
    await expect.element(page.getByText('业务阅读语言', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '完成', exact: true }).click();
    await expect
      .poll(() => wrapper.findComponent({ name: 'WorkbenchLanguageSettings' }).props('open'))
      .toBe(false);
    await expect.element(page.getByText('界面语言', { exact: true })).not.toBeVisible();
    await page.elementLocator(wrapper.get('.user-button').element).click();
    await expect.element(page.getByRole('menuitem', { name: '语言设置', exact: true })).toBeVisible();
    expect(wrapper.emitted('userCommand')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});

it.each(uiThemeSkins)(
  'retains visible account hover, keyboard focus and expanded feedback in $id',
  async (skin) => {
    await page.viewport(1004, 804);
    const { default: WorkbenchGlobalTools } = await import('@/platform-workbench/WorkbenchGlobalTools.vue');
    const wrapper = mount(UiThemeProvider, {
      attachTo: document.body,
      props: { theme: skin.theme, scope: 'global' },
      slots: {
        default: () =>
          h(WorkbenchGlobalTools, {
            currentUser: { userId: 'admin', username: 'admin', system: true },
            employeeName: '张三',
            assistantAvailable: true,
            assistantOpen: false,
          }),
      },
    });
    try {
      const button = wrapper.get('.user-button').element as HTMLButtonElement;
      await page.elementLocator(document.body).hover();
      const background = getComputedStyle(button).backgroundColor;
      await page.elementLocator(button).hover();
      expect(getComputedStyle(button).cursor).toBe('pointer');
      expect(getComputedStyle(button).backgroundColor).not.toBe(background);
      await page.elementLocator(document.body).hover();
      let focusColor: string | undefined;
      for (const selector of ['.assistant-toggle', '.user-button']) {
        await userEvent.keyboard('{Tab}');
        const tool = wrapper.get(selector).element;
        expect(document.activeElement).toBe(tool);
        expect(getComputedStyle(tool).outlineStyle).toBe('solid');
        expect(getComputedStyle(tool).outlineWidth).toBe('2px');
        expect(getComputedStyle(tool).outlineOffset).toBe('2px');
        focusColor ??= getComputedStyle(tool).outlineColor;
        expect(getComputedStyle(tool).outlineColor).toBe(focusColor);
      }
      expect(document.activeElement).toBe(button);
      expect(button.matches(':focus-visible')).toBe(true);
      expect(getComputedStyle(button).outlineStyle).toBe('solid');
      expect(getComputedStyle(button).outlineWidth).toBe('2px');
      await userEvent.keyboard('{Enter}');
      await expect.poll(() => button.getAttribute('aria-expanded')).toBe('true');
      expect(getComputedStyle(button).backgroundColor).not.toBe(background);
      expect(wrapper.get('.user-meta small').text()).toBe('张三');
      const menuItems = document.querySelectorAll('.muyun-ui-dropdown-overlay li.ant-dropdown-menu-item');
      expect(menuItems.length).toBeGreaterThanOrEqual(6);
      for (const item of menuItems) {
        expect(item.querySelector('.ui-dropdown-item-icon svg')).not.toBeNull();
        expect(item.querySelector('.ui-dropdown-item-icon')?.getAttribute('aria-hidden')).toBe('true');
      }
      await page.getByRole('menuitem', { name: '皮肤切换', exact: true }).click();
      expect(wrapper.findComponent(WorkbenchGlobalTools).emitted('userCommand')).toEqual([['themeSkin']]);
      await expect.poll(() => button.getAttribute('aria-expanded')).toBe('false');
    } finally {
      wrapper.unmount();
    }
  },
);
