import { appDataChangeDispatcher } from '@/platform-admin-runtime/realtime';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  createLanguageContext,
  provideLanguageContext,
  ModuleContextProvider,
  type ModuleContext,
  type HttpRequestOptions,
} from '@muyun/web-core';
import type { CurrentUser } from '@muyun/web-contracts';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
import WorkflowConfigurationView from '@/views/WorkflowConfigurationView.vue';
import { confirmAction } from '@muyun/vue-ui-antdv';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));

it('keeps pending saves as disabled drafts and retains their optimistic lock version after switching versions', async () => {
  const base = '/platform.module/demo.purchase/workflow-definitions';
  const definition = { id: 'definition', alias: 'purchase', title: '采购审批' };
  let storedVersion = 1;
  let finishSave!: () => void;
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/context') || options.path.endsWith('/view-context'))
      return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
    if (options.path === `${base}/query`) return { records: [definition] };
    if (options.path.endsWith('/configuration-catalog'))
      return { tasks: [], queries: [], generations: [], associations: [] };
    if (options.path.endsWith('/versions/query'))
      return {
        records: [
          { id: 'draft', version: storedVersion, versionNo: 2, publishStatus: 'draft' },
          { id: 'published', version: 3, versionNo: 1, publishStatus: 'published' },
        ],
      };
    if (options.path.endsWith('/design') && options.method === 'POST') {
      expect(options.body).toMatchObject({ version: storedVersion });
      await new Promise<void>((resolve) => (finishSave = resolve));
      return { id: 'draft', version: ++storedVersion, versionNo: 2, publishStatus: 'draft' };
    }
    if (options.path.endsWith('/design'))
      return { nodes: [{ nodeKey: 'approval', nodeType: 'approval', title: '审批' }], links: [] };
    throw new Error(`unexpected request: ${options.path}`);
  });
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const Harness = defineComponent({
    setup: () => () =>
      h(ModuleContextProvider, { context }, () =>
        h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase' }),
      ),
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        WorkflowDiagram: true,
        WorkflowParticipantEditor: true,
        WorkflowBusinessTaskEditor: true,
        RecordListExplorer: true,
        RecordDetailDrawer: true,
        AdaptiveHeaderActionBar: true,
        RecordPicker: true,
      },
    },
  });
  await flushPromises();
  wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('select', definition);
  await flushPromises();
  const versionSelector = wrapper
    .findAllComponents({ name: 'UiSelect' })
    .find((item) => item.props('value') === 'draft')!;
  const actions = wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' });
  for (let round = 0; round < 2; round++) {
    wrapper.findComponent({ name: 'WorkflowDiagram' }).vm.$emit('layout-change', `layout-${round}`);
    await flushPromises();
    actions.vm.$emit('action', { key: 'save' });
    await flushPromises();
    expect(versionSelector.props('value')).toBe('draft');
    expect(versionSelector.props('options')).toContainEqual(
      expect.objectContaining({ value: 'draft', label: expect.stringContaining('草稿') }),
    );
    expect(versionSelector.props('disabled')).toBe(true);
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('editable')).toBe(false);
    expect(wrapper.text()).toContain('草稿有未保存的修改');
    expect(wrapper.text()).not.toContain('此版本已冻结');
    finishSave();
    await flushPromises();
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('editable')).toBe(true);
    if (round === 0) {
      versionSelector.vm.$emit('update:value', 'published');
      await flushPromises();
      expect(wrapper.text()).toContain('此版本已冻结');
      versionSelector.vm.$emit('update:value', 'draft');
      await flushPromises();
    }
  }
  expect(storedVersion).toBe(3);
  expect(
    request.mock.calls.filter(([options]) => options.path.endsWith('/design') && options.method === 'POST'),
  ).toHaveLength(2);
});

