import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, ref } from 'vue';
import { confirmAction } from '@muyun/vue-ui-antdv';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));
import { configureModuleContext, createHttpClient } from '@muyun/web-core';
import { provideWorkspaceViewHost } from '@/platform-workbench/workspaceViewHost';
import ModuleBusinessPreview from '@/views/ModuleBusinessPreview.vue';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import { moduleGovernanceWorkspaceView } from '@/views/moduleGovernanceWorkspaceView';

const runtime = (published = true) => ({
  moduleAlias: 'education.exam',
  capabilities: [],
  actions: [],
  uiDescriptor: {
    schemaVersion: '1',
    moduleAlias: 'education.exam',
    ...(published
      ? {
          page: {
            template: 'LIST_DETAIL_CARD',
            list: { fields: { viewCode: 'default_list', viewKind: 'LIST', fields: [] } },
            detail: { editor: { viewCode: 'default_form', viewKind: 'FORM', fields: [] } },
            traits: ['STANDARD_CRUD'],
          },
        }
      : {}),
  },
});
const wrappers: Array<{ unmount: () => void }> = [];
function setup(handler: (url: string) => unknown | Promise<unknown>) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input) => {
      const url = new Request(input).url;
      if (url.endsWith('/query/schema'))
        return Response.json({
          scopeName: 'education.exam',
          quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
          fields: [],
          externalCriteria: [],
          defaultSorts: [],
        });
      return Response.json(await handler(url));
    }),
  );
  configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
}
function render(shallow = true) {
  const wrapper = (shallow ? shallowMount : mount)(ModuleBusinessPreview, {
    props: { moduleAlias: 'education.exam' },
  });
  wrappers.push(wrapper);
  return wrapper;
}
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  vi.unstubAllGlobals();
});

