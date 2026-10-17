import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref, type Ref } from 'vue';
import { it, expect, vi } from 'vitest';
import { confirmAction } from '@muyun/vue-ui-antdv';
vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(async () => false),
}));
import WorkflowRecordPanel from '@/platform-components/WorkflowRecordPanel.vue';
import {
  createLanguageContext,
  createCodeTextPresentation,
  provideLanguageContext,
  platformLanguageContext,
  type LanguageContext,
  type ModuleContext,
} from '@muyun/web-core';
const drawer = defineComponent({
  name: 'RecordDetailDrawer',
  props: ['open', 'title'],
  setup(props, { slots }) {
    return () => (props.open ? h('section', [slots.default?.(), slots.operation?.()]) : null);
  },
});
const fields = defineComponent({
  name: 'RecordFormFields',
  props: ['record'],
  emits: ['validity-change', 'update:field'],
  setup() {
    return () => h('div', '业务编辑');
  },
});
function fixture(
  onChanged?: () => void,
  language?: LanguageContext,
  textBindings?: Ref<unknown>,
  instanceId?: string,
  workflowScenario?: { archived?: boolean; paged?: boolean },
) {
  const request = vi.fn(
    async ({
      path,
      query,
    }: {
      path: string;
      method?: string;
      body?: unknown;
      query?: { page?: number };
    }): Promise<unknown> => {
      if (path.endsWith('/submit/status')) return { displayStatus: 'PROCESSING', instanceId: 'i' };
      if (path === '/workflow/history/query')
        return {
          records: workflowScenario?.archived
            ? [{ id: 'i', versionNo: 1, instanceStatus: 'completed', startedAt: '2026-10-01T00:00:00Z' }]
            : [],
        };
      if (path.endsWith('/instances')) {
        const page = query?.page ?? 1;
        const ids = workflowScenario?.archived
          ? []
          : page === 2
            ? ['i-next']
            : workflowScenario?.paged
              ? ['i', ...Array.from({ length: 19 }, (_, index) => `round-${index}`)]
              : ['i'];
        return {
          records: ids.map((id) => ({
            id,
            title: `Collaboration ${id}`,
            versionNo: 1,
            instanceStatus: 'running',
            approvalEnabled: false,
          })),
          pageNum: page,
          pageSize: 20,
          total: workflowScenario?.archived ? 0 : workflowScenario?.paged ? 21 : 1,
          pages: workflowScenario?.archived ? 0 : workflowScenario?.paged ? 2 : 1,
        };
      }
      if (path.endsWith('/bundle'))
        return {
          instance: {
            id: workflowScenario ? path.split('/').at(-2) : 'i',
            instanceStatus: workflowScenario?.archived ? 'completed' : 'running',
          },
          nodes: [],
          routes: [],
        };
      if (path.endsWith('/actions'))
        return {
          records: workflowScenario
            ? [{ actionCode: 'reset', title: '重置', reasonRequired: true }]
            : [
                {
                  actionCode: 'complete',
                  title: '完成任务',
                  nodeKey: 'business',
                  taskId: 't',
                  reasonRequired: false,
                },
              ],
        };
      if (path.endsWith('/prepare'))
        return {
          evaluation: {
            passed: false,
            checkResults: [],
            guides: [
              {
                guideKey: 'form',
                guideKind: 'open_form',
                title: '登记到货',
                guideConfigText: '{"editableFields":["remark"]}',
              },
              {
                guideKey: 'deliver',
                guideKind: 'execute_action',
                title: '确认到货',
                targetActionCode: 'deliver',
              },
            ],
          },
        };
      return { records: [] };
    },
  );
  const context = {
    moduleAlias: 'demo.purchase',
    http: { request },
    ...(workflowScenario ? { abilities: { has: (code: string) => code === 'workflow' } } : {}),
    runtime: {
      ...(textBindings ? { snapshot: () => ({ textBindings: textBindings.value }) } : {}),
      ready: Promise.resolve({
        uiDescriptor: {
          defaultEditor: {
            fields: [{ fieldRef: { fieldName: 'remark' }, label: '说明', valueType: 'STRING' }],
          },
        },
      }),
    },
    crud: { view: vi.fn(async () => ({ id: 'r', version: 1, remark: '原内容' })) },
  } as unknown as ModuleContext<Record<string, unknown>>;
  const Host = defineComponent({
    inheritAttrs: false,
    setup() {
      provideLanguageContext(language!);
      return () => h(WorkflowRecordPanel, { context, recordId: 'r', onChanged, instanceId });
    },
  });
  const wrapper = mount(language ? Host : WorkflowRecordPanel, {
    props: { context, recordId: 'r', onChanged, instanceId },
    global: {
      stubs: {
        RecordDetailDrawer: drawer,
        RecordFormFields: fields,
        WorkflowDiagram: true,
        RecordQueryListPanel: true,
        RecordPicker: true,
        AdaptiveHeaderActionBar: true,
        RecordQueryListSurface: true,
      },
    },
  });
  return { wrapper, request, context };
}
it('pages ordinary workflow rounds and sends instance actions to the selected authorized round', async () => {
  const { wrapper, request } = fixture(undefined, undefined, undefined, undefined, { paged: true });
  try {
    await flushPromises();
    expect(request.mock.calls.some(([call]) => call.path.endsWith('/submit/status'))).toBe(false);
    expect(wrapper.text()).toContain('进行中');
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '下一页')!
      .trigger('click');
    await flushPromises();
    expect(
      request.mock.calls.some(([call]) => call.path.endsWith('/instances') && call.query?.page === 2),
    ).toBe(true);
    const selector = wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((select) =>
        select.props('options')?.some((item: { value: string }) => item.value === 'current:i-next'),
      )!;
    expect(selector.props('options').some((item: { value: string }) => item.value === 'current:i')).toBe(
      true,
    );
    selector.vm.$emit('update:value', 'current:i-next');
    await flushPromises();
    expect(
      request.mock.calls.some(([call]) => call.path === '/workflow/runtime/instance/i-next/bundle'),
    ).toBe(true);
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
    await flushPromises();
    await wrapper.find('textarea').setValue('Archive selected round');
    await wrapper
      .findAll('button')
      .find((button) => button.text().startsWith('确认') && button.text().includes('重置'))!
      .trigger('click');
    await flushPromises();
    expect(
      request.mock.calls.some(([call]) => call.path === '/workflow/runtime/instance/i-next/actions/reset'),
    ).toBe(true);
  } finally {
    wrapper.unmount();
  }
});
it.each([undefined, 'i'])(
  'opens archived ordinary workflow opinions without an approval gate (instance %s)',
  async (instanceId) => {
    const { wrapper, request } = fixture(undefined, undefined, undefined, instanceId, { archived: true });
    try {
      await flushPromises();
      expect(request.mock.calls.some(([call]) => call.path.endsWith('/submit/status'))).toBe(false);
      expect(request.mock.calls.some(([call]) => call.path === '/workflow/history/i/bundle')).toBe(true);
      expect(request.mock.calls.some(([call]) => call.path === '/workflow/runtime/instance/i/bundle')).toBe(
        false,
      );
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '办理记录')!
        .trigger('click');
      expect(wrapper.findComponent({ name: 'WorkflowTimeline' }).props()).toMatchObject({
        instanceId: 'i',
        history: true,
      });
    } finally {
      wrapper.unmount();
    }
  },
);
it('reads an explicitly selected ordinary workflow without querying unrelated approval submission status', async () => {
  const { wrapper, request } = fixture(undefined, undefined, undefined, 'i');
  try {
    await flushPromises();
    expect(request.mock.calls.some(([call]) => call.path.endsWith('/submit/status'))).toBe(false);
    expect(wrapper.text()).toContain('进行中');
    expect(wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).exists()).toBe(true);
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '办理记录')!
      .trigger('click');
    expect(wrapper.findComponent({ name: 'WorkflowTimeline' }).props('instanceId')).toBe('i');
  } finally {
    wrapper.unmount();
  }
});
it('opens task requirements immediately and protects refresh while editing', async () => {
  const { wrapper, request } = fixture();
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  expect(request).toHaveBeenCalledWith({ path: '/workflow/runtime/task/t/module-task/prepare' });
  expect(wrapper.text()).toContain('登记到货');
  expect(
    wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新流程')
      ?.attributes('disabled'),
  ).toBeDefined();
  wrapper.unmount();
});
it('blocks invalid business data and retains the draft when execution fails', async () => {
  const { wrapper, request } = fixture();
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '登记到货')!
    .trigger('click');
  await flushPromises();
  const form = wrapper.findComponent(fields);
  form.vm.$emit('validity-change', { valid: false, errors: { remark: 'required' } });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '保存业务并完成任务')!
    .trigger('click');
  await flushPromises();
  expect(request.mock.calls.some(([call]) => call.path.endsWith('/execute'))).toBe(false);
  expect(wrapper.text()).toContain('请修正');
  form.vm.$emit('validity-change', { valid: true, errors: {} });
  form.vm.$emit('update:field', 'remark', '保留我的输入');
  await flushPromises();
  request.mockImplementationOnce(async () => {
    throw new Error('version conflict');
  });
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '保存业务并完成任务')!
    .trigger('click');
  await flushPromises();
  expect(form.props('record').remark).toBe('保留我的输入');
  expect(wrapper.findComponent(drawer).props('open')).toBe(true);
  wrapper.unmount();
});

