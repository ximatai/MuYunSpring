import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import type * as Consumer from '../../../src/consumer';
import type TenantScopedOverviewExample from '../../../examples/business-web/src/views/TenantScopedOverviewExample.vue';

type Sdk = Pick<typeof Consumer, 'TenantScopeExplorer' | 'ManagementExplorerColumn' | 'createHttpClient'> & {
  TenantScopedOverviewExample: typeof TenantScopedOverviewExample;
};
let sdk: Sdk;
beforeAll(() => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run through npm run verify:consumer.');
  sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
});
afterEach(() => vi.unstubAllGlobals());

const admin = { userId: 'system-a', system: true };
const tenants = [
  { id: 'tenant-a', title: 'Tenant A' },
  { id: 'tenant-b', title: 'Tenant B' },
];
function transport(overview: (request: Request) => Response | Promise<Response>) {
  const requests: Request[] = [];
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = new Request(input, init);
    requests.push(request);
    const path = new URL(request.url).pathname;
    if (path === '/platform.module/iam.tenant/reference-context')
      return Response.json({ moduleAlias: 'iam.tenant', capabilities: [], abilities: ['crud'], actions: [] });
    if (path === '/iam.tenant/navigator/reference/query') {
      const query = await request.json();
      const records = tenants.filter((item) => item.title.includes(query.quickSearch ?? ''));
      return Response.json({
        records,
        total: records.length,
        totalKnown: true,
        pages: 1,
        pageNum: 1,
        pageSize: 200,
      });
    }
    if (path === '/business.overview') return overview(request);
    throw new Error(`Unexpected request: ${path}`);
  });
  return { http: sdk.createHttpClient({ baseUrl: 'http://api.local' }), requests };
}
function businessRequests(requests: Request[]) {
  return requests.filter((item) => item.url.endsWith('/business.overview'));
}

it('consumes public components with reference candidates, selection, refresh and collapse', async () => {
  const { http, requests } = transport((request) =>
    Response.json({ tenant: request.headers.get('X-MuYun-Tenant-Id') }),
  );
  const wrapper = mount(sdk.TenantScopedOverviewExample, {
    props: { http, overviewPath: '/business.overview' },
  });
  await flushPromises();
  expect(requests).toHaveLength(0);
  expect(wrapper.findComponent(sdk.TenantScopeExplorer).exists()).toBe(false);
  await wrapper.setProps({ currentUser: admin });
  await flushPromises();
  expect(wrapper.text()).toContain('Tenant A');
  expect(businessRequests(requests)).toHaveLength(0);
  await wrapper.get('[title="Tenant A"]').trigger('click');
  await flushPromises();
  expect(businessRequests(requests)[0]?.headers.get('X-MuYun-Tenant-Id')).toBe('tenant-a');
  expect(wrapper.get('pre').text()).toContain('tenant-a');

  await wrapper.get('button[aria-label="刷新：租户"]').trigger('click');
  await flushPromises();
  expect(requests.filter((item) => item.url.endsWith('/navigator/reference/query'))).toHaveLength(2);
  expect(
    requests
      .filter((item) => !item.url.endsWith('/business.overview'))
      .every((item) => !item.headers.has('X-MuYun-Tenant-Id')),
  ).toBe(true);
  await wrapper.get('button[title="搜索租户"]').trigger('click');
  await wrapper.get('input').setValue('Tenant B');
  await flushPromises();
  expect(wrapper.text()).toContain('Tenant B');
  expect(wrapper.text()).not.toContain('Tenant A');
  expect(businessRequests(requests)).toHaveLength(1);

  expect(wrapper.findComponent(sdk.ManagementExplorerColumn).exists()).toBe(true);
  await wrapper.get('button[title="收起租户"]').trigger('click');
  expect(wrapper.find('.management-explorer-column--collapsed').exists()).toBe(true);
});

it('pins ordinary users to their own tenant and pauses when that tenant is missing', async () => {
  const { http, requests } = transport((request) =>
    Response.json({ tenant: request.headers.get('X-MuYun-Tenant-Id') }),
  );
  const wrapper = mount(sdk.TenantScopedOverviewExample, {
    props: {
      http,
      overviewPath: '/business.overview',
      currentUser: { userId: 'user-a', system: false, tenantId: 'own' },
    },
  });
  await flushPromises();
  expect(wrapper.findComponent(sdk.TenantScopeExplorer).exists()).toBe(false);
  expect(requests).toHaveLength(1);
  expect(requests[0]?.headers.get('X-MuYun-Tenant-Id')).toBe('own');
  await wrapper.setProps({ currentUser: { userId: 'user-a', system: false } });
  expect(wrapper.find('pre').exists()).toBe(false);
  await flushPromises();
  expect(requests).toHaveLength(1);
});

