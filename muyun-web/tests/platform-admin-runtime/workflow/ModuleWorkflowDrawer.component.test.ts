import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref, KeepAlive } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ModuleContextProvider, type ModuleContext, type HttpRequestOptions } from '@muyun/web-core';
import type { WorkflowDefinition } from '@muyun/web-contracts';
import { provideWorkbenchNavigation, createWorkspaceViewRegistry } from '@muyun/platform-workbench';
import { UiSidePanelHost, confirmAction } from '@muyun/vue-ui-antdv';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
import ModuleWorkflowDrawer from '@/platform-admin-runtime/workflow/ModuleWorkflowDrawer.vue';
import RecordDetailLayout from '@/platform-components/RecordDetailLayout.vue';
import DrawerTitleActions from '@/platform-components/DrawerTitleActions.vue';
import { useModulePageDetailExtensionRuntime } from '@/dynamic-page-runtime/composables/useModulePageDetailExtensionRuntime';
import { workflowConfigurationWorkspaceView } from '@/views/workflowConfigurationWorkspaceView';

vi.mock('@muyun/vue-ui-antdv', async (original) => ({
  ...(await original<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));
afterEach(() => vi.unstubAllGlobals());
const definition = {
  id: 'a',
  version: 1,
  alias: 'purchase',
  title: '采购审批',
  definitionStatus: 'draft',
  approvalEnabled: true,
} satisfies WorkflowDefinition;
function setup(
  options: {
    records?: WorkflowDefinition[];
    tenantId?: string;
    query?: () => Promise<unknown>;
    can?: (action: string) => boolean;
  } = {},
) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockReturnValue({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  );
  let records = options.records ?? [];
  const request = vi.fn(async (call: HttpRequestOptions): Promise<unknown> => {
    if (call.path.endsWith('/context') || call.path.endsWith('/view-context'))
      return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
    if (call.path.endsWith('/query')) return options.query ? options.query() : { records };
    if (call.path.endsWith('/insert')) {
      const saved = { ...definition, alias: null, ...(call.body as object) };
      records = [...records, saved];
      return saved;
    }
    if (call.path.endsWith('/selection')) {
      const saved = { ...records[0], ...(call.body as object), version: 2 };
      records = [saved as WorkflowDefinition];
      return saved;
    }
    if (call.path.includes('/sort/')) {
      records = [...records].reverse().map((item) => ({ ...item, version: 2 }));
      return 1;
    }
    if (call.path.endsWith('/enable') || call.path.endsWith('/disable') || call.path.endsWith('/archive'))
      return {
        ...records[0],
        version: 2,
        definitionStatus: call.path.endsWith('/enable')
          ? 'published'
          : call.path.endsWith('/disable')
            ? 'disabled'
            : 'archived',
      };
    throw new Error(call.path);
  });
  const context = {
    moduleAlias: 'platform.workflow.definition',
    can: options.can ?? (() => true),
    http: { request },
  } as unknown as ModuleContext<Record<string, unknown>>;
  const openPage = vi.fn();
  let runtime!: ReturnType<typeof useModulePageDetailExtensionRuntime>;
  const refreshDetail = vi.fn();
  const Harness = defineComponent({
    setup() {
      provideCurrentUserContext(
        ref({ userId: 'admin', tenantId: options.tenantId, system: !options.tenantId }),
      );
      provideWorkbenchNavigation({ openPage } as never);
      runtime = useModulePageDetailExtensionRuntime({
        module: context,
        scope: () => undefined,
        refreshList: () => {},
        reload: () => {},
        closeDetail: () => {},
      });
      runtime.openDrawer(
        { title: '审批流程', component: ModuleWorkflowDrawer },
        { alias: 'demo.purchase', title: '采购' },
      );
      runtime.drawer.value!.context.refreshDetailExtensions = refreshDetail;
      return () =>
        h(UiSidePanelHost, {}, () =>
          h(ModuleContextProvider, { context }, () =>
            h(
              RecordDetailLayout,
              { title: '审批流程', surface: 'drawer', scrollableContent: true },
              {
                default: () => h(ModuleWorkflowDrawer, { context: runtime.drawer.value!.context }),
                ...(runtime.drawer.value!.operation
                  ? {
                      operation: () =>
                        h(DrawerTitleActions, { actions: runtime.drawer.value!.operation!.actions }),
                    }
                  : {}),
              },
            ),
          ),
        );
    },
  });
  const active = ref(true);
  const Root = defineComponent({
    setup: () => () =>
      h(KeepAlive, {}, () => (active.value ? h(Harness) : h(defineComponent({ render: () => h('span') })))),
  });
  const wrapper = mount(Root, {
    global: { stubs: { RecordPicker: true, FormulaExpressionEditor: true } },
  });
  const button = (name: string) =>
    wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((item) => item.text().replace(/\s/g, '') === name || item.props('ariaLabel') === name)!;
  const input = (name: string) =>
    wrapper.findAllComponents({ name: 'UiInput' }).find((item) => item.props('ariaLabel') === name)!;
  const list = () => wrapper.findComponent({ name: 'UiDataTable' });
  return { wrapper, runtime, request, openPage, button, input, list, refreshDetail, active };
}

describe('module workflow management drawer', () => {
  it('lists definitions without loading canvases or navigating, and uses one flow for all module kinds', async () => {
    const t = setup({ records: [definition] });
    try {
      await flushPromises();
      expect(t.list().props('rows')).toEqual([definition]);
      expect(t.wrapper.findComponent({ name: 'WorkflowDiagram' }).exists()).toBe(false);
      expect(
        t.request.mock.calls.some(([call]) => /versions|design|configuration-catalog/.test(call.path)),
      ).toBe(false);
      expect(t.runtime.drawer.value!.operation).toBeUndefined();
      expect(t.openPage).not.toHaveBeenCalled();
    } finally {
      t.wrapper.unmount();
    }
  });
  it('uses standard columns and inline status controls, and filters without changing priority', async () => {
    const t = setup({
      records: [
        definition,
        {
          ...definition,
          id: 'other',
          title: '报销审批',
          currentVersionNo: 2,
          definitionStatus: 'disabled',
          defaultDefinition: true,
        },
      ],
    });
    try {
      await flushPromises();
      expect(
        t
          .list()
          .props('columns')
          .map((column: { title: string }) => column.title),
      ).toEqual(['流程名称', '流程状态', '发布版本', '匹配条件', '启用状态']);
      expect(t.list().props('showActionColumn')).toBe(true);
      expect(t.wrapper.findComponent({ name: 'RecordListExplorer' }).exists()).toBe(false);
      expect(
        t.wrapper.findAllComponents({ name: 'UiSwitch' }).map((control) => control.props('disabled')),
      ).toEqual([true, false]);
      expect(t.wrapper.text()).toContain('v2');
      expect(t.wrapper.text()).toContain('兜底流程');
      t.wrapper.findComponent({ name: 'UiSearchInput' }).vm.$emit('update:value', '报销');
      await flushPromises();
      expect(
        t
          .list()
          .props('rows')
          .map((row: { id: string }) => row.id),
      ).toEqual(['other']);
      expect(t.wrapper.findComponent({ name: 'RecordPanelButton' }).props('disabled')).toBe(true);
      t.list().vm.$emit('rowDrop', { source: definition, target: { id: 'other' }, position: 'before' });
      await flushPromises();
      expect(t.request.mock.calls.some(([call]) => call.path.includes('/sort/'))).toBe(false);
    } finally {
      t.wrapper.unmount();
    }
  });
  it('keeps draft activation and unauthorized status changes blocked', async () => {
    const t = setup({ records: [definition], can: (action) => action !== 'archiveWorkflowDefinition' });
    try {
      await flushPromises();
      vi.mocked(confirmAction).mockClear();
      t.wrapper.findComponent({ name: 'UiSwitch' }).vm.$emit('change', true);
      await t.button('归档').trigger('click');
      await flushPromises();
      expect(t.button('归档').props('disabled')).toBe(true);
      expect(vi.mocked(confirmAction)).not.toHaveBeenCalled();
      expect(t.request.mock.calls.some(([call]) => /\/(enable|archive)$/.test(call.path))).toBe(false);
    } finally {
      t.wrapper.unmount();
    }
  });
  it('rejects reordering between different tenant partitions', async () => {
    const t = setup({ records: [definition, { ...definition, id: 'other', tenantId: 'tenant-b' }] });
    try {
      await flushPromises();
      await t.wrapper.findComponent({ name: 'RecordPanelButton' }).trigger('click');
      t.list().vm.$emit('rowDrop', {
        source: definition,
        target: { id: 'other', tenantId: 'tenant-b' },
        position: 'after',
      });
      await flushPromises();
      expect(t.request.mock.calls.some(([call]) => call.path.includes('/sort/'))).toBe(false);
    } finally {
      t.wrapper.unmount();
    }
  });
  it('opens a separate standard editor while preserving the list and search on cancellation', async () => {
    const t = setup({ records: [definition] });
    try {
      await flushPromises();
      const table = t.list().element;
      t.wrapper.findComponent({ name: 'UiSearchInput' }).vm.$emit('update:value', '采购');
      await t.button('新建流程').trigger('click');
      await flushPromises();
      const editor = t.wrapper.findComponent({ name: 'RecordDetailDrawer' });
      expect(editor.props()).toMatchObject({ title: '新建流程', width: 'standard', open: true });
      expect(t.list().element).toBe(table);
      expect(t.list().props('rows')).toEqual([definition]);
      expect(t.runtime.drawer.value!.subtitle).toBe('采购');
      expect(t.runtime.drawer.value!.operation).toBeUndefined();
      await t.button('关闭').trigger('click');
      await flushPromises();
      expect(t.wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);
      expect(t.wrapper.findComponent({ name: 'UiSearchInput' }).props('value')).toBe('采购');
      expect(t.list().element).toBe(table);
      expect(t.runtime.drawerOpen.value).toBe(true);
    } finally {
      t.wrapper.unmount();
    }
  });
  it('guards the child close button and keeps both drawers blocked while saving', async () => {
    const t = setup({ records: [definition] });
    let finish!: (record: unknown) => void;
    try {
      await flushPromises();
      await t.button('新建流程').trigger('click');
      t.input('流程名称').vm.$emit('update:value', '新增草稿');
      await flushPromises();
      vi.mocked(confirmAction).mockResolvedValueOnce(false);
      await t.button('关闭').trigger('click');
      await flushPromises();
      expect(t.input('流程名称').props('value')).toBe('新增草稿');
      const original = t.request.getMockImplementation()!;
      t.request.mockImplementation((call) =>
        call.path.endsWith('/insert')
          ? new Promise((resolve) => {
              finish = resolve;
            })
          : original(call),
      );
      await t.button('创建流程草稿').trigger('click');
      await flushPromises();
      expect(t.runtime.drawer.value!.closeBlocked).toBe(true);
      await t.button('关闭').trigger('click');
      await t.wrapper.get('form').trigger('submit');
      expect(t.request.mock.calls.filter(([call]) => call.path.endsWith('/insert'))).toHaveLength(1);
      expect(t.wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(true);
      finish({ ...definition, id: 'created', title: '新增草稿' });
      await flushPromises();
      expect(t.wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);
      expect(t.list().props('rows')).toHaveLength(2);
      expect(t.runtime.drawerOpen.value).toBe(true);
      expect(t.runtime.drawer.value!.closeBlocked).toBe(false);
    } finally {
      t.wrapper.unmount();
    }
  });
  it('validates a global draft and creates its definition once without creating a version', async () => {
    const t = setup();
    try {
      await flushPromises();
      await t.button('新建流程').trigger('click');
      expect(t.wrapper.findComponent({ name: 'RecordPicker' }).exists()).toBe(false);
      expect(t.wrapper.text()).toContain('当前为全局流程模板');
      expect(
        t.wrapper
          .findAllComponents({ name: 'UiInput' })
          .some((item) => item.props('ariaLabel') === '流程编码'),
      ).toBe(false);
      expect(t.wrapper.text()).not.toContain('流程编码');
      await flushPromises();
      expect(t.button('创建流程草稿').props('disabled')).toBe(true);
      await t.wrapper.get('form').trigger('submit');
      expect(t.request.mock.calls.some(([call]) => call.path.endsWith('/insert'))).toBe(false);
      t.input('流程名称').vm.$emit('update:value', '采购审批');
      await flushPromises();
      expect(t.button('创建流程草稿').element.closest('.record-detail-layout-operation')).not.toBeNull();
      expect(t.runtime.drawer.value!.titleActions).toEqual([]);
      await t.button('创建流程草稿').trigger('click');
      await flushPromises();
      expect(t.request.mock.calls.find(([call]) => call.path.endsWith('/insert'))![0].body).toMatchObject({
        title: '采购审批',
        approvalEnabled: true,
        organizationId: null,
      });
      expect(
        t.request.mock.calls.find(([call]) => call.path.endsWith('/insert'))![0].body,
      ).not.toHaveProperty('alias');
      expect(t.request.mock.calls.filter(([call]) => call.path.endsWith('/insert'))).toHaveLength(1);
      expect(t.request.mock.calls.some(([call]) => call.path.endsWith('/upgrade'))).toBe(false);
      expect(t.list().props('rows')).toHaveLength(1);
      expect(t.list().props('rows')[0].alias).toBeNull();
      expect(t.runtime.drawer.value!.closeBlocked).toBe(false);
      expect(t.openPage).not.toHaveBeenCalled();
    } finally {
      t.wrapper.unmount();
    }
  });
  it.each(['title', 'approval'])(
    'protects a draft when only %s changes and confirms cancellation',
    async (field) => {
      const t = setup({ tenantId: 'tenant-a' });
      try {
        await flushPromises();
        await t.button('新建流程').trigger('click');
        expect(t.wrapper.findComponent({ name: 'RecordPicker' }).props('context').moduleAlias).toBe(
          'iam.organization',
        );
        if (field === 'title') t.input('流程名称').vm.$emit('update:value', '采购审批');
        else t.wrapper.findAllComponents({ name: 'UiCheckbox' })[0].vm.$emit('update:checked', false);
        await flushPromises();
        expect(t.runtime.drawer.value!.beforeClose).toBeTypeOf('function');
        vi.mocked(confirmAction).mockResolvedValueOnce(false);
        t.runtime.closeDrawer();
        await flushPromises();
        expect(t.runtime.drawerOpen.value).toBe(true);
        vi.mocked(confirmAction).mockResolvedValueOnce(false);
        await t.button('取消').trigger('click');
        await flushPromises();
        expect(t.wrapper.findComponent({ name: 'WorkflowDefinitionSettings' }).exists()).toBe(true);
        vi.mocked(confirmAction).mockResolvedValueOnce(true);
        await t.button('取消').trigger('click');
        await flushPromises();
        expect(t.list().exists()).toBe(true);
        expect(t.runtime.drawer.value!.closeBlocked).toBe(false);
      } finally {
        t.wrapper.unmount();
      }
    },
  );
  it('keeps input after save failure, then returns to the list on successful retry', async () => {
    const t = setup();
    try {
      await flushPromises();
      await t.button('新建流程').trigger('click');
      t.input('流程名称').vm.$emit('update:value', '采购审批');
      await flushPromises();
      const original = t.request.getMockImplementation()!;
      t.request.mockImplementationOnce(async () => {
        throw new Error('保存失败');
      });
      await t.button('创建流程草稿').trigger('click');
      await flushPromises();
      expect(t.input('流程名称').props('value')).toBe('采购审批');
      expect(t.wrapper.findComponent({ name: 'UiError' }).exists()).toBe(true);
      t.request.mockImplementation(original);
      await t.button('创建流程草稿').trigger('click');
      await flushPromises();
      expect(t.list().props('rows')).toHaveLength(1);
    } finally {
      t.wrapper.unmount();
    }
  });
  it('starts creation with fresh matching settings after editing an existing definition', async () => {
    const t = setup({
      records: [{ ...definition, organizationId: 'org-a', matchExpression: 'true', defaultDefinition: true }],
      tenantId: 'tenant-a',
    });
    try {
      await flushPromises();
      await t.button('设置').trigger('click');
      await flushPromises();
      await t.button('取消').trigger('click');
      await flushPromises();
      await t.button('新建流程').trigger('click');
      expect(t.input('流程名称').props('value')).toBe('');
      const fields = t.wrapper.findComponent({ name: 'WorkflowSelectionFields' });
      expect(fields.props('organizationId')).toBe('');
      expect(fields.props('matchExpression')).toBe('');
      expect(fields.props('fallback')).toBe(false);
    } finally {
      t.wrapper.unmount();
    }
  });
  it('saves matching rules with the latest version obtained after sorting', async () => {
    const second = { ...definition, id: 'b', alias: 'second' };
    const t = setup({ records: [definition, second] });
    try {
      await flushPromises();
      await t.wrapper.findComponent({ name: 'RecordPanelButton' }).trigger('click');
      await flushPromises();
      t.list().vm.$emit('rowDrop', { source: second, target: definition, position: 'before' });
      await flushPromises();
      expect(t.request.mock.calls.find(([call]) => call.path.endsWith('/sort/b'))![0].body).toEqual({
        nextId: 'a',
      });
      await t.button('设置').trigger('click');
      await flushPromises();
      const fields = t.wrapper.findComponent({ name: 'WorkflowSelectionFields' });
      expect(fields.findComponent({ name: 'FormulaExpressionEditor' }).props('tools')).toBeDefined();
      fields.vm.$emit('update:matchExpression', '{amount} > 100');
      await flushPromises();
      await t.button('保存').trigger('click');
      await flushPromises();
      expect(t.request.mock.calls.find(([call]) => call.path.endsWith('/selection'))![0].body).toMatchObject({
        version: 2,
        matchExpression: '{amount} > 100',
      });
    } finally {
      t.wrapper.unmount();
    }
  });
  it('opens only the requested definition in a restorable independent design tab', async () => {
    const t = setup({ records: [definition] });
    try {
      await flushPromises();
      await t.button('编排').trigger('click');
      await flushPromises();
      expect(t.openPage).toHaveBeenCalledOnce();
      expect(
        createWorkspaceViewRegistry([workflowConfigurationWorkspaceView]).resolve(
          t.openPage.mock.calls[0][0],
        ),
      ).toMatchObject({
        input: { moduleAlias: 'demo.purchase', definitionId: 'a', definitionTitle: '采购审批' },
      });
      expect(t.runtime.drawerOpen.value).toBe(true);
      expect(workflowConfigurationWorkspaceView.parse({ moduleAlias: 'demo.purchase' })).toBeUndefined();
      expect(
        workflowConfigurationWorkspaceView.titleOf({
          moduleAlias: 'demo.purchase',
          definitionId: 'a',
          definitionTitle: '采购审批',
        }),
      ).toBe('流程编排：采购审批');
    } finally {
      t.wrapper.unmount();
    }
  });
  it('reloads published state on returning from design without overwriting an unsaved settings form', async () => {
    const query = vi
      .fn()
      .mockResolvedValueOnce({ records: [definition] })
      .mockResolvedValue({ records: [{ ...definition, definitionStatus: 'enabled', version: 2 }] });
    const t = setup({ query });
    try {
      await flushPromises();
      t.active.value = false;
      await flushPromises();
      t.active.value = true;
      await flushPromises();
      expect(t.list().props('rows')[0].definitionStatus).toBe('enabled');
      await t.button('设置').trigger('click');
      await flushPromises();
      t.input('流程名称').vm.$emit('update:value', '尚未保存');
      await flushPromises();
      const count = query.mock.calls.length;
      t.active.value = false;
      await flushPromises();
      t.active.value = true;
      await flushPromises();
      expect(t.input('流程名称').props('value')).toBe('尚未保存');
      expect(query).toHaveBeenCalledTimes(count);
    } finally {
      t.wrapper.unmount();
    }
  });

  it('distinguishes a failed list request from empty results and supports retry', async () => {
    const query = vi.fn().mockRejectedValueOnce(new Error('列表读取失败')).mockResolvedValue({ records: [] });
    const t = setup({ query });
    try {
      await flushPromises();
      expect(t.list().exists()).toBe(false);
      expect(t.wrapper.findComponent({ name: 'UiError' }).exists()).toBe(true);
      await t.button('重新加载').trigger('click');
      await flushPromises();
      expect(t.list().exists()).toBe(true);
      expect(t.list().props('rows')).toEqual([]);
    } finally {
      t.wrapper.unmount();
    }
  });
  it.each(['enable', 'disable', 'archive'])(
    'updates the definition after %s without opening the design',
    async (command) => {
      const t = setup({
        records: [
          {
            ...definition,
            currentVersionNo: 1,
            definitionStatus: command === 'enable' ? 'disabled' : 'published',
          },
        ],
      });
      try {
        await flushPromises();
        vi.mocked(confirmAction).mockResolvedValueOnce(true);
        if (command === 'archive') await t.button('归档').trigger('click');
        else t.wrapper.findComponent({ name: 'UiSwitch' }).vm.$emit('change', command === 'enable');
        await flushPromises();
        expect(t.request.mock.calls.find(([call]) => call.path.endsWith('/' + command))![0].body).toEqual({
          version: 1,
        });
        expect(t.list().props('rows')[0].version).toBe(2);
        expect(t.openPage).not.toHaveBeenCalled();
      } finally {
        t.wrapper.unmount();
      }
    },
  );
});

it('requires and submits an explicit module action code for non-approval workflow creation', async () => {
  const t = setup();
  try {
    await flushPromises();
    await t.button('新建流程').trigger('click');
    t.input('流程名称').vm.$emit('update:value', '数据同步');
    t.wrapper.findComponent({ name: 'WorkflowDefinitionSettings' }).vm.$emit('update:approvalEnabled', false);
    await flushPromises();
    expect(t.input('模块动作编码').exists()).toBe(true);
    expect(t.button('创建流程草稿').props('disabled')).toBe(true);
    await t.wrapper.get('form').trigger('submit');
    expect(t.request.mock.calls.some(([call]) => call.path.endsWith('/insert'))).toBe(false);
    t.input('模块动作编码').vm.$emit('update:value', 'Invalid action');
    await flushPromises();
    expect(t.button('创建流程草稿').props('disabled')).toBe(true);
    t.input('模块动作编码').vm.$emit('update:value', 'syncData');
    await flushPromises();
    await t.button('创建流程草稿').trigger('click');
    await flushPromises();
    expect(t.request.mock.calls.find(([call]) => call.path.endsWith('/insert'))![0].body).toMatchObject({
      approvalEnabled: false,
      actionCode: 'syncData',
      title: '数据同步',
    });
  } finally {
    t.wrapper.unmount();
  }
});

it.each([false, true])(
  'repairs draft action identity and locks it after first publication: published=%s',
  async (published) => {
    const t = setup({
      records: [
        {
          ...definition,
          approvalEnabled: false,
          actionCode: published ? 'syncData' : null,
          currentVersionNo: published ? 1 : undefined,
          definitionStatus: published ? 'published' : 'draft',
        },
      ],
    });
    try {
      await flushPromises();
      await t.button('设置').trigger('click');
      await flushPromises();
      expect(t.input('模块动作编码').props('disabled')).toBe(published);
      if (!published) {
        t.input('模块动作编码').vm.$emit('update:value', 'syncData');
        await flushPromises();
        await t.button('保存').trigger('click');
        await flushPromises();
        expect(
          t.request.mock.calls.find(([call]) => call.path.endsWith('/selection'))![0].body,
        ).toMatchObject({ actionCode: 'syncData' });
      }
    } finally {
      t.wrapper.unmount();
    }
  },
);
