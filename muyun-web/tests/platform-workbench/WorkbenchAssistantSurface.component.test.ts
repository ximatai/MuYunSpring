import { defineComponent, h, nextTick, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import Workbench from '@/platform-workbench/Workbench.vue';
import { useAssistantSurfaceHost, type AssistantSurfaceHost } from '@muyun/web-core';
import type { WorkbenchStartupState } from '@muyun/web-contracts';

it.each([false, true])(
  'guards workbench navigation while awaiting a formal surface (interrupted=%s)',
  async (interrupted) => {
    let host: AssistantSurfaceHost | undefined;
    let switchBack!: () => void;
    const Probe = defineComponent({
      setup() {
        host = useAssistantSurfaceHost();
        return () => h('div', { 'data-probe': '' });
      },
    });
    const requestTurn = vi.fn(async () => ({ text: 'ready', toolCalls: [] }));
    const waitForPageReady = vi.fn(async () => nextTick(() => 'customers-instance'));
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
        switchBack = () => {
          startup.value = { ...startup.value, activeTabKey: 'static-page' };
        };
        return () =>
          h(
            Workbench,
            {
              startup: startup.value,
              assistantRequestTurn: requestTurn,
              assistantWaitForPageReady: waitForPageReady,
              onSelectMenu: () => {
                startup.value = {
                  ...startup.value,
                  tabs: [
                    ...(startup.value.tabs ?? []),
                    {
                      key: 'customers',
                      instanceKey: 'customers-instance',
                      title: 'Customers',
                      pageDescriptor: {
                        pageType: 'dynamic-module',
                        openMode: 'dynamic-runner',
                        hostType: 'module-page-host',
                        tabPolicy: { identity: 'by-menu' },
                        target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
                      },
                    },
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
    const opening = host!.registry.invoke(
      { id: 'call-1', code: 'workbench.open-menu', input: { menuId: 'customers' } },
      snapshot.token,
    );
    await nextTick();
    expect(host?.registry.snapshot()?.token.pageInstanceKey).toBe('customers-instance');
    expect(waitForPageReady).toHaveBeenCalledOnce();

    if (interrupted) {
      await flushPromises();
      const rejected = expect(opening).rejects.toMatchObject({ name: 'AbortError' });
      switchBack();
      await nextTick();
      await rejected;
      expect(host?.registry.snapshot()?.token.pageInstanceKey).toBe('static-instance');
      wrapper.unmount();
      return;
    }

    const unregisterPage = host!.registry.register({
      pageInstanceKey: 'customers-instance',
      contextRevision: () => 'page-1',
      surface: {
        describe: () => ({ surface: 'static-page', facts: {} }),
        capabilities: () => [],
        requestTurn,
      },
    });
    await expect(opening).resolves.toEqual({
      value: { openedMenuId: 'customers', title: 'Customers' },
      contextChanged: true,
    });
    expect(host?.registry.snapshot()?.context.surface).toBe('static-page');

    unregisterPage();
    expect(host?.registry.snapshot()?.context.surface).toBe('workbench');

    wrapper.unmount();
  },
);
