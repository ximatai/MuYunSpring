import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
// Types describe the public entry only; all executed SDK code comes from the rebuilt tarball.
import type * as Consumer from '../../../src/consumer';

type ConsumerContract = Pick<
  typeof Consumer,
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
