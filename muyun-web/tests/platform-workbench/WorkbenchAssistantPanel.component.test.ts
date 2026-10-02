import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { createConfigurationCollaboration } from '@/platform-workbench/configurationCollaboration';
import WorkbenchAssistantPanel from '@/platform-workbench/WorkbenchAssistantPanel.vue';
import type { AssistantTurnOutput } from '@muyun/web-contracts';
import {
  AppError,
  createAssistantSurfaceRegistry,
  AssistantCapabilityUsageError,
  StaleAssistantInvocationError,
  type AssistantCapability,
  type AssistantTurnRequester,
} from '@muyun/web-core';

function createRegistry(requestTurn: AssistantTurnRequester) {
  return createRegistryWithCapabilities(requestTurn, []);
}

it.each([
  ['CONFIG_MISSING', '当前身份缺少可用的模型配置'],
  ['AI_PROVIDER_AUTHENTICATION_FAILED', '模型连接鉴权失败'],
  ['AI_PROVIDER_RATE_LIMITED', '模型服务限制了本次请求'],
  ['AI_PROVIDER_UNAVAILABLE', '模型服务暂时不可用'],
  ['AI_PROVIDER_REQUEST_REJECTED', '模型服务拒绝了本次请求'],
  ['AI_MODEL_TIMEOUT', '等待模型回复超时'],
  ['AI_MODEL_CONNECTION_FAILED', '模型连接失败'],
  ['AI_MODEL_INCOMPLETE_RESPONSE', '模型回复在完成前断开'],
  ['AI_MODEL_INTERRUPTED', '模型请求已中断'],
])(
  'explains the model connection failure by stable code without replaying the request: %s',
  async (code, explanation) => {
    const requestTurn = vi.fn(async () => {
      throw new AppError('opaque diagnostic', { code });
    });
    const wrapper = mount(WorkbenchAssistantPanel, {
      props: { open: true, registry: createRegistry(requestTurn) },
    });
    await wrapper.get('textarea').setValue('查看当前客户配置，先不要改');
    await wrapper.get('button.ant-btn-primary').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain(explanation);
    expect(wrapper.text()).toContain('待确认内容没有提交');
    if (['AI_MODEL_TIMEOUT', 'AI_MODEL_INCOMPLETE_RESPONSE', 'AI_MODEL_INTERRUPTED'].includes(code))
      expect(wrapper.text()).toContain('请核实当前页面后继续处理');
    expect(wrapper.text()).not.toContain('opaque diagnostic');
    if (code === 'CONFIG_MISSING') expect(wrapper.text()).not.toContain('可以调整需求后继续处理');
    expect(requestTurn).toHaveBeenCalledOnce();
    wrapper.unmount();
  },
);

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

