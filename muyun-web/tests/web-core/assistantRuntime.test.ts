import { expect, it, vi } from 'vitest';
import {
  AssistantCapabilityUsageError,
  AssistantConversationFollowUpError,
  createAssistantSurfaceRegistry,
  runAssistantConversation,
  runAssistantStep,
  StaleAssistantInvocationError,
  type AssistantCapability,
  type AssistantActivityPhase,
  type AssistantRuntimeDiagnosticEvent,
} from '@muyun/web-core';

it('reports user-facing activity phases from runtime facts', async () => {
  const phases: AssistantActivityPhase[] = [];
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'module-page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'page.inspect', description: 'Inspect', inputSchema: {} },
          parseInput: (input) => input,
          execute: async () => ({ inspected: true }),
        },
      ],
      requestTurn: vi
        .fn()
        .mockResolvedValueOnce({
          toolCalls: [{ id: 'call-1', code: 'page.inspect', input: {} }],
        })
        .mockImplementationOnce(async (_input, _signal, progress) => {
          progress?.onTextDelta?.('完成');
          return { text: '完成', toolCalls: [] };
        }),
    },
  });
  registry.activate('tab-a');

  await runAssistantConversation(registry, '检查当前页面', {
    onTextDelta() {},
    onActivity(phase) {
      phases.push(phase);
    },
  });

  expect(phases).toEqual(['understanding', 'executing', 'understanding', 'responding']);
});

it('emits content-free structured diagnostics without affecting execution', async () => {
  const diagnostics: AssistantRuntimeDiagnosticEvent[] = [];
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'module-page', facts: { record: 'private-record' } }),
      capabilities: () => [
        {
          descriptor: { code: 'page.inspect', description: 'Inspect', inputSchema: {} },
          parseInput: (input) => input,
          execute: async () => ({ detail: 'private-result' }),
        },
      ],
      requestTurn: vi
        .fn()
        .mockResolvedValueOnce({
          text: 'private-model-text',
          requestId: 'request-1',
          finishReason: 'tool_calls',
          toolCalls: [{ id: 'call-1', code: 'page.inspect', input: { secret: 'private-input' } }],
        })
        .mockResolvedValueOnce({ text: 'done', finishReason: 'stop', toolCalls: [] }),
    },
  });
  registry.activate('tab-a');

  await runAssistantConversation(registry, 'private-user-message', {
    onDiagnostic(event) {
      diagnostics.push(event);
    },
  });

  expect(diagnostics).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ type: 'decision.started', surface: 'module-page' }),
      expect.objectContaining({
        type: 'decision.completed',
        toolCallCount: 1,
      }),
      expect.objectContaining({
        type: 'capability.completed',
        capabilityCode: 'page.inspect',
        outcome: 'succeeded',
      }),
      expect.objectContaining({ type: 'conversation.completed', bounded: false }),
    ]),
  );
  expect(JSON.stringify(diagnostics)).not.toMatch(
    /private-user-message|private-model-text|private-input|private-result|private-record|request-1/,
  );
});

it('ignores synchronous and asynchronous diagnostic observer failures', async () => {
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => [],
      requestTurn: async () => ({ text: 'done', toolCalls: [] }),
    },
  });
  registry.activate('tab-a');

  await expect(
    runAssistantConversation(registry, 'hello', {
      onDiagnostic() {
        throw new Error('diagnostic adapter failed');
      },
    }),
  ).resolves.toMatchObject({ completed: true });

  await expect(
    runAssistantConversation(registry, 'hello', {
      async onDiagnostic() {
        throw new Error('async diagnostic adapter failed');
      },
    }),
  ).resolves.toMatchObject({ completed: true });
});

