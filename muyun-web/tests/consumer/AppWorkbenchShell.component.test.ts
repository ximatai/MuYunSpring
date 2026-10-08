import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import AppWorkbenchShell from '@/consumer/AppWorkbenchShell.vue';
import type { AppWorkbenchNavigation } from '@/consumer/workbenchNavigation';
import Workbench from '@/platform-workbench/Workbench.vue';
import { useWorkbenchNavigation, type WorkbenchNavigation } from '@/platform-workbench/workbenchNavigation';
import type { MenuTreeNode, WorkbenchStartupState } from '@/web-contracts';
import { configureUserPreferenceBackend } from '@/web-core/userPreferences';
import {
  clearWorkspaceViewUnsavedState,
  registerWorkspaceViewUnsavedState,
  workspaceViewUnsavedStateSources,
} from '@/platform-workbench/workspaceViewUnsavedState';

const discardFeedback = vi.hoisted(() => ({ confirm: vi.fn<() => Promise<boolean>>(), busy: vi.fn() }));
vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/vue-ui-antdv')>()),
  confirmAction: discardFeedback.confirm,
  showErrorMessage: discardFeedback.busy,
}));

beforeEach(() => {
  discardFeedback.confirm.mockReset().mockResolvedValue(false);
  discardFeedback.busy.mockReset();
});
afterEach(() => {
  clearWorkspaceViewUnsavedState('menu:A');
  clearWorkspaceViewUnsavedState('menu:B');
});

const tabs = [
  {
    instanceKey: 'menu:A',
    key: 'menu:A',
    title: 'A',
    fullPath: '/a?InstanceKey=menu%3AA',
    target: { menuId: 'A', menuType: 'route' as const, openMode: 'tab' as const, route: '/a' },
    pageDescriptor: {
      pageType: 'business-route' as const,
      openMode: 'workbench-route' as const,
      hostType: 'business-route-host' as const,
      target: { route: '/a' },
      tabPolicy: { identity: 'by-menu' as const },
    },
    closable: true,
  },
  {
    instanceKey: 'menu:B',
    key: 'menu:B',
    title: 'B',
    fullPath: '/b?InstanceKey=menu%3AB',
    target: { menuId: 'B', menuType: 'route' as const, openMode: 'tab' as const, route: '/b' },
    pageDescriptor: {
      pageType: 'business-route' as const,
      openMode: 'workbench-route' as const,
      hostType: 'business-route-host' as const,
      target: { route: '/b' },
      tabPolicy: { identity: 'by-menu' as const },
    },
    closable: true,
  },
];

function startup(): WorkbenchStartupState {
  return {
    session: { currentUser: { userId: 'u1', username: 'tester', tenantId: 'tenant', system: false } },
    menus: tabs.map((tab) => ({
      record: {
        id: tab.target.menuId,
        title: tab.title,
        schemeId: 'test',
        moduleAlias: 'test.workspace',
        entryType: 'route',
        openMode: 'tab',
        route: tab.target.route,
      },
      children: [],
    })),
    tabs: structuredClone(tabs),
    activeTabKey: 'menu:A',
  };
}

function mountShell() {
  return mount(AppWorkbenchShell, {
    props: {
      startup: startup(),
      location: tabs[0]!.fullPath,
      realtimeStatus: 'connected',
      themeAppearance: 'dark',
    },
  });
}

function mountShellWithNavigation(loadMenus?: () => Promise<MenuTreeNode[]>) {
  let navigation: WorkbenchNavigation | undefined;
  // eslint-disable-next-line vue/one-component-per-file -- The probe only exposes the shell's provided navigation to this test.
  const NavigationProbe = defineComponent({
    setup() {
      navigation = useWorkbenchNavigation();
      return () => h('div');
    },
  });
  const wrapper = mount(AppWorkbenchShell, {
    props: {
      startup: startup(),
      loadMenus,
      location: tabs[0]!.fullPath,
      realtimeStatus: 'connected',
      themeAppearance: 'dark',
    },
    slots: {
      default: () => h(NavigationProbe),
    },
  });
  return { wrapper, navigation: () => navigation };
}

async function syncStartup(wrapper: ReturnType<typeof mountShell>) {
  const state = wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState | undefined;
  if (state) await wrapper.setProps({ startup: state });
}