it('retains association choices in the read-only business task publication review', async () => {
  const definition = { id: 'definition', title: '采购交付' };
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/context') || options.path.endsWith('/view-context'))
      return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
    if (options.path.endsWith('/configuration-catalog'))
      return {
        tasks: [],
        queries: [],
        generations: [],
        associations: [{ id: 'delivery-lines', title: '到货明细' }],
      };
    if (options.path.endsWith('/versions/query'))
      return { records: [{ id: 'draft', version: 1, versionNo: 1, publishStatus: 'draft' }] };
    if (options.path.endsWith('/design'))
      return { nodes: [{ nodeKey: 'delivery', nodeType: 'task', title: '到货办理' }], links: [] };
    if (options.path.endsWith('/query')) return { records: [definition] };
    throw new Error(`unexpected request: ${options.path}`);
  });
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(ModuleContextProvider, {
    props: { context },
    slots: { default: () => h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase' }) },
    global: {
      stubs: {
        WorkflowDiagram: true,
        WorkflowParticipantEditor: true,
        WorkflowBusinessTaskEditor: true,
        RecordListExplorer: true,
        RecordDetailDrawer: { template: '<section><slot /></section>' },
        AdaptiveHeaderActionBar: true,
        RecordPicker: true,
      },
    },
  });
  await flushPromises();
  wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('select', definition);
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'publish' });
  await flushPromises();
  const taskReview = wrapper
    .findAllComponents({ name: 'WorkflowBusinessTaskEditor' })
    .find((item) => item.props('disabled'))!;
  expect(taskReview.props('disabled')).toBe(true);
  expect(taskReview.props('associations')).toEqual([{ value: 'delivery-lines', label: '到货明细' }]);
  wrapper.unmount();
});

it('publishes a supported participant rule whose type defaults to USER', async () => {
  const definition = { id: 'definition', title: '审批', version: 1 };
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/context') || options.path.endsWith('/view-context'))
      return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
    if (options.path.endsWith('/configuration-catalog'))
      return { tasks: [], queries: [], generations: [], associations: [] };
    if (options.path.endsWith('/versions/query'))
      return { records: [{ id: 'draft', version: 1, versionNo: 1, publishStatus: 'draft' }] };
    if (options.path.endsWith('/design'))
      return {
        nodes: [
          {
            nodeKey: 'approval',
            nodeType: 'approval',
            title: '审批',
            participantPolicyText: '{"rules":[{"ids":["alice"]}]}',
          },
        ],
        links: [],
      };
    if (options.path.endsWith('/publish'))
      return { id: 'draft', version: 2, versionNo: 1, publishStatus: 'published' };
    if (options.path.endsWith('/query')) return { records: [definition] };
    if (options.path.endsWith('/definition')) return definition;
    throw new Error(`unexpected request: ${options.path}`);
  });
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(ModuleContextProvider, {
    props: { context },
    slots: { default: () => h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase' }) },
    global: {
      stubs: {
        WorkflowDiagram: true,
        WorkflowParticipantEditor: true,
        WorkflowBusinessTaskEditor: true,
        RecordListExplorer: true,
        RecordDetailDrawer: { template: '<section><slot/><slot name="operation"/></section>' },
        AdaptiveHeaderActionBar: true,
        RecordPicker: true,
      },
    },
  });
  await flushPromises();
  wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('select', definition);
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'publish' });
  await flushPromises();
  const confirm = wrapper
    .findAllComponents({ name: 'UiButton' })
    .find((button) => button.text() === '确认发布')!;
  await confirm.trigger('click');
  await flushPromises();
  expect(request.mock.calls.some(([options]) => options.path.endsWith('/publish'))).toBe(true);
  wrapper.unmount();
});

