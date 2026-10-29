import { appDataChangeDispatcher } from '@/platform-admin-runtime/realtime';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, KeepAlive, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  AppError,
  createLanguageContext,
  provideLanguageContext,
  ModuleContextProvider,
  type ModuleContext,
  type HttpRequestOptions,
} from '@muyun/web-core';
import WorkflowConfigurationView from '@/views/WorkflowConfigurationView.vue';

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
    if (options.path.endsWith('/view/definition')) return definition;
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
        h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'definition' }),
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
    expect(wrapper.get('[role="status"]').text()).toBe('未保存');
    expect(
      wrapper
        .findAllComponents({ name: 'UiTooltip' })
        .some((tooltip) => tooltip.props('title') === '草稿有未保存的修改'),
    ).toBe(true);
    expect(wrapper.text()).not.toContain('此版本已冻结');
    finishSave();
    await flushPromises();
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('editable')).toBe(true);
    if (round === 0) {
      versionSelector.vm.$emit('update:value', 'published');
      await flushPromises();
      expect(wrapper.text()).toContain('只读');
      expect(
        wrapper
          .findAllComponents({ name: 'UiTooltip' })
          .some((tooltip) => String(tooltip.props('title')).startsWith('此版本已冻结')),
      ).toBe(true);
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
    if (options.path.endsWith('/view/definition')) return definition;
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
    slots: {
      default: () =>
        h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'definition' }),
    },
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
    if (options.path.endsWith('/view/definition')) return definition;
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
    slots: {
      default: () =>
        h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'definition' }),
    },
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
    if (options.path.endsWith('/view/definition')) return definition;
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
          h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'definition' }),
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

it('initializes a first draft on explicit design entry without inserting another definition', async () => {
  const request = vi.fn(async (call: HttpRequestOptions) => {
    if (call.path.endsWith('/view/a'))
      return { id: 'a', alias: 'purchase', title: '采购审批', version: 1, approvalEnabled: true };
    if (call.path.endsWith('/configuration-catalog'))
      return { tasks: [], queries: [], generations: [], associations: [] };
    if (call.path.endsWith('/versions/query')) return { records: [] };
    if (call.path.endsWith('/upgrade'))
      return { id: 'draft', version: 1, versionNo: 1, publishStatus: 'draft' };
    if (call.path.endsWith('/design')) return { nodes: [], links: [] };
    return { moduleAlias: 'demo.purchase', actions: [], capabilities: [] };
  });
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(ModuleContextProvider, {
    props: { context },
    slots: {
      default: () => h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'a' }),
    },
    global: { stubs: { WorkflowDiagram: true, RecordDetailDrawer: true, AdaptiveHeaderActionBar: true } },
  });
  try {
    await flushPromises();
    expect(request.mock.calls.filter(([call]) => call.path.endsWith('/upgrade'))).toHaveLength(1);
    expect(
      request.mock.calls.some(
        ([call]) => call.path.endsWith('/insert') || call.path.endsWith('workflow-definitions/query'),
      ),
    ).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordListExplorer' }).exists()).toBe(false);
    expect(
      wrapper
        .findComponent({ name: 'WorkflowDiagram' })
        .props('nodes')
        .map((node: { nodeType: string }) => node.nodeType),
    ).toEqual(['start', 'approval', 'milestone', 'end']);
    expect(wrapper.get('[role="status"]').text()).toBe('未保存');
    expect(
      wrapper
        .findAllComponents({ name: 'UiTooltip' })
        .some((tooltip) => tooltip.props('title') === '草稿有未保存的修改'),
    ).toBe(true);
  } finally {
    wrapper.unmount();
  }
});

