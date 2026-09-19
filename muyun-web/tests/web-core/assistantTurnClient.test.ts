import { expect, it, vi } from 'vitest';
import { createAssistantTurnRequester, type HttpClient, type StreamingHttpClient } from '@muyun/web-core';

it('sends a turn through the supplied scoped client without adding identity facts', async () => {
  const request = vi.fn(async () => ({ text: 'ok', toolCalls: [], finishReason: 'stop' }));
  const requester = createAssistantTurnRequester({ request } as HttpClient);
  const controller = new AbortController();
  const input = {
    message: 'find customers',
    context: { surface: 'workbench', facts: { page: 'home' } },
    capabilities: [],
  };

  await expect(requester(input, controller.signal)).resolves.toMatchObject({ text: 'ok' });
  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/platform.assistant/turn',
    body: input,
    signal: controller.signal,
  });
});

it('streams text deltas and resolves only the terminal structured turn', async () => {
  const encoder = new TextEncoder();
  const stream = vi.fn(
    async () =>
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode('event: text\ndata: {"text":"正在"}\n\n'));
          controller.enqueue(encoder.encode('event: text\ndata: {"text":"处理"}\n\n'));
          controller.enqueue(
            encoder.encode(
              'event: complete\ndata: {"text":"正在处理","toolCalls":[],"finishReason":"stop","requestId":"request-1"}\n\n',
            ),
          );
          controller.close();
        },
      }),
  );
  const requester = createAssistantTurnRequester({ request: vi.fn(), stream } as StreamingHttpClient);
  const deltas: string[] = [];
  const controller = new AbortController();

  await expect(
    requester(
      { message: 'describe', context: { surface: 'workbench', facts: {} }, capabilities: [] },
      controller.signal,
      { onTextDelta: (text) => deltas.push(text) },
    ),
  ).resolves.toEqual({
    text: '正在处理',
    toolCalls: [],
    finishReason: 'stop',
    requestId: 'request-1',
  });
  expect(deltas).toEqual(['正在', '处理']);
  expect(stream).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      path: '/platform.assistant/turn/stream',
      headers: { Accept: 'text/event-stream, application/json' },
      signal: controller.signal,
    }),
  );
});

it('rejects an assistant stream that ends without a terminal event', async () => {
  const encoder = new TextEncoder();
  const stream = vi.fn(
    async () =>
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode('event: text\ndata: {"text":"partial"}\n\n'));
          controller.close();
        },
      }),
  );
  const requester = createAssistantTurnRequester({ request: vi.fn(), stream } as StreamingHttpClient);

  await expect(
    requester(
      { message: 'describe', context: { surface: 'workbench', facts: {} }, capabilities: [] },
      new AbortController().signal,
    ),
  ).rejects.toThrow('无效的流式响应');
});

it('cancels the response body when an assistant stream is malformed', async () => {
  const cancel = vi.fn();
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoder.encode('event: text\ndata: not-json\n\n'));
    },
    cancel,
  });
  const requester = createAssistantTurnRequester({
    request: vi.fn(),
    stream: vi.fn(async () => body),
  } as StreamingHttpClient);

  await expect(
    requester(
      { message: 'describe', context: { surface: 'workbench', facts: {} }, capabilities: [] },
      new AbortController().signal,
    ),
  ).rejects.toThrow('无效的流式响应');
  expect(cancel).toHaveBeenCalledOnce();
});

it('preserves platform error semantics from the stream terminal event', async () => {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(
        encoder.encode(
          'event: error\ndata: {"code":"CONFIG_MISSING","status":409,"message":"未配置模型","traceId":"trace-1"}\n\n',
        ),
      );
      controller.close();
    },
  });
  const requester = createAssistantTurnRequester({
    request: vi.fn(),
    stream: vi.fn(async () => body),
  } as StreamingHttpClient);

  await expect(
    requester(
      { message: 'describe', context: { surface: 'workbench', facts: {} }, capabilities: [] },
      new AbortController().signal,
    ),
  ).rejects.toMatchObject({ code: 'CONFIG_MISSING', status: 409, message: '未配置模型', traceId: 'trace-1' });
});
