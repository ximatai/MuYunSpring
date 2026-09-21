import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import WorkbenchAssistantPanel from '@/platform-workbench/WorkbenchAssistantPanel.vue';
import type { AssistantTurnOutput } from '@muyun/web-contracts';
import {
  createAssistantSurfaceRegistry,
  StaleAssistantInvocationError,
  type AssistantCapability,
  type AssistantTurnRequester,
} from '@muyun/web-core';

function createRegistry(requestTurn: AssistantTurnRequester) {
  return createRegistryWithCapabilities(requestTurn, []);
}

function createRegistryWithCapabilities(
  requestTurn: AssistantTurnRequester,
  capabilities: AssistantCapability[],
) {
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => capabilities,
      requestTurn,
    },
  });
  registry.activate('tab-a');
  return registry;
}

it('submits a user request and renders the final assistant response', async () => {
  const requestTurn = vi.fn(async () => ({ text: '已经找到对应页面', toolCalls: [] as never[] }));
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('打开客户管理');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(requestTurn).toHaveBeenCalledOnce();
  expect(wrapper.text()).toContain('打开客户管理');
  expect(wrapper.text()).toContain('已经找到对应页面');
});

it('continues a broad user goal after clarification with bounded dialogue history', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ text: '请告诉我要在哪个租户新增职员。', toolCalls: [] })
    .mockResolvedValueOnce({ text: '我会继续处理新增职员。', toolCalls: [] });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('我要新增一名职员，帮我做');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  await wrapper.get('textarea').setValue('演示租户');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(requestTurn).toHaveBeenNthCalledWith(
    2,
    expect.objectContaining({
      message: '演示租户',
      history: [
        { role: 'user', text: '我要新增一名职员，帮我做' },
        { role: 'assistant', text: '请告诉我要在哪个租户新增职员。' },
      ],
    }),
    expect.any(AbortSignal),
    expect.any(Object),
  );
});

it('keeps the interrupted user goal so a short continuation can resume it', async () => {
  const requestTurn = vi
    .fn()
    .mockRejectedValueOnce(new StaleAssistantInvocationError())
    .mockResolvedValueOnce({ text: '我会基于当前页面继续录入。', toolCalls: [] });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('我要录入一名新职员，姓名是张三');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('请确认当前页面后告诉我继续或调整目标');

  await wrapper.get('textarea').setValue('继续');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(requestTurn).toHaveBeenNthCalledWith(
    2,
    expect.objectContaining({
      message: '继续',
      history: [{ role: 'user', text: '我要录入一名新职员，姓名是张三' }],
    }),
    expect.any(AbortSignal),
    expect.any(Object),
  );
});

it('renders streamed assistant text before the terminal turn arrives without duplicating it', async () => {
  let complete!: (value: { text: string; toolCalls: never[]; finishReason: string }) => void;
  const requestTurn: AssistantTurnRequester = vi.fn((_input, _signal, progress) => {
    progress?.onTextDelta?.('正在');
    progress?.onTextDelta?.('处理');
    return new Promise<AssistantTurnOutput>((resolve) => {
      complete = resolve;
    });
  });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('描述当前页面');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(wrapper.text()).toContain('正在处理');
  expect(wrapper.text()).toContain('正在理解并执行');

  complete({ text: '正在处理', toolCalls: [], finishReason: 'stop' });
  await flushPromises();

  expect(wrapper.findAll('.assistant-message--assistant')).toHaveLength(1);
  expect(wrapper.text()).not.toContain('正在理解并执行');
});

it('removes an uncommitted partial response when its stream fails', async () => {
  const requestTurn: AssistantTurnRequester = vi.fn(async (_input, _signal, progress) => {
    progress?.onTextDelta?.('不完整的回答');
    throw new Error('stream failed');
  });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('描述当前页面');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(wrapper.text()).not.toContain('不完整的回答');
  expect(wrapper.text()).toContain('stream failed');
});

it('cancels an in-flight request from the panel', async () => {
  const requestTurn = vi.fn(
    (_input, signal: AbortSignal) =>
      new Promise<{ toolCalls: never[] }>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('cancelled', 'AbortError')), {
          once: true,
        });
      }),
  );
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('执行一个较慢的任务');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();

  expect(wrapper.text()).toContain('已停止本次操作');
});

it('cancels an in-flight request when the panel is closed', async () => {
  const requestTurn = vi.fn(
    (_input, signal: AbortSignal) =>
      new Promise<{ toolCalls: never[] }>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('cancelled', 'AbortError')), {
          once: true,
        });
      }),
  );
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('打开职员管理');
  await wrapper.get('textarea').trigger('keydown', { key: 'Enter' });
  await vi.waitFor(() => expect(requestTurn).toHaveBeenCalledOnce());
  await wrapper.get('[aria-label="关闭智能助手"]').trigger('click');
  await flushPromises();

  expect(wrapper.emitted('close')).toHaveLength(1);
  expect(wrapper.text()).toContain('已停止本次操作');
});

it('does not carry a cancelled goal into the next user request', async () => {
  const requestTurn = vi
    .fn()
    .mockImplementationOnce(
      (_input, signal: AbortSignal) =>
        new Promise<{ toolCalls: never[] }>((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(new DOMException('cancelled', 'AbortError')), {
            once: true,
          });
        }),
    )
    .mockResolvedValueOnce({ text: '当前页面是职员管理。', toolCalls: [] });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('删除当前职员');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  await wrapper.get('textarea').setValue('当前是什么页面？');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(requestTurn).toHaveBeenNthCalledWith(
    2,
    expect.objectContaining({ message: '当前是什么页面？', history: [] }),
    expect.any(AbortSignal),
    expect.any(Object),
  );
});

it('does not report a read-only result as a completed page operation', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-1', code: 'page.inspect', input: {} }] })
    .mockResolvedValueOnce({ toolCalls: [] });
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      descriptor: { code: 'page.inspect', description: 'Inspect page', inputSchema: {} },
      parseInput: (input) => input,
      execute: async () => ({ inspected: true }),
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });

  await wrapper.get('textarea').setValue('检查当前页面');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(wrapper.text()).toContain('已获取 1 项结果');
  expect(wrapper.text()).toContain('信息已读取，但未生成可展示的说明');
  expect(wrapper.text()).not.toContain('页面操作已完成');
});

it('treats an empty follow-up as completion after an applied page operation', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-1', code: 'form.patch-draft', input: {} }] })
    .mockResolvedValueOnce({ toolCalls: [] });
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      descriptor: { code: 'form.patch-draft', description: 'Patch draft', inputSchema: {} },
      parseInput: (input) => input,
      async execute(_input, context) {
        context.applyEffect(() => undefined);
        return { changed: true };
      },
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });

  await wrapper.get('textarea').setValue('填写当前草稿');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(wrapper.text()).toContain('已应用 1 项页面操作');
  expect(wrapper.text()).toContain('页面操作已完成，请检查当前页面');
});

it('keeps successful operation feedback when the model follow-up fails', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-1', code: 'form.patch-draft', input: {} }] })
    .mockRejectedValueOnce(new Error('model returned no executable content'));
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      descriptor: { code: 'form.patch-draft', description: 'Patch draft', inputSchema: {} },
      parseInput: (input) => input,
      async execute(_input, context) {
        context.applyEffect(() => undefined);
        return { changed: true };
      },
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });

  await wrapper.get('textarea').setValue('填写当前草稿');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(wrapper.text()).toContain('已应用 1 项页面操作');
  expect(wrapper.text()).toContain('前面的 1 项页面操作已生效，但后续说明未能生成');
  expect(wrapper.text()).not.toContain('model returned no executable content');
});