it('waits for background page transitions before asking the model to decide', async () => {
  let revision = 'loading';
  const requestTurn = vi.fn(async () => ({ text: '页面已经就绪', toolCalls: [] }));
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    interactionRevision: () => 'same-user-scope',
    settle: async () => {
      revision = 'ready';
    },
    surface: {
      describe: () => ({ surface: 'module-page', facts: { revision } }),
      capabilities: () => [],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  await runAssistantConversation(registry, '继续');

  expect(requestTurn).toHaveBeenCalledWith(
    expect.objectContaining({ context: expect.objectContaining({ facts: { revision: 'ready' } }) }),
    expect.any(AbortSignal),
  );
});

it('executes declared capabilities and ends the step when their effect changes context', async () => {
  let revision = 'draft-before';
  const patch: AssistantCapability = {
    descriptor: {
      code: 'form.patch-draft',
      description: 'Patch draft',
      inputSchema: { type: 'object' },
    },
    parseInput: (input) => input,
    async execute(_input, context) {
      context.applyEffect(() => {
        revision = 'draft-after';
      });
      return { changed: true };
    },
  };
  const requestTurn = vi.fn(async () => ({
    toolCalls: [{ id: 'call-1', code: 'form.patch-draft', input: { title: 'Done' } }],
    finishReason: 'tool_calls',
  }));
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    surface: {
      describe: () => ({ surface: 'module-page', facts: {} }),
      capabilities: () => [patch],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantStep(registry, 'fill title');

  expect(result.results).toEqual([
    { callId: 'call-1', capabilityCode: 'form.patch-draft', output: { changed: true } },
  ]);
  expect(result.contextChanged).toBe(true);
  expect(result.appliedEffectCount).toBe(1);
});

it('passes clarification history to the model without interpreting it in the executor', async () => {
  let revision = 'before';
  const registry = createAssistantSurfaceRegistry();
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [{ id: 'call-1', code: 'form.patch-draft', input: {} }],
      finishReason: 'tool_calls',
    })
    .mockResolvedValueOnce({ text: '已填写', toolCalls: [] });
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    surface: {
      describe: () => ({ surface: 'module-page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'form.patch-draft', description: 'Patch draft', inputSchema: {} },
          parseInput: (input) => input,
          async execute(_input, context) {
            context.applyEffect(() => {
              revision = 'after';
            });
            return { changed: true };
          },
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  await runAssistantConversation(registry, '研发部', {
    history: [
      { role: 'user', text: '帮我新增一个部门' },
      { role: 'assistant', text: '部门名称是什么？' },
    ],
  });

  expect(requestTurn).toHaveBeenCalledWith(
    expect.objectContaining({
      message: '研发部',
      history: [
        { role: 'user', text: '帮我新增一个部门' },
        { role: 'assistant', text: '部门名称是什么？' },
      ],
    }),
    expect.any(AbortSignal),
  );
});

it('returns an ordinary capability failure as a structured result', async () => {
  const failing: AssistantCapability = {
    descriptor: { code: 'page.fail', description: 'Fail', inputSchema: {} },
    parseInput: (input) => input,
    async execute() {
      throw new Error('business input is incomplete');
    },
  };
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [failing],
      requestTurn: async () => ({
        toolCalls: [{ id: 'call-1', code: 'page.fail', input: {} }],
      }),
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantStep(registry, 'try');

  expect(result.results).toEqual([
    {
      callId: 'call-1',
      capabilityCode: 'page.fail',
      error: { code: 'CAPABILITY_FAILED', message: 'Capability execution failed' },
    },
  ]);
  expect(result.contextChanged).toBe(false);
  expect(result.appliedEffectCount).toBe(0);
});

it('returns bounded capability usage feedback so the model can repair its next call', async () => {
  const failing: AssistantCapability = {
    descriptor: { code: 'form.patch', description: 'Patch', inputSchema: {} },
    parseInput: (input) => input,
    async execute() {
      throw new AssistantCapabilityUsageError('Choose a writable field from the current form');
    },
  };
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [failing],
      requestTurn: async () => ({ toolCalls: [{ id: 'call-1', code: 'form.patch', input: {} }] }),
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantStep(registry, 'try');

  expect(result.results[0]?.error).toEqual({
    code: 'CAPABILITY_USAGE_INVALID',
    message: 'Choose a writable field from the current form',
  });
});

it('continues from a fresh surface after an effect and stops on the final model answer', async () => {
  let revision = 'before';
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [{ id: 'call-1', code: 'page.change', input: {} }],
      finishReason: 'tool_calls',
    })
    .mockResolvedValueOnce({ text: 'Done', toolCalls: [], finishReason: 'stop' });
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    surface: {
      describe: () => ({ surface: 'page', facts: { revision } }),
      capabilities: () => [
        {
          descriptor: { code: 'page.change', description: 'Change page', inputSchema: {} },
          parseInput: (input) => input,
          async execute(_input, context) {
            context.applyEffect(() => {
              revision = 'after';
            });
            return { changed: true };
          },
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantConversation(registry, 'change it');

  expect(result.completed).toBe(true);
  expect(result.steps).toHaveLength(2);
  expect(requestTurn).toHaveBeenNthCalledWith(
    2,
    expect.objectContaining({
      message: 'change it',
      results: [{ callId: 'call-1', capabilityCode: 'page.change', output: { changed: true } }],
      context: expect.objectContaining({ facts: { revision: 'after' } }),
    }),
    expect.any(AbortSignal),
  );
});

it('keeps prior dialogue on every tool step while refreshing page facts', async () => {
  let revision = 'before';
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [{ id: 'call-1', code: 'page.change', input: {} }],
      finishReason: 'tool_calls',
    })
    .mockResolvedValueOnce({ text: 'continued', toolCalls: [], finishReason: 'stop' });
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    surface: {
      describe: () => ({ surface: 'page', facts: { revision } }),
      capabilities: () => [
        {
          descriptor: { code: 'page.change', description: 'Change page', inputSchema: {} },
          parseInput: (input) => input,
          async execute(_input, context) {
            context.applyEffect(() => {
              revision = 'after';
            });
            return { changed: true };
          },
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');
  const history = [
    { role: 'user' as const, text: '我要处理一项业务' },
    { role: 'assistant' as const, text: '请补充目标范围。' },
  ];

  await runAssistantConversation(registry, '处理当前记录', { history });

  expect(requestTurn).toHaveBeenNthCalledWith(
    1,
    expect.objectContaining({ history, context: expect.objectContaining({ facts: { revision: 'before' } }) }),
    expect.any(AbortSignal),
  );
  expect(requestTurn).toHaveBeenNthCalledWith(
    2,
    expect.objectContaining({ history, context: expect.objectContaining({ facts: { revision: 'after' } }) }),
    expect.any(AbortSignal),
  );
});

it('preserves successful steps when a later model follow-up fails', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [{ id: 'call-1', code: 'form.patch-draft', input: {} }],
    })
    .mockRejectedValueOnce(new Error('model returned no executable content'));
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'form.patch-draft', description: 'Patch draft', inputSchema: {} },
          parseInput: (input) => input,
          async execute(_input, context) {
            context.applyEffect(() => undefined);
            return { changed: true };
          },
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  const conversation = runAssistantConversation(registry, 'change it');

  await expect(conversation).rejects.toMatchObject({
    name: 'AssistantConversationFollowUpError',
    steps: [
      expect.objectContaining({
        results: [
          {
            callId: 'call-1',
            capabilityCode: 'form.patch-draft',
            output: { changed: true },
          },
        ],
      }),
    ],
  });
  await expect(conversation).rejects.toBeInstanceOf(AssistantConversationFollowUpError);
});

it('keeps a failed follow-up after a read-only result as the original error', async () => {
  const followUpError = new Error('model unavailable');
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-1', code: 'page.describe', input: {} }] })
    .mockRejectedValueOnce(followUpError);
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'page.describe', description: 'Describe', inputSchema: {} },
          parseInput: (input) => input,
          execute: async () => ({ title: 'Page' }),
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  await expect(runAssistantConversation(registry, 'describe')).rejects.toBe(followUpError);
});

