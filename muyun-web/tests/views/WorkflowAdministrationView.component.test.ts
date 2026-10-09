import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { ModuleContextProvider, type ModuleContext, type HttpRequestOptions } from '@muyun/web-core';
import { confirmAction } from '@muyun/vue-ui-antdv';
import WorkflowAdministrationView from '@/views/WorkflowAdministrationView.vue';
vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(async () => false),
}));
const surface = defineComponent({
  name: 'RecordQueryListSurface',
  props: ['rows'],
  setup(_, { slots }) {
    return () =>
      h('section', [
        slots.operations?.(),
        slots.persistentQueries?.(),
        slots.queryControls?.(),
        slots.conditions?.(),
        slots.rowActions?.({ record: {} }),
      ]);
  },
});
const drawer = defineComponent({
  name: 'RecordDetailDrawer',
  props: ['open', 'title'],
  setup(props, { slots }) {
    return () => (props.open ? h('aside', [slots.default?.(), slots.operation?.()]) : null);
  },
});
it.each(['refresh', 'query', 'select'])(
  'protects an unfinished administration reason before %s and allows a confirmed discard',
  async (transition) => {
    vi.mocked(confirmAction).mockResolvedValue(false);
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/workbench/modules')) return {};
      if (options.path.endsWith('/instance/query'))
        return {
          records: [
            {
              instanceId: 'i',
              moduleAlias: 'demo.purchase',
              activeNodeTitles: [],
              currentAssigneeTitles: [],
            },
          ],
        };
      if (options.path.endsWith('/bundle'))
        return { instance: { id: 'i', versionNo: 7, instanceStatus: 'running' }, nodes: [], routes: [] };
      return { records: [] };
    });
    const context = {
      moduleAlias: 'platform.workflow_admin',
      http: { request },
    } as unknown as ModuleContext<unknown>;
    const Harness = defineComponent({
      setup: () => () => h(ModuleContextProvider, { context }, () => h(WorkflowAdministrationView)),
    });
    const wrapper = mount(Harness, {
      global: {
        stubs: {
          RecordQueryListSurface: surface,
          RecordDetailDrawer: drawer,
          RecordPicker: true,
          WorkflowDiagram: true,
          ManagementTabs: true,
        },
      },
    });
    await flushPromises();
    const list = wrapper.findComponent(surface);
    list.vm.$emit('row-click', { id: 'i' });
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '终止流程')!
      .trigger('click');
    await flushPromises();
    expect(wrapper.text()).toMatch(/当前流程版本：\s*7\s*。/);
    wrapper.findComponent({ name: 'UiTextArea' }).vm.$emit('update:value', '需要保留的运维原因');
    await flushPromises();
    const before = request.mock.calls.length;
    const perform = async () => {
      if (transition === 'select') list.vm.$emit('row-click', { id: 'other' });
      else
        await wrapper
          .findAll('button')
          .find(
            (button) => button.text().replaceAll(' ', '') === (transition === 'refresh' ? '刷新' : '查询'),
          )!
          .trigger('click');
      await flushPromises();
    };
    await perform();
    expect(request.mock.calls).toHaveLength(before);
    expect(wrapper.findComponent({ name: 'UiTextArea' }).props('value')).toBe('需要保留的运维原因');
    expect(confirmAction).toHaveBeenCalledWith(expect.objectContaining({ title: '放弃未执行的运维操作？' }));
    vi.mocked(confirmAction).mockResolvedValue(true);
    await perform();
    expect(request.mock.calls.length).toBeGreaterThan(before);
    expect(wrapper.findComponent({ name: 'UiTextArea' }).exists()).toBe(false);
  },
);

it('opens terminal current instances and resets them without querying active-only tasks', async () => {
  vi.mocked(confirmAction).mockResolvedValue(true);
  let reset = false;
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/instance/query'))
      return {
        records: reset
          ? []
          : [
              {
                instanceId: 'terminal',
                instanceStatus: 'terminated',
                moduleAlias: 'demo.purchase',
                activeNodeTitles: [],
                currentAssigneeTitles: [],
              },
            ],
      };
    if (options.path.endsWith('/bundle'))
      return {
        instance: { id: 'terminal', versionNo: 3, instanceStatus: 'terminated' },
        nodes: [],
        routes: [],
      };
    if (options.path.endsWith('/active-tasks')) throw new Error('not running');
    if (options.path.endsWith('/actions/reset')) {
      reset = true;
      return { data: {}, changeSetId: 'reset' };
    }
    return { records: [] };
  });
  const context = {
    moduleAlias: 'platform.workflow_admin',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const Harness = defineComponent({
    setup: () => () => h(ModuleContextProvider, { context }, () => h(WorkflowAdministrationView)),
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        RecordQueryListSurface: surface,
        RecordPicker: true,
        RecordDetailDrawer: drawer,
        WorkflowDiagram: true,
        ManagementTabs: true,
      },
    },
  });
  await flushPromises();
  const status = wrapper
    .findAllComponents({ name: 'UiSelect' })
    .find((select) =>
      select.props('options')?.some((option: { value: string }) => option.value === 'terminated'),
    )!;
  status.vm.$emit('update:value', 'terminated');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text().replaceAll(' ', '') === '查询')!
    .trigger('click');
  await flushPromises();
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      path: '/workflow/runtime/admin/instance/query',
      body: expect.objectContaining({ instanceStatus: 'terminated' }),
    }),
  );
  wrapper.findComponent(surface).vm.$emit('row-click', { id: 'terminal' });
  await flushPromises();
  expect(request.mock.calls.some(([call]) => call.path.endsWith('/active-tasks'))).toBe(false);
  expect(wrapper.findAll('button').some((button) => button.text() === '终止流程')).toBe(false);
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重置业务审批')!
    .trigger('click');
  await flushPromises();
  wrapper.findComponent({ name: 'UiTextArea' }).vm.$emit('update:value', '清理已终止的失联实例');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text().replaceAll(' ', '') === '确认操作')!
    .trigger('click');
  await flushPromises();
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      path: '/workflow/runtime/admin/instance/terminal/actions/reset',
      body: { reason: '清理已终止的失联实例' },
    }),
  );
  expect(wrapper.findComponent(surface).props('rows')).toEqual([]);
  expect(wrapper.findComponent(drawer).props('open')).toBe(false);
  wrapper.unmount();
});
