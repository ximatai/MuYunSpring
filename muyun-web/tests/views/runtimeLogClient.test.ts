import { expect, it, vi } from 'vitest';
import type { StreamingHttpClient } from '@/web-core';
import { createRuntimeLogClient } from '@/views/runtimeLogClient.ts';

it('uses the runtime log download and stream endpoints', async () => {
  const stream = vi.fn(async () => new ReadableStream<Uint8Array>());
  const request = vi.fn();
  const client = createRuntimeLogClient({ request, stream } as StreamingHttpClient);

  await client.download('app log.log');
  await client.activeStream(500);

  expect(request).not.toHaveBeenCalled();
  expect(stream).toHaveBeenNthCalledWith(1, {
    path: '/platform.runtime_log/files/app%20log.log/download',
    headers: { Accept: 'application/octet-stream' },
  });
  expect(stream).toHaveBeenNthCalledWith(2, {
    method: 'POST',
    path: '/platform.runtime_log/active/stream',
    body: { tailLines: 500 },
    headers: { Accept: 'text/event-stream, application/json' },
  });
});
