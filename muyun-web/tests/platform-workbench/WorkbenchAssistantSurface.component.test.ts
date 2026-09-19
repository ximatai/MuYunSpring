import { defineComponent, h, nextTick, ref } from 'vue';
import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import Workbench from '@/platform-workbench/Workbench.vue';
import { useAssistantSurfaceHost, type AssistantSurfaceHost } from '@muyun/web-core';
import type { WorkbenchStartupState } from '@muyun/web-contracts';

it('keeps a workbench fallback surface under the active page surface', async () => {
  let host: AssistantSurfaceHost | undefined;
  const Probe = defineComponent({
    setup() {
      host = useAssistantSurfaceHost();
      return () => h('div', { 'data-probe': '' });
    },
  });
  const requestTurn = vi.fn(async () => ({ text: 'ready', toolCalls: [] }));
  const Harness = defineComponent({
    setup() {
      const startup = ref<WorkbenchStartupState>({
        session: { currentUser: { userId: 'user-1', system: true } },
        menus: [
          {
            record: { id: 'root', schemeId: 'default', title: 'Business' },
            children: [
              {
                record: {
                  id: 'customers',
                  schemeId: 'default',
                  title: 'Customers',
                  entryType: 'module',
                  openMode: 'tab',
                  moduleAlias: 'crm.customer',
                },
                children: [],
              },
            ],
          },
        ],
        tabs: [{ key: 'static-page', instanceKey: 'static-instance', title: 'Static page' }],
        activeTabKey: 'static-page',
      });
      return () =>
        h(
          Workbench,
          {
            startup: startup.value,
            assistantRequestTurn: requestTurn,
            onSelectMenu: () => {
              startup.value = {
                ...startup.value,
                tabs: [
                  ...(startup.value.tabs ?? []),
                  { key: 'customers', instanceKey: 'customers-instance', title: 'Customers' },
                ],
                activeTabKey: 'customers',
              };
            },
          },
          { default: () => h(Probe) },
        );
    },
  });
  const wrapper = mount(Harness);
  await nextTick();

  expect(host?.registry.snapshot()?.context.surface).toBe('workbench');
  const snapshot = host!.registry.snapshot()!;
  await expect(
    host!.registry.invoke(
      { id: 'call-1', code: 'workbench.open-menu', input: { menuId: 'customers' } },
      snapshot.token,
    ),
  ).resolves.toEqual({
    value: { openedMenuId: 'customers', title: 'Customers' },
    contextChanged: true,
  });
  expect(host?.registry.snapshot()?.token.pageInstanceKey).toBe('customers-instance');

  const unregisterPage = host!.registry.register({
    pageInstanceKey: 'customers-instance',
    contextRevision: () => 'page-1',
    surface: {
      describe: () => ({ surface: 'static-page', facts: {} }),
      capabilities: () => [],
      requestTurn,
    },
  });
  expect(host?.registry.snapshot()?.context.surface).toBe('static-page');

  unregisterPage();
  expect(host?.registry.snapshot()?.context.surface).toBe('workbench');

  wrapper.unmount();
});
