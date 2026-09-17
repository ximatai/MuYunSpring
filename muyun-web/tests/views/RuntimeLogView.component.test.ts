import { flushPromises, shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { RecordQueryListPanel } from '@muyun/platform-components';
import { configureModuleContext, type StreamingHttpClient } from '@/web-core';
import RuntimeLogView from '@/views/RuntimeLogView.vue';

it('lists log files and opens the active file as an authenticated SSE stream', async () => {
  const cancel = vi.fn();
  const encoder = new TextEncoder();
  const stream = vi.fn(async (options) => {
    if (options.path.endsWith('/download')) return new ReadableStream<Uint8Array>();
    return new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode('event: snapshot\ndata: {"fileName":"app.log","text":"booted"}\n\n'),
        );
      },
      cancel,
    });
  });
  const request = vi.fn(async (options: { path: string }) => {
    if (options.path.endsWith('/query/schema')) {
      return {
        scopeName: 'runtime-log-files',
        quickSearch: { enabled: true, fields: ['name'], fieldSchemas: [] },
        fields: [],
        externalCriteria: [],
        defaultSorts: [{ field: 'lastModifiedAt', desc: true }],
        criteriaComposition: 'FLAT_AND',
      };
    }
    return {
      records: [{ name: 'app.log', sizeBytes: 125, lastModifiedAt: '2026-09-17T10:00:00Z', active: true }],
      total: 1,
      pageNum: 1,
      pageSize: 20,
      pages: 1,
      totalKnown: true,
    };
  });
  configureModuleContext({ http: { request, stream } as StreamingHttpClient });
  const wrapper = shallowMount(RuntimeLogView, {
    global: {
      stubs: {
        RecordQueryListPanel: false,
        RecordDetailDrawer: { template: '<div><slot /><slot name="header-actions" /></div>' },
        UiSpin: { template: '<div><slot /></div>' },
      },
    },
  });

  await flushPromises();
  const list = wrapper.findComponent(RecordQueryListPanel);
  expect(list.props()).toMatchObject({
    title: '程序日志',
    rowKey: 'name',
    pageable: true,
  });
  list.vm.$emit('loaded', [
    { name: 'app.log', sizeBytes: 125, lastModifiedAt: '2026-09-17T10:00:00Z', active: true },
  ]);
  await flushPromises();

  await (wrapper.vm as unknown as { openActiveLog(): Promise<void> }).openActiveLog();
  await flushPromises();

  expect(stream).toHaveBeenCalledWith(
    expect.objectContaining({
      method: 'POST',
      path: '/platform.runtime_log/active/stream',
      body: { tailLines: 500 },
      headers: { Accept: 'text/event-stream, application/json' },
      signal: expect.any(AbortSignal),
    }),
  );
  expect(wrapper.text()).toContain('booted');

  list.vm.$emit('loaded', []);
  await flushPromises();
  await (wrapper.vm as unknown as { openActiveLog(): Promise<void> }).openActiveLog();
  await flushPromises();

  expect(stream).toHaveBeenCalledTimes(2);

  wrapper.unmount();
  expect(cancel).toHaveBeenCalledTimes(2);
});