it.each([false, true])(
  'releases the interaction guard before notifying business refresh (form: %s)',
  async (withForm) => {
    const onChanged = vi.fn(() => {
      expect(wrapper.emitted('interaction-change')?.at(-1)?.[0]).toEqual({
        editing: false,
        busy: false,
        dirty: false,
      });
    });
    const { wrapper } = fixture(onChanged);
    await flushPromises();
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
    await flushPromises();
    if (withForm) {
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '登记到货')!
        .trigger('click');
      await flushPromises();
      wrapper.findComponent(fields).vm.$emit('validity-change', { valid: true, errors: {} });
      await flushPromises();
    }
    await wrapper
      .findAll('button')
      .find((button) => button.text() === (withForm ? '保存业务并完成任务' : '确认完成任务'))!
      .trigger('click');
    await flushPromises();
    expect(onChanged).toHaveBeenCalledOnce();
    expect(wrapper.findComponent(drawer).props('open')).toBe(false);
    wrapper.unmount();
  },
);

it('manual branch choices are single-select with readable suggestions and required reasons', async () => {
  const { wrapper, request } = fixture();
  const original = request.getMockImplementation()!;
  request.mockImplementation(async (call) => {
    if (call.path.endsWith('/manual-branches'))
      return {
        records: [
          {
            branchNodeKey: 'b',
            branchTitle: '采购处理方式',
            selectorNodeKey: 'approve',
            requireManualSelectionReason: true,
            candidates: [
              {
                routeKey: 'normal',
                targetNodeKey: 'normalApproval',
                routeStatus: 'candidate',
                title: '普通采购',
                conditionMatched: true,
                recommended: true,
              },
              {
                routeKey: 'special',
                targetNodeKey: 'specialApproval',
                routeStatus: 'candidate',
                title: '特殊采购',
                defaultRoute: true,
                recommended: false,
              },
            ],
          },
        ],
      };
    if (call.path.endsWith('/actions'))
      return {
        records: [
          { actionCode: 'approve', title: '通过', nodeKey: 'approve', taskId: 't', reasonRequired: false },
        ],
      };
    return original(call);
  });
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  const confirm = () =>
    wrapper
      .findAll('button')
      .find((button) => button.text() === '确认通过')!
      .trigger('click');
  await confirm();
  expect(wrapper.text()).toContain('选择一条路径');
  const picker = wrapper
    .findAllComponents({ name: 'UiSelect' })
    .find((select) => select.props('ariaLabel') === '选择路径：采购处理方式')!;
  expect(picker.props('mode')).toBeUndefined();
  expect(picker.props('options')).toEqual([
    { value: 'normal', label: '普通采购（条件命中 · 系统建议）' },
    { value: 'special', label: '特殊采购（默认出口）' },
  ]);
  picker.vm.$emit('update:value', 'normal');
  await flushPromises();
  await confirm();
  expect(wrapper.text()).toContain('路径选择原因');
  picker.vm.$emit('update:value', 'special');
  await flushPromises();
  await wrapper.find('input[placeholder="路径选择原因（必填）"]').setValue('特殊事项需要补充审批');
  await confirm();
  await flushPromises();
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      path: '/workflow/runtime/task/t/actions/approve',
      body: expect.objectContaining({
        manualRouteSelections: [
          { branchNodeKey: 'b', routeKey: 'special', selectedReason: '特殊事项需要补充审批' },
        ],
      }),
    }),
  );
  wrapper.unmount();
});