it('keeps free text available while a required choice can still be answered', async () => {
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

  expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined();
  expect(wrapper.get('textarea').attributes('placeholder')).toBe('可以选择上方选项，也可以补充说明');
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

it.each(['free_text_allowed', 'selection_required'] as const)(
  'supersedes %s choices when the user supplies text without selecting an option',
  async (inputPolicy) => {
    const requestTurn = vi
      .fn()
      .mockResolvedValueOnce({
        toolCalls: [],
        selection: {
          interactionId: 'selection-1',
          prompt: '你可以继续：',
          inputPolicy,
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

    expect(requestTurn.mock.calls[1]![0].selectionResponse).toBeUndefined();
    expect(wrapper.text()).toContain('此选择已更新');
    expect(wrapper.findAll('button').some((button) => button.text().includes('总结当前页面'))).toBe(false);
  },
);

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
  expect(wrapper.text()).not.toContain('stream failed');
  expect(wrapper.text()).toContain('本轮回复未能完成');
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

it('retains a cancelled discussion with an explicit stop instead of an active execution goal', async () => {
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
    expect.objectContaining({
      message: '当前是什么页面？',
      history: [
        { role: 'user', text: '删除当前职员' },
        { role: 'assistant', text: '用户已停止本轮执行。需求仅作为讨论记录保留，不得自动继续执行。' },
      ],
    }),
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

  expect(wrapper.text()).not.toContain('已获取 1 项结果');
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

  expect(wrapper.text()).not.toContain('已应用 1 项页面操作');
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

  expect(wrapper.text()).not.toContain('已应用 1 项页面操作');
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

it.each([
  ['AI model response body timed out', '等待模型回复超时'],
  ['模型本次回复在返回可用内容前中止，请稍后重试', '模型服务未返回可用内容'],
  ['AI model request was rejected by provider', '模型服务拒绝了本次请求'],
  ['AI model request was rejected with HTTP status 429', '模型服务拒绝了本次请求'],
  ['模型响应被截断，请缩短描述后重试', '模型本次回复达到长度上限'],
])('retains a safe model failure reason after an applied draft: %s', async (message, expected) => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'draft', code: 'form.patch-draft', input: {} }] })
    .mockRejectedValueOnce(new Error(message));
  const apply = vi.fn();
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'configuration-draft',
      descriptor: { code: 'form.patch-draft', description: '', inputSchema: {} },
      parseInput: (input) => input,
      async execute(_input, context) {
        context.applyEffect(apply);
        return { saved: false };
      },
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('准备草稿');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  expect(apply).toHaveBeenCalledOnce();
  expect(requestTurn).toHaveBeenCalledTimes(2);
  expect(wrapper.text()).toContain(expected);
  expect(wrapper.text()).toContain('待确认内容没有提交');
  expect(wrapper.text()).toContain('前面的 1 项页面操作已生效');
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
    expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined();
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

it.each([false, true])(
  'retains the task across execution scopes and rejects late responses, including returning to the old scope: %s',
  async (returnToOriginal) => {
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
      executionScopeKey: () => scope.value,
      surface: { describe: () => ({ surface: 'test', facts: {} }), capabilities: () => [], requestTurn },
    });
    registry.activate('a');
    const { createConstructionPlanSession } = await import('@/platform-workbench/constructionPlanSession');
    const plan = createConstructionPlanSession(
      {} as import('@muyun/web-core').ConstructionPlanClient,
      () => 'owner',
    );
    const wrapper = mount(WorkbenchAssistantPanel, {
      props: { open: true, registry, constructionPlan: plan },
    });
    plan.resetConversation('old-plan');
    await wrapper.get('textarea').setValue('tenant-a secret');
    await wrapper.get('button.ant-btn-primary').trigger('click');
    await flushPromises();
    scope.value = 'tenant-b';
    await flushPromises();
    if (returnToOriginal) {
      scope.value = 'tenant-a';
      await flushPromises();
    }
    finish({ text: 'old answer', toolCalls: [] });
    await flushPromises();
    expect(wrapper.text()).toContain('tenant-a secret');
    expect(wrapper.text()).not.toContain('old answer');
    expect(plan.current().planId).toBe('old-plan');
    expect(wrapper.text()).toContain('对话与建设目标保留');
    expect(requestTurn).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).not.toContain('本轮已暂停');
    expect(wrapper.findAll('button').some((button) => button.text() === '调整需求')).toBe(false);
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('');
    expect(requestTurn).toHaveBeenCalledTimes(1);
    requestTurn.mockResolvedValue({ text: 'new answer', toolCalls: [] });
    await wrapper.get('textarea').setValue('new request');
    await wrapper.get('button.ant-btn-primary').trigger('click');
    await flushPromises();
    expect(requestTurn.mock.calls.at(-1)?.[0]).toMatchObject({ history: [] });
  },
);

it('ends a submitting choice when its execution scope changes without accepting the late reply', async () => {
  const scope = ref('tenant-a');
  const registry = createAssistantSurfaceRegistry();
  let finish!: (value: AssistantTurnOutput) => void;
  const requestTurn = vi
    .fn<AssistantTurnRequester>()
    .mockResolvedValueOnce(requiredChoice)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
  registry.register({
    pageInstanceKey: 'a',
    contextRevision: () => 'stable',
    executionScopeKey: () => scope.value,
    surface: { describe: () => ({ surface: 'test', facts: {} }), capabilities: () => [], requestTurn },
  });
  registry.activate('a');
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('录入职员');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '租户甲')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.find('.ant-btn-loading').exists()).toBe(true);
  scope.value = 'tenant-b';
  await flushPromises();
  expect(wrapper.find('.ant-btn-loading').exists()).toBe(false);
  expect(wrapper.text()).toContain('此选择已更新');
  finish({ text: 'old answer', toolCalls: [] });
  await flushPromises();
  expect(wrapper.text()).not.toContain('old answer');
  expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined();
  wrapper.unmount();
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

it('executes the trusted reviewed proposal from chat without requesting another model turn', async () => {
  const execute = vi.fn(async () => ({ title: '保存成功', lines: ['记录 42'] }));
  const requestTurn = vi.fn(async () => ({
    toolCalls: [{ id: 'save', code: 'form.prepare-save', input: {} }],
  }));
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'read',
      descriptor: { code: 'form.prepare-save', description: 'Prepare save', inputSchema: {} },
      parseInput: () => ({}),
      execute: async () => ({ awaitingHumanConfirmation: true }),
      propose: () => ({
        presentation: { title: '确认保存', lines: ['名称：订单'] },
        expiresAt: Date.now() + 60_000,
        isCurrent: () => true,
        execute,
        lookup: async () => undefined,
      }),
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('保存这条记录');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  expect(execute).not.toHaveBeenCalled();
  expect(wrapper.text()).toContain('名称：订单');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '确认保存')!
    .trigger('click');
  await flushPromises();
  expect(execute).toHaveBeenCalledOnce();
  expect(requestTurn).toHaveBeenCalledOnce();
  expect(wrapper.text()).toContain('保存成功');
  wrapper.unmount();
});

it('expires a prepared operation when its page surface is replaced', async () => {
  const execute = vi.fn(async () => ({ title: '保存成功', lines: [] }));
  const requestTurn = vi.fn(async () => ({
    toolCalls: [{ id: 'save', code: 'form.prepare-save', input: {} }],
  }));
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'read',
      descriptor: { code: 'form.prepare-save', description: '', inputSchema: {} },
      parseInput: () => ({}),
      execute: async () => ({}),
      propose: () => ({
        presentation: { title: '确认保存', lines: [] },
        expiresAt: Date.now() + 60_000,
        isCurrent: () => true,
        execute,
        lookup: async () => undefined,
      }),
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('保存');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => [],
      requestTurn,
    },
  });
  await flushPromises();
  expect(wrapper.text()).toContain('内容或范围已变化');
  expect(execute).not.toHaveBeenCalled();
  wrapper.unmount();
});

it('keeps failed required choices retryable without blocking a free-text recovery', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce(requiredChoice)
    .mockRejectedValueOnce(new Error('暂时无法处理'))
    .mockResolvedValueOnce({ text: '收到补充', toolCalls: [] });
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });
  await wrapper.get('textarea').setValue('录入职员');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  await wrapper.get('.assistant-selection__options button').trigger('click');
  await flushPromises();
  expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined();
  expect(wrapper.text()).toContain('可以重试选项，也可以用文字继续说明');
  expect(wrapper.get('.assistant-selection__options button').attributes('disabled')).toBeUndefined();
  await wrapper.get('textarea').setValue('先看看可用租户');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  expect(requestTurn).toHaveBeenCalledTimes(3);
  expect(wrapper.text()).toContain('收到补充');
  expect(requestTurn.mock.calls[2]![0].history).toContainEqual({ role: 'user', text: '租户甲' });
  wrapper.unmount();
});

