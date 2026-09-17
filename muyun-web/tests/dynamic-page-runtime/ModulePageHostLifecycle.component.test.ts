import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import ModuleBusinessPreview from '@/views/ModuleBusinessPreview.vue';
import { configureModuleContext, type HttpClient, type HttpRequestOptions } from '@muyun/web-core';
import type { MenuPageMode, StandardModulePageDescriptor } from '@muyun/web-contracts';

function descriptor(
  moduleAlias: string,
  options: { menuId?: string; pageMode?: MenuPageMode; params?: StandardModulePageDescriptor['params'] } = {},
): StandardModulePageDescriptor {
  return {
    pageType: 'dynamic-module',
    openMode: 'dynamic-runner',
    hostType: 'module-page-host',
    tabPolicy: { identity: 'by-menu' },
    menuId: options.menuId,
    target: { moduleAlias, pageMode: options.pageMode ?? 'LIST' },
    params: options.params,
  };
}

function runtime(moduleAlias: string, options: { tenantRequired?: boolean; title?: string } = {}) {
  return {
    moduleAlias,
    title: options.title,
    tenantRequired: options.tenantRequired ?? false,
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

function pageBootstrap(menuId: string, moduleAlias = 'crm.customer') {
  return {
    entry: { menuId, moduleAlias, pageMode: 'LIST' },
    clientType: 'WEB',
    mainEntityAlias: 'customer',
    resolvedConfig: { uiFields: [], queryItems: [], actionBlocks: [] },
  };
}

const tenantExplorerStub = {
  name: 'TenantScopeExplorer',
  props: ['selectedId'],
  emits: ['select'],
  template: '<section />',
};
const queryListStub = {
  name: 'RecordQueryListPanel',
  props: ['context'],
  template: '<section />',
};
const hostStubs = {
  TenantScopeExplorer: tenantExplorerStub,
  RecordQueryListPanel: queryListStub,
  ManagementWorkspace: { name: 'ManagementWorkspace', template: '<section><slot /></section>' },
  ManagementExplorerColumn: { name: 'ManagementExplorerColumn', template: '<aside><slot /></aside>' },
  RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
};

function tenantHeader(options: HttpRequestOptions) {
  return options.headers?.['X-MuYun-Tenant-Id'];
}

function runtimeContextOf(wrapper: ReturnType<typeof mount>) {
  return wrapper.findComponent({ name: 'ModulePageHostRuntime' }).props('session').context;
}

function controlSessionUid(wrapper: ReturnType<typeof mount>) {
  return wrapper.findComponent({ name: 'ModulePageHostSession' }).vm.$.uid;
}

describe('ModulePageHost lifecycle boundaries', () => {
  it('recreates the control session for entry identity changes but retains it for ordinary params', async () => {
    const requests: Array<{ path: string; menuId?: string }> = [];
    const http: HttpClient = {
      async request(options) {
        requests.push({ path: options.path, menuId: options.headers?.['X-MuYun-Menu-Id'] });
        if (options.path.startsWith('/platform.menu/')) {
          const menuId = options.path.split('/')[2] ?? 'unknown';
          return pageBootstrap(menuId, menuId === 'order-menu' ? 'crm.order' : 'crm.customer') as never;
        }
        const moduleAlias = options.path.split('/')[2] ?? 'unknown';
        const menuId = options.headers?.['X-MuYun-Menu-Id'];
        return runtime(moduleAlias, {
          tenantRequired: menuId !== 'customer-menu-b',
          title: `${moduleAlias}:${menuId ?? 'control'}`,
        }) as never;
      },
    };
    configureModuleContext({ http });
    const wrapper = mount(ModulePageHost, {
      props: { descriptor: descriptor('crm.customer', { menuId: 'customer-menu-a' }) },
      global: { stubs: hostStubs },
    });
    try {
      await flushPromises();
      await flushPromises();
      const initialSession = controlSessionUid(wrapper);
      expect(wrapper.findComponent(tenantExplorerStub).exists()).toBe(true);
      expect(runtimeContextOf(wrapper).moduleAlias).toBe('crm.customer');

      await wrapper.setProps({
        descriptor: descriptor('crm.customer', { menuId: 'customer-menu-a', params: { q: 'x' } }),
      });
      await flushPromises();
      expect(controlSessionUid(wrapper)).toBe(initialSession);

      await wrapper.setProps({ descriptor: descriptor('crm.customer', { menuId: 'customer-menu-b' }) });
      await flushPromises();
      const menuSession = controlSessionUid(wrapper);
      expect(menuSession).not.toBe(initialSession);
      expect(wrapper.findComponent(tenantExplorerStub).exists()).toBe(false);
      expect(runtimeContextOf(wrapper).runtime.snapshot()?.title).toBe('crm.customer:customer-menu-b');

      await wrapper.setProps({
        descriptor: descriptor('crm.customer', { menuId: 'customer-menu-b', pageMode: 'FORM' }),
      });
      await flushPromises();
      const pageModeSession = controlSessionUid(wrapper);
      expect(pageModeSession).not.toBe(menuSession);
      await wrapper.setProps({ descriptor: descriptor('crm.order', { menuId: 'order-menu' }) });
      await flushPromises();
      expect(controlSessionUid(wrapper)).not.toBe(pageModeSession);
      expect(runtimeContextOf(wrapper).moduleAlias).toBe('crm.order');
      expect(requests.some((request) => request.menuId === 'customer-menu-b')).toBe(true);
    } finally {
      wrapper.unmount();
    }
  });

  it('recreates the control session when the public record-only target changes', async () => {
    const http: HttpClient = {
      async request(options) {
        if (options.path.startsWith('/crm.customer/view/')) {
          return { id: options.path.split('/').at(-1), title: '客户' } as never;
        }
        return runtime('crm.customer') as never;
      },
    };
    configureModuleContext({ http });
    const wrapper = mount(ModulePageHost, {
      props: {
        descriptor: descriptor('crm.customer'),
        recordOnly: { recordId: 'customer-a', renderMode: 'inline', scope: 'tab' },
      },
      global: {
        stubs: {
          ...hostStubs,
          RecordModeDrawer: { template: '<section><slot /></section>' },
          RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
        },
      },
    });
    try {
      await flushPromises();
      const firstSession = controlSessionUid(wrapper);
      expect(
        (
          wrapper.findComponent({ name: 'ModulePageHostRuntime' }).props('session') as {
            props: { recordOnly?: unknown };
          }
        ).props.recordOnly,
      ).toMatchObject({ recordId: 'customer-a' });

      await wrapper.setProps({ recordOnly: { recordId: 'customer-b', renderMode: 'inline', scope: 'tab' } });
      await flushPromises();
      expect(controlSessionUid(wrapper)).not.toBe(firstSession);
      expect(
        (
          wrapper.findComponent({ name: 'ModulePageHostRuntime' }).props('session') as {
            props: { recordOnly?: unknown };
          }
        ).props.recordOnly,
      ).toMatchObject({ recordId: 'customer-b' });
    } finally {
      wrapper.unmount();
    }
  });

  it('keeps tenant selection available when the initial tenant-required business session fails', async () => {
    let unscopedRuntimeRequests = 0;
    const http: HttpClient = {
      async request(options) {
        if (options.path === '/platform.module/crm.customer/context') {
          const selectedTenant = tenantHeader(options);
          if (!selectedTenant && unscopedRuntimeRequests++ > 0)
            throw new Error('initial business runtime unavailable');
          return runtime('crm.customer', { tenantRequired: true }) as never;
        }
        if (options.path === '/crm.customer/query')
          return { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true } as never;
        return runtime('iam.tenant') as never;
      },
    };
    configureModuleContext({ http });
    const wrapper = mount(ModulePageHost, {
      props: { descriptor: descriptor('crm.customer') },
      global: { stubs: hostStubs },
    });
    try {
      await flushPromises();
      await flushPromises();
      expect(wrapper.text()).toContain('initial business runtime unavailable');
      const tenant = wrapper.findComponent(tenantExplorerStub);
      expect(tenant.exists()).toBe(true);

      tenant.vm.$emit('select', { id: 'tenant-a', title: '甲租户' });
      await flushPromises();
      expect(wrapper.findComponent(queryListStub).exists()).toBe(true);
      expect(tenant.props('selectedId')).toBe('tenant-a');
    } finally {
      wrapper.unmount();
    }
  });

  it('keeps the tenant controller and stable layout after a failed switch, then retries with a frozen new tenant client', async () => {
    const requests: Array<{ path: string; tenantId?: string }> = [];
    let tenantBFailures = 0;
    const http: HttpClient = {
      async request(options) {
        const selectedTenant = tenantHeader(options);
        requests.push({ path: options.path, tenantId: selectedTenant });
        if (
          options.path === '/platform.module/crm.customer/context' &&
          selectedTenant === 'tenant-b' &&
          tenantBFailures++ === 0
        )
          throw new Error('tenant runtime unavailable');
        if (options.path === '/crm.customer/query')
          return { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true } as never;
        return runtime('crm.customer', { tenantRequired: !options.path.includes('iam.tenant') }) as never;
      },
    };
    configureModuleContext({ http });
    const wrapper = mount(ModulePageHost, {
      props: { descriptor: descriptor('crm.customer') },
      global: { stubs: hostStubs },
    });
    try {
      await flushPromises();
      const workspace = wrapper.findComponent({ name: 'ManagementWorkspace' }).element;
      const tenant = () => wrapper.findComponent(tenantExplorerStub);
      tenant().vm.$emit('select', { id: 'tenant-a', title: '甲租户' });
      await flushPromises();
      expect(tenant().props('selectedId')).toBe('tenant-a');

      tenant().vm.$emit('select', { id: 'tenant-b', title: '乙租户' });
      await wrapper.vm.$nextTick();
      expect(wrapper.find('.module-business-session-state').exists()).toBe(true);
      expect(wrapper.findComponent(queryListStub).exists()).toBe(false);
      const publicHost = wrapper.vm as unknown as { refreshList(): void };
      const requestsBeforePendingRefresh = requests.length;
      publicHost.refreshList();
      expect(requests).toHaveLength(requestsBeforePendingRefresh);
      await flushPromises();
      expect(wrapper.findAll('.module-business-session-state')).toHaveLength(1);
      expect(wrapper.text()).toContain('tenant runtime unavailable');
      expect(tenant().exists()).toBe(true);
      expect(tenant().props('selectedId')).toBe('tenant-b');
      expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).element).toBe(workspace);
      const requestsBeforeFailedRefresh = requests.length;
      publicHost.refreshList();
      await flushPromises();
      expect(requests).toHaveLength(requestsBeforeFailedRefresh);

      await wrapper.get('.module-business-session-state button').trigger('click');
      await flushPromises();
      const retriedList = wrapper.findComponent(queryListStub);
      await retriedList.props('context').crud.query();
      expect(tenant().props('selectedId')).toBe('tenant-b');
      expect(requests.at(-1)).toMatchObject({ path: '/crm.customer/query', tenantId: 'tenant-b' });

      tenant().vm.$emit('select', { id: 'tenant-a', title: '甲租户' });
      await flushPromises();
      await wrapper.findComponent(queryListStub).props('context').crud.query();
      expect(requests.at(-1)).toMatchObject({ path: '/crm.customer/query', tenantId: 'tenant-a' });
    } finally {
      wrapper.unmount();
    }
  });

  it('does not let a disposed delayed tenant session overwrite the replacement tenant policy', async () => {
    let resolveTenantA: (() => void) | undefined;
    let policyChanged = false;
    const http: HttpClient = {
      async request(options) {
        if (options.path === '/platform.module/crm.customer/context') {
          const selectedTenant = tenantHeader(options);
          if (selectedTenant === 'tenant-a') {
            await new Promise<void>((resolve) => {
              resolveTenantA = resolve;
            });
            return runtime('crm.customer', { tenantRequired: true }) as never;
          }
          if (selectedTenant === 'tenant-b') {
            policyChanged = true;
            return runtime('crm.customer', { tenantRequired: false }) as never;
          }
          return runtime('crm.customer', { tenantRequired: !policyChanged }) as never;
        }
        return runtime('iam.tenant') as never;
      },
    };
    configureModuleContext({ http });
    const wrapper = mount(ModulePageHost, {
      props: { descriptor: descriptor('crm.customer') },
      global: { stubs: hostStubs },
    });
    try {
      await flushPromises();
      const tenant = () => wrapper.findComponent(tenantExplorerStub);
      expect(tenant().exists()).toBe(true);

      tenant().vm.$emit('select', { id: 'tenant-a', title: '甲租户' });
      await flushPromises();
      expect(resolveTenantA).toBeTypeOf('function');
      tenant().vm.$emit('select', { id: 'tenant-b', title: '乙租户' });
      await flushPromises();
      expect(tenant().exists()).toBe(false);

      resolveTenantA?.();
      await flushPromises();
      expect(tenant().exists()).toBe(false);
    } finally {
      wrapper.unmount();
    }
  });

  it('reloads a published preview into a new business definition without losing its selected tenant', async () => {
    let contextVersion = 0;
    const http: HttpClient = {
      async request(options) {
        if (options.path === '/platform.module/crm.customer/context') {
          contextVersion += 1;
          return runtime('crm.customer', {
            tenantRequired: true,
            title: `definition-${contextVersion}`,
          }) as never;
        }
        if (options.path === '/crm.customer/query')
          return { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true } as never;
        return runtime('crm.customer', { tenantRequired: false }) as never;
      },
    };
    configureModuleContext({ http });
    const wrapper = mount(ModuleBusinessPreview, {
      props: { moduleAlias: 'crm.customer' },
      global: { stubs: hostStubs },
    });
    try {
      await flushPromises();
      const tenant = () => wrapper.findComponent(tenantExplorerStub);
      tenant().vm.$emit('select', { id: 'tenant-a', title: '甲租户' });
      await flushPromises();
      const initialContext = wrapper.findComponent(queryListStub).props('context');
      const initialDefinition = initialContext.runtime.snapshot()?.title;

      const reload = wrapper.findAll('button').find((button) => button.text().includes('重新加载已发布页面'));
      expect(reload).toBeDefined();
      await reload!.trigger('click');
      await flushPromises();

      const reloadedContext = wrapper.findComponent(queryListStub).props('context');
      expect(tenant().props('selectedId')).toBe('tenant-a');
      expect(reloadedContext).not.toBe(initialContext);
      expect(reloadedContext.runtime.snapshot()?.title).not.toBe(initialDefinition);
    } finally {
      wrapper.unmount();
    }
  });
});