it('refreshes nested candidates and discards choices on an abandoned parent path', async () => {
  const { wrapper, request } = fixture();
  const original = request.getMockImplementation()!;
  const branch = (key: string) => ({
    branchNodeKey: key,
    branchTitle: key,
    selectorNodeKey: 'approve',
    candidates: [
      { routeKey: 'nested', targetNodeKey: 'child', routeStatus: 'candidate' },
      { routeKey: 'direct', targetNodeKey: 'end', routeStatus: 'candidate' },
    ],
  });
  request.mockImplementation(async (call) => {
    if (call.path.endsWith('/manual-branches')) {
      const selections =
        (call as { body?: { manualRouteSelections?: Array<{ branchNodeKey: string; routeKey: string }> } })
          .body?.manualRouteSelections ?? [];
      return {
        records: [
          branch('outer'),
          ...(selections.some((item) => item.branchNodeKey === 'outer' && item.routeKey === 'nested')
            ? [branch('inner')]
            : []),
        ],
      };
    }
    if (call.path.endsWith('/actions'))
      return {
        records: [
          { actionCode: 'approve', title: '通过', nodeKey: 'approve', taskId: 't', reasonRequired: false },
        ],
      };
    return original(call);
  });
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  const picker = (key: string) =>
    wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((select) => select.props('ariaLabel') === `选择路径：${key}`)!;
  picker('outer').vm.$emit('update:value', 'nested');
  await flushPromises();
  expect(picker('inner')).toBeDefined();
  picker('inner').vm.$emit('update:value', 'direct');
  await flushPromises();
  picker('outer').vm.$emit('update:value', 'direct');
  await flushPromises();
  expect(picker('inner')).toBeUndefined();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '确认通过')!
    .trigger('click');
  await flushPromises();
  expect(request).toHaveBeenCalledWith(
    expect.objectContaining({
      path: '/workflow/runtime/task/t/actions/approve',
      body: expect.objectContaining({
        manualRouteSelections: [{ branchNodeKey: 'outer', routeKey: 'direct', selectedReason: undefined }],
      }),
    }),
  );
  wrapper.unmount();
});

it('blocks approval when refreshed manual candidates cannot be loaded', async () => {
  const { wrapper, request } = fixture();
  const original = request.getMockImplementation()!;
  request.mockImplementation(async (call) => {
    if (call.path.endsWith('/manual-branches') && (call as { method?: string }).method === 'POST')
      throw new Error('candidate unavailable');
    if (call.path.endsWith('/actions'))
      return {
        records: [
          { actionCode: 'approve', title: '通过', nodeKey: 'approve', taskId: 't', reasonRequired: false },
        ],
      };
    return original(call);
  });
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '确认通过')!
    .trigger('click');
  await flushPromises();
  expect(request.mock.calls.some(([call]) => call.path.endsWith('/actions/approve'))).toBe(false);
  expect(wrapper.text()).toContain('路径候选加载');
  wrapper.unmount();
});

function businessBranch(key: string) {
  return {
    branchNodeKey: key,
    branchTitle: key,
    selectorNodeKey: 'business',
    requireManualSelectionReason: true,
    candidates: [
      { routeKey: 'nested', targetNodeKey: 'child', routeStatus: 'candidate', title: '继续办理' },
      { routeKey: 'direct', targetNodeKey: 'end', routeStatus: 'candidate', title: '直接结束' },
    ],
  };
}

