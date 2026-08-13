import { flushPromises, mount } from '@vue/test-utils';
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
