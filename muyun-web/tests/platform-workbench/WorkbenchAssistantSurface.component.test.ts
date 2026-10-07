import { defineComponent, h, nextTick, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import Workbench from '@/platform-workbench/Workbench.vue';
import {
  useAssistantSurfaceHost,
  type AssistantSurfaceHost,
  type AssistantConfigurationEditor,
  type AssistantSurface,
  type AssistantCapability,
} from '@muyun/web-core';
import type { WorkbenchStartupState } from '@muyun/web-contracts';

it('refreshes bounded visible menu facts and invalidates earlier workspace tokens after menu changes', async () => {
  let host: AssistantSurfaceHost | undefined;
  const Probe = defineComponent({
    setup() {
      host = useAssistantSurfaceHost();
      return () => h('div');
    },
  });
  const startup: WorkbenchStartupState = {
    session: { currentUser: { userId: 'ordinary-user', system: false } },
    menus: [
      {
        record: {
          id: 'records',
          title: '业务记录',
          schemeId: 'ordinary',
          moduleAlias: 'work.record',
          entryType: 'module',
          openMode: 'tab',
        },
        children: [],
      },
    ],
    tabs: [{ key: 'page', instanceKey: 'page-instance', title: 'Page' }],
    activeTabKey: 'page',
  };
  const wrapper = mount(Workbench, {
    props: { startup, assistantRequestTurn: vi.fn() },
    slots: { default: () => h(Probe) },
  });
  await nextTick();
  const before = host!.registry.snapshot()!;
  expect(before.context.facts.workspace).toMatchObject({
    menuCatalog: {
      items: [{ menuId: 'records', title: '业务记录', schemeId: 'ordinary' }],
      page: { total: 1, nextOffset: null },
    },
  });
  await wrapper.setProps({ startup: { ...startup, menus: [] } });
  const after = host!.registry.snapshot()!;
  expect(after.context.facts.workspace).toMatchObject({ menuCatalog: { items: [], page: { total: 0 } } });
  expect(after.token).not.toEqual(before.token);
  await expect(
    host!.registry.invoke(
      { id: 'stale-open', code: 'workbench.open-menu', input: { menuId: 'records' } },
      before.token,
    ),
  ).rejects.toThrow();
  await expect(
    host!.registry.invoke(
      { id: 'old-open', code: 'workbench.open-menu', input: { menuId: 'records' } },
      after.token,
    ),
  ).rejects.toThrow('Visible menu is unavailable');
  expect(wrapper.emitted('selectMenu')).toBeUndefined();
  wrapper.unmount();
});

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
      presentation: { title: '已打开Customers', lines: ['仅切换页面，未修改或保存业务数据。'] },
      contextChanged: true,
    });
    expect(host?.registry.snapshot()?.context.surface).toBe('static-page');

    unregisterPage();
    expect(host?.registry.snapshot()?.context.surface).toBe('workbench');

    wrapper.unmount();
  },
);

it('allows requirements planning and authorized navigation without a selected module editor', async () => {
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
        menus: [
          {
            record: {
              id: 'modules',
              title: '模块管理',
              schemeId: 'admin',
              moduleAlias: 'platform.module',
              entryType: 'module',
              openMode: 'tab',
            },
            children: [],
          },
        ],
        tabs: [{ key: 'page', instanceKey: 'page-instance', title: 'Page' }],
        activeTabKey: 'page',
      },
      assistantRequestTurn: vi.fn(),
      constructionPlanClient: { confirm } as unknown as import('@muyun/web-core').ConstructionPlanClient,
      assistantWorkspaceContribution: {
        current: () => ({ revision: 'catalog', facts: {} }),
        editor: () => undefined,
        capabilities: () => [
          {
            effect: 'configuration-draft',
            descriptor: { code: 'rules.select-module', description: '', inputSchema: {} },
            parseInput: (v) => v,
            execute: vi.fn(),
          },
        ],
      },
    },
    slots: { default: () => h(Probe) },
  });
  await nextTick();
  await host!.registry.invoke(
    { id: 'start', code: 'configuration.start-task', input: { goal: '管理联系信息' } },
    host!.registry.snapshot()!.token,
  );
  const before = host!.registry.snapshot()!;
  const workspace = before.context.facts.workspace as { constructionPlan: Record<string, unknown> };
  expect(before.context.facts.workspace).toMatchObject({
    configurationBoundary: {
      state: 'SELECT_CONFIGURATION_TARGET_REQUIRED',
      appliesTo: 'selected-module-metadata-page-and-rule-candidates',
      draftEditingAvailable: false,
    },
  });
  const entries = await host!.registry.invoke(
    { id: 'find', code: 'workbench.find-menu', input: { query: '模块管理' } },
    before.token,
  );
  expect(entries.value).toEqual([
    expect.objectContaining({ menuId: 'modules', moduleAlias: 'platform.module' }),
  ]);
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

