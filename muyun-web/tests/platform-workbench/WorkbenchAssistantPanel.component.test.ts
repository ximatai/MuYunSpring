import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { ref } from 'vue';
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
  expect(wrapper.get('.assistant-message--assistant').text()).toBe('已经找到对应页面');
});

it('renders assistant Markdown as readable semantic content', async () => {
  const requestTurn = vi.fn(async () => ({
    text: [
      '### 处理结果',
      '',
      '- 已找到职员管理',
      '- 可以继续新增数据',
      '',
      '`employee.create`',
      '',
      '| 字段 | 值 |',
      '| --- | --- |',
      '| 姓名 | 张三 |',
      '',
      '[查看说明](https://example.com/help)',
    ].join('\n'),
    toolCalls: [] as never[],
  }));
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('告诉我处理结果');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  const message = wrapper.get('.assistant-message--assistant');
  expect(message.get('h3').text()).toBe('处理结果');
  expect(message.findAll('li').map((item) => item.text())).toEqual(['已找到职员管理', '可以继续新增数据']);
  expect(message.get('code').text()).toBe('employee.create');
  expect(message.findAll('td').map((cell) => cell.text())).toEqual(['姓名', '张三']);
  expect(message.get('a').attributes()).toMatchObject({
    href: 'https://example.com/help',
    rel: 'noopener noreferrer',
    target: '_blank',
  });
});

it('keeps assistant Markdown inside a non-executable rendering boundary', async () => {
  const requestTurn = vi.fn(async () => ({
    text: [
      '<script>window.compromised = true</script>',
      '',
      '[危险链接](javascript:alert(1))',
      '',
      '![远程图片](https://example.com/tracker.png)',
    ].join('\n'),
    toolCalls: [] as never[],
  }));
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('展示不可信内容');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  const message = wrapper.get('.assistant-message--assistant');
  expect(message.find('script').exists()).toBe(false);
  expect(message.find('img').exists()).toBe(false);
  expect(message.find('a').exists()).toBe(false);
  expect(message.text()).toContain('<script>window.compromised = true</script>');
  expect(message.text()).toContain('危险链接');
  expect(message.text()).toContain('远程图片');
});

it('keeps user-authored Markdown as plain text', async () => {
  const requestTurn = vi.fn(async () => ({ text: '收到', toolCalls: [] as never[] }));
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('**不要渲染我的输入**');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  const userMessage = wrapper.get('.assistant-message--user');
  expect(userMessage.text()).toBe('**不要渲染我的输入**');
  expect(userMessage.find('strong').exists()).toBe(false);
});

it('renders optional suggestions and submits a structured selection response', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [],
      finishReason: 'tool_calls',
      selection: {
        interactionId: 'selection-1',
        prompt: '你想先做哪一步？',
        inputPolicy: 'free_text_allowed',
        presentation: 'options',
        options: [
          { id: 'inspect', label: '查看当前页面' },
          { id: 'create', label: '新增一条记录' },
        ],
      },
    })
    .mockResolvedValueOnce({ text: '我会继续新增记录。', toolCalls: [] });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('帮我处理当前业务');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(wrapper.text()).toContain('你想先做哪一步？');
  expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined();
  const createButton = wrapper.findAll('button').find((button) => button.text().includes('新增一条记录'))!;
  await createButton.trigger('click');
  await flushPromises();

  expect(requestTurn).toHaveBeenNthCalledWith(
    2,
    expect.objectContaining({
      message: '新增一条记录',
      selectionResponse: {
        interactionId: 'selection-1',
        optionId: 'create',
        label: '新增一条记录',
      },
    }),
    expect.any(AbortSignal),
    expect.any(Object),
  );
  expect(wrapper.text()).toContain('已选择：新增一条记录');
  expect(wrapper.text()).toContain('我会继续新增记录');
});

it('blocks free text until a required confirmation is answered', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [],
      finishReason: 'tool_calls',
      selection: {
        interactionId: 'confirmation-1',
        prompt: '确认应用当前查询条件吗？',
        inputPolicy: 'selection_required',
        presentation: 'confirmation',
        options: [
          { id: 'confirm', label: '确认' },
          { id: 'cancel', label: '取消' },
        ],
      },
    })
    .mockResolvedValueOnce({ text: '已取消本次提议。', toolCalls: [] });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('帮我调整查询');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(wrapper.get('textarea').attributes('disabled')).toBeDefined();
  expect(wrapper.get('textarea').attributes('placeholder')).toBe('请先完成上方选择');
  const cancelButton = wrapper.findAll('.assistant-selection__options button')[1]!;
  await cancelButton.trigger('click');
  await flushPromises();

  expect(requestTurn).toHaveBeenNthCalledWith(
    2,
    expect.objectContaining({
      selectionResponse: {
        interactionId: 'confirmation-1',
        optionId: 'cancel',
        label: '取消',
      },
    }),
    expect.any(AbortSignal),
    expect.any(Object),
  );
  expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined();
  expect(wrapper.text()).toContain('已选择：取消');
});