it('starts a new read when the account changes within the same tenant', async () => {
  const pending: Array<{ request: Request; resolve: (response: Response) => void }> = [];
  const { http } = transport(
    (request) => new Promise<Response>((resolve) => pending.push({ request, resolve })),
  );
  const wrapper = mount(sdk.TenantScopedOverviewExample, {
    props: {
      http,
      overviewPath: '/business.overview',
      currentUser: { userId: 'user-a', system: false, tenantId: 'own' },
    },
  });
  await flushPromises();
  await wrapper.setProps({ currentUser: { userId: 'user-b', system: false, tenantId: 'own' } });
  expect(pending).toHaveLength(2);
  expect(pending[0]?.request.signal.aborted).toBe(true);
  expect(pending[1]?.request.headers.get('X-MuYun-Tenant-Id')).toBe('own');
  pending[1]!.resolve(Response.json({ viewer: 'user-b' }));
  await flushPromises();
  pending[0]!.resolve(Response.json({ viewer: 'user-a' }));
  await flushPromises();
  expect(wrapper.get('pre').text()).toContain('user-b');
  expect(wrapper.get('pre').text()).not.toContain('user-a');
});

it('only sends the settled identity, transport and endpoint after a host update', async () => {
  const { http, requests } = transport(() => Response.json({ viewer: 'old-account' }));
  const wrapper = mount(sdk.TenantScopedOverviewExample, {
    props: {
      http,
      overviewPath: '/business.overview',
      currentUser: { userId: 'user-a', system: false, tenantId: 'tenant-a' },
    },
  });
  await flushPromises();
  expect(wrapper.get('pre').text()).toContain('old-account');
  const request = vi.fn().mockResolvedValue({ viewer: 'new-account' });
  await wrapper.setProps({
    currentUser: { userId: 'user-b', system: false, tenantId: 'tenant-b' },
    http: { request },
    overviewPath: '/new.overview',
  });
  await flushPromises();
  expect(requests).toHaveLength(1);
  expect(request).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ path: '/new.overview', headers: { 'X-MuYun-Tenant-Id': 'tenant-b' } }),
  );
  expect(wrapper.get('pre').text()).toContain('new-account');
});

it.each([false, true])(
  'clears old facts and ignores obsolete success/error responses: %s',
  async (lateFailure) => {
    const pending: Array<{ request: Request; resolve: (response: Response) => void }> = [];
    const { http, requests } = transport(
      (request) => new Promise<Response>((resolve) => pending.push({ request, resolve })),
    );
    const wrapper = mount(sdk.TenantScopedOverviewExample, {
      props: { http, overviewPath: '/business.overview', currentUser: admin },
    });
    await flushPromises();
    const explorer = wrapper.getComponent(sdk.TenantScopeExplorer);
    explorer.vm.$emit('select', tenants[0]);
    await flushPromises();
    explorer.vm.$emit('select', tenants[1]);
    await Vue.nextTick();
    expect(pending[0]?.request.signal.aborted).toBe(true);
    expect(wrapper.find('pre').exists()).toBe(false);
    pending[1]!.resolve(Response.json({ tenant: 'tenant-b' }));
    await flushPromises();
    pending[0]!.resolve(
      lateFailure
        ? Response.json({ message: 'Old request failed' }, { status: 500 })
        : Response.json({ tenant: 'tenant-a' }),
    );
    await flushPromises();
    expect(wrapper.get('pre').text()).toContain('tenant-b');
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    explorer.vm.$emit('deselect');
    await Vue.nextTick();
    expect(wrapper.find('pre').exists()).toBe(false);
    expect(businessRequests(requests)).toHaveLength(2);

    explorer.vm.$emit('select', tenants[0]);
    await flushPromises();
    await wrapper.setProps({ currentUser: undefined });
    expect(pending[2]?.request.signal.aborted).toBe(true);
    pending[2]!.resolve(Response.json({ tenant: 'previous-account' }));
    await flushPromises();
    expect(wrapper.find('pre').exists()).toBe(false);
    expect(wrapper.findComponent(sdk.TenantScopeExplorer).exists()).toBe(false);
    expect(wrapper.text()).toContain('等待身份加载');
  },
);