it('projects the selected editor kind and continues a confirmed configuration without any plan binding', async () => {
  let host: AssistantSurfaceHost | undefined;
  const Probe = defineComponent({
    setup() {
      host = useAssistantSurfaceHost();
      return () => h('div');
    },
  });
  const save = vi.fn(async () => ({ title: '已保存', lines: [] }));
  const wrapper = mount(Workbench, {
    props: {
      startup: {
        session: { currentUser: { userId: 'builder', system: true } },
        menus: [],
        tabs: [{ key: 'page', instanceKey: 'page-instance', title: '配置' }],
        activeTabKey: 'page',
      },
      assistantRequestTurn: vi.fn(),
      assistantWorkspaceContribution: {
        current: () => ({ revision: 'catalog', facts: {} }),
        editor: () => ({
          kind: 'rules',
          openingCapability: 'rules.open-editor',
          moduleAlias: 'work.customer',
          title: '客户规则',
          visible: true,
          hasUnsavedChanges: true,
        }),
        capabilities: () => [
          {
            effect: 'read',
            descriptor: { code: 'rules.prepare-apply', description: '', inputSchema: {} },
            parseInput: (input) => input,
            execute: vi.fn(async () => ({})),
            propose: () => ({
              presentation: { title: '确认规则', lines: ['优惠系数在0到1之间'] },
              lookup: async () => undefined,
              confirmLabel: '确认',
              expiresAt: Date.now() + 60_000,
              isCurrent: () => true,
              execute: save,
            }),
          },
        ],
      },
    },
    slots: { default: () => h(Probe) },
  });
  await nextTick();
  await host!.registry.invoke(
    { id: 'task', code: 'configuration.start-task', input: { goal: '完成完整应用' } },
    host!.registry.snapshot()!.token,
  );
  expect(host!.registry.snapshot()!.context.facts.workspace).toMatchObject({
    configurationEditor: { kind: 'rules', openingCapability: 'rules.open-editor', visible: true },
    configurationBoundary: { editorKind: 'rules', openingCapability: 'rules.open-editor', state: 'READY' },
  });
  const prepared = await host!.registry.invoke(
    { id: 'prepare', code: 'rules.prepare-apply', input: {} },
    host!.registry.snapshot()!.token,
  );
  expect(save).not.toHaveBeenCalled();
  await prepared.confirmation!.confirm();
  expect(save).toHaveBeenCalledOnce();
  expect(prepared.confirmation!.takeContinuation()).toContain('用户已明确目标中的剩余事项');
  expect(prepared.confirmation!.takeContinuation()).toBeUndefined();
  wrapper.unmount();
});