describe('ModuleBusinessPreview', () => {
  it('preserves old diagnostics links as business preview', () => {
    expect(
      moduleGovernanceWorkspaceView.parse?.({ moduleAlias: 'education.exam', governanceTab: 'diagnostics' }),
    ).toMatchObject({ governanceTab: 'preview' });
  });
  it('does not mount a generic business page without a published definition', async () => {
    setup(() => runtime(false));
    const wrapper = render();
    await flushPromises();
    expect(wrapper.findComponent(ModulePageHost).exists()).toBe(false);
    expect(wrapper.html()).toContain('尚未发布');
  });
  it('runs the real module host and business query without draft preview APIs', async () => {
    const urls: string[] = [];
    setup((url) => {
      urls.push(url);
      if (url.endsWith('/platform.module/education.exam/context')) return runtime();
      if (url.endsWith('/education.exam/query'))
        return { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0 };
      throw new Error(`Unexpected request: ${url}`);
    });
    const wrapper = render(false);
    await flushPromises();
    expect(wrapper.findComponent(ModulePageHost).props('descriptor')).toMatchObject({
      target: { moduleAlias: 'education.exam', pageMode: 'LIST' },
    });
    expect(urls.some((url) => url.endsWith('/education.exam/query'))).toBe(true);
    expect(urls.every((url) => url.endsWith('/context') || url.endsWith('/education.exam/query'))).toBe(true);
  });
  it('completes create and reload through the standard business runtime', async () => {
    let created = false;
    let finishSave!: () => void;
    const pendingSave = new Promise<void>((resolve) => {
      finishSave = resolve;
    });
    const record = { id: 'exam-1', title: '业务记录' };
    const urls: string[] = [];
    setup((url) => {
      urls.push(url);
      if (url.endsWith('/context'))
        return {
          ...runtime(),
          actions: [
            { actionCode: 'create', authorized: true },
            { actionCode: 'query', authorized: true },
            { actionCode: 'detail', authorized: true },
          ],
        };
      if (url.endsWith('/query'))
        return { records: created ? [record] : [], total: created ? 1 : 0, pageNum: 1, pageSize: 20 };
      if (url.endsWith('/insert')) {
        created = true;
        return pendingSave.then(() => record);
      }
      if (url.endsWith('/actions/exam-1')) return { recordId: record.id, actions: [] };
      if (url.endsWith('/view/exam-1')) return record;
      throw new Error(`Unexpected request: ${url}`);
    });
    const wrapper = render(false);
    await flushPromises();
    wrapper
      .findComponent({ name: 'RecordQueryListPanel' })
      .vm.$emit('action', { key: 'create', actionCode: 'create' });
    await flushPromises();
    wrapper
      .findComponent({ name: 'ModulePageRecordContent' })
      .vm.$emit('update:field', 'title', record.title);
    await flushPromises();
    const reload = wrapper.find('header.business-preview__toolbar button');
    expect(reload.attributes('disabled')).toBeDefined();
    wrapper.findComponent({ name: 'ModuleRecordDetailActions' }).vm.$emit('save');
    await flushPromises();
    expect(reload.attributes('disabled')).toBeDefined();
    finishSave();
    await flushPromises();
    expect(created).toBe(true);
    expect(reload.attributes('disabled')).toBeUndefined();
    expect(urls.filter((url) => url.endsWith('/query')).length).toBeGreaterThan(1);
    expect(wrapper.findComponent({ name: 'ModulePageRecordContent' }).props('record')).toMatchObject(record);
  });

  it('registers active business edits with the workbench close guard', async () => {
    setup(() => runtime());
    let isDirty!: () => boolean;
    const unregister = vi.fn();
    const wrapper = mount(
      defineComponent({
        components: { ModuleBusinessPreview },
        setup() {
          provideWorkspaceViewHost({
            presentation: 'tab',
            setTitle: vi.fn(),
            replaceQuery: vi.fn(),
            dismiss: vi.fn(),
            close: vi.fn(),
            registerUnsavedState: (_source, check) => {
              isDirty = check;
              return unregister;
            },
          });
        },
        template: '<ModuleBusinessPreview module-alias="education.exam" />',
      }),
      { global: { stubs: { ModulePageHost: true } } },
    );
    wrappers.push(wrapper);
    await flushPromises();
    expect(isDirty()).toBe(false);
    const host = wrapper.findComponent(ModulePageHost);
    host.vm.$emit('interaction-state-change', { editing: true, busy: false });
    expect(isDirty()).toBe(true);
    host.vm.$emit('interaction-state-change', { editing: false, busy: true });
    expect(isDirty()).toBe(true);
    host.vm.$emit('interaction-state-change', { editing: false, busy: false });
    expect(isDirty()).toBe(false);
    wrapper.unmount();
    expect(unregister).toHaveBeenCalledOnce();
  });

  it('preserves an editor loading its record while a reload request is pending', async () => {
    let resolveReload!: (value: unknown) => void;
    let resolveView!: (value: unknown) => void;
    let delaying = false;
    const record = { id: 'exam-1', title: 'editing record' };
    const context = { ...runtime(), actions: [{ actionCode: 'update', authorized: true }] };
    setup((url) => {
      if (url.endsWith('/context'))
        return delaying
          ? new Promise((resolve) => {
              resolveReload = resolve;
            })
          : context;
      if (url.endsWith('/query')) return { records: [], total: 0, pageNum: 1, pageSize: 20 };
      if (url.endsWith('/view/exam-1'))
        return new Promise((resolve) => {
          resolveView = resolve;
        });
      if (url.endsWith('/actions/exam-1')) return { recordId: record.id, actions: [] };
      throw new Error(`Unexpected request: ${url}`);
    });
    const wrapper = render(false);
    await flushPromises();
    const host = wrapper.findComponent(ModulePageHost);
    delaying = true;
    await wrapper.find('header.business-preview__toolbar button').trigger('click');
    await flushPromises();
    wrapper.findComponent({ name: 'RecordQueryListPanel' }).vm.$emit('rowAction', { key: 'edit' }, record);
    await flushPromises();
    expect(wrapper.find('header.business-preview__toolbar button').attributes('disabled')).toBeDefined();
    resolveReload({ ...context, title: 'new publication' });
    await flushPromises();
    expect(wrapper.findComponent(ModulePageHost).vm.$).toBe(host.vm.$);
    expect(wrapper.find('[role="status"]').text()).toContain('已更新');
    resolveView(record);
    await flushPromises();
    expect(wrapper.findComponent({ name: 'ModulePageRecordContent' }).props('mode')).toBe('edit');
  });

  it.each(['success', 'failure', 'cancel'] as const)(
    'protects preview reload during deletion and releases it on %s',
    async (outcome) => {
      let confirm!: (accepted: boolean) => void;
      vi.mocked(confirmAction).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            confirm = resolve;
          }),
      );
      let finishDelete!: () => void;
      const pendingDelete = new Promise<void>((resolve) => {
        finishDelete = resolve;
      });
      let deleted = false;
      let deleteCalls = 0;
      let contextCalls = 0;
      const record = { id: 'exam-1', version: 1, title: '待删除记录' };
      setup(async (url) => {
        if (url.endsWith('/context')) {
          contextCalls++;
          return { ...runtime(), actions: [{ actionCode: 'delete', authorized: true }] };
        }
        if (url.endsWith('/query'))
          return { records: deleted ? [] : [record], total: deleted ? 0 : 1, pageNum: 1, pageSize: 20 };
        if (url.endsWith('/view/exam-1')) return record;
        if (url.endsWith('/actions/exam-1')) return { recordId: record.id, actions: [] };
        if (url.endsWith('/delete/exam-1')) {
          deleteCalls++;
          await pendingDelete;
          if (outcome === 'failure') throw new Error('delete failed');
          deleted = true;
          return 1;
        }
        throw new Error(`Unexpected request: ${url}`);
      });
      const wrapper = render(false);
      await flushPromises();
      const panel = wrapper.findComponent({ name: 'RecordQueryListPanel' });
      const host = wrapper.findComponent(ModulePageHost);
      const reload = wrapper.find('header.business-preview__toolbar button');
      const initialContexts = contextCalls;
      panel.vm.$emit('rowAction', { key: 'delete' }, record);
      await flushPromises();
      expect(reload.attributes('disabled')).toBeDefined();
      confirm(outcome !== 'cancel');
      await flushPromises();
      if (outcome !== 'cancel') {
        expect(reload.attributes('disabled')).toBeDefined();
        await reload.trigger('click');
        expect(contextCalls).toBe(initialContexts);
        expect(wrapper.findComponent(ModulePageHost).vm.$).toBe(host.vm.$);
        finishDelete();
        await flushPromises();
      }
      expect(deleteCalls).toBe(outcome === 'cancel' ? 0 : 1);
      expect(reload.attributes('disabled')).toBeUndefined();
      if (outcome === 'success') expect(wrapper.text()).not.toContain(record.title);
    },
  );

  it('blocks generic CRUD if the configured page disappears during startup', async () => {
    let contexts = 0;
    const urls: string[] = [];
    setup((url) => {
      urls.push(url);
      return runtime(++contexts === 1);
    });
    const wrapper = render(false);
    await flushPromises();
    expect(wrapper.text()).toContain('尚未发布页面');
    expect(urls.some((url) => url.endsWith('/query'))).toBe(false);
  });

  it('shows load errors and allows retry', async () => {
    let fail = true;
    setup(() => {
      if (fail) throw new Error('connection lost');
      return runtime();
    });
    const wrapper = render(false);
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    expect(wrapper.findComponent(ModulePageHost).exists()).toBe(false);
    fail = false;
    await wrapper.find('button').trigger('click');
    await flushPromises();
    expect(wrapper.findComponent(ModulePageHost).exists()).toBe(true);
  });
  it('preserves active forms on return and offers reload after publication', async () => {
    let title = 'old';
    setup(() => ({ ...runtime(), title }));
    const visible = ref(true);
    const wrapper = mount(
      defineComponent({
        components: { ModuleBusinessPreview },
        setup: () => ({ visible }),
        template:
          '<KeepAlive><ModuleBusinessPreview v-if="visible" module-alias="education.exam" /></KeepAlive>',
      }),
      { global: { stubs: { ModulePageHost: true } } },
    );
    wrappers.push(wrapper);
    await flushPromises();
    const original = wrapper.findComponent(ModulePageHost).vm;
    visible.value = false;
    await flushPromises();
    title = 'new';
    visible.value = true;
    await flushPromises();
    expect(wrapper.find('[role="status"]').text()).toContain('已更新');
    expect(wrapper.findComponent(ModulePageHost).vm).toBe(original);
    await wrapper.find('button').trigger('click');
    await flushPromises();
    expect(wrapper.findComponent(ModulePageHost).vm).not.toBe(original);
    expect(wrapper.find('[role="status"]').exists()).toBe(false);
  });
});