it.each(['complete', 'action', 'form'] as const)(
  'business %s requires and carries the complete nested manual frontier and reasons',
  async (operation) => {
    const { wrapper, request } = fixture();
    const original = request.getMockImplementation()!;
    request.mockImplementation(async (call) => {
      if (call.path.endsWith('/manual-branches')) {
        const selections =
          (call as { body?: { manualRouteSelections?: Array<{ branchNodeKey: string; routeKey: string }> } })
            .body?.manualRouteSelections ?? [];
        return {
          records: [
            businessBranch('outer'),
            ...(selections.some((item) => item.branchNodeKey === 'outer' && item.routeKey === 'nested')
              ? [businessBranch('inner')]
              : []),
          ],
        };
      }
      return original(call);
    });
    await flushPromises();
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
    await flushPromises();
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/workflow/runtime/instance/i/manual-branches',
        method: 'POST',
        body: { taskId: 't', manualRouteSelections: [] },
      }),
    );
    if (operation === 'form') {
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '登记到货')!
        .trigger('click');
      await flushPromises();
      wrapper.findComponent(fields).vm.$emit('validity-change', { valid: true, errors: {} });
      wrapper.findComponent(fields).vm.$emit('update:field', 'remark', '到货已确认');
      await flushPromises();
    }
    const finish = () =>
      wrapper
        .findAll('button')
        .find(
          (button) =>
            button.text() ===
            { complete: '确认完成任务', action: '确认到货', form: '保存业务并完成任务' }[operation],
        )!
        .trigger('click');
    const picker = (key: string) =>
      wrapper
        .findAllComponents({ name: 'UiSelect' })
        .find((select) => select.props('ariaLabel') === `选择路径：${key}`)!;
    await finish();
    expect(wrapper.text()).toContain('选择一条路径');
    picker('outer').vm.$emit('update:value', 'nested');
    await flushPromises();
    await finish();
    expect(wrapper.text()).toContain('路径选择原因');
    await wrapper.find('input[aria-label="路径选择原因：outer"]').setValue('需要配送');
    picker('inner').vm.$emit('update:value', 'direct');
    await flushPromises();
    await wrapper.find('input[aria-label="路径选择原因：inner"]').setValue('到货完成');
    await finish();
    await flushPromises();
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        path:
          operation === 'complete'
            ? '/workflow/runtime/task/t/actions/complete'
            : `/workflow/runtime/task/t/module-task/guides/${operation === 'form' ? 'form' : 'deliver'}/execute`,
        body: expect.objectContaining({
          manualRouteSelections: [
            { branchNodeKey: 'outer', routeKey: 'nested', selectedReason: '需要配送' },
            { branchNodeKey: 'inner', routeKey: 'direct', selectedReason: '到货完成' },
          ],
          ...(operation === 'form' ? { version: 1, values: { remark: '到货已确认' } } : {}),
        }),
      }),
    );
    wrapper.unmount();
  },
);

it.each(['complete', 'action', 'form'] as const)(
  'business %s stays blocked after manual candidate loading fails',
  async (operation) => {
    const { wrapper, request } = fixture();
    const original = request.getMockImplementation()!;
    request.mockImplementation(async (call) => {
      if (call.path.endsWith('/manual-branches') && call.method === 'POST')
        throw new Error('candidate unavailable');
      return original(call);
    });
    await flushPromises();
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
    await flushPromises();
    if (operation === 'form') {
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '登记到货')!
        .trigger('click');
      await flushPromises();
    }
    const button = wrapper
      .findAll('button')
      .find(
        (item) =>
          item.text() ===
          { complete: '确认完成任务', action: '确认到货', form: '保存业务并完成任务' }[operation],
      )!;
    expect(button.attributes('disabled')).toBeDefined();
    await button.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('路径候选加载失败');
    expect(request.mock.calls.some(([call]) => /\/(actions\/complete|execute)$/.test(call.path))).toBe(false);
    wrapper.unmount();
  },
);

it('business completion waits for the refreshed manual frontier before allowing submission', async () => {
  const { wrapper, request } = fixture();
  const original = request.getMockImplementation()!;
  let resolveChoices!: (value: unknown) => void;
  request.mockImplementation(async (call) => {
    if (call.path.endsWith('/manual-branches') && call.method === 'POST')
      return new Promise((resolve) => {
        resolveChoices = resolve;
      });
    return original(call);
  });
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  const confirm = wrapper.findAll('button').find((button) => button.text() === '确认完成任务')!;
  expect(confirm.attributes('disabled')).toBeDefined();
  await confirm.trigger('click');
  expect(request.mock.calls.some(([call]) => call.path.endsWith('/actions/complete'))).toBe(false);
  resolveChoices({ records: [] });
  await flushPromises();
  expect(confirm.attributes('disabled')).toBeUndefined();
  await confirm.trigger('click');
  await flushPromises();
  expect(request.mock.calls.some(([call]) => call.path.endsWith('/actions/complete'))).toBe(true);
  wrapper.unmount();
});

it('keeps the draft when switching a business guide is declined and only replaces it after confirmation', async () => {
  const { wrapper, request, context } = fixture();
  const original = request.getMockImplementation()!;
  request.mockImplementation(async (call) => {
    const result = await original(call);
    if (call.path.endsWith('/prepare')) {
      const process = result as { evaluation: { guides: Array<Record<string, unknown>> } };
      process.evaluation.guides.push({
        guideKey: 'other-form',
        guideKind: 'open_form',
        title: '补充到货',
        guideConfigText: '{"editableFields":["remark"]}',
      });
    }
    return result;
  });
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  const open = (title: string) =>
    wrapper
      .findAll('button')
      .find((button) => button.text() === title)!
      .trigger('click');
  await open('登记到货');
  await flushPromises();
  wrapper.findComponent(fields).vm.$emit('update:field', 'remark', '尚未保存');
  await flushPromises();
  vi.mocked(confirmAction).mockResolvedValueOnce(false);
  await open('补充到货');
  await flushPromises();
  expect(vi.mocked(context.crud.view)).toHaveBeenCalledOnce();
  expect(wrapper.findComponent(fields).props('record').remark).toBe('尚未保存');
  vi.mocked(confirmAction).mockResolvedValueOnce(true);
  await open('补充到货');
  await flushPromises();
  expect(vi.mocked(context.crud.view)).toHaveBeenCalledTimes(2);
  expect(wrapper.findComponent(fields).props('record').remark).toBe('原内容');
  wrapper.findComponent(fields).vm.$emit('validity-change', { valid: true, errors: {} });
  await flushPromises();
  await open('保存业务并完成任务');
  await flushPromises();
  expect(request.mock.calls.some(([call]) => call.path.endsWith('/guides/other-form/execute'))).toBe(true);
});

