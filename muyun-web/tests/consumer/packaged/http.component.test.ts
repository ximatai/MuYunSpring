import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
// Types describe the public entry only; all executed SDK code comes from the rebuilt tarball.
import type * as Consumer from '../../../src/consumer';
import { createBusinessDirectoryReferenceProvider } from '../../../examples/business-web/src/fixtures/businessDirectoryReference';

type ConsumerContract = Pick<
  typeof Consumer,
  | 'AppWorkbenchShell'
  | 'ReadonlyRecordWorkspace'
  | 'ReferencePicker'
  | 'resolveRecordReadEntry'
  | 'createMenuTab'
  | 'getMenuNavigationTarget'
  | 'resolvePageDescriptor'
  | 'usePageDescriptor'
  | 'usePageRoute'
  | 'ManagementWorkspace'
  | 'RecordQueryListSurface'
  | 'RecordQueryListCell'
  | 'RecordDetailPanel'
  | 'RecordDetailFields'
  | 'resolveRecordQueryListColumns'
  | 'resolveRecordDetailFields'
  | 'UiSelect'
  | 'UiEmpty'
  | 'UiError'
  | 'UiButton'
  | 'UiDropdown'
  | 'UiIcon'
  | 'useWorkbenchNavigation'
  | 'UiDatePicker'
  | 'UiLanguageProvider'
  | 'createBackendHttpClient'
  | 'configureAuthenticationRecovery'
  | 'configureModuleContext'
  | 'provideModuleContextConfig'
  | 'useModuleHttpClient'
  | 'ModuleHttpProvider'
  | 'PlatformAdminOutlet'
  | 'createHttpClient'
  | 'createLanguageContext'
  | 'saveAuthToken'
  | 'clearAuthToken'
  | 'storedAuthToken'
  | 'subscribeAppDataChanges'
>;
let sdk: ConsumerContract;
beforeAll(() => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run this contract through npm run verify:consumer.');
  const javascript = readFileSync(bundle, 'utf8');
  expect(javascript).not.toContain('import.meta.env');
  sdk = new Function('Vue', `${javascript}\nreturn MuyunConsumerContract;`)(Vue) as ConsumerContract;
});
afterEach(() => {
  sdk.configureAuthenticationRecovery(undefined);
  sdk.configureModuleContext({});
  sdk.clearAuthToken();
  vi.unstubAllGlobals();
});

it('uses host build configuration, current login, anonymous requests and stream credentials', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(Response.json({})));
  vi.stubGlobal('fetch', fetcher);
  const language = sdk.createLanguageContext({ languageTag: 'en' });
  const client = sdk.createBackendHttpClient({ language });
  await client.request({ path: '/iam.role/query' });
  expect(String(fetcher.mock.calls[0]?.[0])).toBe(
    `${window.location.origin}/consumer-api-contract/iam.role/query`,
  );
  expect(fetcher.mock.calls[0]?.[1]).toMatchObject({
    credentials: 'include',
    headers: { Authorization: 'Bearer consumer-environment-token', 'Accept-Language': 'en' },
  });
  sdk.saveAuthToken('first-login');
  await client.request({ path: '/first' });
  sdk.saveAuthToken('second-login');
  language.setLanguage('zh-CN');
  await client.stream({ path: '/stream' });
  await sdk.createBackendHttpClient({ withAuth: false }).request({ path: '/login' });
  expect(fetcher.mock.calls[1]?.[1].headers.Authorization).toBe('Bearer first-login');
  expect(fetcher.mock.calls[2]?.[1]).toMatchObject({
    credentials: 'include',
    headers: { Authorization: 'Bearer second-login', 'Accept-Language': 'zh-CN' },
  });
  expect(fetcher.mock.calls[3]?.[1].headers.Authorization).toBeUndefined();
});

