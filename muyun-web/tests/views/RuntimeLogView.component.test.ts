import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { RecordQueryListPanel } from '@muyun/platform-components';
import {
  configureModuleContext,
  createLanguageContext,
  provideLanguageContext,
  type StreamingHttpClient,
} from '@/web-core';
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

it('changes live log labels and retained reset notices without reconnecting or rewriting log content', async () => {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const cancel = vi.fn();
  const stream = vi.fn(
    async () =>
      new ReadableStream<Uint8Array>({
        start(value) {
          controller = value;
        },
        cancel,
      }),
  );
  const request = vi.fn(async () => ({}));
  configureModuleContext({ http: { request, stream } as StreamingHttpClient });
  const language = createLanguageContext({ languageTag: 'en' });
  const Harness = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(RuntimeLogView);
    },
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        RecordQueryListPanel: {
          name: 'RecordQueryListPanel',
          props: ['columns'],
          template: '<section><slot name="operations" /></section>',
        },
        RecordDetailDrawer: {
          props: ['open', 'subtitle'],
          template:
            '<section v-if="open"><p>{{ subtitle }}</p><slot /><slot name="header-actions" /></section>',
        },
        UiButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
        UiActionButton: { template: '<button><slot /></button>' },
      },
    },
  });
  try {
    await flushPromises();
    const list = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(list.props('columns')[0].title).toBe('File name');
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'View live logs')!
      .trigger('click');
    await flushPromises();
    controller.enqueue(
      new TextEncoder().encode(
        'event: reset\ndata: {"fileName":"业务日志.log","text":"原始中文诊断\\n"}\n\n',
      ),
    );
    await flushPromises();
    expect(wrapper.text()).toContain('Log content was reset. Live updates are continuing.');
    expect(wrapper.text()).toContain('原始中文诊断');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(list.props('columns')[0].title).toBe('文件名称');
    expect(wrapper.text()).toContain('日志内容已重置，正在继续实时更新。');
    expect(wrapper.text()).toContain('原始中文诊断');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.text()).toContain('Log content was reset. Live updates are continuing.');
    expect(stream).toHaveBeenCalledOnce();
    expect(cancel).not.toHaveBeenCalled();
  } finally {
    wrapper.unmount();
  }
  expect(cancel).toHaveBeenCalledOnce();
});
