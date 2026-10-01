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
      const rejected = expect(opening).rejects.toMatchObject({
        name: 'AssistantEffectInterruptedError',
        execution: 'effect-applied',
        cause: expect.objectContaining({ name: 'AbortError' }),
      });
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

it('supplies current candidate state without another construction.describe round trip', async () => {
  let host: AssistantSurfaceHost | undefined;
  const Probe = defineComponent({
    setup() {
      host = useAssistantSurfaceHost();
      return () => h('div');
    },
  });
  const confirm = vi.fn();
  const wrapper = mount(Workbench, {
    props: {
      startup: {
        session: { currentUser: { userId: 'user-1', system: true } },
        menus: [],
        tabs: [{ key: 'page', instanceKey: 'page-instance', title: 'Page' }],
        activeTabKey: 'page',
      },
      assistantRequestTurn: vi.fn(),
      constructionPlanClient: { confirm } as unknown as import('@muyun/web-core').ConstructionPlanClient,
    },
    slots: { default: () => h(Probe) },
  });
  await nextTick();
  const before = host!.registry.snapshot()!;
  const workspace = before.context.facts.workspace as { constructionPlan: Record<string, unknown> };
  expect(workspace.constructionPlan).toMatchObject({ persistence: 'NO_PLAN', dirty: false });
  await host!.registry.invoke(
    {
      id: 'proposal',
      code: 'construction.propose',
      input: {
        generation: workspace.constructionPlan.generation,
        content: {
          title: '通讯录',
          goal: '管理联系信息',
          inScope: ['名称查询'],
          outOfScope: [],
          objects: [],
          relationships: [],
          rules: [],
          questions: [],
          assumptions: [],
          acceptanceExamples: ['可以查询名称'],
          decisions: [],
          requirements: [],
        },
      },
    },
    before.token,
  );
  const after = host!.registry.snapshot()!;
  expect((after.context.facts.workspace as typeof workspace).constructionPlan).toMatchObject({
    title: '通讯录',
    persistence: 'UNSAVED_CANDIDATE',
    dirty: true,
    manualEditing: false,
    reviewRequired: false,
    confirmationResultUnknown: false,
  });
  expect(after.token.contextRevision).not.toBe(before.token.contextRevision);
  expect(confirm).not.toHaveBeenCalled();
  wrapper.unmount();
});
