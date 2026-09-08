import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, ref } from 'vue';
import { confirmAction, UiSelect } from '@muyun/vue-ui-antdv';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));
import {
  configureModuleContext,
  createHttpClient,
  createManagedDetailRelationClient,
  createReferenceResolveClient,
} from '@muyun/web-core';
import { provideWorkspaceViewHost } from '@/platform-workbench/workspaceViewHost';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
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
function setup(handler: (url: string, options?: RequestInit) => unknown | Promise<unknown>) {
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
    vi.fn(async (input, options) => {
      const url = new Request(input).url;
      if (url.endsWith('/query/schema'))
        return Response.json({
          scopeName: 'education.exam',
          quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
          fields: [],
          externalCriteria: [],
          defaultSorts: [],
        });
      return Response.json(await handler(url, options));
    }),
  );
  configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
}
function render(shallow = true) {
  const wrapper = (shallow ? shallowMount : mount)(ModuleBusinessPreview, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: { ModuleHttpProvider: false } },
  });
  wrappers.push(wrapper);
  return wrapper;
}
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  vi.unstubAllGlobals();
});

describe('ModuleBusinessPreview', () => {
  it('searches tenant candidates remotely and ignores older search responses', async () => {
    let finishOld!: (value: unknown) => void;
    const searches: string[] = [];
    setup((url, options) => {
      if (url.endsWith('/context')) return { ...runtime(), tenantRequired: true };
      if (url.endsWith('/iam.tenant/navigator/reference/query')) {
        const body = JSON.parse(String(options?.body));
        searches.push(body.quickSearch ?? '');
        if (body.quickSearch === 'old')
          return new Promise((resolve) => {
            finishOld = resolve;
          });
        return { records: [{ id: body.quickSearch || 'initial', title: '租户' }], total: 1000 };
      }
      return {};
    });
    const wrapper = render();
    await flushPromises();
    const selector = wrapper.findComponent(UiSelect);
    selector.vm.$emit('search', 'old');
    await flushPromises();
    selector.vm.$emit('search', 'new');
    await flushPromises();
    finishOld({ records: [{ id: 'old' }] });
    await flushPromises();
    expect(selector.props('options')).toEqual([{ value: 'new', label: '租户' }]);
    expect(searches).toEqual(['', 'old', 'new']);
  });

  it('waits for tenant selection, carries it on real queries and isolates late responses', async () => {
    const calls: Array<{ url: string; tenant: string | null }> = [];
    let finishOld!: (value: unknown) => void;
    setup((url, options) => {
      const tenant = new Headers(options?.headers).get('X-MuYun-Tenant-Id');
      calls.push({ url, tenant });
      if (url.endsWith('/context')) return { ...runtime(), tenantRequired: true };
      if (url.endsWith('/iam.tenant/navigator/reference/query'))
        return {
          records: [
            { id: 'a', title: '甲' },
            { id: 'b', title: '乙' },
            { id: 'off', enabled: false },
          ],
          total: 3,
        };
      if (url.endsWith('/education.exam/query')) {
        if (tenant === 'a')
          return new Promise((resolve) => {
            finishOld = resolve;
          });
        return { records: [{ id: 'b-record', title: '乙记录' }], total: 1, pageNum: 1, pageSize: 20 };
      }
      throw new Error(`Unexpected ${url}`);
    });
    const wrapper = render(false);
    await flushPromises();
    expect(wrapper.findComponent(ModulePageHost).exists()).toBe(false);
    expect(wrapper.text()).toContain('请选择业务租户后');
    expect(calls.some((call) => call.url.endsWith('/education.exam/query'))).toBe(false);
    expect(wrapper.find('option[value="off"]').exists()).toBe(false);
    wrapper.findComponent(UiSelect).vm.$emit('update:value', 'a');
    await flushPromises();
    const oldHost = wrapper.findComponent(ModulePageHost).vm;
    wrapper.findComponent(UiSelect).vm.$emit('update:value', 'b');
    await flushPromises();
    expect(wrapper.findComponent(ModulePageHost).vm).not.toBe(oldHost);
    finishOld({ records: [{ id: 'a-record', title: '甲旧记录' }], total: 1, pageNum: 1, pageSize: 20 });
    await flushPromises();
    const records = wrapper.findComponent({ name: 'RecordQueryListPanel' }).findAll('tbody tr');
    expect(records.map((row) => row.text()).join('')).not.toContain('甲旧记录');
    expect(
      calls.filter((call) => call.url.endsWith('/education.exam/query')).map((call) => call.tenant),
    ).toEqual(['a', 'b']);
    expect(
      calls
        .filter((call) => call.url.endsWith('/iam.tenant/navigator/reference/query'))
        .every((call) => call.tenant === null),
    ).toBe(true);
  });

  it('blocks tenant changes during editing and carries the tenant through create and detail reload', async () => {
    const calls: Array<{ url: string; tenant: string | null }> = [];
    const record = { id: 'saved', title: '新记录' };
    setup((url, options) => {
      calls.push({ url, tenant: new Headers(options?.headers).get('X-MuYun-Tenant-Id') });
      if (url.endsWith('/context'))
        return {
          ...runtime(),
          tenantRequired: true,
          actions: [
            { actionCode: 'create', authorized: true },
            { actionCode: 'detail', authorized: true },
          ],
        };
      if (url.endsWith('/iam.tenant/navigator/reference/query'))
        return { records: [{ id: 'a' }, { id: 'b' }], total: 2 };
      if (url.endsWith('/query')) return { records: [], total: 0, pageNum: 1, pageSize: 20 };
      if (url.endsWith('/insert') || url.endsWith('/view/saved')) return record;
      if (url.endsWith('/actions/saved')) return { recordId: 'saved', actions: [] };
      throw new Error(`Unexpected ${url}`);
    });
    const wrapper = render(false);
    await flushPromises();
    wrapper.findComponent(UiSelect).vm.$emit('update:value', 'a');
    await flushPromises();
    const host = wrapper.findComponent(ModulePageHost).vm.$;
    wrapper
      .findComponent({ name: 'RecordQueryListPanel' })
      .vm.$emit('action', { key: 'create', actionCode: 'create' });
    await flushPromises();
    expect(wrapper.findComponent(UiSelect).props('disabled')).toBe(true);
    wrapper.findComponent(UiSelect).vm.$emit('update:value', 'b');
    await flushPromises();
    expect(wrapper.findComponent(ModulePageHost).vm.$).toBe(host);
    wrapper.findComponent({ name: 'ModuleRecordDetailActions' }).vm.$emit('save');
    await flushPromises();
    expect(
      calls
        .filter((call) => call.url.includes('/education.exam/') && !call.url.endsWith('/context'))
        .every((call) => call.tenant === 'a'),
    ).toBe(true);
    expect(calls.some((call) => call.url.endsWith('/insert'))).toBe(true);
  });

  it('passes one tenant through the standard update, delete, child, reference and action transports', async () => {
    const calls: Array<{ url: string; tenant: string | null }> = [];
    setup((url, options) => {
      calls.push({ url, tenant: new Headers(options?.headers).get('X-MuYun-Tenant-Id') });
      if (url.endsWith('/context')) return { ...runtime(), tenantRequired: true };
      if (url.endsWith('/iam.tenant/navigator/reference/query')) return { records: [{ id: 'a' }], total: 1 };
      if (url.endsWith('/query')) return { records: [], total: 0, pageNum: 1, pageSize: 20 };
      if (url.includes('/delete/')) return 1;
      return { id: 'r', version: 1 };
    });
    const wrapper = render(false);
    await flushPromises();
    wrapper.findComponent(UiSelect).vm.$emit('update:value', 'a');
    await flushPromises();
    const context = wrapper.findComponent({ name: 'RecordQueryListPanel' }).props('context');
    await context.crud.update('r', { id: 'r', version: 1 });
    await context.crud.delete('r', { version: 1 });
    const child = createManagedDetailRelationClient(context.http, {
      parentModuleAlias: 'education.exam',
      parentId: 'r',
      relationCode: 'participants',
    });
    await child.query();
    await child.insert({ title: 'child' });
    await child.update('child', { version: 1 });
    await child.delete('child', { version: 1 });
    await createReferenceResolveClient(context.http, 'education.exam').resolve('classroomId');
    await context.http.request({ method: 'POST', path: '/education.exam/approve/r', body: {} });
    await context.http.request({
      method: 'POST',
      path: '/education.exam/form-actions/compute',
      body: { record: {} },
    });
    const businessCalls = calls.filter(
      (call) => call.url.includes('/education.exam/') && !call.url.endsWith('/context'),
    );
    expect(businessCalls).toHaveLength(10);
    expect(businessCalls.every((call) => call.tenant === 'a')).toBe(true);
  });

  it('automatically uses the login tenant and hides the selector', async () => {
    const calls: Array<{ url: string; tenant: string | null }> = [];
    setup((url, options) => {
      calls.push({ url, tenant: new Headers(options?.headers).get('X-MuYun-Tenant-Id') });
      if (url.endsWith('/context')) return { ...runtime(), tenantRequired: true };
      if (url.endsWith('/query')) return { records: [], total: 0, pageNum: 1, pageSize: 20 };
      throw new Error(`Unexpected ${url}`);
    });
    const wrapper = mount(
      defineComponent({
        components: { ModuleBusinessPreview },
        setup() {
          provideCurrentUserContext(ref({ userId: 'u', username: 'u', system: false, tenantId: 'a' }));
        },
        template: '<ModuleBusinessPreview module-alias="education.exam" />',
      }),
    );
    wrappers.push(wrapper);
    await flushPromises();
    expect(wrapper.find('[aria-label="业务租户"]').exists()).toBe(false);
    expect(calls.find((call) => call.url.endsWith('/education.exam/query'))?.tenant).toBe('a');
    expect(calls.some((call) => call.url.includes('/iam.tenant/'))).toBe(false);
  });

  it('shows query failure separately from empty success and retries in the selected scope', async () => {
    let fail = true;
    setup((url) => {
      if (url.endsWith('/context')) return runtime();
      if (url.endsWith('/query')) {
        if (fail) throw new Error('business read failed');
        return { records: [], total: 0, pageNum: 1, pageSize: 20 };
      }
      throw new Error(`Unexpected ${url}`);
    });
    const wrapper = render(false);
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    fail = false;
    await wrapper.find('[role="alert"] button').trigger('click');
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  });

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