it('blocks leaving while a business form is loading and ignores its result after changing records', async () => {
  const { wrapper, context } = fixture();
  let resolveForm!: (record: Record<string, unknown>) => void;
  vi.mocked(context.crud.view).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveForm = resolve;
      }),
  );
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '登记到货')!
    .trigger('click');
  await flushPromises();
  expect(await (wrapper.vm as unknown as { mayLeave: () => Promise<boolean> }).mayLeave()).toBe(false);
  expect(wrapper.emitted('interaction-change')?.at(-1)?.[0]).toMatchObject({ busy: true });
  await wrapper.setProps({ recordId: 'another-record' });
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  resolveForm({ id: 'r', version: 1, remark: '旧记录延迟返回' });
  await flushPromises();
  expect(wrapper.findComponent(fields).exists()).toBe(false);
  expect(wrapper.findAll('button').some((button) => button.text() === '保存业务并完成任务')).toBe(false);
  expect(await (wrapper.vm as unknown as { mayLeave: () => Promise<boolean> }).mayLeave()).toBe(true);
});

it('ignores task requirements from an old record and cannot inspect during business execution', async () => {
  const { wrapper, request } = fixture();
  const original = request.getMockImplementation()!;
  let resolveOld!: (value: unknown) => void;
  let resolveExecution!: (value: unknown) => void;
  let prepareCount = 0;
  request.mockImplementation(async (call) => {
    if (call.path.endsWith('/prepare') && ++prepareCount === 1) {
      return new Promise((resolve) => {
        resolveOld = resolve;
      });
    }
    if (call.path.endsWith('/execute')) {
      return new Promise((resolve) => {
        resolveExecution = resolve;
      });
    }
    return original(call);
  });
  await flushPromises();
  const enter = () =>
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  enter();
  await flushPromises();
  await wrapper.setProps({ recordId: 'another-record' });
  await flushPromises();
  enter();
  await flushPromises();
  expect(prepareCount).toBe(2);
  resolveOld({
    evaluation: {
      passed: false,
      checkResults: [],
      guides: [{ guideKey: 'old', title: '旧记录指引', guideKind: 'open_form' }],
    },
  });
  await flushPromises();
  expect(wrapper.text()).toContain('登记到货');
  expect(wrapper.text()).not.toContain('旧记录指引');
  const click = (title: string) =>
    wrapper
      .findAll('button')
      .find((button) => button.text() === title)!
      .trigger('click');
  await click('确认到货');
  await flushPromises();
  expect(wrapper.emitted('interaction-change')?.at(-1)?.[0]).toMatchObject({ busy: true });
  await click('检查任务完成条件');
  await flushPromises();
  expect(prepareCount).toBe(2);
  resolveExecution({});
  await flushPromises();
});

it('replaces an unactivated add-sign segment by reconnecting to its original exit', async () => {
  const { wrapper, request } = fixture();
  const original = request.getMockImplementation()!;
  let inserted = false;
  request.mockImplementation(async (call) => {
    if (call.path.endsWith('/bundle'))
      return {
        instance: { id: 'i', instanceStatus: 'running' },
        nodes: [
          { nodeKey: 'approve', nodeTitle: '采购审批' },
          { nodeKey: 'end', nodeTitle: '完成' },
        ],
        routes: inserted
          ? [
              { routeKey: 'old', sourceNodeKey: 'approve', targetNodeKey: 'end', routeStatus: 'canceled' },
              {
                routeKey: 'added_in',
                sourceNodeKey: 'approve',
                targetNodeKey: 'added',
                routeStatus: 'candidate',
              },
              {
                routeKey: 'added_out',
                sourceNodeKey: 'added',
                targetNodeKey: 'end',
                routeStatus: 'candidate',
              },
            ]
          : [{ routeKey: 'old', sourceNodeKey: 'approve', targetNodeKey: 'end', routeStatus: 'candidate' }],
      };
    if (call.path.endsWith('/add-sign-explanations'))
      return {
        records: inserted
          ? [
              { dimension: 'NODE', nodeKey: 'added', nodeStatus: 'waiting', addSignSourceNodeKey: 'approve' },
              {
                dimension: 'ROUTE',
                routeKey: 'added_in',
                routeSourceNodeKey: 'approve',
                routeTargetNodeKey: 'added',
                addSignSourceNodeKey: 'approve',
              },
              {
                dimension: 'ROUTE',
                routeKey: 'added_out',
                routeSourceNodeKey: 'added',
                routeTargetNodeKey: 'end',
                addSignSourceNodeKey: 'approve',
              },
            ]
          : [],
      };
    if (call.path.endsWith('/actions'))
      return {
        records: [
          { actionCode: 'addSign', title: '加签', taskId: 't', nodeKey: 'approve', reasonRequired: true },
        ],
      };
    if (call.path.endsWith('/actions/addSign')) {
      inserted = true;
      return {};
    }
    return original(call);
  });
  await flushPromises();
  for (const userId of ['user-a', 'user-b']) {
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
    await flushPromises();
    wrapper.findComponent({ name: 'UiTextArea' }).vm.$emit('update:value', '增加专业审批');
    wrapper.findComponent({ name: 'RecordPicker' }).vm.$emit('update:value', userId);
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '确认加签')!
      .trigger('click');
    await flushPromises();
  }
  const commands = request.mock.calls
    .map(([call]) => call)
    .filter((call) => call.path.endsWith('/actions/addSign'));
  expect(commands).toHaveLength(2);
  for (const command of commands)
    expect(command.body).toMatchObject({
      addSignSegment: {
        linkDefinitions: [expect.anything(), expect.objectContaining({ targetNodeKey: 'end' })],
      },
    });
});