it('keeps a stale invoke after a successful effect as a stale invocation error', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-1', code: 'page.change', input: {} }] })
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-2', code: 'page.stale', input: {} }] });
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'page.change', description: 'Change', inputSchema: {} },
          parseInput: (input) => input,
          async execute(_input, context) {
            context.applyEffect(() => undefined);
            return { changed: true };
          },
        },
        {
          descriptor: { code: 'page.stale', description: 'Stale', inputSchema: {} },
          parseInput: (input) => input,
          async execute() {
            throw new StaleAssistantInvocationError();
          },
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  await expect(runAssistantConversation(registry, 'change')).rejects.toBeInstanceOf(
    StaleAssistantInvocationError,
  );
});

it('counts an applied effect once when its result is replayed before a failed follow-up', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-1', code: 'page.change', input: {} }] })
    .mockResolvedValueOnce({
      toolCalls: [
        { id: 'call-2', code: 'page.change', input: {} },
        { id: 'call-3', code: 'page.describe', input: {} },
      ],
    })
    .mockRejectedValueOnce(new Error('model unavailable'));
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'page.change', description: 'Change', inputSchema: {} },
          parseInput: (input) => input,
          async execute(_input, context) {
            context.applyEffect(() => undefined);
            return { changed: true };
          },
        },
        {
          descriptor: { code: 'page.describe', description: 'Describe', inputSchema: {} },
          parseInput: (input) => input,
          execute: async () => ({ title: 'Page' }),
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  const conversation = runAssistantConversation(registry, 'change');

  await expect(conversation).rejects.toMatchObject({
    name: 'AssistantConversationFollowUpError',
    steps: [
      expect.objectContaining({ appliedEffectCount: 1 }),
      expect.objectContaining({ appliedEffectCount: 0 }),
    ],
  });
});