it('replaces streamed claims with the validated terminal response before displaying a choice', async () => {
  const requestTurn: AssistantTurnRequester = async (_request, _signal, progress) => {
    progress?.onTextDelta?.('已经创建应用');
    return { ...requiredChoice, text: '尚未执行操作，请先选择' };
  };
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });
  await wrapper.get('textarea').setValue('建设应用');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  expect(wrapper.text()).not.toContain('已经创建应用');
  expect(wrapper.text()).toContain('尚未执行操作，请先选择');
  wrapper.unmount();
});

it('shows safe rejection reasons without exposing unexpected execution errors', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [
        { id: 'bad-date', code: 'draft.validate', input: {} },
        { id: 'failed-read', code: 'record.read', input: {} },
      ],
    })
    .mockResolvedValueOnce({ text: '请修正日期后继续。', toolCalls: [] });
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'read',
      descriptor: { code: 'draft.validate', description: 'Validate', inputSchema: {} },
      parseInput: (input) => input,
      async execute() {
        throw new AssistantCapabilityUsageError('订单日期必须使用 YYYY-MM-DD 格式');
      },
    },
    {
      effect: 'read',
      descriptor: { code: 'record.read', description: 'Read', inputSchema: {} },
      parseInput: (input) => input,
      async execute() {
        throw new Error('private database failure');
      },
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('填写订单');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  expect(wrapper.get('details').text()).toContain('订单日期必须使用 YYYY-MM-DD 格式');
  expect(wrapper.text()).toContain('这一步未完成，可根据校验结果继续调整');
  expect(wrapper.text()).not.toContain('private database failure');
});

it.each([undefined, '保存当前单据，尚未执行。'])(
  'keeps pending approval questions separate from human review content (%s)',
  async (modelSummary) => {
    let current = true;
    const execute = vi.fn().mockResolvedValue({ title: '已完成', lines: [] });
    const requestTurn = vi
      .fn()
      .mockResolvedValueOnce({ toolCalls: [{ id: 'prepare', code: 'form.prepare-save', input: {} }] })
      .mockResolvedValueOnce({ text: '确认后才会保存这一条记录。', toolCalls: [] });
    const registry = createRegistryWithCapabilities(requestTurn, [
      {
        effect: 'read',
        descriptor: { code: 'form.prepare-save', description: 'Prepare', inputSchema: {} },
        parseInput: (input) => input,
        async execute() {
          return {};
        },
        propose: () => ({
          modelSummary,
          presentation: {
            title: '保存订单',
            lines: ['订单：小林'],
            details: { title: '完整内容', lines: ['备注：不要糖'] },
          },
          expiresAt: Date.now() + 60000,
          isCurrent: () => current,
          execute,
          lookup: async () => undefined,
        }),
      },
    ]);
    const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
    await wrapper.get('textarea').setValue('帮我记下来');
    await wrapper.get('.assistant-panel__actions button').trigger('click');
    await flushPromises();
    await wrapper.get('textarea').setValue('点完会发生什么？');
    await wrapper.get('.assistant-panel__actions button').trigger('click');
    await flushPromises();
    expect(wrapper.text()).not.toContain('已取消本次确认');
    expect(wrapper.findAll('button').some((button) => button.text() === '确认保存')).toBe(true);
    expect(requestTurn.mock.calls[1]![0].history).toContainEqual(
      expect.objectContaining({ text: expect.stringContaining('平台待确认内容（尚未执行') }),
    );
    const modelRequest = JSON.stringify(requestTurn.mock.calls[1]![0]);
    expect(modelRequest).toContain(modelSummary ?? '有一项操作等待用户确认，尚未执行。');
    for (const humanOnly of ['保存订单', '小林', '不要糖']) expect(modelRequest).not.toContain(humanOnly);
    expect(wrapper.text()).toContain('订单：小林');
    expect(execute).not.toHaveBeenCalled();
    expect(wrapper.get('details').attributes('open')).toBeUndefined();
    current = false;
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '确认保存')!
      .trigger('click');
    await flushPromises();
    expect(execute).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('内容或范围已变化');
    wrapper.unmount();
  },
);

it('expires an old confirmation when a later draft operation changes its content within the same surface', async () => {
  let current = true;
  const execute = vi.fn();
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'prepare', code: 'form.prepare-save', input: {} }] })
    .mockResolvedValueOnce({ toolCalls: [{ id: 'change', code: 'form.patch-draft', input: {} }] })
    .mockResolvedValueOnce({ text: '已补充备注，请重新确认。', toolCalls: [] });
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'read',
      descriptor: { code: 'form.prepare-save', description: 'Prepare', inputSchema: {} },
      parseInput: (input) => input,
      execute: async () => ({}),
      propose: () => ({
        presentation: { title: '保存订单', lines: ['物品：台灯'] },
        expiresAt: Date.now() + 60000,
        isCurrent: () => current,
        execute,
        lookup: async () => undefined,
      }),
    },
    {
      effect: 'page',
      descriptor: { code: 'form.patch-draft', description: 'Patch draft', inputSchema: {} },
      parseInput: (input) => input,
      async execute(_input, context) {
        context.applyEffect(() => {
          current = false;
        });
        return { changed: true };
      },
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('记一下这件台灯');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  expect(wrapper.findAll('button').some((button) => button.text() === '确认保存')).toBe(true);
  const token = registry.snapshot()?.token;
  await wrapper.get('textarea').setValue('等一下，再记个备注');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  expect(registry.snapshot()?.token).toEqual(token);
  expect(wrapper.findAll('button').some((button) => button.text() === '确认保存')).toBe(false);
  expect(wrapper.text()).toContain('内容或范围已变化');
  expect(execute).not.toHaveBeenCalled();
  wrapper.unmount();
});