it('keeps the current version and canvas together when reading another version fails', async () => {
  const request = vi.fn(async (call: HttpRequestOptions) => {
    if (call.path.endsWith('/view/a')) return { id: 'a', alias: 'purchase', title: '采购审批', version: 1 };
    if (call.path.endsWith('/configuration-catalog'))
      return { tasks: [], queries: [], generations: [], associations: [] };
    if (call.path.endsWith('/versions/query'))
      return {
        records: [
          { id: 'draft', version: 1, versionNo: 2, publishStatus: 'draft' },
          { id: 'published', version: 1, versionNo: 1, publishStatus: 'published' },
        ],
      };
    if (call.path.includes('/published/design')) throw new Error('版本读取失败');
    if (call.path.endsWith('/design'))
      return { nodes: [{ nodeKey: 'approval', nodeType: 'approval' }], links: [] };
    return { moduleAlias: 'demo.purchase', actions: [], capabilities: [] };
  });
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(ModuleContextProvider, {
    props: { context },
    slots: {
      default: () => h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'a' }),
    },
    global: { stubs: { WorkflowDiagram: true, RecordDetailDrawer: true, AdaptiveHeaderActionBar: true } },
  });
  try {
    await flushPromises();
    const selector = wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((item) => item.props('value') === 'draft')!;
    selector.vm.$emit('update:value', 'published');
    await flushPromises();
    expect(selector.props('value')).toBe('draft');
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('nodes')[0].nodeKey).toBe('approval');
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('editable')).toBe(true);
  } finally {
    wrapper.unmount();
  }
});

it('refreshes definition metadata on tab activation and realtime changes while preserving an unsaved design', async () => {
  const visible = ref(true);
  let definitionVersion = 1;
  let finishSave!: () => void;
  const request = vi.fn(async (call: HttpRequestOptions) => {
    if (call.path.endsWith('/view/definition'))
      return { id: 'definition', title: '流程 ' + definitionVersion, version: definitionVersion };
    if (call.path.endsWith('/configuration-catalog'))
      return { tasks: [], queries: [], generations: [], associations: [] };
    if (call.path.endsWith('/versions/query'))
      return { records: [{ id: 'draft', version: 1, versionNo: 1, publishStatus: 'draft' }] };
    if (call.path.endsWith('/design') && call.method === 'POST') {
      expect(call.body).toMatchObject({
        design: { layoutJson: 'unsaved-layout', nodes: [{ title: '未保存节点名称' }] },
      });
      await new Promise<void>((resolve) => {
        finishSave = resolve;
      });
      return { id: 'draft', version: 2, versionNo: 1, publishStatus: 'draft' };
    }
    if (call.path.endsWith('/design'))
      return { nodes: [{ nodeKey: 'start', nodeType: 'start', title: '提交' }], links: [] };
    if (call.path.endsWith('/publish')) {
      expect(call.body).toMatchObject({ definitionVersion: 4, version: 2 });
      return { id: 'draft', version: 3, versionNo: 1, publishStatus: 'published' };
    }
    return { moduleAlias: 'demo.purchase', actions: [], capabilities: [] };
  });
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const Harness = defineComponent({
    setup: () => () =>
      h(ModuleContextProvider, { context }, () =>
        h(KeepAlive, null, {
          default: () =>
            visible.value
              ? h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'definition' })
              : h('div'),
        }),
      ),
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        WorkflowDiagram: true,
        WorkflowDesignProperties: true,
        AdaptiveHeaderActionBar: true,
        RecordDetailDrawer: { template: '<section><slot/><slot name="operation"/></section>' },
      },
    },
  });
  try {
    await flushPromises();
    const diagram = wrapper.findComponent({ name: 'WorkflowDiagram' });
    diagram.vm.$emit('select', 'start');
    await flushPromises();
    wrapper
      .findComponent({ name: 'WorkflowDesignProperties' })
      .vm.$emit('update-node', { title: '未保存节点名称' });
    diagram.vm.$emit('layout-change', 'unsaved-layout');
    await flushPromises();
    const nodes = diagram.props('nodes');
    visible.value = false;
    await flushPromises();
    definitionVersion = 2;
    visible.value = true;
    await flushPromises();
    expect(wrapper.text()).toContain('流程 2');
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('nodes')).toBe(nodes);
    expect(nodes[0].title).toBe('未保存节点名称');
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('layoutJson')).toBe('unsaved-layout');
    definitionVersion = 3;
    await appDataChangeDispatcher.dispatch({
      changeSetId: 'selection-updated',
      changes: [
        { type: 'record-updated', moduleAlias: 'platform.workflow.definition', recordId: 'definition' },
      ],
    });
    await flushPromises();
    expect(wrapper.text()).toContain('流程 3');
    expect(wrapper.get('[role="status"]').text()).toBe('未保存');
    expect(request.mock.calls.filter(([call]) => call.path.endsWith('/design'))).toHaveLength(1);
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: 'publish' });
    await flushPromises();
    await wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((button) => button.text() === '确认发布')!
      .trigger('click');
    definitionVersion = 4;
    await appDataChangeDispatcher.dispatch({
      changeSetId: 'sort-updated-during-save',
      changes: [
        { type: 'record-updated', moduleAlias: 'platform.workflow.definition', recordId: 'definition' },
      ],
    });
    await flushPromises();
    finishSave();
    await flushPromises();
    expect(request.mock.calls.filter(([call]) => call.path.endsWith('/publish'))).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