it('keeps pinned tab order in account preferences after a drag reorder', async () => {
  const save = vi.fn().mockResolvedValue(undefined);
  configureUserPreferenceBackend({ load: vi.fn().mockResolvedValue(undefined), save, remove: vi.fn() });
  const wrapper = mountShell();
  const workbench = wrapper.findComponent(Workbench);

  await workbench.vm.$emit('toggleTabLock', 'menu:A');
  await syncStartup(wrapper);
  await workbench.vm.$emit('toggleTabLock', 'menu:B');
  await syncStartup(wrapper);
  await workbench.vm.$emit('reorderTabs', ['menu:B', 'menu:A']);
  await flushPromises();

  const state = wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState | undefined;
  expect(state?.tabs?.map((tab) => tab.key)).toEqual(['menu:B', 'menu:A']);
  expect(save).toHaveBeenLastCalledWith('workbench.locked-tabs', expect.stringContaining('"key":"menu:B"'));
  wrapper.unmount();
  configureUserPreferenceBackend(undefined);
});

it('does not let a slow pinned-tab restore overwrite a local lock change', async () => {
  let resolveLoad!: (value: unknown) => void;
  const load = new Promise<unknown>((resolve) => {
    resolveLoad = resolve;
  });
  configureUserPreferenceBackend({ load: vi.fn().mockReturnValue(load), save: vi.fn(), remove: vi.fn() });
  const wrapper = mountShell();
  const workbench = wrapper.findComponent(Workbench);

  await workbench.vm.$emit('toggleTabLock', 'menu:B');
  await syncStartup(wrapper);
  resolveLoad([]);
  await flushPromises();

  expect(workbench.props('lockedTabKeys')).toEqual(['menu:B']);
  wrapper.unmount();
  configureUserPreferenceBackend(undefined);
});

it('passes standard appearance and connection state through to the workbench', () => {
  const wrapper = mountShell();
  const workbench = wrapper.findComponent(Workbench);

  expect(workbench.props('themeAppearance')).toBe('dark');
  expect(workbench.props('realtimeStatus')).toBe('connected');
});

it('forwards a failed-load retry to the consumer that owns startup', async () => {
  const wrapper = mount(AppWorkbenchShell, {
    props: { startup: startup(), location: '/a', error: '服务暂时未就绪' },
  });
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试加载')!
    .trigger('click');
  expect(wrapper.emitted('retryLoad')).toHaveLength(1);
  expect(wrapper.emitted('navigate')).toBeUndefined();
  wrapper.unmount();
});

it('uses the consumer router to apply an active tab change', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push('/a');
  await router.isReady();
  const startupState = ref<WorkbenchStartupState>(startup());
  const ShellHarness = defineComponent({
    setup() {
      return () =>
        h(
          AppWorkbenchShell,
          {
            startup: startupState.value,
            location: router.currentRoute.value.fullPath,
            realtimeStatus: 'connected',
            themeAppearance: 'dark',
            'onUpdate:startup': (value: WorkbenchStartupState) => (startupState.value = value),
            onNavigate: ({ url, mode }: AppWorkbenchNavigation) => router[mode](url),
          },
          { default: () => [] },
        );
    },
  });
  const wrapper = mount(ShellHarness, { global: { plugins: [router] } });
  const workbench = wrapper.findComponent(Workbench);

  await workbench.vm.$emit('changeTab', 'menu:B');
  await flushPromises();

  expect(router.currentRoute.value.fullPath).toBe('/b?InstanceKey=menu%3AB');
});

it('creates independent tabs when the same user page opens twice', async () => {
  const { wrapper, navigation } = mountShellWithNavigation();
  await flushPromises();
  await syncStartup(wrapper);
  const workbenchNavigation = navigation();
  expect(workbenchNavigation).toBeDefined();

  expect(workbenchNavigation?.openRoute('/iam/users/form/user-1?action=view', { newInstance: true })).toEqual(
    {
      created: true,
    },
  );
  await syncStartup(wrapper);
  expect(workbenchNavigation?.openRoute('/iam/users/form/user-1?action=view', { newInstance: true })).toEqual(
    {
      created: true,
    },
  );

  const state = wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState;
  expect(state.tabs?.filter((tab) => tab.fullPath?.startsWith('/iam/users/form/user-1'))).toHaveLength(2);
  wrapper.unmount();
});