it('refreshes translated field and action candidates without changing a workflow draft or semantic field names', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const definition = { id: 'definition', title: '用户流程原名' };
  let label = 'Participant field';
  let actionLabel = 'Receive';
  const binding = (id: string, originalText: string, text: string) => ({
    source: { sourceKind: 'CONFIG', sourceId: id, sourceVersion: 'v1', originalText },
    languages: { en: { text, requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false } },
  });
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/context') || options.path.endsWith('/view-context'))
      return {
        moduleAlias: 'demo.purchase',
        capabilities: [],
        actions: [{ actionCode: 'receive', title: '接收原文', authorized: true }],
        uiDescriptor: {
          moduleAlias: 'demo.purchase',
          defaultEditor: {
            viewKind: 'FORM',
            fields: [
              {
                fieldRef: { fieldName: 'participantId' },
                label: '人员原文',
                valueType: 'REFERENCE',
                reference: { targetModuleAlias: 'iam.user', cardinality: 'ONE' },
              },
            ],
          },
        },
        textBindings: {
          '/uiDescriptor/defaultEditor/fields/0/label': binding('participant-label', '人员原文', label),
          '/actions/0/title': binding('action-title', '接收原文', actionLabel),
        },
      };
    if (options.path.endsWith('/configuration-catalog'))
      return { tasks: [], queries: [], generations: [], associations: [] };
    if (options.path.endsWith('/versions/query'))
      return { records: [{ id: 'draft', version: 1, versionNo: 1, publishStatus: 'draft' }] };
    if (options.path.endsWith('/design'))
      return { nodes: [{ nodeKey: 'task', nodeType: 'task', title: '用户节点原名' }], links: [] };
    if (options.path.endsWith('/query')) return { records: [definition] };
    throw new Error(`unexpected request: ${options.path}`);
  });
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const Harness = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () =>
        h(ModuleContextProvider, { context }, () =>
          h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase' }),
        );
    },
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        WorkflowDiagram: true,
        WorkflowDesignProperties: true,
        RecordListExplorer: true,
        RecordDetailDrawer: { template: '<section><slot /></section>' },
        AdaptiveHeaderActionBar: true,
        RecordPicker: true,
      },
    },
  });
  try {
    await flushPromises();
    wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('select', definition);
    await flushPromises();
    const properties = wrapper.findComponent({ name: 'WorkflowDesignProperties' });
    const originalDesign = properties.props('design');
    expect(properties.props('fields')).toEqual([
      {
        name: 'participantId',
        label: 'Participant field',
        valueType: 'REFERENCE',
        referenceModule: 'iam.user',
      },
    ]);
    expect(properties.props('actions')).toEqual([{ value: 'receive', label: 'Receive' }]);
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(properties.props('fields')[0].label).toBe('人员原文');
    expect(properties.props('actions')).toEqual([{ value: 'receive', label: '接收原文' }]);
    language.setLanguage('en');
    await flushPromises();
    label = 'Responsible account';
    actionLabel = 'Confirm receipt';
    await appDataChangeDispatcher.dispatch({
      changeSetId: 'workflow-language-candidates',
      changes: [{ type: 'configuration-translations-changed', moduleAlias: 'demo.purchase' }],
    });
    await flushPromises();
    expect(properties.props('fields')[0]).toMatchObject({
      name: 'participantId',
      label: 'Responsible account',
    });
    expect(properties.props('actions')).toEqual([{ value: 'receive', label: 'Confirm receipt' }]);
    expect(properties.props('design')).toBe(originalDesign);
    expect(originalDesign.nodes[0].title).toBe('用户节点原名');
    expect(request.mock.calls.filter(([options]) => options.path.endsWith('/design'))).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

function mountSettings(
  tenantId?: string,
  initialDefinition?: Record<string, unknown>,
  queryDefinitions?: () => Promise<{ records: Record<string, unknown>[] }>,
) {
  const request = vi.fn<(options: HttpRequestOptions) => Promise<unknown>>(
    async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/context') || options.path.endsWith('/view-context'))
        return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
      if (options.path.endsWith('/versions/query')) return { records: [] };
      if (options.path.endsWith('/query'))
        return queryDefinitions
          ? queryDefinitions()
          : { records: initialDefinition ? [initialDefinition] : [] };
      if (options.path.endsWith('/insert'))
        return { id: 'new-definition', version: 1, ...(options.body as object) };
      if (options.path.endsWith('/configuration-catalog'))
        return { tasks: [], queries: [], generations: [], associations: [] };
      if (options.path.endsWith('/upgrade'))
        return { id: 'new-draft', version: 1, versionNo: 1, publishStatus: 'draft' };
      throw new Error(`unexpected request: ${options.path}`);
    },
  );
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const Harness = defineComponent({
    setup() {
      provideCurrentUserContext(ref({ userId: 'operator', system: !tenantId, tenantId } as CurrentUser));
      return () =>
        h(ModuleContextProvider, { context }, () =>
          h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase' }),
        );
    },
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        WorkflowDiagram: true,
        WorkflowDesignProperties: true,
        RecordListExplorer: true,
        RecordPicker: true,
        RecordDetailDrawer: true,
        AdaptiveHeaderActionBar: true,
      },
    },
  });
  const button = (text: string) =>
    wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((item) => item.text() === text || item.props('ariaLabel') === text)!;
  const input = (label: string) =>
    wrapper.findAllComponents({ name: 'UiInput' }).find((item) => item.props('ariaLabel') === label)!;
  return { wrapper, request, button, input };
}