it('retains each request token during late 401 recovery and keeps anonymous failures local', async () => {
  let finishOld!: (response: Response) => void;
  const expired = () => Response.json({ code: 'AUTH_REQUIRED', message: 'expired' }, { status: 401 });
  const fetcher = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finishOld = resolve;
        }),
    )
    .mockImplementation(() => Promise.resolve(expired()));
  vi.stubGlobal('fetch', fetcher);
  const recovery = vi.fn((_error, token) => token === sdk.storedAuthToken());
  sdk.configureAuthenticationRecovery(recovery);
  sdk.saveAuthToken('old-login');
  const client = sdk.createBackendHttpClient();
  const old = client.request({ path: '/old' });
  sdk.saveAuthToken('new-login');
  finishOld(expired());
  await expect(old).rejects.toMatchObject({ globallyHandled: false });
  expect(recovery).toHaveBeenLastCalledWith(expect.objectContaining({ status: 401 }), 'old-login');
  await expect(client.stream({ path: '/stream' })).rejects.toMatchObject({ globallyHandled: true });
  expect(recovery).toHaveBeenLastCalledWith(expect.objectContaining({ status: 401 }), 'new-login');
  await expect(
    sdk.createBackendHttpClient({ withAuth: false }).request({ path: '/login' }),
  ).rejects.toMatchObject({ globallyHandled: false });
  expect(recovery).toHaveBeenCalledTimes(2);
  sdk.configureAuthenticationRecovery(undefined);
  await expect(client.request({ path: '/unconfigured' })).rejects.toMatchObject({ globallyHandled: false });
  expect(recovery).toHaveBeenCalledTimes(2);
});

it('uses injected page transport ahead of the global factory, including custom auth and credentials', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(Response.json({})));
  vi.stubGlobal('fetch', fetcher);
  const globalHttp = sdk.createHttpClient({ baseUrl: 'https://global.example/api' });
  const pageHttp = sdk.createHttpClient({
    baseUrl: 'https://page.example/runtime',
    credentials: 'omit',
    headers: { 'X-Host-Session': 'host-cookie-bridge' },
  });
  sdk.configureModuleContext({ httpFactory: () => globalHttp });
  const Leaf = Vue.defineComponent({
    setup() {
      const http = sdk.useModuleHttpClient();
      Vue.onMounted(() => void http.request({ path: '/iam.role/scope-selection/descriptor' }));
      return () => Vue.h('span');
    },
  });
  const Host = Vue.defineComponent({
    setup() {
      sdk.provideModuleContextConfig({ httpFactory: () => pageHttp });
      return () => Vue.h(Leaf);
    },
  });
  const wrapper = mount(Host);
  await flushPromises();
  expect(String(fetcher.mock.calls[0]?.[0])).toBe(
    'https://page.example/runtime/iam.role/scope-selection/descriptor',
  );
  expect(fetcher.mock.calls[0]?.[1]).toMatchObject({
    credentials: 'omit',
    headers: { 'X-Host-Session': 'host-cookie-bridge' },
  });
  expect(fetcher.mock.calls[0]?.[1].headers.Authorization).toBeUndefined();
  wrapper.unmount();
  const global = mount(Leaf);
  await flushPromises();
  expect(String(fetcher.mock.calls[1]?.[0])).toBe(
    'https://global.example/api/iam.role/scope-selection/descriptor',
  );
  global.unmount();
  const scoped = mount(
    Vue.defineComponent({
      setup() {
        return () => Vue.h(sdk.ModuleHttpProvider, { http: pageHttp }, { default: () => Vue.h(Leaf) });
      },
    }),
  );
  await flushPromises();
  expect(String(fetcher.mock.calls[2]?.[0])).toBe(
    'https://page.example/runtime/iam.role/scope-selection/descriptor',
  );
  scoped.unmount();
});

it('preserves committed data-change dispatch without changing the receipt', async () => {
  const receipt = {
    resultType: 'action',
    data: { id: 'role' },
    changeSetId: 'consumer-change',
    changes: [{ type: 'record-updated', moduleAlias: 'iam.role', recordId: 'role' }],
  };
  const fetcher = vi.fn().mockResolvedValue(Response.json(receipt));
  vi.stubGlobal('fetch', fetcher);
  const handler = vi.fn();
  const subscription = sdk.subscribeAppDataChanges(handler);
  try {
    expect(await sdk.createBackendHttpClient().request({ path: '/iam.role/update' })).toEqual(receipt);
    await flushPromises();
    expect(handler).toHaveBeenCalledOnce();
    expect(handler).toHaveBeenCalledWith({ changeSetId: receipt.changeSetId, changes: receipt.changes });
  } finally {
    subscription.unsubscribe();
  }
});

