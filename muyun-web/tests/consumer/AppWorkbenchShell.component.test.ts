import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { expect, it, vi } from 'vitest';
import AppWorkbenchShell from '@/consumer/AppWorkbenchShell.vue';
import type { AppWorkbenchNavigation } from '@/consumer/workbenchNavigation';
import Workbench from '@/platform-workbench/Workbench.vue';
import { useWorkbenchNavigation, type WorkbenchNavigation } from '@/platform-workbench/workbenchNavigation';
import type { WorkbenchStartupState } from '@/web-contracts';
import { configureUserPreferenceBackend } from '@/web-core/userPreferences';

const tabs = [
  {
    key: 'menu:A',
    title: 'A',
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
    key: 'menu:B',
    title: 'B',
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
    menus: [],
    tabs: structuredClone(tabs),
    activeTabKey: 'menu:A',
  };
}

function mountShell() {
  return mount(AppWorkbenchShell, {
    props: {
      startup: startup(),
      location: '/a',
      realtimeStatus: 'connected',
      themeAppearance: 'dark',
    },
  });
}

function mountShellWithNavigation() {
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
      location: '/a',
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

  expect(router.currentRoute.value.fullPath).toBe('/b');
});

it('creates independent tabs when the same user page opens twice', async () => {
  const { wrapper, navigation } = mountShellWithNavigation();
  await flushPromises();
  await syncStartup(wrapper);
  const workbenchNavigation = navigation();
  expect(workbenchNavigation).toBeDefined();

  expect(workbenchNavigation?.openRoute('/iam/users/user-1', { newInstance: true })).toEqual({
    created: true,
  });
  await syncStartup(wrapper);
  expect(workbenchNavigation?.openRoute('/iam/users/user-1', { newInstance: true })).toEqual({
    created: true,
  });

  const state = wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState;
  expect(state.tabs?.filter((tab) => tab.fullPath?.startsWith('/iam/users/user-1'))).toHaveLength(2);
  wrapper.unmount();
});

it('replaces the current tab address and closes it into the fallback address', async () => {
  const { wrapper, navigation } = mountShellWithNavigation();
  await flushPromises();
  await syncStartup(wrapper);
  const workbenchNavigation = navigation();
  expect(workbenchNavigation).toBeDefined();

  workbenchNavigation?.openRoute('/iam/users?userAction=add', { newInstance: true });
  await syncStartup(wrapper);
  expect(workbenchNavigation?.replaceRoute('/iam/users/user-1')).toEqual({ created: false });
  await syncStartup(wrapper);

  let state = wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState;
  expect(state.tabs?.find((tab) => tab.key === state.activeTabKey)?.fullPath).toMatch(
    /^\/iam\/users\/user-1\?InstanceKey=[0-9a-f-]{36}$/i,
  );
  expect(wrapper.emitted('navigate')?.at(-1)?.[0]).toMatchObject({ mode: 'replace' });

  expect(workbenchNavigation?.closeCurrentTab('/a')).toEqual({ created: false });
  await syncStartup(wrapper);
  state = wrapper.emitted('update:startup')?.at(-1)?.[0] as WorkbenchStartupState;
  expect(state.tabs?.some((tab) => tab.fullPath?.includes('/iam/users/user-1'))).toBe(false);
  expect(state.activeTabKey).toContain('/a');
  expect(wrapper.emitted('navigate')?.at(-1)?.[0]).toMatchObject({ mode: 'replace' });
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
