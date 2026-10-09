import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { ModuleContextProvider, type ModuleContext, type HttpRequestOptions } from '@muyun/web-core';
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