it('localizes stable workflow actions and their frozen node labels in an independent consumer scope', async () => {
  const { createLanguageContext, provideLanguageContext } = await import('@muyun/web-core');
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi.fn(async ({ path }: { path: string }) => {
    if (path.endsWith('/submit/status')) return { displayStatus: 'PROCESSING', instanceId: 'i' };
    if (path.endsWith('/bundle'))
      return {
        instance: { id: 'i', instanceStatus: 'running' },
        nodes: [
          {
            nodeKey: 'approve',
            nodeTitle: '冻结审批节点',
            nodeTitleText: {
              source: {
                sourceKind: 'CONFIG',
                sourceId: 'old-node',
                sourceVersion: 'v1',
                originalText: '冻结审批节点',
              },
              languages: {
                en: {
                  text: 'Frozen approval',
                  requestedTag: 'en',
                  resourceTag: 'en',
                  missing: false,
                  fallback: false,
                },
              },
            },
          },
        ],
        routes: [],
      };
    if (path.endsWith('/actions'))
      return {
        records: [
          {
            actionCode: 'approve',
            title: '通过',
            nodeTitle: '冻结审批节点',
            nodeKey: 'approve',
            taskId: 't',
            reasonRequired: false,
          },
        ],
      };
    return { records: [] };
  });
  const context = {
    moduleAlias: 'demo.purchase',
    http: { request },
    runtime: { ready: Promise.resolve({}) },
  } as unknown as ModuleContext<Record<string, unknown>>;
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(WorkflowRecordPanel, { context, recordId: 'r' });
    },
  });
  const wrapper = mount(Host, {
    global: {
      stubs: {
        RecordDetailDrawer: drawer,
        WorkflowDiagram: true,
        RecordPicker: true,
        AdaptiveHeaderActionBar: true,
        RecordQueryListSurface: true,
      },
    },
  });
  try {
    await flushPromises();
    const bar = wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' });
    expect(bar.props('actions')[0].title).toBe('Frozen approval · Approve');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(bar.props('actions')[0].title).toBe('冻结审批节点 · 通过');
    expect(request.mock.calls.filter(([args]) => args.path.endsWith('/actions'))).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

it('keeps an opened list guide caption reactive instead of freezing its first translated title', async () => {
  const { platformLanguageContext } = await import('@muyun/web-core');
  const previous = platformLanguageContext.requestedLanguageTag.value;
  platformLanguageContext.setLanguage('en');
  const { wrapper, request } = fixture();
  const original = request.getMockImplementation()!;
  request.mockImplementation(async (call) =>
    call.path.endsWith('/prepare')
      ? {
          evaluation: {
            passed: true,
            checkResults: [],
            guides: [
              {
                guideKey: 'list',
                guideKind: 'open_list',
                title: '相关业务记录',
                targetModuleAlias: 'demo.purchase',
                titleText: {
                  source: {
                    sourceKind: 'CONFIG',
                    sourceId: 'frozen-guide',
                    sourceVersion: 'v1',
                    originalText: '相关业务记录',
                  },
                  languages: {
                    en: {
                      text: 'Related business records',
                      requestedTag: 'en',
                      resourceTag: 'en',
                      missing: false,
                      fallback: false,
                    },
                  },
                },
              },
            ],
          },
        }
      : original(call),
  );
  try {
    await flushPromises();
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Related business records')!
      .trigger('click');
    await flushPromises();
    const list = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(list.props('title')).toBe('Related business records');
    platformLanguageContext.setLanguage('zh-CN');
    await flushPromises();
    expect(list.props('title')).toBe('相关业务记录');
    expect(request.mock.calls.filter(([call]) => call.path.endsWith('/prepare'))).toHaveLength(1);
  } finally {
    wrapper.unmount();
    platformLanguageContext.setLanguage(previous);
  }
});

it('passes the independent consumer language to task-draft discard confirmation', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  expect(platformLanguageContext.languageTag.value).toBe('zh-CN');
  const { wrapper, context } = fixture(undefined, language);
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '登记到货')!
    .trigger('click');
  await flushPromises();
  wrapper.findComponent(fields).vm.$emit('update:field', 'remark', '尚未保存');
  await flushPromises();
  vi.mocked(confirmAction).mockClear();
  vi.mocked(confirmAction).mockResolvedValueOnce(false);
  const panel = wrapper.findComponent(WorkflowRecordPanel);
  expect(await (panel.vm as unknown as { mayLeave(): Promise<boolean> }).mayLeave()).toBe(false);
  expect(confirmAction).toHaveBeenCalledWith(
    expect.objectContaining({ title: 'Discard unsaved task input?' }),
    language,
  );
  expect(wrapper.findComponent(fields).props('record').remark).toBe('尚未保存');
  expect(vi.mocked(context.crud.view)).toHaveBeenCalledOnce();
  wrapper.unmount();
});

it('updates an existing validation prompt when language changes while preserving the task draft', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const { wrapper, request, context } = fixture(undefined, language);
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '登记到货')!
    .trigger('click');
  await flushPromises();
  const form = wrapper.findComponent(fields);
  form.vm.$emit('update:field', 'remark', '业务原文草稿');
  form.vm.$emit('validity-change', { valid: false, errors: { remark: 'required' } });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Save business data and complete task')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('Correct the marked business fields before submitting');
  const reads = request.mock.calls.length;
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(wrapper.text()).toContain('请修正标记的业务字段后再提交');
  expect(form.props('record').remark).toBe('业务原文草稿');
  expect(request.mock.calls).toHaveLength(reads);
  expect(vi.mocked(context.crud.view)).toHaveBeenCalledOnce();
  expect(request.mock.calls.some(([call]) => call.path.endsWith('/execute'))).toBe(false);
  wrapper.unmount();
});