it('keeps global templates usable without querying tenant organizations and validates creation inputs', async () => {
  const { wrapper, request, button, input } = mountSettings();
  try {
    await flushPromises();
    await button('新建流程').trigger('click');
    expect(wrapper.findComponent({ name: 'RecordPicker' }).exists()).toBe(false);
    expect(wrapper.text()).toContain('当前为全局流程模板');
    expect(request.mock.calls.some(([options]) => options.path.includes('iam.organization'))).toBe(false);
    expect(wrapper.get('details').attributes('open')).toBeUndefined();
    input('流程名称').vm.$emit('update:value', '采购审批');
    input('流程编码').vm.$emit('update:value', 'Invalid-Code');
    await flushPromises();
    expect(input('流程编码').props('invalid')).toBe(true);
    expect(button('创建流程草稿').props('disabled')).toBe(true);
    input('流程编码').vm.$emit('update:value', 'purchase');
    input('优先级').vm.$emit('update:value', '1.5');
    await flushPromises();
    expect(button('创建流程草稿').props('disabled')).toBe(true);
    await wrapper.get('form').trigger('submit');
    expect(request.mock.calls.some(([options]) => options.path.endsWith('/insert'))).toBe(false);
    input('优先级').vm.$emit('update:value', '2');
    await flushPromises();
    expect(button('创建流程草稿').props('disabled')).toBe(false);
    expect(button('创建流程草稿').element.closest('.record-detail-layout-operation')).not.toBeNull();
    await button('创建流程草稿').trigger('click');
    await flushPromises();
    expect(request.mock.calls.find(([options]) => options.path.endsWith('/insert'))?.[0].body).toMatchObject({
      alias: 'purchase',
      title: '采购审批',
      organizationId: null,
      matchPriority: 2,
      approvalEnabled: true,
    });
    expect(wrapper.text()).toContain('草稿有未保存的修改');
  } finally {
    wrapper.unmount();
  }
});

