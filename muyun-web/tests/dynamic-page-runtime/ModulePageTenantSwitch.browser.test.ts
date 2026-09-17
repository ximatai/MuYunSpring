import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, onMounted, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import { configureModuleContext, createHttpClient, type ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import '@/styles.css';

it('keeps the tenant explorer and its search mounted while a new tenant business request is pending', async () => {
  const originalFetch = globalThis.fetch;
  let tenantQueries = 0;
  let finishFirst: (() => void) | undefined;
  let finishSecond: (() => void) | undefined;
  let finishSecondDiscovery: (() => void) | undefined;
  const businessRequests: string[] = [];
  const tenants = [
    { id: 'tenant-a', title: '测试租户甲' },
    { id: 'tenant-b', title: '测试租户乙' },
  ];
  const runtime = {
    moduleAlias: 'crm.customer',
    tenantRequired: true,
    abilities: ['crud'],
    capabilities: [],
    actions: [],
    uiDescriptor: {
      schemaVersion: '1',
      moduleAlias: 'crm.customer',
      page: {
        template: 'LIST_DETAIL_CARD',
        navigator: {
          levels: [
            {
              key: 'organization',
              kind: 'MICRO_LIST',
              sourceModuleAlias: 'iam.organization',
              title: '机构树',
            },
          ],
          contextBindings: [],
        },
        list: { fields: { viewCode: 'list', viewKind: 'LIST', fields: [] } },
        detail: { editor: { viewCode: 'form', viewKind: 'FORM', fields: [] } },
      },
    },
  };
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = new Request(input, init);
    if (request.url.endsWith('/platform.module/crm.customer/context')) {
      if (request.headers.get('X-MuYun-Tenant-Id') === 'tenant-b') {
        await new Promise<void>((resolve) => {
          finishSecondDiscovery = resolve;
        });
      }
      return Response.json(runtime);
    }
    if (request.url.endsWith('/platform.module/iam.tenant/reference-context'))
      return Response.json({ moduleAlias: 'iam.tenant', abilities: ['crud'], capabilities: [], actions: [] });
    if (request.url.endsWith('/platform.module/iam.organization/reference-context'))
      return Response.json({
        moduleAlias: 'iam.organization',
        abilities: ['crud'],
        capabilities: [],
        actions: [],
      });
    if (request.url.endsWith('/iam.organization/navigator/reference/query'))
      return Response.json({ records: [], total: 0, totalKnown: true });
    if (request.url.endsWith('/iam.tenant/navigator/reference/query')) {
      tenantQueries += 1;
      return Response.json({ records: tenants, total: tenants.length, totalKnown: true });
    }
    if (request.url.endsWith('/crm.customer/query')) {
      const tenant = request.headers.get('X-MuYun-Tenant-Id') ?? '';
      businessRequests.push(tenant);
      if (tenant === 'tenant-a')
        await new Promise<void>((resolve) => {
          finishFirst = resolve;
        });
      if (tenant === 'tenant-b')
        await new Promise<void>((resolve) => {
          finishSecond = resolve;
        });
      return Response.json({ records: [{ id: tenant, title: tenant }], total: 1, totalKnown: true });
    }
    return originalFetch(input, init);
  });
  configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
  // Business data is deliberately slow; use the real Host, provider, workspace,
  // explorer and search so no stub can hide destruction of the visible shell.
  const businessPanel = defineComponent({
    name: 'RecordQueryListPanel',
    props: ['context', 'ready'],
    setup(props) {
      const title = ref('等待业务数据');
      onMounted(async () => {
        if (!props.ready) return;
        const result = await (props.context as ModuleContext<QueryListRecord>).crud.query();
        title.value = String(result.records[0]?.title ?? '');
      });
      return () => h('div', { class: 'tenant-switch-business-panel' }, title.value);
    },
  });
  const container = document.createElement('div');
  container.style.cssText = 'width:1400px;height:700px;position:relative';
  document.body.appendChild(container);
  const wrapper = mount(ModulePageHost, {
    attachTo: container,
    props: {
      descriptor: {
        pageType: 'dynamic-module',
        openMode: 'dynamic-runner',
        hostType: 'module-page-host',
        tabPolicy: { identity: 'by-menu' },
        target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
      },
    },
    global: { stubs: { RecordQueryListPanel: businessPanel } },
  });
  try {
    await expect.element(page.getByText('测试租户甲', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'search' }).first().click();
    await page.getByPlaceholder('搜索租户').fill('测试');
    await expect.poll(() => tenantQueries).toBe(2);
    const search = wrapper.get('.tenant-scope-explorer input').element;
    const explorer = wrapper.get('.tenant-scope-explorer').element;
    const workspace = explorer.closest('.management-workspace');
    expect(workspace).not.toBeNull();
    await page.getByRole('button', { name: '收起机构树' }).click();
    const organizationColumn = wrapper
      .findAll('.management-explorer-column')
      .find((column) => column.text().includes('机构树'))!.element;
    await expect
      .poll(() => organizationColumn.classList.contains('management-explorer-column--collapsed'))
      .toBe(true);
    await page.getByText('测试租户甲', { exact: true }).click();
    await expect.poll(() => businessRequests).toContain('tenant-a');
    expect(wrapper.get('.tenant-scope-explorer').element).toBe(explorer);
    const tenantExplorerPosition = (() => {
      const { x, y } = explorer.getBoundingClientRect();
      return { x, y };
    })();
    await page.getByText('测试租户乙', { exact: true }).click();
    await expect.poll(() => finishSecondDiscovery).toBeTypeOf('function');
    expect(wrapper.get('.tenant-scope-explorer').element).toBe(explorer);
    expect(organizationColumn.isConnected).toBe(true);
    expect(wrapper.text()).not.toContain('加载页面入口');
    expect(wrapper.text()).toContain('加载租户数据');
    expect(
      (() => {
        const { x, y } = explorer.getBoundingClientRect();
        return { x, y };
      })(),
    ).toEqual(tenantExplorerPosition);
    finishSecondDiscovery?.();
    await expect.poll(() => businessRequests).toContain('tenant-b');

    expect(explorer.isConnected).toBe(true);
    expect(wrapper.get('.tenant-scope-explorer').element).toBe(explorer);
    expect(wrapper.get('.tenant-scope-explorer input').element).toBe(search);
    expect((search as HTMLInputElement).value).toBe('测试');
    expect(explorer.closest('.management-workspace')).toBe(workspace);
    expect(organizationColumn.isConnected).toBe(true);
    expect(organizationColumn.classList.contains('management-explorer-column--collapsed')).toBe(true);
    await expect.element(page.getByRole('button', { name: '展开机构树' })).toBeVisible();
    expect(wrapper.text()).not.toContain('加载页面入口');
    expect(tenantQueries).toBe(2);
    finishSecond?.();
    await expect.poll(() => wrapper.find('.tenant-switch-business-panel').text()).toBe('tenant-b');
    finishFirst?.();
    await flushPromises();
    await expect.poll(() => wrapper.find('.tenant-switch-business-panel').text()).toBe('tenant-b');
    expect(wrapper.find('.tenant-switch-business-panel').text()).not.toContain('tenant-a');
  } finally {
    finishFirst?.();
    finishSecond?.();
    finishSecondDiscovery?.();
    wrapper.unmount();
    container.remove();
    vi.unstubAllGlobals();
  }
});
