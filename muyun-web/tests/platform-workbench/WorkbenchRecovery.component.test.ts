import WorkbenchMenu from '@/platform-workbench/WorkbenchMenu.vue';
import { defineComponent, h, nextTick } from 'vue';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import Workbench from '@/platform-workbench/Workbench.vue';

it('lets the owner retry a failed startup without opening a page or replaying an assistant request', async () => {
  const wrapper = mount(Workbench, { props: { error: '服务暂时无法连接' } });
  const retry = wrapper.findAll('button').find((button) => button.text() === '重试加载');
  expect(retry).toBeDefined();
  await retry!.trigger('click');
  expect(wrapper.emitted('retryLoad')).toHaveLength(1);
  expect(wrapper.emitted('selectMenu')).toBeUndefined();
  await wrapper.setProps({ loading: true });
  expect(wrapper.text()).not.toContain('重试加载');
  await wrapper.setProps({ loading: false, error: undefined });
  expect(wrapper.text()).not.toContain('重试加载');
  wrapper.unmount();
});

it('switches account commands and workspace captions without changing command IDs or identity facts', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () =>
        h(Workbench, {
          startup: {
            session: { currentUser: { userId: 'stable-user', username: '原始账户', system: true } },
            menus: [],
            tabs: [],
          },
        });
    },
  });
  const wrapper = mount(Host);
  try {
    await flushPromises();
    language.setLanguage('en');
    await nextTick();
    const menu = wrapper.findComponent({ name: 'UiDropdown' });
    const ids = menu.props('items').map((item: { key: string }) => item.key);
    expect(menu.props('items').map((item: { title: string }) => item.title)).toEqual([
      'Change password',
      'My profile',
      'Sign out',
    ]);
    expect(wrapper.text()).toContain('System administrator');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(menu.props('items').map((item: { key: string }) => item.key)).toEqual(ids);
    expect(menu.props('items').map((item: { title: string }) => item.title)).toEqual([
      '修改密码',
      '个人信息',
      '退出登录',
    ]);
    menu.vm.$emit('select', 'profile');
    expect(wrapper.findComponent(Workbench).emitted('userCommand')).toEqual([['profile']]);
  } finally {
    wrapper.unmount();
  }
});

it('presents realtime status through its own consumer language without changing transport state', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(WorkbenchMenu, { menus: [], presentation: 'expanded', realtimeStatus: 'disconnected' });
    },
  });
  const wrapper = mount(Host);
  try {
    expect(wrapper.get('[role="status"]').text()).toBe('Live connection disconnected');
    expect(wrapper.get('[role="status"]').attributes('title')).toContain('Waiting to reconnect');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.get('[role="status"]').text()).toBe('实时连接已断开');
    expect(wrapper.findComponent(WorkbenchMenu).props('realtimeStatus')).toBe('disconnected');
  } finally {
    wrapper.unmount();
  }
});

it('shows translated workspace tab resources while preserving original tab titles and selection keys', async () => {
  const { createWorkspaceViewDescriptor } = await import('@/platform-workbench/workspaceViews');
  const { moduleGovernanceWorkspaceView } = await import('@/views/moduleGovernanceWorkspaceView');
  const { tabKeyOf } = await import('@/platform-workbench/menuNavigation');
  const language = createLanguageContext({ languageTag: 'en' });
  const descriptor = createWorkspaceViewDescriptor(
    moduleGovernanceWorkspaceView,
    { moduleAlias: 'education.exam', moduleTitle: '业务原名' },
    'tab',
    undefined,
    language,
  );
  expect(descriptor.titleText?.languages.en?.text).toBe('Low-code governance: 业务原名');
  const key = tabKeyOf(descriptor);
  const tab = { key, title: descriptor.title!, pageDescriptor: descriptor, closable: true };
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () =>
          h(Workbench, {
            startup: {
              session: { currentUser: { userId: 'stable-user', username: '原始账户', system: true } },
              menus: [],
              tabs: [tab],
              activeTabKey: key,
            },
            activeTabKey: key,
          });
      },
    }),
  );
  try {
    await flushPromises();
    const tabs = wrapper.findComponent({ name: 'UiTabs' });
    language.setLanguage('en');
    await nextTick();
    expect(tabs.props('tabs')[0].title).toBe('Low-code governance: 业务原名');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(tabs.props('tabs')[0].title).toBe('低代码治理：业务原名');
    language.setLanguage('en');
    await nextTick();
    expect(tabs.props('tabs')[0].key).toBe(key);
    expect(tab.title).toBe('低代码治理：业务原名');
    expect(wrapper.findComponent(Workbench).emitted('changeTab')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});