it('expires optional suggestions when the user continues with free text', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [],
      selection: {
        interactionId: 'selection-1',
        prompt: '你可以继续：',
        inputPolicy: 'free_text_allowed',
        presentation: 'options',
        options: [
          { id: 'summary', label: '总结当前页面' },
          { id: 'next', label: '执行下一步' },
        ],
      },
    })
    .mockResolvedValueOnce({ text: '我会按你的新描述继续。', toolCalls: [] });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('给我几个建议');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  await wrapper.get('textarea').setValue('我想换一个处理方式');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(wrapper.text()).toContain('此选择已更新');
  expect(wrapper.findAll('button').some((button) => button.text().includes('总结当前页面'))).toBe(false);
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
  expect(wrapper.text()).toContain('正在组织回复');

  complete({ text: '正在处理', toolCalls: [], finishReason: 'stop' });
  await flushPromises();

  expect(wrapper.findAll('.assistant-message--assistant')).toHaveLength(1);
  expect(wrapper.text()).not.toContain('正在组织回复');
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
      effect: 'page',
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
      effect: 'page',
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
    .mockRejectedValueOnce(new Error('model returned no executable content'))
    .mockResolvedValueOnce({ text: '继续填写剩余字段。', toolCalls: [] });
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'page',
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
  expect(wrapper.text()).toContain('前面的 1 项页面操作已生效，后续处理失败，目标可能尚未完成');
  expect(wrapper.text()).not.toContain('model returned no executable content');

  await wrapper.get('textarea').setValue('继续');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  expect(requestTurn).toHaveBeenLastCalledWith(
    expect.objectContaining({ message: '继续', history: [{ role: 'user', text: '填写当前草稿' }] }),
    expect.any(AbortSignal),
    expect.any(Object),
  );
});

const requiredChoice: AssistantTurnOutput = {
  toolCalls: [],
  selection: {
    interactionId: 'choose-tenant',
    prompt: '请选择在哪个租户录入职员',
    inputPolicy: 'selection_required',
    presentation: 'options',
    options: [
      { id: 'a', label: '租户甲' },
      { id: 'b', label: '租户乙' },
    ],
  },
};

it('lets the user abandon a required choice locally and express a new goal', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce(requiredChoice)
    .mockResolvedValueOnce({ text: '收到', toolCalls: [] });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });
  await wrapper.get('textarea').setValue('录入职员');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '放弃本次提议')!
    .trigger('click');
  expect(requestTurn).toHaveBeenCalledOnce();
  expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined();
  await wrapper.get('textarea').setValue('查看当前页面');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  expect(requestTurn.mock.calls[1]![0].history.at(-1)).toEqual({
    role: 'user',
    text: '放弃本次提议：请选择在哪个租户录入职员',
  });
});

it.each(['navigation', 'unregister', 'context'] as const)(
  'expires an unanswered choice after %s changes',
  async (change) => {
    const revision = ref('initial');
    const requestTurn = vi.fn().mockResolvedValue(requiredChoice);
    const registry = createAssistantSurfaceRegistry();
    const unregister = registry.register({
      pageInstanceKey: 'tab-a',
      contextRevision: () => revision.value,
      surface: { describe: () => ({ surface: 'workbench', facts: {} }), capabilities: () => [], requestTurn },
    });
    registry.activate('tab-a');
    const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
    await wrapper.get('textarea').setValue('录入职员');
    await wrapper.get('.assistant-panel__actions button').trigger('click');
    await flushPromises();
    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined();
    if (change === 'navigation') registry.activate('tab-b');
    else if (change === 'unregister') unregister();
    else revision.value = 'changed';
    await flushPromises();
    expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('.assistant-selection__options').exists()).toBe(false);
    expect(requestTurn).toHaveBeenCalledOnce();
  },
);

it('does not append missing-response feedback after a selection-only follow-up', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'inspect-1', code: 'page.inspect', input: {} }] })
    .mockResolvedValueOnce(requiredChoice);
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'page',
      descriptor: { code: 'page.inspect', description: 'Inspect', inputSchema: {} },
      parseInput: (input) => input,
      execute: async () => ({ inspected: true }),
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('录入职员');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain(requiredChoice.selection!.prompt);
  expect(wrapper.text()).not.toContain('未生成可展示的说明');
});

it('clears history and input across tenant scopes and discards an old in-flight response', async () => {
  const scope = ref('tenant-a');
  const registry = createAssistantSurfaceRegistry();
  let finish!: (value: AssistantTurnOutput) => void;
  const requestTurn = vi.fn<AssistantTurnRequester>(
    () =>
      new Promise<AssistantTurnOutput>((resolve) => {
        finish = resolve;
      }),
  );
  registry.register({
    pageInstanceKey: 'a',
    contextRevision: () => 'stable',
    conversationScopeKey: () => scope.value,
    surface: { describe: () => ({ surface: 'test', facts: {} }), capabilities: () => [], requestTurn },
  });
  registry.activate('a');
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('tenant-a secret');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  scope.value = 'tenant-b';
  await flushPromises();
  finish({ text: 'old answer', toolCalls: [] });
  await flushPromises();
  expect(wrapper.text()).not.toContain('tenant-a secret');
  expect(wrapper.text()).not.toContain('old answer');
  expect(wrapper.text()).toContain('已开始新会话');
  expect(requestTurn).toHaveBeenCalledTimes(1);
  const reuse = wrapper.findAll('button').find((button) => button.text() === '复用上一条输入')!;
  await reuse.trigger('click');
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('tenant-a secret');
  expect(requestTurn).toHaveBeenCalledTimes(1);
  requestTurn.mockResolvedValue({ text: 'new answer', toolCalls: [] });
  await wrapper.get('textarea').setValue('new request');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  expect(requestTurn.mock.calls.at(-1)?.[0]).toMatchObject({ history: [] });
});

it('renders trusted capability presentations without knowing the capability code', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'new', code: 'extension.custom-preview', input: {} }] })
    .mockResolvedValueOnce({ text: '请审阅', toolCalls: [] });
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'read',
      descriptor: { code: 'extension.custom-preview', description: 'Preview', inputSchema: {} },
      parseInput: (input) => input,
      execute: async () => ({ valid: true }),
      present: () => ({ title: '配置候选（尚未生效）', lines: ['预检通过', '新增字段：备注'] }),
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('检查候选');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('配置候选（尚未生效）');
  expect(wrapper.text()).toContain('新增字段：备注');
});