it('uses a safe reactive load failure instead of exposing upstream server diagnostics', async () => {
  const { AppError } = await import('@muyun/web-core');
  const language = createLanguageContext({ languageTag: 'en' });
  const { wrapper, request } = fixture(undefined, language);
  await flushPromises();
  request.mockImplementationOnce(async () => {
    throw new AppError('private upstream database details', { code: 'HTTP_ERROR', status: 503 });
  });
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Refresh workflow')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.get('[role="alert"]').text()).toBe('A system error prevented the operation from completing');
  expect(wrapper.text()).not.toContain('private upstream');
  const calls = request.mock.calls.length;
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(wrapper.get('[role="alert"]').text()).toBe('系统异常，操作未完成');
  expect(request.mock.calls).toHaveLength(calls);
  wrapper.unmount();
});

it('preserves a newly authored additional-approval title across language switching and protects it from discard', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const { wrapper, request } = fixture(undefined, language);
  const original = request.getMockImplementation()!;
  request.mockImplementation(async (call) =>
    call.path.endsWith('/actions')
      ? {
          records: [
            { actionCode: 'addSign', title: '加签', taskId: 't', nodeKey: 'approve', reasonRequired: true },
          ],
        }
      : original(call),
  );
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  const title = wrapper.findComponent({ name: 'UiInput' });
  expect(title.props('value')).toBe('Additional approval');
  title.vm.$emit('update:value', '我填写的审批标题');
  await flushPromises();
  const calls = request.mock.calls.length;
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(title.props('value')).toBe('我填写的审批标题');
  expect(request.mock.calls).toHaveLength(calls);
  vi.mocked(confirmAction).mockClear();
  vi.mocked(confirmAction).mockResolvedValueOnce(false);
  const panel = wrapper.findComponent(WorkflowRecordPanel);
  expect(await (panel.vm as unknown as { mayLeave(): Promise<boolean> }).mayLeave()).toBe(false);
  expect(confirmAction).toHaveBeenCalledWith(
    expect.objectContaining({ title: '放弃未保存的办理内容？' }),
    language,
  );
  expect(title.props('value')).toBe('我填写的审批标题');
  wrapper.unmount();
});

it('re-resolves an opened submit preview title from its stable action code on language switching', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const { wrapper, request } = fixture(undefined, language);
  const original = request.getMockImplementation()!;
  request.mockImplementation(async (call) => {
    if (call.path.endsWith('/submit/status')) return { displayStatus: 'UNSUBMITTED', canSubmit: true };
    if (call.path.endsWith('/submit/preview'))
      return {
        instance: { id: 'preview', instanceStatus: 'unsubmitted' },
        nodes: [],
        routes: [],
        tasks: [],
      };
    return original(call);
  });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Refresh workflow')!
    .trigger('click');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Preview and submit approval')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.findComponent(drawer).props('title')).toBe('Submit for approval');
  const calls = request.mock.calls.length;
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(wrapper.findComponent(drawer).props('title')).toBe('提交审批');
  expect(request.mock.calls).toHaveLength(calls);
  expect(request.mock.calls.some(([call]) => call.path.endsWith('/actions/submitApproval'))).toBe(false);
  wrapper.unmount();
});

it.each([true, false])(
  'refreshes frozen check text after live evidence changes (all checks pass: %s) while retaining captured evidence and input',
  async (allPass) => {
    const language = createLanguageContext({ languageTag: 'en' });
    const bindings = ref<unknown>({ publication: 0 });
    const { wrapper, request, context } = fixture(undefined, language, bindings);
    const original = request.getMockImplementation()!;
    let publication = 0;
    const text = (property: string, originalText: string, chinese: string, checkKey = 'ready') => ({
      source: {
        sourceKind: 'CONFIG',
        sourceId: `config/workflow/version/${publication === 2 ? 'v2' : 'v1'}/node/business/check/${checkKey}/${property}`,
        sourceVersion: 'frozen-original-v1',
        originalText,
      },
      languages: {
        en: { text: originalText, requestedTag: 'en', missing: false, fallback: false },
        'zh-CN': { text: chinese, requestedTag: 'zh-CN', missing: false, fallback: false },
      },
    });
    request.mockImplementation(async (call) => {
      const result = await original(call);
      if (!call.path.endsWith('/prepare')) return result;
      const title = 'Business completion condition';
      const failure = 'Business data does not yet meet the completion condition';
      const failureMessageText = text(
        'failureMessage',
        failure,
        publication === 0
          ? '业务完成条件尚未满足'
          : publication === 1
            ? '修正后的完成提示'
            : '另一版本的错误提示',
      );
      const titleText = text(
        'title',
        title,
        publication === 0 ? '业务完成条件' : publication === 1 ? '修正后的检查名称' : '另一版本的错误名称',
      );
      const changed = publication > 0;
      const otherFailure = text('failureMessage', failure, '其他检查仍未满足', 'other');
      return {
        ...(result as object),
        evaluation: {
          ...(result as { evaluation: object }).evaluation,
          passed: changed && allPass,
          failureCheckKey: changed ? (allPass ? null : 'other') : 'ready',
          failureMessage: changed && allPass ? null : failure,
          failureMessageText: changed ? (allPass ? undefined : otherFailure) : failureMessageText,
          checkTexts: [{ checkKey: 'ready', title, titleText, failureMessage: failure, failureMessageText }],
          checkResults: [
            {
              checkKey: 'ready',
              checkTitle: title,
              checkTitleText: titleText,
              failureMessage: changed ? null : failure,
              failureMessageText: changed ? undefined : failureMessageText,
              passed: changed,
              checkRunId: changed ? 'new-check-run' : 'original-check-run',
              resultPayloadText: JSON.stringify({ evidence: publication }),
            },
            ...(changed && !allPass
              ? [
                  {
                    checkKey: 'other',
                    checkTitle: '其他检查',
                    passed: false,
                    failureMessage: failure,
                    failureMessageText: otherFailure,
                  },
                ]
              : []),
          ],
        },
      };
    });
    await flushPromises();
    wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
    await flushPromises();
    expect(wrapper.text()).toContain('Business completion condition');
    expect(wrapper.text()).toContain('Business data does not yet meet the completion condition');
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '登记到货')!
      .trigger('click');
    await flushPromises();
    const form = wrapper.findComponent(fields);
    form.vm.$emit('update:field', 'remark', '保留用户原文');
    await flushPromises();
    const reads = request.mock.calls.length;
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.text()).toContain('业务完成条件尚未满足');
    expect(wrapper.text()).toContain('业务完成条件');
    expect(request.mock.calls).toHaveLength(reads);
    expect(form.props('record').remark).toBe('保留用户原文');
    publication = 1;
    bindings.value = { publication };
    await flushPromises();
    expect(wrapper.text()).toContain('修正后的检查名称');
    expect(wrapper.text()).toContain('修正后的完成提示');
    // Re-evaluation during resource refresh cannot replace the displayed check verdict.
    expect(wrapper.text()).not.toContain('当前业务条件已满足');
    expect(wrapper.findAll('li').some((item) => item.text().includes('其他检查'))).toBe(false);
    expect(wrapper.findAll('li').some((item) => item.text() === '修正后的检查名称 · 修正后的完成提示')).toBe(
      true,
    );
    expect(form.props('record').remark).toBe('保留用户原文');
    publication = 2;
    bindings.value = { publication };
    await flushPromises();
    expect(wrapper.text()).toContain('修正后的检查名称');
    expect(wrapper.text()).not.toContain('另一版本的错误');
    expect(wrapper.text()).not.toContain('当前业务条件已满足');
    expect(vi.mocked(context.crud.view)).toHaveBeenCalledOnce();
    expect(request.mock.calls.some(([call]) => /\/(execute|actions\/complete)$/.test(call.path))).toBe(false);
    wrapper.unmount();
  },
);