it.each(['alias', 'approval'])('protects a creation draft when only %s changes', async (field) => {
  vi.mocked(confirmAction).mockClear();
  vi.mocked(confirmAction).mockResolvedValueOnce(false);
  const { wrapper, button, input } = mountSettings('tenant-a');
  try {
    await flushPromises();
    await button('新建流程').trigger('click');
    expect(wrapper.findComponent({ name: 'RecordPicker' }).props('context').moduleAlias).toBe(
      'iam.organization',
    );
    expect(button('新建流程').props('disabled')).toBe(true);
    if (field === 'alias') input('流程编码').vm.$emit('update:value', 'purchase');
    else wrapper.findAllComponents({ name: 'UiCheckbox' })[0]!.vm.$emit('update:checked', false);
    await flushPromises();
    expect(button('新建流程').props('disabled')).toBe(true);
    expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('editing')).toBe(true);
    await button('取消新建').trigger('click');
    await flushPromises();
    expect(confirmAction).toHaveBeenCalledWith(
      expect.objectContaining({ title: '放弃未保存的流程设置？' }),
      expect.anything(),
    );
    expect(input('流程编码').exists()).toBe(true);
    vi.mocked(confirmAction).mockResolvedValueOnce(true);
    await button('取消新建').trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'UiEmpty' }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('editing')).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it('starts a new workflow with fresh matching settings after viewing an existing definition', async () => {
  const definition = {
    id: 'existing',
    alias: 'old',
    title: '已有流程',
    organizationId: 'org-a',
    matchExpression: 'true',
    matchPriority: 9,
    defaultDefinition: true,
  };
  const { wrapper, button, input } = mountSettings('tenant-a', definition);
  try {
    await flushPromises();
    wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('select', definition);
    await flushPromises();
    await button('新建流程').trigger('click');
    expect(input('流程名称').props('value')).toBe('');
    expect(input('流程编码').props('value')).toBe('');
    expect(input('优先级').props('value')).toBe('0');
    expect(wrapper.findComponent({ name: 'RecordPicker' }).props('value')).toBeUndefined();
    expect(wrapper.findComponent({ name: 'FormulaExpressionEditor' }).props('value')).toBe('');
    expect(wrapper.findAllComponents({ name: 'UiCheckbox' }).map((item) => item.props('checked'))).toEqual([
      true,
      false,
    ]);
    expect(button('新建流程').props('disabled')).toBe(true);
    await button('取消新建').trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'StaticManagementLayout' }).props('detailTitle')).toBe('已有流程');
    expect(wrapper.findComponent({ name: 'RecordListExplorer' }).props('selectedId')).toBe('existing');
    expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('editing')).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it('keeps loading, failure and retry distinct from a genuinely empty workflow list', async () => {
  let finish!: (result: { records: Record<string, unknown>[] }) => void;
  const query = vi
    .fn()
    .mockRejectedValueOnce(new Error('流程列表暂时不可用'))
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
  const { wrapper, button } = mountSettings(undefined, undefined, query);
  try {
    await flushPromises();
    expect(wrapper.findComponent({ name: 'UiError' }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'RecordListExplorer' }).exists()).toBe(false);
    button('重新加载').vm.$emit('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'UiSpin' }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('editing')).toBe(true);
    finish({ records: [] });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'UiError' }).exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordListExplorer' }).exists()).toBe(true);
    const layout = wrapper.findComponent({ name: 'StaticManagementLayout' });
    layout.vm.$emit('update:explorerSearchKeyword', '采购');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordListExplorer' }).props('keyword')).toBe('采购');
    expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('editing')).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it('does not mix a new definition or version identity with the previous canvas when reading fails', async () => {
  const definitions = [
    { id: 'a', alias: 'a', title: '第一流程' },
    { id: 'b', alias: 'b', title: '第二流程' },
  ];
  let failSecond = true;
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/context') || options.path.endsWith('/view-context'))
      return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
    if (options.path.endsWith('/configuration-catalog'))
      return { tasks: [], queries: [], generations: [], associations: [] };
    const second = options.path.includes('/b/');
    if (options.path.endsWith('/versions/query'))
      return {
        records: [
          { id: second ? 'b-draft' : 'a-draft', versionNo: 2, version: 1, publishStatus: 'draft' },
          { id: 'b-published', versionNo: 1, version: 1, publishStatus: 'published' },
        ],
      };
    if (options.path.endsWith('/design')) {
      if ((second && failSecond) || options.path.includes('/b-published/')) throw new Error('流程图读取失败');
      return {
        nodes: [{ nodeKey: second ? 'b-node' : 'a-node', nodeType: 'approval', title: '审批' }],
        links: [],
      };
    }
    if (options.path.endsWith('/query')) return { records: definitions };
    throw new Error(`unexpected request: ${options.path}`);
  });
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(ModuleContextProvider, {
    props: { context },
    slots: { default: () => h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase' }) },
    global: {
      stubs: {
        WorkflowDiagram: true,
        WorkflowDesignProperties: true,
        RecordListExplorer: true,
        RecordDetailDrawer: true,
        AdaptiveHeaderActionBar: true,
      },
    },
  });
  try {
    await flushPromises();
    const list = wrapper.findComponent({ name: 'RecordListExplorer' });
    list.vm.$emit('select', definitions[0]);
    await flushPromises();
    list.vm.$emit('select', definitions[1]);
    await flushPromises();
    expect(wrapper.findComponent({ name: 'StaticManagementLayout' }).props('detailTitle')).toBe('第一流程');
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('nodes')[0].nodeKey).toBe('a-node');
    failSecond = false;
    list.vm.$emit('select', definitions[1]);
    await flushPromises();
    const versions = wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((item) => item.props('value') === 'b-draft')!;
    versions.vm.$emit('update:value', 'b-published');
    await flushPromises();
    expect(versions.props('value')).toBe('b-draft');
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('nodes')[0].nodeKey).toBe('b-node');
  } finally {
    wrapper.unmount();
  }
});

