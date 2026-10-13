import { appDataChangeDispatcher } from '@/platform-admin-runtime/realtime';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  createLanguageContext,
  provideLanguageContext,
  ModuleContextProvider,
  type ModuleContext,
  type HttpRequestOptions,
} from '@muyun/web-core';
import WorkflowConfigurationView from '@/views/WorkflowConfigurationView.vue';

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
    expect(properties.props('actions')).toEqual([{ value: 'receive', label: '接收原文' }]);
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(properties.props('fields')[0].label).toBe('人员原文');
    language.setLanguage('en');
    await flushPromises();
    label = 'Responsible account';
    await appDataChangeDispatcher.dispatch({
      changeSetId: 'workflow-language-candidates',
      changes: [{ type: 'configuration-translations-changed', moduleAlias: 'demo.purchase' }],
    });
    await flushPromises();
    expect(properties.props('fields')[0]).toMatchObject({
      name: 'participantId',
      label: 'Responsible account',
    });
    expect(properties.props('design')).toBe(originalDesign);
    expect(originalDesign.nodes[0].title).toBe('用户节点原名');
    expect(request.mock.calls.filter(([options]) => options.path.endsWith('/design'))).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});