it('offers explicit request recovery for model failures without repeating a save', async () => {
  const requestTurn = vi
    .fn()
    .mockRejectedValue(new Error('AI model returned an oversized structured response'));
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });
  await wrapper.get('textarea').setValue('能记小林这一单了吗？');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('本轮回复未能完成');
  expect(wrapper.text()).not.toContain('oversized structured response');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '调整需求')!
    .trigger('click');
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('能记小林这一单了吗？');
  expect(requestTurn).toHaveBeenCalledOnce();
  wrapper.unmount();
});

it('follows new replies but preserves reading position after scrolling into history', async () => {
  let complete!: (output: AssistantTurnOutput) => void;
  const requestTurn = vi.fn<AssistantTurnRequester>(
    () =>
      new Promise((resolve) => {
        complete = resolve;
      }),
  );
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });
  const conversation = wrapper.get('.assistant-panel__conversation');
  const element = conversation.element as HTMLElement;
  Object.defineProperties(element, {
    scrollHeight: { configurable: true, value: 1000 },
    clientHeight: { configurable: true, value: 200 },
  });
  await wrapper.get('textarea').setValue('我想记个订单');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  expect(element.scrollTop).toBe(1000);
  element.scrollTop = 100;
  await conversation.trigger('scroll');
  complete({ text: '请核对订单', toolCalls: [] });
  await flushPromises();
  expect(element.scrollTop).toBe(100);
  const latest = wrapper.findAll('button').find((button) => button.text() === '查看最新回复');
  expect(latest).toBeDefined();
  await latest!.trigger('click');
  expect(element.scrollTop).toBe(1000);
  expect(wrapper.text()).not.toContain('查看最新回复');
});

it.each([false, true])(
  'continues confirmed construction only when no user input is pending: %s',
  async (typing) => {
    const execute = vi.fn(async () => ({ title: '配置已提交', lines: ['下一步核实'] }));
    const requestTurn = vi.fn<AssistantTurnRequester>(async () => ({
      toolCalls: [{ id: 'prepare', code: 'construction.prepare-test', input: {} }],
    }));
    const registry = createRegistryWithCapabilities(requestTurn, [
      {
        effect: 'read',
        descriptor: { code: 'construction.prepare-test', description: 'prepare', inputSchema: {} },
        parseInput: (input) => input,
        async execute() {
          return {};
        },
        propose: () => ({
          presentation: { title: '确认这一步', lines: ['仅提交当前变更'] },
          expiresAt: Date.now() + 60000,
          isCurrent: () => true,
          execute,
          lookup: async () => undefined,
          continuation: { message: '平台续接：读取任务', isCurrent: () => true },
        }),
      },
    ]);
    const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
    await wrapper.get('textarea').setValue('帮我准备');
    await wrapper.get('.assistant-panel__actions button').trigger('click');
    await flushPromises();
    requestTurn.mockClear();
    requestTurn.mockResolvedValue({
      text: '下一步需要你核对',
      toolCalls: [{ id: 'next', code: 'construction.prepare-test', input: {} }],
    });
    if (typing) await wrapper.get('textarea').setValue('我还有一个要求');
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '确认保存')!
      .trigger('click');
    await flushPromises();
    expect(execute).toHaveBeenCalledOnce();
    expect(requestTurn).toHaveBeenCalledTimes(typing ? 0 : 1);
    if (!typing) {
      expect(requestTurn.mock.calls[0]?.[0].message).toContain('用户最近明确提出的要求：帮我准备');
      expect(wrapper.text()).toContain('正在核实已完成结果并准备下一步');
      expect(wrapper.findAll('.assistant-message--user').map((item) => item.text())).toEqual(['帮我准备']);
      for (let index = 0; index < 4; index++) {
        await wrapper
          .findAll('button')
          .find((button) => button.text() === '确认保存')!
          .trigger('click');
        await flushPromises();
      }
      const resumed = requestTurn.mock.calls.at(-1)![0];
      expect(
        resumed.history?.filter((item) => item.role === 'user').every((item) => item.text === '帮我准备'),
      ).toBe(true);
      expect(resumed.message).toContain('用户最近明确提出的要求：帮我准备');
    } else expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('我还有一个要求');
    wrapper.unmount();
  },
);

