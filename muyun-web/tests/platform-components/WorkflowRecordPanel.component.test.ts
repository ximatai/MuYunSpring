import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { it, expect, vi } from 'vitest';
import { confirmAction } from '@muyun/vue-ui-antdv';
vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(async () => false),
}));
import WorkflowRecordPanel from '@/platform-components/WorkflowRecordPanel.vue';
import type { ModuleContext } from '@muyun/web-core';
const drawer = defineComponent({
  name: 'RecordDetailDrawer',
  props: ['open'],
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
function fixture(onChanged?: () => void) {
  const request = vi.fn(
    async ({ path }: { path: string; method?: string; body?: unknown }): Promise<unknown> => {
      if (path.endsWith('/submit/status')) return { displayStatus: 'PROCESSING', instanceId: 'i' };
      if (path === '/workflow/history/query') return { records: [] };
      if (path.endsWith('/bundle'))
        return { instance: { id: 'i', instanceStatus: 'running' }, nodes: [], routes: [] };
      if (path.endsWith('/actions'))
        return {
          records: [
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
    runtime: {
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
  const wrapper = mount(WorkflowRecordPanel, {
    props: { context, recordId: 'r', onChanged },
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