it('routes the shipped role scope navigator through the host factory', async () => {
  const requests: Array<{ url: URL; init?: RequestInit }> = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: URL, init?: RequestInit) => {
      const url = new URL(String(input));
      requests.push({ url, init });
      if (url.pathname.endsWith('/scope-selection/descriptor'))
        return Response.json({
          directSelections: [{ selectionKey: 'platform', title: 'Platform roles' }],
          navigations: [],
        });
      if (url.pathname.includes('/platform.module/')) return Response.json(moduleRuntime('iam.role'));
      throw new Error(`Unexpected consumer request: ${url}`);
    }),
  );
  const hostHttp = sdk.createHttpClient({
    baseUrl: 'https://host.example/runtime',
    credentials: 'omit',
    headers: { 'X-Host-Session': 'role-host' },
  });
  sdk.configureModuleContext({ httpFactory: () => hostHttp });
  const wrapper = mount(sdk.PlatformAdminOutlet, {
    props: {
      descriptor: {
        pageType: 'dynamic-module',
        openMode: 'dynamic-runner',
        hostType: 'module-page-host',
        tabPolicy: { identity: 'by-menu' },
        target: { moduleAlias: 'iam.role', pageMode: 'LIST' },
      },
    },
    global: {
      stubs: {
        RecordQueryListPanel: { template: '<section />' },
        ManagementWorkspace: { template: '<section><slot /></section>' },
        ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
        RecordExplorerPanel: { template: '<aside><slot /></aside>' },
        UiTree: true,
        UiSpin: true,
      },
    },
  });
  try {
    for (let i = 0; i < 4; i += 1) await flushPromises();
    const scope = requests.find(({ url }) => url.pathname.endsWith('/scope-selection/descriptor'));
    expect(scope, requests.map(({ url }) => url.href).join('\n')).toBeDefined();
    expect(scope?.url.href).toBe('https://host.example/runtime/iam.role/scope-selection/descriptor');
    expect(scope?.init).toMatchObject({ credentials: 'omit', headers: { 'X-Host-Session': 'role-host' } });
    expect(requests.every(({ url }) => url.origin === 'https://host.example')).toBe(true);
  } finally {
    wrapper.unmount();
  }
});

function moduleRuntime(moduleAlias: string) {
  return {
    moduleAlias,
    tenantRequired: false,
    capabilities: [],
    abilities: ['crud'],
    actions: [],
    uiDescriptor: {
      schemaVersion: '1',
      moduleAlias,
      page: {
        template: 'LIST_DETAIL_CARD',
        list: { fields: { viewCode: 'list', viewKind: 'LIST', fields: [] } },
        detail: { editor: { viewCode: 'form', viewKind: 'FORM', fields: [] } },
      },
    },
  };
}

it('batches the shipped user online cells through their owning page transport', async () => {
  const requests: Array<{ url: URL; init?: RequestInit }> = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: URL, init?: RequestInit) => {
      const url = new URL(String(input));
      requests.push({ url, init });
      if (url.pathname.endsWith('/iam.user/sessions/status'))
        return Response.json([
          { userId: 'user-one', online: true, activeSessionCount: 2 },
          { userId: 'user-two', online: true, activeSessionCount: 3 },
        ]);
      if (url.pathname.includes('/platform.module/')) return Response.json(moduleRuntime('iam.user'));
      throw new Error(`Unexpected consumer request: ${url}`);
    }),
  );
  sdk.configureModuleContext({
    httpFactory: () =>
      sdk.createHttpClient({
        baseUrl: 'https://users.example/runtime',
        credentials: 'include',
        token: 'host-user-token',
      }),
  });
  const TableRows = Vue.defineComponent({
    props: {
      additionalColumns: {
        type: Array as Vue.PropType<Consumer.ModulePageColumnContribution[]>,
        default: () => [],
      },
    },
    setup(props) {
      return () =>
        Vue.h(
          'section',
          props.additionalColumns.flatMap((column) =>
            ['user-one', 'user-two'].map((id) => Vue.h(column.cell, { record: { id } })),
          ),
        );
    },
  });
  const wrapper = mount(sdk.PlatformAdminOutlet, {
    props: {
      descriptor: {
        pageType: 'dynamic-module',
        openMode: 'dynamic-runner',
        hostType: 'module-page-host',
        tabPolicy: { identity: 'by-menu' },
        target: { moduleAlias: 'iam.user', pageMode: 'LIST' },
      },
    },
    global: {
      stubs: {
        RecordQueryListPanel: TableRows,
        ManagementWorkspace: { template: '<section><slot /></section>' },
        ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
      },
    },
  });
  try {
    for (let i = 0; i < 4; i += 1) await flushPromises();
    const statuses = requests.filter(({ url }) => url.pathname.endsWith('/iam.user/sessions/status'));
    expect(statuses).toHaveLength(1);
    expect(statuses[0]?.url.href).toBe('https://users.example/runtime/iam.user/sessions/status');
    expect(statuses[0]?.init).toMatchObject({
      credentials: 'include',
      headers: { Authorization: 'Bearer host-user-token' },
    });
    expect(JSON.parse(String(statuses[0]?.init?.body))).toEqual({ userIds: ['user-one', 'user-two'] });
    expect(wrapper.text()).toContain('(2)');
    expect(wrapper.text()).toContain('(3)');
  } finally {
    wrapper.unmount();
  }
});