it.each([
  ['metadata', 'configuration.open-metadata-editor'],
  ['page', 'configuration.open-page-editor'],
  ['rules', 'rules.open-editor'],
] as const)(
  'declares the selected %s editor opening schema within the existing core budget',
  async (kind, code) => {
    let host: AssistantSurfaceHost | undefined;
    const editor = ref<AssistantConfigurationEditor>({
      kind,
      openingCapability: code,
      moduleAlias: 'work.customer',
      title: '客户配置',
      visible: false,
      hasUnsavedChanges: false,
    });
    const available = ref(true);
    const open = vi.fn(async (_input: unknown, context: Parameters<AssistantCapability['execute']>[1]) =>
      context.applyEffect(() => {
        editor.value = { ...editor.value, visible: true };
        return { opened: true, saved: false };
      }),
    );
    const openingSchema = { type: 'object', additionalProperties: false, properties: {} };
    const Probe = defineComponent({
      setup() {
        host = useAssistantSurfaceHost();
        return () => h('div');
      },
    });
    const wrapper = mount(Workbench, {
      props: {
        startup: {
          session: { currentUser: { userId: 'builder', system: true } },
          menus: [],
          tabs: [{ key: 'page', instanceKey: 'page-instance', title: '模块管理' }],
          activeTabKey: 'page',
        },
        assistantRequestTurn: vi.fn(),
        assistantWorkspaceContribution: {
          current: () => ({ revision: JSON.stringify([editor.value, available.value]), facts: {} }),
          editor: () => editor.value,
          capabilities: () => [
            ...Array.from({ length: 16 }, (_, index) => ({
              effect: 'read' as const,
              descriptor: {
                code: `configuration.inspect-${index}`,
                description: '读取配置',
                inputSchema: {},
              },
              parseInput: (input: unknown) => input,
              execute: vi.fn(),
            })),
            ...(available.value
              ? [
                  {
                    effect: 'page' as const,
                    descriptor: { code, description: '打开当前共享编辑页', inputSchema: openingSchema },
                    parseInput: (input: unknown) => input,
                    execute: open,
                  },
                ]
              : []),
          ],
        },
      },
      slots: { default: () => h(Probe) },
    });
    await nextTick();
    const requestTurn = vi.fn<AssistantSurface['requestTurn']>(async () => ({ toolCalls: [] }));
    const unregister = host!.registry.register({
      pageInstanceKey: 'page-instance',
      contextRevision: () => 'module-page',
      surface: {
        describe: () => ({ surface: 'module-page', facts: {} }),
        capabilities: () => [
          {
            effect: 'read',
            schemaDiscovery: 'eager',
            descriptor: { code: 'form.describe', description: '当前记录', inputSchema: {} },
            parseInput: (input) => input,
            execute: vi.fn(),
          },
          ...host!.capabilities!(),
        ],
        requestTurn,
      },
    });
    host!.registry.activate('page-instance');
    const request = async (readOnly = false) => {
      await host!.registry.requestTurn(
        { message: '继续配置', results: [] },
        host!.registry.snapshot()!.token,
        undefined,
        undefined,
        { readOnly },
      );
      return requestTurn.mock.calls.at(-1)![0];
    };
    expect((await request()).capabilities.map((item) => item.code)).not.toContain(code);
    await host!.registry.invoke(
      { id: 'start', code: 'configuration.start-task', input: { goal: '配置客户信息' } },
      host!.registry.snapshot()!.token,
    );
    let input = await request();
    expect(input.context.facts.workspace).toMatchObject({
      configurationBoundary: { state: 'OPEN_SELECTED_EDITOR_REQUIRED' },
    });
    expect(input.capabilities.find((item) => item.code === code)?.inputSchema).toEqual(openingSchema);
    expect(input.capabilities).toHaveLength(4); // Loader plus the existing three core definitions.
    expect(input.capabilities.map((item) => item.code)).toContain('form.describe');
    expect(input.capabilities.map((item) => item.code)).not.toContain('workbench.open-menu');
    expect(input.context.facts.capabilityIndex).toEqual(
      expect.arrayContaining([expect.objectContaining({ code: 'workbench.open-menu' })]),
    );
    expect(open).not.toHaveBeenCalled();
    expect((await request(true)).capabilities.map((item) => item.code)).not.toContain(code);
    await host!.registry.invoke({ id: 'open', code, input: {} }, host!.registry.snapshot()!.token);
    expect(open).toHaveBeenCalledOnce();
    input = await request();
    expect(input.context.facts.workspace).toMatchObject({ configurationBoundary: { state: 'READY' } });
    expect(input.capabilities.map((item) => item.code)).not.toContain(code);
    editor.value = { ...editor.value, visible: false };
    available.value = false;
    input = await request();
    expect(input.capabilities.map((item) => item.code)).not.toContain(code);
    await expect(
      host!.registry.invoke({ id: 'unavailable', code, input: {} }, host!.registry.snapshot()!.token),
    ).rejects.toThrow();
    expect(open).toHaveBeenCalledOnce();
    unregister();
    wrapper.unmount();
  },
);