it('restores persisted text after remount without reactivating old confirmation or selection controls', async () => {
  const { createAssistantConversationClient } = await import('@muyun/web-core');
  const configurationCollaboration = createConfigurationCollaboration();
  let saved: import('@muyun/web-core').AssistantConversationSnapshot | undefined;
  const checkpoints: import('@muyun/web-core').AssistantConversationContent[] = [];
  const client = createAssistantConversationClient({
    request: vi.fn(async ({ method, body }) => {
      if (method === 'PUT') {
        const command = body as {
          expectedRevision: number;
          content: import('@muyun/web-core').AssistantConversationContent;
        };
        saved = {
          id: 'saved',
          revision: command.expectedRevision + 1,
          updatedAt: '2026-09-26',
          content: JSON.parse(JSON.stringify(command.content)),
        };
        checkpoints.push(saved.content);
        return saved;
      }
      return saved;
    }) as never,
  });
  client.list = vi.fn(async () => [{ id: 'saved', title: '合同需求', updatedAt: '2026-09-26' }]);
  const requestTurn = vi.fn(async () => ({ text: '先整理客户信息，再讨论合同。', toolCalls: [] }));
  let wrapper = mount(WorkbenchAssistantPanel, {
    props: {
      open: true,
      registry: createRegistry(requestTurn),
      conversationClient: client,
      configurationCollaboration,
    },
  });
  configurationCollaboration.restore({ goal: '调整合同字段', mode: 'visual' });
  await wrapper.get('textarea').setValue('我想记录合同');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('对话已保存');
  expect(saved?.content.messages.map((message) => message.text)).toContain('我想记录合同');
  expect(saved?.content.configurationTask).toEqual({ goal: '调整合同字段', mode: 'visual' });
  expect(checkpoints[0]?.pendingRequest).toBe('我想记录合同');
  wrapper.unmount();
  saved!.content.messages.push({ role: 'assistant', text: '历史保存提议：确认保存合同；历史状态：待确认' });
  wrapper = mount(WorkbenchAssistantPanel, {
    attachTo: document.body,
    props: {
      open: true,
      registry: createRegistry(requestTurn),
      conversationClient: client,
      configurationCollaboration,
    },
  });
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '历史会话')!
    .trigger('click');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text().includes('合同需求'))!
    .trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('我想记录合同');
  expect(configurationCollaboration.task.value).toEqual({ goal: '调整合同字段', mode: 'visual' });
  expect(wrapper.text()).toContain('边看配置页面，边在对话中确认');
  expect(wrapper.text()).toContain('已建业务按当前配置继续改进');
  expect(wrapper.text()).toContain('历史记录不会恢复旧确认授权');
  expect(requestTurn).toHaveBeenCalledOnce();
  expect(JSON.stringify(saved?.content.history)).not.toContain('历史会话已恢复。');
  expect(wrapper.findAll('button').some((button) => button.text() === '确认保存合同')).toBe(false);
  expect(wrapper.findAll('.assistant-message').every((item) => !item.isVisible())).toBe(true);
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '查看之前的对话')!
    .trigger('click');
  expect(wrapper.findAll('.assistant-message').every((item) => item.isVisible())).toBe(true);
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '继续处理')!
    .trigger('click');
  await flushPromises();
  expect(JSON.stringify(requestTurn.mock.calls.at(-1))).toContain('我想记录合同');
  expect(JSON.stringify(requestTurn.mock.calls.at(-1))).toContain('暂不修改或保存');
  expect(wrapper.find('[aria-label="继续会话"]').exists()).toBe(false);
  expect(wrapper.findAll('.assistant-message').at(-1)!.isVisible()).toBe(true);
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '新对话')!
    .trigger('click');
  await flushPromises();
  expect(configurationCollaboration.task.value).toBeUndefined();
  expect(wrapper.find('[aria-label="本次配置协作方式"]').exists()).toBe(false);
  wrapper.unmount();
});

it('keeps a request editable when its initial checkpoint fails without invoking the model', async () => {
  const requestTurn = vi.fn();
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: {
      open: true,
      registry: createRegistry(requestTurn),
      conversationClient: {
        list: vi.fn(async () => []),
        read: vi.fn(),
        save: vi.fn().mockRejectedValue(new Error('暂时无法保存聊天')),
      },
    },
  });
  await wrapper.get('textarea').setValue('帮我整理客户合同');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();
  expect(requestTurn).not.toHaveBeenCalled();
  expect(wrapper.text()).toContain('暂时无法保存聊天');
  const adjust = wrapper.findAll('button').find((button) => button.text() === '调整需求')!;
  await wrapper.get('textarea').setValue('我补充一个要求');
  expect(adjust.attributes('disabled')).toBeDefined();
  await wrapper.get('textarea').setValue('');
  await adjust.trigger('click');
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('帮我整理客户合同');
});

it('shows read recovery instead of discard controls for a history failure', async () => {
  const list = vi.fn().mockRejectedValueOnce(new Error('读取历史失败')).mockResolvedValueOnce([]);
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: {
      open: true,
      registry: createRegistry(vi.fn()),
      conversationClient: { list, read: vi.fn(), save: vi.fn() },
    },
  });
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '历史会话')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('读取历史失败');
  expect(wrapper.text()).not.toContain('放弃未保存');
  expect(wrapper.text()).not.toContain('重试保存');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试读取会话')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.text()).not.toContain('读取历史失败');
  expect(list).toHaveBeenCalledTimes(2);
});