it('ships the string date picker with the host language context and shared vendor runtime', async () => {
  const context = sdk.createLanguageContext({ languageTag: 'en' });
  const wrapper = mount(sdk.UiLanguageProvider, {
    props: { context },
    slots: {
      default: () =>
        Vue.h(sdk.UiDatePicker, {
          mode: 'datetime',
          value: '2026-10-20T14:15:37',
          ariaLabel: 'Effective time',
        }),
    },
  });
  try {
    expect(wrapper.get('input').element.value).toBe('2026-10-20 14:15:37');
    expect(wrapper.get('input').attributes('aria-label')).toBe('Effective time');
    expect(wrapper.getComponent({ name: 'ADatePicker' }).props('locale')?.lang.locale).toBe('en_US');
    context.setLanguage('zh-CN');
    await Vue.nextTick();
    expect(wrapper.getComponent({ name: 'ADatePicker' }).props('locale')?.lang.locale).toBe('zh_CN');
  } finally {
    wrapper.unmount();
  }
});

it('ships host-owned topbar components with the shell navigation and reactive user context', async () => {
  const HostTools = Vue.defineComponent({
    props: { username: String },
    setup(props) {
      const navigation = sdk.useWorkbenchNavigation();
      return () =>
        Vue.h(
          sdk.UiDropdown,
          {
            items: [{ key: 'report', title: 'Business report' }],
            onSelect: () => navigation?.openRoute('/business/report'),
          },
          {
            default: ({ toggle, open }: { toggle: () => void; open: boolean }) =>
              Vue.h(
                sdk.UiButton,
                {
                  ariaLabel: `Business tools: ${props.username}`,
                  'aria-expanded': open,
                  'aria-haspopup': 'menu',
                  onClick: (event: MouseEvent) => {
                    event.stopPropagation();
                    toggle();
                  },
                },
                { default: () => Vue.h(sdk.UiIcon, { name: 'app' }) },
              ),
          },
        );
    },
  });
  const startup: Consumer.WorkbenchStartupState = {
    session: { currentUser: { userId: 'one', username: 'first', system: false } },
    menus: [],
    tabs: [],
  };
  const wrapper = mount(sdk.AppWorkbenchShell, {
    props: { startup, location: '/' },
    slots: {
      'topbar-actions': ({ currentUser }) => Vue.h(HostTools, { username: currentUser?.username }),
    },
  });
  try {
    await flushPromises();
    const action = wrapper.get('button[aria-label="Business tools: first"]');
    expect(action.find('svg').exists()).toBe(true);
    await action.trigger('click');
    expect(action.attributes('aria-expanded')).toBe('true');
    wrapper.getComponent(HostTools).getComponent(sdk.UiDropdown).vm.$emit('select', 'report');
    expect(wrapper.emitted('navigate')?.at(-1)?.[0]).toMatchObject({ mode: 'push', url: '/business/report' });
    expect(wrapper.emitted('userCommand')).toBeUndefined();
    await wrapper.setProps({
      startup: {
        ...startup,
        session: { currentUser: { userId: 'two', username: 'second', system: false } },
      },
    });
    expect(wrapper.find('button[aria-label="Business tools: second"]').exists()).toBe(true);
  } finally {
    wrapper.unmount();
  }
});