it('updates a retained submission rejection on language changes without rematching or submitting', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  language.registerResources('en', { 'test.workflow.match-rejected': 'Cannot submit {record}' });
  language.registerResources('zh-CN', { 'test.workflow.match-rejected': '不能提交{record}' });
  const raw = '不能提交原始 {record}';
  const status = {
    displayStatus: 'MATCH_ERROR',
    canSubmit: false,
    errorMessage: raw,
    errorMessageText: createCodeTextPresentation('test.workflow.match-rejected', raw, language, {
      record: '原始 {record}',
    }),
  };
  const { wrapper, request } = fixture(undefined, language);
  const original = request.getMockImplementation()!;
  request.mockImplementation(async (call) =>
    call.path.endsWith('/submit/status') ? status : original(call),
  );
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Refresh workflow')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('Cannot submit 原始 {record}');
  const calls = request.mock.calls.length;
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(wrapper.text()).toContain(raw);
  language.setLanguage('en');
  await flushPromises();
  expect(wrapper.text()).toContain('Cannot submit 原始 {record}');
  expect(request.mock.calls).toHaveLength(calls);
  expect(wrapper.text()).not.toContain('Preview and submit approval');
  expect(status.errorMessage).toBe(raw);
  expect(status.canSubmit).toBe(false);
  wrapper.unmount();
});

it('preserves the explicitly chosen opinion language and draft across interface language changes', async () => {
  const language = createLanguageContext();
  const { wrapper, request } = fixture(undefined, language);
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  const sourceLanguage = wrapper
    .findAllComponents({ name: 'UiSelect' })
    .find(
      (select) =>
        select.props('ariaLabel') === language.t('platform.content.source-language', '本次原文的填写语言'),
    )!;
  expect(sourceLanguage.props('value')).toBe('__unknown__');
  await wrapper.find('textarea').setValue('Bis zur Nachprüfung nicht versenden.');
  sourceLanguage.vm.$emit('update:value', 'de');
  await flushPromises();
  language.setLanguage('en');
  await flushPromises();
  expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe(
    'Bis zur Nachprüfung nicht versenden.',
  );
  expect(sourceLanguage.props('value')).toBe('de');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '确认到货')!
    .trigger('click');
  await flushPromises();
  expect(
    request.mock.calls.find(([entry]) => entry.path.endsWith('/guides/deliver/execute'))?.[0],
  ).toMatchObject({
    body: { reason: 'Bis zur Nachprüfung nicht versenden.', reasonSourceLanguage: 'de' },
  });
  wrapper.unmount();
});

it('carries task-form source inputs separately and only for editable submitted business fields', async () => {
  const { wrapper, request } = fixture();
  await flushPromises();
  wrapper.findComponent({ name: 'AdaptiveHeaderActionBar' }).vm.$emit('action', { key: '0' });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '登记到货')!
    .trigger('click');
  await flushPromises();
  const form = wrapper.findComponent(fields);
  form.vm.$emit('validity-change', { valid: true, errors: {} });
  form.vm.$emit('update:field', 'remark', 'Retest the replacement seal.');
  form.vm.$emit('update:field', '$contentLanguages', {
    remark: { languageTag: 'en', expectedRevision: 2 },
    privateField: { languageTag: 'de', expectedRevision: 1 },
  });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '保存业务并完成任务')!
    .trigger('click');
  await flushPromises();
  expect(
    request.mock.calls.find(([entry]) => entry.path.endsWith('/guides/form/execute'))?.[0],
  ).toMatchObject({
    body: {
      values: { remark: 'Retest the replacement seal.' },
      contentLanguages: { remark: { languageTag: 'en', expectedRevision: 2 } },
    },
  });
  const submitted = request.mock.calls.find(([entry]) => entry.path.endsWith('/guides/form/execute'))?.[0]
    .body as { values: unknown; contentLanguages: Record<string, unknown> };
  expect(submitted.contentLanguages).not.toHaveProperty('privateField');
  expect(submitted.values).not.toHaveProperty('$contentLanguages');
  wrapper.unmount();
});