it('stops a conversation at the configured bounded step limit', async () => {
  let sequence = 0;
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'page.read', description: 'Read', inputSchema: {} },
          parseInput: (input) => input,
          async execute() {
            return { ready: true };
          },
        },
      ],
      requestTurn: async () => ({
        toolCalls: [{ id: crypto.randomUUID(), code: 'page.read', input: { sequence: sequence++ } }],
      }),
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantConversation(registry, 'keep reading', { maxSteps: 2 });

  expect(result.completed).toBe(false);
  expect(result.steps).toHaveLength(2);
});

it('does not execute the same successful capability call twice in one conversation', async () => {
  const deliveredSteps: Array<{ text?: string; resultCount: number }> = [];
  const execute = vi.fn(async () => ({ opened: true }));
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [{ id: 'call-1', code: 'page.open', input: { menuId: 'apps' } }],
    })
    .mockResolvedValueOnce({
      text: 'already open',
      toolCalls: [{ id: 'call-2', code: 'page.open', input: { menuId: 'apps' } }],
    });
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'page.open', description: 'Open', inputSchema: {} },
          parseInput: (input) => input,
          execute,
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantConversation(registry, 'open apps', {
    onStep(step) {
      deliveredSteps.push({ text: step.output.text, resultCount: step.results.length });
    },
  });

  expect(result.completed).toBe(true);
  expect(result.steps).toHaveLength(2);
  expect(result.steps[1]?.results).toEqual([
    { callId: 'call-2', capabilityCode: 'page.open', output: { opened: true } },
  ]);
  expect(execute).toHaveBeenCalledOnce();
  expect(deliveredSteps).toEqual([
    { text: undefined, resultCount: 1 },
    { text: 'already open', resultCount: 0 },
  ]);
});

it('only reuses a successful call in the immediately following model decision', async () => {
  const execute = vi.fn(async (input) => input);
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-1', code: 'page.read', input: { key: 'a' } }] })
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-2', code: 'page.read', input: { key: 'b' } }] })
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-3', code: 'page.read', input: { key: 'a' } }] })
    .mockResolvedValueOnce({ text: 'done', toolCalls: [] });
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'page.read', description: 'Read', inputSchema: {} },
          parseInput: (input) => input,
          execute,
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantConversation(registry, 'read values');

  expect(result.completed).toBe(true);
  expect(execute).toHaveBeenCalledTimes(3);
  expect(execute).toHaveBeenNthCalledWith(1, { key: 'a' }, expect.anything());
  expect(execute).toHaveBeenNthCalledWith(2, { key: 'b' }, expect.anything());
  expect(execute).toHaveBeenNthCalledWith(3, { key: 'a' }, expect.anything());
});