it('published business surfaces reuse platform pagination, detail fields and refresh actions', async () => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  const wrapper = mount(sdk.ManagementWorkspace, {
    props: { explorerCount: 0, detailSurface: true },
    slots: {
      default: () => [
        Vue.h(sdk.RecordQueryListSurface, {
          title: '业务列表',
          columns: [{ key: 'title', title: '名称' }],
          rows: [{ id: 'one', title: '一号记录' }],
          pageable: true,
          total: 21,
          pageNum: 1,
          pages: 2,
          pageSize: 20,
          pageSizeOptions: [20],
          titleActionIcon: 'reload',
          titleActionTitle: '刷新业务列表',
        }),
        Vue.h(
          sdk.RecordDetailPanel,
          { title: '一号记录' },
          {
            default: () =>
              Vue.h(sdk.RecordDetailFields, {
                record: { state: '已结束' },
                fallback: { state: { label: '状态', readOnly: true } },
              }),
          },
        ),
      ],
    },
  });
  await flushPromises();
  expect(wrapper.text()).toContain('一号记录');
  expect(wrapper.text()).toContain('已结束');
  expect(wrapper.find('button[aria-label="下一页"]').exists()).toBe(true);
  expect(wrapper.find('.record-detail-panel-region').exists()).toBe(true);
  wrapper.unmount();
});

it('published custom lists reuse authorized reference titles, statuses and readings through standard cells', async () => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  const columns: Consumer.RecordQueryListColumn[] = [
    {
      key: 'ownerId',
      title: '负责人',
      titleField: 'ownerTitle',
      reference: {
        targetModuleAlias: 'example.person',
        cardinality: 'ONE',
      } as Consumer.RecordQueryListColumn['reference'],
    },
    { key: 'enabled', title: '状态', type: 'enabledStatus' },
    { key: 'summary', title: '摘要' },
  ];
  const wrapper = mount(sdk.RecordQueryListSurface, {
    props: {
      columns,
      rows: [
        { id: 'one', ownerId: 'raw-owner', ownerTitle: '已授权负责人', enabled: false, summary: 'Original' },
      ],
    },
    slots: {
      cell: ({
        column,
        record,
      }: {
        column: Consumer.UiDataTableColumn;
        record: Consumer.UiDataTableRecord;
      }) =>
        Vue.h(sdk.RecordQueryListCell, {
          column: columns.find((item) => item.key === column.key)!,
          record,
          reading: column.key === 'summary' ? '阅读文本' : undefined,
        }),
    },
  });
  await flushPromises();
  expect(wrapper.text()).toContain('已授权负责人');
  expect(wrapper.text()).not.toContain('raw-owner');
  expect(wrapper.text()).toContain('停用');
  expect(wrapper.text()).not.toContain('false');
  expect(wrapper.text()).toContain('阅读文本');
  wrapper.unmount();
});

it('published presentation adapters consume compiled DSL labels and date controls without local fields', () => {
  const descriptor = {
    page: {
      list: { fields: { fields: [{ fieldRef: { fieldName: 'title' }, label: '设备', width: '220px' }] } },
      detail: {
        display: {
          fields: [
            {
              fieldRef: { fieldName: 'startedAt' },
              label: '领用开始',
              uiType: 'datetime',
              readOnly: { constant: true },
              fieldControl: {
                alias: 'datetime',
                rendererType: 'DATETIME',
                valueShape: 'SCALAR',
                properties: {},
                bindings: [],
              },
            },
          ],
        },
      },
    },
  } as unknown as Parameters<ConsumerContract['resolveRecordDetailFields']>[0];
  expect(sdk.resolveRecordQueryListColumns(descriptor?.page?.list?.fields)).toMatchObject([
    { key: 'title', title: '设备', width: '220px' },
  ]);
  expect(sdk.resolveRecordDetailFields(descriptor).get('startedAt')).toMatchObject({
    label: '领用开始',
    uiType: 'datetime',
  });
  expect(sdk.resolveRecordDetailFields(undefined).size).toBe(0);
});