it.each([false, true, 'denied'])(
  'continues from current business without requiring a historical plan lookup: %s',
  async (fails) => {
    const { createConstructionPlanSession } = await import('@/platform-workbench/constructionPlanSession');
    const read = vi.fn(async () => {
      if (fails === 'denied') throw new AppError('private server detail', { status: 403 });
      if (fails) throw new Error('unavailable');
      return {
        planId: 'linked-plan',
        revision: 1,
        confirmedAt: '',
        constructionStatus: 'NOT_STARTED',
        deliveredObjectKeys: [],
        initializations: [],
        deliveries: [],
        fieldChanges: [],
        content: {
          title: '试用',
          goal: '登记',
          inScope: [],
          outOfScope: [],
          objects: [],
          relationships: [],
          rules: [],
          questions: [],
          assumptions: [],
          decisions: [],
          acceptanceExamples: [],
        },
      } as import('@muyun/web-contracts').ConstructionPlanSnapshot;
    });
    const plan = createConstructionPlanSession(
      { read } as unknown as import('@muyun/web-core').ConstructionPlanClient,
      () => 'owner',
    );
    const requestTurn = vi.fn(async () => {
      expect(plan.current().planId).toBe('linked-plan');
      expect(plan.current().saved?.planId).toBe(fails ? undefined : 'linked-plan');
      return { text: '已核实进度', toolCalls: [] };
    });
    const wrapper = mount(WorkbenchAssistantPanel, {
      props: {
        open: true,
        registry: createRegistry(requestTurn),
        constructionPlan: plan,
        conversationClient: {
          list: vi.fn(async () => [{ id: 'archived', title: '之前的讨论', updatedAt: '2026-09-27' }]),
          read: vi.fn(async () => ({
            id: 'archived',
            revision: 1,
            updatedAt: '2026-09-27',
            content: {
              title: '之前的讨论',
              messages: [{ role: 'user' as const, text: '帮我登记' }],
              history: [],
              planId: 'linked-plan',
            },
          })),
          save: vi.fn(async (id, _scope, revision, content) => ({
            id,
            revision: revision + 1,
            updatedAt: '2026-09-27',
            content,
          })),
        },
      },
    });
    const click = async (label: string) => {
      await wrapper
        .findAll('button')
        .find((button) => button.text().includes(label))!
        .trigger('click');
      await flushPromises();
    };
    await click('历史会话');
    await click('之前的讨论');
    if (fails === 'denied') {
      expect(wrapper.text()).toContain('关联方案不存在或当前身份无权访问');
      expect(wrapper.text()).not.toContain('private server detail');
      expect(wrapper.text()).not.toContain('未完成设计请稍后重试读取');
    }
    await click('继续处理');
    expect(read).toHaveBeenCalledWith('linked-plan');
    expect(requestTurn).toHaveBeenCalledOnce();
    expect(JSON.stringify(requestTurn.mock.calls)).toContain('暂不修改或保存');
    expect(wrapper.text()).not.toContain('查看关联的已保存建设方案');
    await click('新对话');
    expect(plan.current().planId).toBeUndefined();
    expect(plan.current().candidate).toBeUndefined();
    wrapper.unmount();
  },
);

it('offers a return to the hidden editor without changing the chosen collaboration mode', async () => {
  const configurationCollaboration = createConfigurationCollaboration();
  const open = vi.fn();
  const configurationEditor = { title: '合同', visible: false, hasUnsavedChanges: true, open };
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: {
      open: true,
      registry: createRegistry(vi.fn()),
      configurationCollaboration,
      configurationEditor,
    },
  });
  configurationCollaboration.restore({ goal: '修改合同备注', mode: 'visual' });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '返回配置页')!
    .trigger('click');
  expect(open).toHaveBeenCalledOnce();
  expect(configurationCollaboration.task.value?.mode).toBe('visual');
  await wrapper.setProps({ configurationEditor: { ...configurationEditor, visible: true } });
  expect(wrapper.text()).not.toContain('返回配置页');
  wrapper.unmount();
});

it('resumes the original request with a fresh budget without inventing a new user message', async () => {
  let sequence = 0;
  const requestTurn = vi.fn<AssistantTurnRequester>(async (input) => {
    if (input.executionBudget?.phase === 'summary')
      return { text: '已查到客户登记，订单部分还需核实。', toolCalls: [] };
    return { toolCalls: [{ id: `read-${sequence}`, code: 'page.read', input: { offset: sequence++ } }] };
  });
  const read = vi.fn(async () => ({ title: '客户登记' }));
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'read',
      descriptor: { code: 'page.read', description: 'Read', inputSchema: {} },
      parseInput: (input) => input,
      execute: read,
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('看看能不能记订单，先别改');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('订单部分还需核实');
  expect(wrapper.text()).toContain('读取信息不代表修改或保存');
  expect(wrapper.text()).not.toContain('草稿修改会保留');
  expect(read).toHaveBeenCalledTimes(3);
  requestTurn.mockResolvedValue({ text: '已重新核实当前配置', toolCalls: [] });
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '继续处理')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('已重新核实当前配置');
  expect(requestTurn.mock.calls.at(-1)?.[0].executionBudget?.step).toBe(1);
  expect(requestTurn.mock.calls.at(-1)?.[0].message).toContain('看看能不能记订单，先别改');
  expect(requestTurn.mock.calls.at(-1)?.[0].message).toContain('新的保存仍须重新确认');
  wrapper.unmount();
});

it.each([
  ['模型响应被截断，请缩短描述后重试', '模型本次回复达到长度上限'],
  ['AI model response body timed out', '等待模型回复超时'],
  ['模型本次回复在返回可用内容前中止，请稍后重试', '模型服务未返回可用内容'],
  ['AI model request was rejected by provider', '模型服务拒绝了本次请求'],
  ['AI model request was rejected with HTTP status 429', '模型服务拒绝了本次请求'],
  ['本次内容预计超过模型上下文预算，尚未发送给模型', '本次内容预计超过模型上下文预算'],
  ['本次输出预算超过模型容量，请调整配置', '本次输出预算超过模型容量'],
])(
  'explains a known model interruption without suggesting that the business was saved: %s',
  async (failure, explanation) => {
    const requestTurn = vi.fn(async () => {
      throw new Error(failure);
    });
    const wrapper = mount(WorkbenchAssistantPanel, {
      props: { open: true, registry: createRegistry(requestTurn) },
    });
    await wrapper.get('textarea').setValue('新建晨光小店，不继续旧方案');
    await wrapper.get('button.ant-btn-primary').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain(explanation);
    if (failure.startsWith('AI model request was rejected')) {
      expect(wrapper.text()).not.toContain('可以调整需求');
      expect(wrapper.text()).not.toContain(failure);
      expect(requestTurn).toHaveBeenCalledOnce();
    }
    expect(wrapper.text()).toContain('待确认内容没有提交');
    requestTurn.mockResolvedValue({ text: '核实当前需求', toolCalls: [] } as never);
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '继续处理')!
      .trigger('click');
    await flushPromises();
    expect(JSON.stringify(requestTurn.mock.calls.at(-1))).toContain('新建晨光小店，不继续旧方案');
    wrapper.unmount();
  },
);