it('restarts a post-navigation decision only when the target page replaces its fallback surface', async () => {
  const registry = createAssistantSurfaceRegistry();
  const fallbackRequest = vi.fn(
    (_input, signal: AbortSignal) =>
      new Promise<{ toolCalls: never[] }>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('replaced', 'AbortError')), {
          once: true,
        });
      }),
  );
  registry.register({
    pageInstanceKey: 'tab-b',
    fallback: true,
    contextRevision: () => 'fallback',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => [],
      requestTurn: fallbackRequest,
    },
  });
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'source',
    surface: {
      describe: () => ({ surface: 'source-page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'page.open', description: 'Open target', inputSchema: {} },
          parseInput: (input) => input,
          async execute(_input, context) {
            context.applyEffect(() => registry.activate('tab-b'));
            return { opened: true };
          },
        },
      ],
      requestTurn: async () => ({
        toolCalls: [{ id: 'call-1', code: 'page.open', input: {} }],
        finishReason: 'tool_calls',
      }),
    },
  });
  registry.activate('tab-a');
  const conversation = runAssistantConversation(registry, 'describe');
  await vi.waitFor(() => expect(fallbackRequest).toHaveBeenCalledOnce());
  registry.register({
    pageInstanceKey: 'tab-b',
    contextRevision: () => 'page',
    surface: {
      describe: () => ({ surface: 'module-page', facts: {} }),
      capabilities: () => [],
      requestTurn: async () => ({ text: 'page ready', toolCalls: [] }),
    },
  });

  const result = await conversation;

  expect(result.completed).toBe(true);
  expect(result.steps).toHaveLength(2);
  expect(result.steps[1]?.output.text).toBe('page ready');
});

it('does not replay a model decision after the user switches pages', async () => {
  const registry = createAssistantSurfaceRegistry();
  const requestTurn = vi.fn(
    (_input, signal: AbortSignal) =>
      new Promise<{ toolCalls: never[] }>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('switched', 'AbortError')), {
          once: true,
        });
      }),
  );
  for (const pageInstanceKey of ['tab-a', 'tab-b']) {
    registry.register({
      pageInstanceKey,
      contextRevision: () => pageInstanceKey,
      surface: {
        describe: () => ({ surface: 'page', facts: {} }),
        capabilities: () => [],
        requestTurn,
      },
    });
  }
  registry.activate('tab-a');
  const conversation = runAssistantConversation(registry, 'describe');
  await vi.waitFor(() => expect(requestTurn).toHaveBeenCalledOnce());

  registry.activate('tab-b');

  await expect(conversation).rejects.toBeInstanceOf(StaleAssistantInvocationError);
  expect(requestTurn).toHaveBeenCalledOnce();
});