it('published read workspace handles named views without a writable CRUD context', async () => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  const fields = [{ fieldRef: { fieldName: 'title' }, label: '事项' }];
  const descriptor = {
    page: {
      list: { fields: { title: '只读队列', fields }, searchPlaceholder: '搜索' },
      detail: { display: { fields } },
    },
  } as unknown as Consumer.ResolvedModuleUiDescriptor;
  function source(viewCode: string): Consumer.RecordReadView {
    return {
      sourceId: 'example.queue',
      querySchema: {
        scopeName: 'example.queue',
        criteriaComposition: 'TREE',
        fields: [{ name: 'title', title: '事项', valueType: 'STRING', operators: ['EQ'], sortable: true }],
        quickSearch: { enabled: true, fields: ['title'], fieldSchemas: [] },
        externalCriteria: [],
        defaultSorts: [],
      },
      viewCode,
      scopeKey: 'tenant-a:user-a',
      descriptor,
      recordKey: (row: Record<string, unknown>) => `${row.origin}:${row.id}`,
      query: vi.fn(async () => ({
        records: [{ id: 'same', origin: 'call', title: viewCode }],
        pageNum: 1,
        pageSize: 20,
        pages: 1,
        total: 1,
        totalKnown: true,
      })),
      detail: {
        title: (row: Record<string, unknown>) => String(row.title),
        load: async (row: Record<string, unknown>) => row,
      },
    };
  }
  const current = source('current');
  const wrapper = mount(sdk.ReadonlyRecordWorkspace, {
    props: { view: current },
    attachTo: document.body,
  });
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('current');
    expect(current.query).toHaveBeenCalledTimes(1);
    await wrapper.get('button[aria-label^="刷新"]').trigger('click');
    await flushPromises();
    expect(current.query).toHaveBeenCalledTimes(2);
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('高级筛选'))!
      .trigger('click');
    await wrapper.get('input[aria-label="事项"]').setValue('needle');
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '应用条件')!
      .trigger('click');
    await flushPromises();
    expect(current.query).toHaveBeenLastCalledWith(
      expect.objectContaining({ criteria: expect.objectContaining({ operator: 'AND' }) }),
      expect.anything(),
    );
    await wrapper.setProps({ view: source('history') });
    await flushPromises();
    expect(wrapper.text()).toContain('history');
    expect(wrapper.text()).not.toContain('current');
    expect(wrapper.find('button[aria-label="新增"]').exists()).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it('published named-entry contract preserves menu defaults across custom route mapping', () => {
  const menu = {
    id: 'use.history',
    title: '交接记录',
    moduleAlias: 'example.device_use',
    entryType: 'module',
    openMode: 'tab',
    entryParamsJson: '{"view":"history"}',
    defaultUiConfigId: 'history-ui',
    defaultQueryTemplateId: 'history-query',
  } as Consumer.MenuRecord;
  const tab = sdk.createMenuTab(menu, sdk.getMenuNavigationTarget(menu)!);
  expect(tab.pageDescriptor?.menuId).toBe(menu.id);
  const page = sdk.resolvePageDescriptor(sdk.getMenuNavigationTarget(menu)!, {
    businessModuleRoutes: { 'example.device_use': '/business/device-use' },
  });
  const entry = sdk.resolveRecordReadEntry({
    moduleAlias: 'example.device_use',
    viewCodes: ['current', 'history'],
    page,
  });
  expect(entry).toMatchObject({
    viewCode: 'history',
    menuId: menu.id,
    defaultUiConfigId: 'history-ui',
    defaultQueryTemplateId: 'history-query',
  });
  expect(() =>
    sdk.resolveRecordReadEntry({ moduleAlias: 'example.device_use', viewCodes: ['current'], page }),
  ).toThrow('Unknown read view');
});

it('published picker resolves unseen history and prevents removed history being selected again', async () => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  const wrapper = mount(sdk.ReferencePicker, {
    props: {
      provider: createBusinessDirectoryReferenceProvider('all'),
      multiple: true,
      value: ['west-24', 'retired-service', 'hidden-service'],
      pageSize: 10,
    },
  });
  try {
    await flushPromises();
    expect(wrapper.emitted('selection-resolved')?.at(-1)?.[0]).toEqual([
      expect.objectContaining({ id: 'west-24', title: '西部服务 24' }),
      expect.objectContaining({ id: 'retired-service', disabled: true }),
      expect.objectContaining({ id: 'hidden-service', unavailable: true }),
    ]);
    wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
    await flushPromises();
    const table = wrapper.findComponent({ name: 'UiDataTable' });
    table.props('selection').onChange(['east-1']);
    await flushPromises();
    table.props('selection').onChange(['east-1', 'retired-service', 'hidden-service']);
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual([['east-1']]);
  } finally {
    wrapper.unmount();
  }
});