it('replaces the current tab address and closes it into the fallback address', async () => {
  const { wrapper, navigation } = mountShellWithNavigation();
  await flushPromises();
  await syncStartup(wrapper);
  const workbenchNavigation = navigation();
  expect(workbenchNavigation).toBeDefined();

  workbenchNavigation?.openRoute('/iam/users/form?action=add', { newInstance: true });
  await syncStartup(wrapper);
  expect(
    workbenchNavigation?.replaceRoute('/iam/users/form/user-1?action=view', {
      tabTitle: '浏览用户：alice',
    }),
  ).toEqual({ created: false });
  await syncStartup(wrapper);

  let state = wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState;
  const activeTabUrl = new URL(
    state.tabs?.find((tab) => tab.key === state.activeTabKey)?.fullPath ?? '',
    'http://muyun.local',
  );
  expect(activeTabUrl.pathname).toBe('/iam/users/form/user-1');
  expect(activeTabUrl.searchParams.get('action')).toBe('view');
  expect(activeTabUrl.searchParams.get('InstanceKey')).toBeNull();
  expect(state.tabs?.find((tab) => tab.key === state.activeTabKey)?.instanceKey).toMatch(/^[0-9a-f-]{36}$/i);
  expect(state.tabs?.find((tab) => tab.key === state.activeTabKey)?.title).toBe('浏览用户：alice');
  expect(wrapper.emitted('navigate')?.at(-1)?.[0]).toMatchObject({ mode: 'replace' });

  expect(workbenchNavigation?.closeCurrentTab('/a')).toEqual({ created: false });
  await flushPromises();
  await syncStartup(wrapper);
  state = wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState;
  expect(state.tabs?.some((tab) => tab.fullPath?.includes('/iam/users/form/user-1'))).toBe(false);
  expect(state.activeTabKey).toBe('menu:A');
  expect(wrapper.emitted('navigate')?.at(-1)?.[0]).toMatchObject({ mode: 'replace' });
  wrapper.unmount();
});

it('closes a clean current tab immediately and reports creation of a missing fallback', async () => {
  const { wrapper, navigation } = mountShellWithNavigation();
  await flushPromises();
  await syncStartup(wrapper);
  expect(navigation()?.closeCurrentTab('/new-fallback')).toEqual({ created: true });
  const state = wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState;
  expect(state.tabs?.some((tab) => tab.key === 'menu:A')).toBe(false);
  expect(state.tabs?.find((tab) => tab.key === state.activeTabKey)?.fullPath).toBe('/new-fallback');
  expect(discardFeedback.confirm).not.toHaveBeenCalled();
  wrapper.unmount();
});

it('protects public close-page navigation while a workspace mutation is in flight', async () => {
  const { wrapper, navigation } = mountShellWithNavigation();
  await flushPromises();
  await syncStartup(wrapper);
  registerWorkspaceViewUnsavedState(
    'menu:A',
    '业务保存',
    () => false,
    () => true,
  );
  navigation()?.closePage('menu:A');
  await flushPromises();
  expect(discardFeedback.busy).toHaveBeenCalledWith(expect.stringContaining('业务保存'));
  expect(discardFeedback.confirm).not.toHaveBeenCalled();
  expect((wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState).tabs).toHaveLength(2);
  wrapper.unmount();
});

it('keeps or closes a dirty tab according to the shared discard decision', async () => {
  const wrapper = mountShell();
  await flushPromises();
  await syncStartup(wrapper);
  registerWorkspaceViewUnsavedState('menu:A', '流程配置', () => true);
  const workbench = wrapper.findComponent(Workbench);
  workbench.vm.$emit('closeTab', 'menu:A');
  await flushPromises();
  expect((wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState).tabs).toHaveLength(2);
  expect(workspaceViewUnsavedStateSources('menu:A')).toEqual(['流程配置']);
  discardFeedback.confirm.mockResolvedValue(true);
  workbench.vm.$emit('closeTab', 'menu:A');
  await flushPromises();
  expect(
    (wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState).tabs?.map((tab) => tab.key),
  ).toEqual(['menu:B']);
  expect(workspaceViewUnsavedStateSources('menu:A')).toEqual([]);
  wrapper.unmount();
});

it('protects a batch close as one operation and rechecks mutations after confirmation', async () => {
  const wrapper = mountShell();
  await flushPromises();
  await syncStartup(wrapper);
  let busy = true;
  registerWorkspaceViewUnsavedState('menu:A', '配置', () => true);
  registerWorkspaceViewUnsavedState(
    'menu:B',
    '办理',
    () => false,
    () => busy,
  );
  const workbench = wrapper.findComponent(Workbench);
  workbench.vm.$emit('closeTabs', ['menu:A', 'menu:B']);
  await flushPromises();
  expect(discardFeedback.confirm).not.toHaveBeenCalled();
  expect((wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState).tabs).toHaveLength(2);
  busy = false;
  let resolveConfirmation!: (confirmed: boolean) => void;
  discardFeedback.confirm.mockReturnValue(
    new Promise((resolve) => {
      resolveConfirmation = resolve;
    }),
  );
  workbench.vm.$emit('closeTabs', ['menu:A', 'menu:B']);
  await flushPromises();
  busy = true;
  resolveConfirmation(true);
  await flushPromises();
  expect((wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState).tabs).toHaveLength(2);
  busy = false;
  discardFeedback.confirm.mockResolvedValue(true);
  workbench.vm.$emit('closeTabs', ['menu:A', 'menu:B']);
  await flushPromises();
  expect((wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState).tabs).toEqual([]);
  wrapper.unmount();
});