it('restarts an initial model decision from a fresh snapshot when the same surface refreshes', async () => {
  let revision = 'before';
  const requestTurn = vi
    .fn()
    .mockImplementationOnce(async () => {
      revision = 'after';
      return { text: 'stale answer', toolCalls: [] };
    })
    .mockResolvedValueOnce({ text: 'fresh answer', toolCalls: [] });
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    interactionRevision: () => 'stable-page-state',
    surface: {
      describe: () => ({ surface: 'page', facts: { revision } }),
      capabilities: () => [],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantConversation(registry, 'describe');

  expect(result.completed).toBe(true);
  expect(result.steps).toHaveLength(1);
  expect(result.steps[0]?.output.text).toBe('fresh answer');
  expect(requestTurn).toHaveBeenCalledTimes(2);
  expect(requestTurn.mock.calls[0]?.[0].context.facts).toEqual({ revision: 'before' });
  expect(requestTurn.mock.calls[1]?.[0].context.facts).toEqual({ revision: 'after' });
});

it('restarts before invoking the first capability when its decision snapshot just refreshed', async () => {
  let revision = 'before';
  const create = vi.fn(async () => ({ opened: true }));
  const staleOutput = {
    get toolCalls() {
      revision = 'after';
      return [{ id: 'stale-call', code: 'page.create', input: {} }];
    },
  };
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce(staleOutput)
    .mockResolvedValueOnce({
      toolCalls: [{ id: 'fresh-call', code: 'page.create', input: {} }],
    })
    .mockResolvedValueOnce({ text: 'draft opened', toolCalls: [] });
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    interactionRevision: () => 'stable-page-state',
    surface: {
      describe: () => ({ surface: 'page', facts: { revision } }),
      capabilities: () => [
        {
          descriptor: { code: 'page.create', description: 'Create', inputSchema: {} },
          parseInput: (input) => input,
          execute: create,
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantConversation(registry, 'create');

  expect(result.completed).toBe(true);
  expect(create).toHaveBeenCalledOnce();
  expect(result.steps[0]?.results).toEqual([
    { callId: 'fresh-call', capabilityCode: 'page.create', output: { opened: true } },
  ]);
});

it('bounds initial decision restarts when the same surface keeps refreshing', async () => {
  let revision = 0;
  const requestTurn = vi.fn(async () => {
    revision += 1;
    return { text: `stale-${revision}`, toolCalls: [] };
  });
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => String(revision),
    interactionRevision: () => 'stable-page-state',
    surface: {
      describe: () => ({ surface: 'page', facts: { revision } }),
      capabilities: () => [],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  await expect(runAssistantConversation(registry, 'describe')).rejects.toBeInstanceOf(
    StaleAssistantInvocationError,
  );
  expect(requestTurn).toHaveBeenCalledTimes(4);
});

it('does not replay an initial decision when user-controlled page state changes', async () => {
  let revision = 'record-a:list-1';
  let interactionRevision = 'record-a';
  const requestTurn = vi.fn(async () => {
    revision = 'record-b:list-1';
    interactionRevision = 'record-b';
    return { text: 'answer for record a', toolCalls: [] };
  });
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    interactionRevision: () => interactionRevision,
    surface: {
      describe: () => ({ surface: 'page', facts: { revision } }),
      capabilities: () => [],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  await expect(runAssistantConversation(registry, 'update this record')).rejects.toBeInstanceOf(
    StaleAssistantInvocationError,
  );
  expect(requestTurn).toHaveBeenCalledOnce();
});

it('does not replay a post-effect decision when a formal surface is refreshed', async () => {
  let revision = 'before';
  const registry = createAssistantSurfaceRegistry();
  const fallbackRequest = vi.fn(async () => ({ text: 'fallback', toolCalls: [] }));
  registry.register({
    pageInstanceKey: 'tab-a',
    fallback: true,
    contextRevision: () => 'fallback',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => [],
      requestTurn: fallbackRequest,
    },
  });
  const formalRequest = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-1', code: 'page.change', input: {} }] })
    .mockImplementationOnce(
      (_input, signal: AbortSignal) =>
        new Promise<{ toolCalls: never[] }>((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(new DOMException('refreshed', 'AbortError')), {
            once: true,
          });
        }),
    );
  const change: AssistantCapability = {
    descriptor: { code: 'page.change', description: 'Change', inputSchema: {} },
    parseInput: (input) => input,
    async execute(_input, context) {
      context.applyEffect(() => {
        revision = 'after';
      });
      return { changed: true };
    },
  };
  const formalSurface = {
    describe: () => ({ surface: 'page', facts: {} }),
    capabilities: () => [change],
    requestTurn: formalRequest,
  };
  const unregisterFormal = registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    surface: formalSurface,
  });
  registry.activate('tab-a');
  const conversation = runAssistantConversation(registry, 'change');
  await vi.waitFor(() => expect(formalRequest).toHaveBeenCalledTimes(2));

  unregisterFormal();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    surface: { ...formalSurface, requestTurn: async () => ({ text: 'new page', toolCalls: [] }) },
  });

  await expect(conversation).rejects.toBeInstanceOf(StaleAssistantInvocationError);
  expect(fallbackRequest).not.toHaveBeenCalled();
});