it('retains the inserted definition after follow-up failure so retry never inserts it again', async () => {
  const { wrapper, request, button, input } = mountSettings();
  try {
    await flushPromises();
    const original = request.getMockImplementation()!;
    request.mockImplementation(async (options) => {
      if (options.path.endsWith('/configuration-catalog')) throw new Error('配置目录暂不可用');
      return original(options);
    });
    await button('新建流程').trigger('click');
    input('流程名称').vm.$emit('update:value', '新审批');
    input('流程编码').vm.$emit('update:value', 'approval');
    await flushPromises();
    await button('创建流程草稿').trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'StaticManagementLayout' }).props('detailTitle')).toBe('新审批');
    expect(
      wrapper.findAllComponents({ name: 'UiButton' }).some((item) => item.text() === '创建流程草稿'),
    ).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordListExplorer' }).props('records')).toHaveLength(1);
    request.mockImplementation(async (options) =>
      options.path.endsWith('/design') ? { nodes: [], links: [] } : original(options),
    );
    wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('select', {
      id: 'new-definition',
      alias: 'approval',
      title: '新审批',
      approvalEnabled: true,
    });
    await flushPromises();
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'upgrade' });
    await flushPromises();
    expect(wrapper.text()).toContain('草稿有未保存的修改');
    expect(request.mock.calls.filter(([options]) => options.path.endsWith('/insert'))).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

it('uses the same controlled matching fields when editing a definition and persists their values through the page client', async () => {
  const definition = {
    id: 'existing',
    alias: 'approval',
    title: '审批流程',
    version: 1,
    matchPriority: 9,
    defaultDefinition: true,
  };
  const { wrapper, request, button } = mountSettings('tenant-a', definition);
  try {
    await flushPromises();
    const original = request.getMockImplementation()!;
    request.mockImplementation(async (options) =>
      options.path.endsWith('/selection')
        ? { ...definition, ...(options.body as object), version: 2 }
        : original(options),
    );
    wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('select', definition);
    await flushPromises();
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'selection' });
    await flushPromises();
    const settings = wrapper.findComponent({ name: 'WorkflowSelectionFields' });
    expect(settings.props('matchPriority')).toBe('9');
    expect(settings.props('fallback')).toBe(true);
    expect(settings.text()).toContain('填写整数，同一组织范围内数值越大越优先');
    settings.vm.$emit('update:matchPriority', '4');
    settings.vm.$emit('update:fallback', false);
    await flushPromises();
    await button('保存匹配规则').trigger('click');
    await flushPromises();
    expect(
      request.mock.calls.find(([options]) => options.path.endsWith('/selection'))?.[0].body,
    ).toMatchObject({ version: 1, matchPriority: 4, defaultDefinition: false });
    expect(wrapper.findComponent({ name: 'WorkflowSelectionFields' }).exists()).toBe(false);
  } finally {
    wrapper.unmount();
  }
});
