import { expect, it } from 'vitest';
import { createRuntimeLogSseParser } from '@/views/runtimeLogSse.ts';

it('keeps SSE event boundaries when a JSON payload spans transport chunks', () => {
  const received: Array<{ event: string; data: string }> = [];
  const parser = createRuntimeLogSseParser((event) => received.push(event));

  parser.push('event: snap');
  parser.push('shot\ndata: {"fileName":"app.log",\n');
  parser.push('data: "text":"first line\\nsecond line"}\n\nevent: append\ndata: {"text":"next"}\n\n');

  expect(received).toEqual([
    { event: 'snapshot', data: '{"fileName":"app.log",\n"text":"first line\\nsecond line"}' },
    { event: 'append', data: '{"text":"next"}' },
  ]);
});

it('flushes a final event when the stream ends without a trailing blank line', () => {
  const received: Array<{ event: string; data: string }> = [];
  const parser = createRuntimeLogSseParser((event) => received.push(event));

  parser.push('event: error\ndata: {"message":"stopped"}');
  parser.finish();

  expect(received).toEqual([{ event: 'error', data: '{"message":"stopped"}' }]);
});