it('does not replay a post-effect decision after the same surface context drifts', async () => {
  let revision = 'before';
  let resolveSecond!: (value: { toolCalls: never[] }) => void;
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({
      toolCalls: [{ id: 'call-1', code: 'page.change', input: {} }],
      finishReason: 'tool_calls',
    })
    .mockImplementationOnce(
      () => new Promise<{ toolCalls: never[] }>((resolve) => (resolveSecond = resolve)),
    );
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [
        {
          descriptor: { code: 'page.change', description: 'Change', inputSchema: {} },
          parseInput: (input) => input,
          async execute(_input, context) {
            context.applyEffect(() => {
              revision = 'assistant-effect';
            });
            return { changed: true };
          },
        },
      ],
      requestTurn,
    },
  });
  registry.activate('tab-a');
  const conversation = runAssistantConversation(registry, 'change');
  await vi.waitFor(() => expect(requestTurn).toHaveBeenCalledTimes(2));
  revision = 'user-change';
  resolveSecond({ toolCalls: [] });

  await expect(conversation).rejects.toBeInstanceOf(StaleAssistantInvocationError);
  expect(requestTurn).toHaveBeenCalledTimes(2);
});

it('discards streamed text when its structured turn fails before completion', async () => {
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [],
      requestTurn: async (_input, _signal, progress) => {
        progress?.onTextDelta?.('partial');
        throw new Error('stream failed');
      },
    },
  });
  registry.activate('tab-a');
  const onTextDelta = vi.fn();
  const onTextDiscard = vi.fn();

  await expect(
    runAssistantConversation(registry, 'describe', { onTextDelta, onTextDiscard }),
  ).rejects.toThrow('stream failed');
  expect(onTextDelta).toHaveBeenCalledWith('partial', 0);
  expect(onTextDiscard).toHaveBeenCalledWith(0);
});

it('waits for a pending fallback to resolve before sending history to its transport', async () => {
  const registry = createAssistantSurfaceRegistry(() => 'user');
  const fallback = vi.fn();
  const formal = vi.fn(async () => ({ text: 'ok', toolCalls: [] }));
  registry.register({
    pageInstanceKey: 'a',
    fallback: true,
    conversationScopePending: true,
    contextRevision: () => '',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => [],
      requestTurn: fallback,
    },
  });
  registry.activate('a');
  const pending = runAssistantConversation(registry, 'hello');
  registry.register({
    pageInstanceKey: 'a',
    conversationScopeKey: () => 'tenant-a',
    contextRevision: () => '',
    surface: {
      describe: () => ({ surface: 'test', facts: {} }),
      capabilities: () => [],
      requestTurn: formal,
    },
  });
  await expect(pending).resolves.toMatchObject({ completed: true });
  expect(fallback).not.toHaveBeenCalled();
  expect(formal).toHaveBeenCalledOnce();
});

it('does not send the previous goal or results after a capability changes tenant scope', async () => {
  let scope = 'tenant-a';
  const registry = createAssistantSurfaceRegistry(() => 'user');
  const requestTurn = vi.fn(async () => ({ toolCalls: [{ id: 'call', code: 'scope.change', input: {} }] }));
  registry.register({
    pageInstanceKey: 'a',
    conversationScopeKey: () => scope,
    contextRevision: () => '',
    surface: {
      describe: () => ({ surface: 'test', facts: {} }),
      requestTurn,
      capabilities: () => [
        {
          descriptor: { code: 'scope.change', description: 'change', inputSchema: {} },
          parseInput: (value) => value,
          execute: async (_input, context) => {
            context.applyEffect(() => {
              scope = 'tenant-b';
            });
            return { changed: true };
          },
        },
      ],
    },
  });
  registry.activate('a');
  await expect(runAssistantConversation(registry, 'old goal')).rejects.toThrow(StaleAssistantInvocationError);
  expect(requestTurn).toHaveBeenCalledOnce();
});