it('guards close-current-tab without changing its synchronous navigation result', async () => {
  const { wrapper, navigation } = mountShellWithNavigation();
  await flushPromises();
  await syncStartup(wrapper);
  registerWorkspaceViewUnsavedState('menu:A', '业务详情', () => true);
  const previousNavigations = wrapper.emitted('navigate')?.length ?? 0;
  expect(navigation()?.closeCurrentTab('/b')).toEqual({ created: false });
  await flushPromises();
  expect(wrapper.emitted('navigate')?.length ?? 0).toBe(previousNavigations);
  expect((wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState).tabs).toHaveLength(2);
  discardFeedback.confirm.mockResolvedValue(true);
  expect(navigation()?.closeCurrentTab('/b')).toEqual({ created: false });
  await flushPromises();
  expect((wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState).activeTabKey).toBe(
    'menu:B',
  );
  expect(workspaceViewUnsavedStateSources('menu:A')).toEqual([]);
  wrapper.unmount();
});

it('emits a page refresh only after its workspace state permits reconstruction', async () => {
  const wrapper = mountShell();
  await flushPromises();
  await syncStartup(wrapper);
  let busy = true;
  registerWorkspaceViewUnsavedState(
    'menu:A',
    '记录编辑',
    () => true,
    () => busy,
  );
  const workbench = wrapper.findComponent(Workbench);
  workbench.vm.$emit('refreshPage', 'menu:A');
  await flushPromises();
  expect(wrapper.emitted('refreshPage')).toBeUndefined();
  busy = false;
  workbench.vm.$emit('refreshPage', 'menu:A');
  await flushPromises();
  expect(discardFeedback.confirm).toHaveBeenCalledWith(
    expect.objectContaining({ title: '刷新页面', okText: '刷新' }),
  );
  expect(wrapper.emitted('refreshPage')).toBeUndefined();
  discardFeedback.confirm.mockResolvedValue(true);
  workbench.vm.$emit('refreshPage', 'menu:A');
  await flushPromises();
  expect(wrapper.emitted('refreshPage')).toEqual([['menu:A']]);
  wrapper.unmount();
});

it('preserves descriptor URL semantics when a menu opens in a new window', async () => {
  const open = vi.spyOn(window, 'open').mockReturnValue(null);
  const wrapper = mountShell();
  const workbench = wrapper.findComponent(Workbench);
  const menu = {
    id: 'external-bi',
    schemeId: 'default',
    title: 'External BI',
    entryType: 'link' as const,
    moduleAlias: 'ops.report',
    openMode: 'window' as const,
    externalUrl: 'https://bi.example.com/report',
  };
  const target = {
    menuId: menu.id,
    menuType: 'link' as const,
    openMode: 'window' as const,
    moduleAlias: menu.moduleAlias,
    externalUrl: menu.externalUrl,
  };

  await workbench.vm.$emit('selectMenu', menu, target);

  expect(open).toHaveBeenCalledWith('https://bi.example.com/report', '_blank', 'noopener,noreferrer');
  open.mockRestore();
});

it('refreshes menus without replacing open pages or the active tab', async () => {
  const menus = [
    {
      record: {
        id: 'new',
        title: '客户',
        schemeId: 's',
        moduleAlias: 'crm.customer',
        entryType: 'module',
        openMode: 'tab',
      },
      children: [],
    },
  ] as MenuTreeNode[];
  const { wrapper, navigation } = mountShellWithNavigation(async () => menus);
  await flushPromises();
  const before = wrapper.props('startup');
  await navigation()!.refreshMenus!();
  const after = wrapper.emitted('update:startup')!.at(-1)![0] as WorkbenchStartupState;
  expect(after.menus).toEqual(menus);
  expect(after.tabs).toBe(before.tabs);
  expect(after.activeTabKey).toBe(before.activeTabKey);
  wrapper.unmount();
});

it('rejects an old menu response after the session has changed', async () => {
  let resolve!: (nodes: MenuTreeNode[]) => void;
  const { wrapper, navigation } = mountShellWithNavigation(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  await flushPromises();
  const pending = navigation()!.refreshMenus!();
  await wrapper.setProps({ startup: startup() });
  const rejection = expect(pending).rejects.toThrow('登录状态已变化');
  resolve([]);
  await rejection;
  wrapper.unmount();
});
