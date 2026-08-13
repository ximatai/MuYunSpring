import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { expect, it, vi } from 'vitest';
import AppWorkbenchShell from '@/consumer/AppWorkbenchShell.vue';
import Workbench from '@/platform-workbench/Workbench.vue';
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

function startup() {
  return {
    session: { currentUser: { userId: 'u1', username: 'tester', tenantId: 'tenant' } },
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

async function syncStartup(wrapper: ReturnType<typeof mountShell>) {
  const state = wrapper.emitted('update:startup')?.at(-1)?.[0];
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

  expect(
    wrapper
      .emitted('update:startup')
      ?.at(-1)?.[0]
      .tabs.map((tab) => tab.key),
  ).toEqual(['menu:B', 'menu:A']);
  expect(save).toHaveBeenLastCalledWith('workbench.locked-tabs', expect.stringContaining('"key":"menu:B"'));
  wrapper.unmount();
  configureUserPreferenceBackend(undefined);
});

it('does not let a slow pinned-tab restore overwrite a local lock change', async () => {
  let resolveLoad: (value: unknown) => void;
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
  const startupState = ref(startup());
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
            'onUpdate:startup': (value) => (startupState.value = value),
            onNavigate: ({ url, mode }) => router[mode](url),
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

it('preserves descriptor URL semantics when a menu opens in a new window', async () => {
  const open = vi.spyOn(window, 'open').mockReturnValue(null);
  const wrapper = mountShell();
  const workbench = wrapper.findComponent(Workbench);
  const menu = {
    id: 'external-bi',
    schemeId: 'default',
    title: 'External BI',
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

  expect(open).toHaveBeenCalledWith(
    '/platform/external?_muyunMenuId=external-bi&_muyunTitle=External+BI&mode=new-window&url=https%3A%2F%2Fbi.example.com%2Freport',
    '_blank',
    'noopener,noreferrer',
  );
  open.mockRestore();
});