it.each(['lost-response', 'conflict'])(
  'preserves the local draft after %s and resumes only after explicit current-version recovery',
  async (failure) => {
    let storedVersion = 1;
    let posts = 0;
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/view/definition')) return { id: 'definition', title: '采购审批' };
      if (options.path.endsWith('/context') || options.path.endsWith('/view-context'))
        return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
      if (options.path.endsWith('/configuration-catalog'))
        return { tasks: [], queries: [], generations: [], associations: [] };
      if (options.path.endsWith('/versions/query'))
        return { records: [{ id: 'draft', version: storedVersion, versionNo: 1, publishStatus: 'draft' }] };
      if (options.path.endsWith('/design') && options.method === 'POST') {
        posts++;
        expect(options.body).toMatchObject({ version: storedVersion });
        storedVersion++;
        if (posts === 1)
          throw failure === 'conflict'
            ? new AppError('Version conflict', { status: 409 })
            : new Error('response lost');
        return { id: 'draft', version: storedVersion, versionNo: 1, publishStatus: 'draft' };
      }
      if (options.path.endsWith('/design'))
        return {
          nodes: [{ nodeKey: 'approval', nodeType: 'approval', title: '审批' }],
          links: [],
          layoutJson: 'server-layout',
        };
      throw new Error(`unexpected request: ${options.path}`);
    });
    const context = {
      moduleAlias: 'platform.workflow.definition',
      http: { request },
    } as unknown as ModuleContext<unknown>;
    const wrapper = mount(ModuleContextProvider, {
      props: { context },
      slots: {
        default: () =>
          h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'definition' }),
      },
      global: {
        stubs: {
          WorkflowDiagram: true,
          WorkflowParticipantEditor: true,
          WorkflowBusinessTaskEditor: true,
          RecordDetailDrawer: true,
          AdaptiveHeaderActionBar: true,
        },
      },
    });
    await flushPromises();
    const diagram = () => wrapper.findAllComponents({ name: 'WorkflowDiagram' }).at(-1)!;
    const actions = wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' });
    diagram().vm.$emit('layout-change', 'local-layout');
    await flushPromises();
    actions.vm.$emit('action', { key: 'save' });
    await flushPromises();
    expect(wrapper.text()).toContain('上次保存未能确认');
    expect(diagram().props('layoutJson')).toBe('local-layout');
    expect(diagram().props('editable')).toBe(false);
    actions.vm.$emit('action', { key: 'save' });
    actions.vm.$emit('action', { key: 'upgrade' });
    await flushPromises();
    expect(posts).toBe(1);
    const button = (text: string) =>
      wrapper.findAllComponents({ name: 'UiButton' }).find((item) => item.text() === text)!;
    await button('读取当前版本').trigger('click');
    await flushPromises();
    expect(diagram().props('editable')).toBe(false);
    expect(wrapper.findAllComponents({ name: 'WorkflowDiagram' })[0].props('layoutJson')).toBe(
      'server-layout',
    );
    await button('保留本地草稿并重新核对').trigger('click');
    await flushPromises();
    expect(diagram().props('editable')).toBe(true);
    expect(diagram().props('layoutJson')).toBe('local-layout');
    actions.vm.$emit('action', { key: 'save' });
    await flushPromises();
    expect(posts).toBe(2);
    expect(wrapper.text()).not.toContain('上次保存未能确认');
    wrapper.unmount();
  },
);
