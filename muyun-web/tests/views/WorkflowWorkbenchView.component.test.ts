import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { ModuleContextProvider, type ModuleContext, type HttpRequestOptions } from '@muyun/web-core';
import type { CurrentUser } from '@muyun/web-contracts';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
import WorkflowWorkbenchView from '@/views/WorkflowWorkbenchView.vue';

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ query: { tenantId: 'tenant-a' } }),
}));
const surface = defineComponent({
  name: 'RecordQueryListSurface',
  props: ['rows', 'quickSearchValue'],
  setup(_props, { slots }) {
    return () =>
      h('section', [
        slots.operations?.(),
        slots.persistentQueries?.(),
        slots.queryControls?.(),
        slots.conditions?.(),
      ]);
  },
});
const drawer = defineComponent({
  name: 'RecordDetailDrawer',
  props: ['open'],
  setup(props, { slots }) {
    return () => (props.open ? h('aside', [slots.default?.(), slots.operation?.()]) : null);
  },
});
function page(id: string) {
  return {
    records: [{ instanceId: id, taskId: id, currentAssigneeTitles: [], business: { title: id } }],
    total: 1,
    pages: 1,
    navigation: { modules: { 'demo.old': '旧租户模块' } },
  };
}
function mountWorkbench(request: (options: HttpRequestOptions) => Promise<unknown>) {
  const context = {
    moduleAlias: 'workflow.workbench',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const harness = defineComponent({
    setup() {
      provideCurrentUserContext(ref({ system: true } as CurrentUser));
      return () => h(ModuleContextProvider, { context }, { default: () => h(WorkflowWorkbenchView) });
    },
  });
  return mount(harness, {
    global: {
      stubs: {
        RecordQueryListSurface: surface,
        RecordDetailDrawer: drawer,
        RecordPicker: true,
        ManagementTabs: true,
        WorkflowRecordPanel: true,
      },
    },
  });
}
it('resets tenant filters before requesting the new tenant and ignores delayed old-tenant results', async () => {
  let resolveOld!: (value: ReturnType<typeof page>) => void;
  let oldRequestPending = false;
  const request = vi.fn(async (options: HttpRequestOptions): Promise<unknown> => {
    if (!options.path.endsWith('/workbench/todo/page')) return {};
    if (options.headers?.['X-MuYun-Tenant-Id'] === 'tenant-b') return page('tenant-b-current');
    if (oldRequestPending)
      return new Promise((resolve) => {
        resolveOld = resolve;
      });
    return page('tenant-a-initial');
  });
  const wrapper = mountWorkbench(request);
  await flushPromises();
  const list = wrapper.findComponent(surface);
  const moduleSelect = wrapper.findAllComponents({ name: 'UiSelect' })[0]!;
  moduleSelect.vm.$emit('update:value', 'demo.old');
  list.vm.$emit('update:quick-search-value', '旧租户事项');
  const dates = wrapper.findAllComponents({ name: 'UiInput' });
  dates[0]!.vm.$emit('update:value', '2026-10-01');
  dates[1]!.vm.$emit('update:value', '2026-10-02');
  await flushPromises();
  oldRequestPending = true;
  list.vm.$emit('quick-search');
  await flushPromises();
  const previousQuery = request.mock.calls
    .map(([options]) => options)
    .filter((options) => options.path.endsWith('/workbench/todo/page'))
    .at(-1)!;
  expect(previousQuery.body).toMatchObject({ query: { moduleAlias: 'demo.old' }, keyword: '旧租户事项' });
  const tenantPicker = wrapper.findAllComponents({ name: 'RecordPicker' })[0]!;
  tenantPicker.vm.$emit('update:value', 'tenant-b');
  await flushPromises();
  const tenantRequest = request.mock.calls
    .map(([options]) => options)
    .filter((options) => options.path.endsWith('/workbench/todo/page'))
    .at(-1)!;
  expect(tenantRequest).toMatchObject({
    headers: { 'X-MuYun-Tenant-Id': 'tenant-b' },
    body: { query: { page: { pageNum: 1, pageSize: 30 } }, keyword: '' },
  });
  const query = (tenantRequest.body as { query: Record<string, unknown> }).query;
  expect(query).not.toHaveProperty('moduleAlias');
  expect(query).not.toHaveProperty('receivedFrom');
  expect(moduleSelect.props('value')).toBeUndefined();
  expect(list.props('quickSearchValue')).toBe('');
  expect(dates.map((date) => date.props('value'))).toEqual(['', '']);
  resolveOld(page('tenant-a-delayed'));
  await flushPromises();
  expect(list.props('rows').map((row: { id: string }) => row.id)).toEqual(['tenant-b-current']);
});

it('binds delegation loading and saving to its tenant and discards a closed tenant session response', async () => {
  let resolveOld!: (value: unknown) => void;
  let resolveSave!: (value: unknown) => void;
  const request = vi.fn(async (options: HttpRequestOptions): Promise<unknown> => {
    if (options.path.endsWith('/workbench/todo/page')) return page('task');
    if (options.path === '/workflow/runtime/workbench/modules') return {};
    if (options.path === '/workflow/delegation/query') {
      if (options.headers?.['X-MuYun-Tenant-Id'] === 'tenant-a')
        return new Promise((resolve) => {
          resolveOld = resolve;
        });
      return { records: [] };
    }
    if (options.path === '/workflow/delegation/insert')
      return new Promise((resolve) => {
        resolveSave = resolve;
      });
    return {};
  });
  const wrapper = mountWorkbench(request);
  await flushPromises();
  const click = (title: string) =>
    wrapper
      .findAll('button')
      .find((button) => button.text() === title)!
      .trigger('click');
  await click('个人代办设置');
  await flushPromises();
  const tenantPicker = wrapper.findAllComponents({ name: 'RecordPicker' })[0]!;
  expect(tenantPicker.props('disabled')).toBe(true);
  // Programmatic identity replacement also invalidates the existing drawer session.
  tenantPicker.vm.$emit('update:value', 'tenant-b');
  await flushPromises();
  await click('个人代办设置');
  await flushPromises();
  resolveOld({ records: [{ id: 'old-policy', title: '旧租户规则', delegateUserId: 'old-user' }] });
  await flushPromises();
  expect(wrapper.text()).not.toContain('旧租户规则');
  const titleInput = wrapper.findAllComponents({ name: 'UiInput' }).at(-1)!;
  titleInput.vm.$emit('update:value', '新租户委托');
  wrapper.findAllComponents({ name: 'RecordPicker' }).at(-1)!.vm.$emit('update:value', 'tenant-b-user');
  await flushPromises();
  await click('保存委托规则');
  await flushPromises();
  expect(
    request.mock.calls.find(([options]) => options.path === '/workflow/delegation/insert')?.[0],
  ).toMatchObject({
    headers: { 'X-MuYun-Tenant-Id': 'tenant-b' },
    body: { title: '新租户委托', delegateUserId: 'tenant-b-user' },
  });
  expect(tenantPicker.props('disabled')).toBe(true);
  await click('保存委托规则');
  await flushPromises();
  expect(
    request.mock.calls.filter(([options]) => options.path === '/workflow/delegation/insert'),
  ).toHaveLength(1);
  expect(
    wrapper
      .findAll('button')
      .find((button) => button.text() === '个人代办设置')!
      .attributes('disabled'),
  ).toBeDefined();
  resolveSave({});
  await flushPromises();
});
