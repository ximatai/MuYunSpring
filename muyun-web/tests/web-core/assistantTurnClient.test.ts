import { expect, it, vi } from 'vitest';
import { createAssistantTurnRequester, type HttpClient } from '@muyun/web-core';

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
