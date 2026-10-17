import { appDataChangeDispatcher } from '@/platform-admin-runtime/realtime';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  createLanguageContext,
  businessContentLanguageContext,
  provideLanguageContext,
  ModuleContextProvider,
  type ModuleContext,
  type HttpRequestOptions,
} from '@muyun/web-core';
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
function mountWorkbench(
  request: (options: HttpRequestOptions) => Promise<unknown>,
  language?: ReturnType<typeof createLanguageContext>,
) {
  const context = {
    moduleAlias: 'workflow.workbench',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const harness = defineComponent({
    setup() {
      if (language) provideLanguageContext(language);
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
it('retains the owning business session while interface and reading languages change', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi.fn(async (options: HttpRequestOptions): Promise<unknown> => {
    if (options.path.endsWith('/workbench/todo/page'))
      return {
        records: [
          {
            instanceId: 'workflow',
            taskId: 'task',
            moduleAlias: 'quality.exception',
            recordId: 'r1',
            currentAssigneeTitles: [],
            business: { title: '原文', readable: true },
          },
        ],
        total: 1,
        pages: 1,
      };
    if (options.path === '/quality.exception/view/r1') return { id: 'r1', version: 1, title: '原文' };
    return { moduleAlias: 'quality.exception', actions: [], capabilities: [], uiDescriptor: {} };
  });
  const wrapper = mountWorkbench(request, language);
  try {
    await flushPromises();
    const list = wrapper.findComponent(surface);
    list.vm.$emit('row-click', list.props('rows')[0]);
    await flushPromises();
    const panel = wrapper.findComponent({ name: 'WorkflowRecordPanel' });
    expect(panel.exists()).toBe(true);
    const session = panel.props('context');
    const reads = () =>
      request.mock.calls.filter(([call]) => call.path === '/quality.exception/view/r1').length;
    expect(reads()).toBe(1);
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(panel.props('context')).toBe(session);
    businessContentLanguageContext(language).setLanguage('de');
    await flushPromises();
    expect(panel.props('context')).toBe(session);
    expect(reads()).toBe(1);
  } finally {
    wrapper.unmount();
  }
});
it('reads workflow business titles in the selected content language without changing originals, identity or permissions', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const original = '中国质量异常单';
  const titleContentText = {
    originalText: original,
    readings: { en: 'China quality exception', de: 'Qualitätsabweichung China' },
  };
  const cards = [
    {
      instanceId: 'one',
      recordId: 'r1',
      currentAssigneeTitles: ['原作者'],
      business: { title: original, readable: true, titleContentText },
    },
    {
      instanceId: 'two',
      recordId: 'r2',
      currentAssigneeTitles: [],
      business: { title: 'Changed original', readable: true, titleContentText },
    },
    { instanceId: 'denied', currentAssigneeTitles: [], business: { readable: false } },
  ];
  const request = vi.fn(
    async (options: HttpRequestOptions): Promise<unknown> =>
      options.path.endsWith('/page') ? { records: cards, total: 3, pages: 1 } : {},
  );
  const wrapper = mountWorkbench(request, language);
  try {
    await flushPromises();
    const list = wrapper.findComponent(surface);
    expect(list.props('rows')[0]).toMatchObject({
      title: 'China quality exception',
      recordId: 'r1',
      business: { title: original },
    });
    expect(list.props('rows')[1].title).toBe('Changed original');
    const count = request.mock.calls.length;
    businessContentLanguageContext(language).setLanguage('de');
    await flushPromises();
    expect(list.props('rows')[0].title).toBe('Qualitätsabweichung China');
    expect(list.props('rows')[2].title).toBe('Restricted business record');
    expect(request.mock.calls).toHaveLength(count);
    expect(cards[0]?.business.title).toBe(original);
  } finally {
    wrapper.unmount();
  }
});
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

it('creates the delegation name in the current language and preserves the draft when that language changes', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi.fn(
    async (options: HttpRequestOptions): Promise<unknown> =>
      options.path.endsWith('/page') ? page('task') : { records: [], modules: {} },
  );
  const wrapper = mountWorkbench(request, language);
  try {
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Personal delegation settings')!
      .trigger('click');
    await flushPromises();
    const titleInput = wrapper.findAllComponents({ name: 'UiInput' }).at(-1)!;
    expect(titleInput.props('value')).toBe('Approval delegation');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(titleInput.props('value')).toBe('Approval delegation');
    titleInput.vm.$emit('update:value', 'My literal delegation');
    language.setLanguage('en');
    await flushPromises();
    expect(titleInput.props('value')).toBe('My literal delegation');
    expect(request.mock.calls.some(([options]) => options.path.endsWith('/insert'))).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it('switches and refreshes module labels without replacing workflow facts, selection or filters', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  let translated = 'Examinations';
  let later = false;
  const text = () => ({
    source: {
      sourceKind: 'CONFIG',
      sourceId: 'config/module/education.exam/title',
      sourceVersion: 'v1',
      originalText: '考试',
    },
    languages: {
      en: { text: translated, requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false },
    },
  });
  const directory = () => ({
    modules: { 'education.exam': { moduleAlias: 'education.exam', title: '考试', titleText: text() } },
  });
  const request = vi.fn(async (options: HttpRequestOptions): Promise<unknown> => {
    if (options.path.endsWith('/workbench/modules')) return directory();
    if (options.path.endsWith('/page'))
      return {
        records: [
          {
            instanceId: 'same-instance',
            taskId: 'same-task',
            moduleAlias: 'education.exam',
            recordId: 'record-id',
            currentAssigneeTitles: ['业务人员原名'],
            taskStatus: later ? 'completed' : 'created',
            business: {
              title: later ? '服务端新业务数据' : '业务原值',
              moduleTitle: '考试',
              moduleTitleText: text(),
              readable: false,
            },
          },
        ],
        total: later ? 99 : 1,
        pages: 1,
        navigation: { modules: { 'education.exam': '考试' }, moduleTitles: directory() },
      };
    return { moduleAlias: 'iam.tenant', actions: [], capabilities: [] };
  });
  const wrapper = mountWorkbench(request, language);
  try {
    await flushPromises();
    const list = wrapper.findComponent(surface);
    const select = wrapper.findAllComponents({ name: 'UiSelect' })[0]!;
    expect(list.props('rows')[0].moduleTitle).toBe('Examinations');
    expect(select.props('options')).toContainEqual({ value: 'education.exam', label: 'Examinations' });
    select.vm.$emit('update:value', 'education.exam');
    await flushPromises();
    const fact = list.props('rows')[0];
    const count = request.mock.calls.length;
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(list.props('rows')[0].moduleTitle).toBe('考试');
    expect(select.props('options')).toContainEqual({ value: 'education.exam', label: '考试' });
    expect(select.props('value')).toBe('education.exam');
    expect(request.mock.calls).toHaveLength(count);
    language.setLanguage('en');
    await flushPromises();
    translated = 'Exam administration';
    later = true;
    await appDataChangeDispatcher.dispatch({
      changeSetId: 'module-directory-title-publication',
      changes: [{ type: 'configuration-translations-changed', moduleAlias: 'education.exam' }],
    });
    await flushPromises();
    const refreshed = list.props('rows')[0];
    expect(refreshed.moduleTitle).toBe('Exam administration');
    expect(select.props('options')).toContainEqual({ value: 'education.exam', label: 'Exam administration' });
    expect(select.props('value')).toBe('education.exam');
    expect(refreshed.taskStatus).toBe(fact.taskStatus);
    expect(refreshed.business.title).toBe('业务原值');
    expect(refreshed.instanceId).toBe('same-instance');
    expect(refreshed.currentAssigneeTitles).toEqual(['业务人员原名']);
  } finally {
    wrapper.unmount();
  }
});