it('does not persist an unsaved candidate as a restorable plan, but retains confirmed and uncertain bindings', async () => {
  const { createConstructionPlanSession } = await import('@/platform-workbench/constructionPlanSession');
  const plan = createConstructionPlanSession(
    {} as import('@muyun/web-core').ConstructionPlanClient,
    () => 'owner',
  );
  const save = vi.fn<import('@muyun/web-core').AssistantConversationClient['save']>(
    async (id, _scope, revision, content) => ({
      id,
      revision: revision + 1,
      content,
      updatedAt: '',
    }),
  );
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: {
      open: true,
      registry: createRegistry(vi.fn(async () => ({ text: '已读取', toolCalls: [] }))),
      constructionPlan: plan,
      conversationClient: { save, list: vi.fn(), read: vi.fn() },
    },
  });
  plan.edit({
    title: '小店试用',
    goal: '登记订单',
    inScope: [],
    outOfScope: [],
    objects: [],
    relationships: [],
    rules: [],
    questions: [],
    assumptions: [],
    decisions: [],
    acceptanceExamples: [],
  });
  expect(plan.current().planId).toBeTruthy();
  const send = async () => {
    await wrapper.get('textarea').setValue('核实当前进度，先不保存');
    await wrapper.get('button.ant-btn-primary').trigger('click');
    await flushPromises();
  };
  await send();
  expect(save.mock.calls.at(-1)?.[3].planId).toBeUndefined();
  plan.recovery.value = async () => undefined;
  await send();
  expect(save.mock.calls.at(-1)?.[3].planId).toBe(plan.current().planId);
  plan.recovery.value = undefined;
  plan.state.value = {
    ...plan.current(),
    saved: {
      planId: plan.current().planId!,
      content: plan.current().candidate!,
      revision: 1,
      confirmedAt: '',
      constructionStatus: 'NOT_STARTED',
      deliveredObjectKeys: [],
      initializations: [],
      fieldChanges: [],
      deliveries: [],
    },
  };
  await send();
  expect(save.mock.calls.at(-1)?.[3].planId).toBe(plan.current().planId);
  wrapper.unmount();
});

it('keeps the same archived conversation across governance navigation and expires old confirmations', async () => {
  const identity = ref('owner');
  const scope = ref('tenant-a');
  const write = vi.fn(async () => ({ title: '已保存', lines: [] }));
  const requestTurn = vi
    .fn<AssistantTurnRequester>()
    .mockResolvedValueOnce({
      toolCalls: [{ id: 'confirm', code: 'record.prepare', input: {} }],
    })
    .mockResolvedValue({ text: '请确认', toolCalls: [] });
  const registry = createAssistantSurfaceRegistry(() => identity.value);
  const surface = {
    describe: () => ({ surface: 'test', facts: {} }),
    requestTurn,
    capabilities: () => [
      {
        effect: 'read' as const,
        descriptor: { code: 'record.prepare', description: '', inputSchema: {} },
        parseInput: (value: unknown) => value,
        execute: async () => ({}),
        propose: () => ({
          presentation: { title: '准备保存', lines: [] },
          modelSummary: '旧范围候选',
          confirmLabel: '确认保存',
          expiresAt: Date.now() + 60000,
          isCurrent: () => true,
          execute: write,
          lookup: async () => undefined,
        }),
      },
    ],
  };
  registry.register({
    pageInstanceKey: 'business',
    contextRevision: () => '',
    executionScopeKey: () => scope.value,
    surface,
  });
  registry.register({ pageInstanceKey: 'governance', contextRevision: () => '', surface });
  registry.activate('business');
  const save = vi.fn(async (id, _scope, revision, content) => ({
    id,
    revision: revision + 1,
    updatedAt: '',
    content,
  }));
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry, conversationClient: { save, list: vi.fn(async () => []), read: vi.fn() } },
  });
  await wrapper.get('textarea').setValue('准备保存当前记录');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  const id = save.mock.calls[0]![0];
  registry.activate('governance');
  await flushPromises();
  expect(wrapper.text()).toContain('准备保存当前记录');
  expect(wrapper.text()).toContain('内容或范围已变化');
  expect(write).not.toHaveBeenCalled();
  expect(new Set(save.mock.calls.map(([savedId]) => savedId))).toEqual(new Set([id]));
  identity.value = 'another-owner';
  await flushPromises();
  expect(wrapper.text()).not.toContain('准备保存当前记录');
  expect(write).not.toHaveBeenCalled();
});

it('continues in the same conversation after an agent opens a page in another execution scope', async () => {
  const registry = createAssistantSurfaceRegistry();
  const targetRequest = vi.fn<AssistantTurnRequester>(async () => ({
    text: '已准备好，请查看页面',
    toolCalls: [],
  }));
  registry.register({
    pageInstanceKey: 'governance',
    executionScopeKey: () => '',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'test', facts: {} }),
      capabilities: () => [],
      requestTurn: targetRequest,
    },
  });
  registry.register({
    pageInstanceKey: 'business',
    executionScopeKey: () => 'tenant-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'test', facts: {} }),
      requestTurn: async () => ({ toolCalls: [{ id: 'open', code: 'page.open', input: {} }] }),
      capabilities: () => [
        {
          effect: 'page',
          descriptor: { code: 'page.open', description: 'Open', inputSchema: {} },
          parseInput: (value) => value,
          execute: async (_input, context) => {
            context.applyEffect(
              () => registry.activate('governance'),
              async () => registry.snapshot()?.token,
            );
            await flushPromises();
            return { opened: true };
          },
        },
      ],
    },
  });
  registry.activate('business');
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
  await wrapper.get('textarea').setValue('帮我准备商品表单');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  await vi.waitFor(() => expect(targetRequest, wrapper.text()).toHaveBeenCalledOnce());
  expect(wrapper.text()).toContain('帮我准备商品表单');
  expect(wrapper.text()).toContain('已准备好，请查看页面');
  expect(wrapper.text()).not.toContain('本轮已暂停');
});

it.each(['继续处理', '调整需求'])(
  'recovers the user goal after automatic continuation fails via %s',
  async (action) => {
    const execute = vi.fn(async () => ({ title: '配置已提交', lines: [] }));
    const requestTurn = vi
      .fn<AssistantTurnRequester>()
      .mockResolvedValueOnce({
        toolCalls: [{ id: 'prepare', code: 'configuration.prepare-test', input: {} }],
      })
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValue({ text: '已核实，继续准备下一项候选。', toolCalls: [] });
    const registry = createRegistryWithCapabilities(requestTurn, [
      {
        effect: 'read',
        descriptor: { code: 'configuration.prepare-test', description: 'prepare', inputSchema: {} },
        parseInput: (input) => input,
        async execute() {
          return {};
        },
        propose: () => ({
          presentation: { title: '确认当前配置', lines: [] },
          expiresAt: Date.now() + 60000,
          isCurrent: () => true,
          execute,
          lookup: async () => undefined,
          continuation: { message: '内部续接：读取 configuration.task', isCurrent: () => true },
        }),
      },
    ]);
    const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });
    await wrapper.get('textarea').setValue('给订单加送货日期');
    await wrapper.get('.assistant-panel__actions button').trigger('click');
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '确认保存')!
      .trigger('click');
    await flushPromises();
    expect(execute).toHaveBeenCalledOnce();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === action)!
      .trigger('click');
    await flushPromises();
    if (action === '调整需求') {
      expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('给订单加送货日期');
    } else {
      const resumed = requestTurn.mock.calls.at(-1)![0];
      expect(resumed.message).toContain('给订单加送货日期');
      expect(resumed.message).not.toContain('内部续接');
      expect(JSON.stringify(resumed.history)).not.toContain('内部续接');
    }
    expect(wrapper.findAll('.assistant-message--user').map((item) => item.text())).toEqual([
      '给订单加送货日期',
    ]);
    expect(execute).toHaveBeenCalledOnce();
    wrapper.unmount();
  },
);

it('checkpoints the receipt before submission and restores only read-only recovery after a lost response', async () => {
  const reference = {
    kind: 'record-save' as const,
    moduleAlias: 'sales.order',
    requestId: 'original-request-123',
  };
  let saved: import('@muyun/web-core').AssistantConversationSnapshot | undefined;
  const execute = vi.fn(async () => {
    expect(
      saved?.content.messages.some(
        (message) =>
          message.operationReceipt?.reference.kind === 'record-save' &&
          message.operationReceipt.reference.requestId === reference.requestId,
      ),
    ).toBe(true);
    throw new Error('response lost after commit');
  });
  const client: import('@muyun/web-core').AssistantConversationClient = {
    list: vi.fn(async () => [{ id: 'history', title: '保存订单', updatedAt: '' }]),
    read: vi.fn(async () => saved!),
    save: vi.fn(async (_id, _scope, revision, content) => {
      saved = {
        id: 'history',
        revision: revision + 1,
        updatedAt: '',
        content: JSON.parse(JSON.stringify(content)),
      };
      return saved;
    }),
    lookupOperation: vi.fn(async () => ({ title: '原保存已确认', lines: ['记录仍存在'] })),
  };
  const requestTurn = vi.fn(async () => ({
    toolCalls: [{ id: 'prepare', code: 'record.prepare', input: {} }],
  }));
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      effect: 'read',
      descriptor: { code: 'record.prepare', description: '', inputSchema: {} },
      parseInput: (input) => input,
      execute: async () => ({}),
      propose: () => ({
        receiptReference: reference,
        presentation: { title: '保存订单', lines: [] },
        expiresAt: Date.now() + 60000,
        isCurrent: () => true,
        execute,
        lookup: async () => undefined,
      }),
    },
  ]);
  let wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry, conversationClient: client },
  });
  await wrapper.get('textarea').setValue('保存订单');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '确认保存')!
    .trigger('click');
  await flushPromises();
  expect(execute).toHaveBeenCalledOnce();
  expect(wrapper.text()).toContain('查询操作结果');
  wrapper.unmount();
  wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry, conversationClient: client } });
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '历史会话')!
    .trigger('click');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text().includes('保存订单'))!
    .trigger('click');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '查看之前的对话')!
    .trigger('click');
  expect(wrapper.findAll('button').some((button) => button.text() === '确认保存')).toBe(false);
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '查询操作结果')!
    .trigger('click');
  await flushPromises();
  expect(client.lookupOperation).toHaveBeenCalledExactlyOnceWith(reference);
  expect(wrapper.text()).toContain('原保存已确认');
  expect(execute).toHaveBeenCalledOnce();
  expect(requestTurn).toHaveBeenCalledOnce();
  wrapper.unmount();
});
