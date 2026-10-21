import { appDataChangeDispatcher } from '@/platform-admin-runtime/realtime';
import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { computed, defineComponent, h, onMounted, provide, ref } from 'vue';
import { assistantBusinessScopeKey } from '@/dynamic-page-runtime/assistantBusinessScope';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import ModuleBusinessPreview from '@/views/ModuleBusinessPreview.vue';
import {
  configureModuleContext,
  createLanguageContext,
  provideLanguageContext,
  displayModuleRuntime,
  createAssistantSurfaceRegistry,
  provideAssistantSurfaceHost,
  type HttpClient,
  type HttpRequestOptions,
} from '@muyun/web-core';
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

function runtime(
  moduleAlias: string,
  options: {
    tenantRequired?: boolean;
    title?: string;
    template?: 'LIST_DETAIL_CARD' | 'FLAT_MANAGEMENT';
    quickSearchFields?: string[];
  } = {},
) {
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
        template: options.template ?? 'LIST_DETAIL_CARD',
        quickSearchFields: options.quickSearchFields,
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
  emits: ['select', 'deselect', 'loaded'],
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
  it('keeps a flat business page ready after choosing its tenant', async () => {
    const http: HttpClient = {
      async request(options) {
        if (options.path === '/crm.customer/query') return { records: [], total: 0 } as never;
        return {
          ...runtime('crm.customer', {
            tenantRequired: !options.path.includes('iam.tenant'),
            template: 'FLAT_MANAGEMENT',
          }),
          actions: [{ actionCode: 'create', authorized: true }],
        } as never;
      },
    };
    configureModuleContext({ http });
    const wrapper = mount(ModulePageHost, {
      props: { descriptor: descriptor('crm.customer') },
      global: { stubs: hostStubs },
    });
    try {
      await flushPromises();
      const tenant = wrapper.findComponent(tenantExplorerStub);
      tenant.vm.$emit('select', { id: 'tenant-a', title: '甲租户' });
      await flushPromises();
      const session = wrapper.findComponent({ name: 'ModulePageHostRuntime' }).props('session');

      expect(session.navigatorListScopeReady).toBe(true);
      expect(wrapper.text()).not.toContain('请选择模块');
      const createButton = () =>
        wrapper
          .findAllComponents({ name: 'ModuleActionButton' })
          .find((button) => button.props('actionCode') === 'create')!;
      expect(createButton().props('disabled')).toBe(false);
      tenant.vm.$emit('deselect');
      await flushPromises();
      expect(wrapper.text()).toContain('请先选择租户');
      const create = wrapper
        .findAllComponents({ name: 'ModuleActionButton' })
        .find((button) => button.props('actionCode') === 'create')!;
      expect(create.props('disabled')).toBe(true);
      expect(create.props('title')).toBe('请先选择租户');
      tenant.vm.$emit('select', { id: 'tenant-a', title: '甲租户' });
      await flushPromises();
      expect(wrapper.text()).not.toContain('请先选择租户');
      expect(createButton().props('disabled')).toBe(false);
    } finally {
      wrapper.unmount();
    }
  });

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

  it('settles assistant tenant selection only after the replacement business surface is ready', async () => {
    const listSettlementReleases: Array<() => void> = [];
    const settleList = vi.fn(
      (signal?: AbortSignal) =>
        new Promise<void>((resolve, reject) => {
          const abort = () => reject(new DOMException('cancelled', 'AbortError'));
          signal?.addEventListener('abort', abort, { once: true });
          listSettlementReleases.push(() => {
            signal?.removeEventListener('abort', abort);
            resolve();
          });
        }),
    );
    const settlingQueryListStub = defineComponent({
      name: 'RecordQueryListPanel',
      props: ['context'],
      emits: ['query-controller-change'],
      setup(_props, { emit }) {
        onMounted(() => {
          emit('query-controller-change', {
            revision: () => 0,
            snapshot: () => ({
              rows: [],
              truncated: false,
              quickSearchFields: [],
              quickSearchEnabled: false,
              mode: 'normal',
              status: 'ready',
              pageNum: 1,
              pageSize: 20,
              total: 0,
              totalKnown: true,
            }),
            applyQuickSearch: vi.fn(),
            settle: settleList,
          });
        });
        return () => h('section');
      },
    });
    const http: HttpClient = {
      async request(options) {
        if (options.path === '/iam.tenant/navigator/reference/query') {
          return {
            records: [
              { id: 'tenant-a', title: '甲租户' },
              { id: 'tenant-b', title: '乙租户' },
            ],
            total: 2,
            pageNum: 1,
            pageSize: 20,
            pages: 1,
            totalKnown: true,
          } as never;
        }
        if (options.path === '/crm.customer/query') {
          return { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true } as never;
        }
        const moduleAlias = options.path.split('/')[2] ?? 'unknown';
        return runtime(moduleAlias, { tenantRequired: moduleAlias === 'crm.customer' }) as never;
      },
    };
    configureModuleContext({ http });
    const registry = createAssistantSurfaceRegistry();
    registry.activate('page-1');
    const Harness = defineComponent({
      setup() {
        provideAssistantSurfaceHost({
          registry,
          activePageInstanceKey: () => 'page-1',
        });
        return () => h(ModulePageHost, { descriptor: descriptor('crm.customer') });
      },
    });
    const wrapper = mount(Harness, {
      global: { stubs: { ...hostStubs, RecordQueryListPanel: settlingQueryListStub } },
    });
    try {
      await flushPromises();
      await flushPromises();
      expect(registry.snapshot()).toBeUndefined();
      wrapper.findComponent(tenantExplorerStub).vm.$emit(
        'loaded',
        [
          { id: 'tenant-a', title: '甲租户' },
          { id: 'tenant-b', title: '乙租户' },
        ],
        true,
        2,
      );
      await flushPromises();
      listSettlementReleases.shift()?.();
      await flushPromises();
      const before = registry.snapshot()!.token;
      expect(registry.snapshot()!.capabilities.map(({ code }) => code)).toContain('scope.select-tenant');

      const invocation = registry.invoke(
        { id: 'tenant-1', code: 'scope.select-tenant', input: { title: '甲租户' } },
        before,
      );
      let invocationSettled = false;
      void invocation.finally(() => {
        invocationSettled = true;
      });
      await flushPromises();
      expect(settleList).toHaveBeenCalled();
      expect(invocationSettled).toBe(false);
      listSettlementReleases.shift()?.();
      await expect(invocation).resolves.toEqual({
        value: { scope: 'tenant', selectedTitle: '甲租户', changed: true },
        contextChanged: true,
      });

      const after = registry.snapshot()!.token;
      expect(after.pageInstanceKey).toBe('page-1');
      expect(after.surfaceGeneration).not.toBe(before.surfaceGeneration);
      expect(wrapper.findComponent(tenantExplorerStub).props('selectedId')).toBe('tenant-a');

      const replacedInvocation = registry.invoke(
        { id: 'tenant-2', code: 'scope.select-tenant', input: { title: '乙租户' } },
        after,
      );
      const replacedRejection = expect(replacedInvocation).rejects.toMatchObject({
        name: 'AssistantEffectInterruptedError',
        execution: 'effect-applied',
        cause: expect.objectContaining({
          message: 'Tenant scope selection was replaced before its session became ready',
        }),
      });
      await flushPromises();
      wrapper.findComponent(tenantExplorerStub).vm.$emit('select', { id: 'tenant-a', title: '甲租户' });
      await flushPromises();
      await replacedRejection;
    } finally {
      wrapper.unmount();
    }
  });

  it('exposes the standard quick-search capability for flat management explorers', async () => {
    const flatExplorerStub = defineComponent({
      name: 'CrudRecordListExplorer',
      props: ['keyword'],
      emits: ['loaded', 'update:keyword', 'queryControllerChange'],
      setup(props, { emit }) {
        const records = [
          { id: 'tenant-a', title: '演示租户' },
          { id: 'tenant-b', title: '正式租户' },
        ];
        const snapshot = () => ({
          mode: 'normal' as const,
          status: 'ready' as const,
          quickSearchEnabled: true,
          quickSearchFields: [{ name: 'title', title: '标题', valueType: 'STRING' as const }],
          ...(props.keyword ? { appliedQuickSearch: props.keyword } : {}),
          pageNum: 1,
          pageSize: props.keyword ? 1 : 2,
          total: props.keyword ? 1 : 2,
          totalKnown: true,
          rows: (props.keyword ? records.slice(0, 1) : records).map((record) => ({
            id: record.id,
            cells: [{ fieldName: 'title', title: '标题', value: record.title }],
          })),
          truncated: false,
        });
        onMounted(() => {
          emit('loaded', records);
          emit('queryControllerChange', {
            revision: () => 0,
            snapshot,
            async applyQuickSearch(keyword: string) {
              emit('update:keyword', keyword);
              await flushPromises();
              return snapshot();
            },
            async settle() {
              await flushPromises();
              return snapshot();
            },
          });
        });
        return () => h('section', props.keyword);
      },
    });
    const http: HttpClient = {
      async request() {
        return runtime('iam.tenant', {
          template: 'FLAT_MANAGEMENT',
          quickSearchFields: ['title'],
        }) as never;
      },
    };
    configureModuleContext({ http });
    const registry = createAssistantSurfaceRegistry();
    registry.activate('page-1');
    const Harness = defineComponent({
      setup() {
        provideAssistantSurfaceHost({ registry, activePageInstanceKey: () => 'page-1' });
        return () => h(ModulePageHost, { descriptor: descriptor('iam.tenant') });
      },
    });
    const wrapper = mount(Harness, {
      global: {
        stubs: {
          ...hostStubs,
          CrudRecordListExplorer: flatExplorerStub,
          StaticManagementLayout: { template: '<section><slot name="explorer" /></section>' },
        },
      },
    });
    try {
      await flushPromises();
      await flushPromises();
      const snapshot = registry.snapshot()!;
      expect(snapshot.capabilities.map(({ code }) => code)).toContain('query.apply-quick-search');

      await expect(
        registry.invoke(
          { id: 'query-1', code: 'query.apply-quick-search', input: { keyword: '演示' } },
          snapshot.token,
        ),
      ).resolves.toEqual({
        value: expect.objectContaining({
          appliedQuickSearch: '演示',
          total: 1,
          rows: [expect.objectContaining({ id: 'tenant-a' })],
        }),
        contextChanged: true,
      });
    } finally {
      wrapper.unmount();
    }
  });

  it('cancels assistant tenant settlement while the replacement business session is pending', async () => {
    let resolveTenantSession!: () => void;
    const http: HttpClient = {
      async request(options) {
        if (options.path === '/iam.tenant/navigator/reference/query') {
          return {
            records: [
              { id: 'tenant-a', title: '甲租户' },
              { id: 'tenant-b', title: '乙租户' },
            ],
            total: 2,
            pageNum: 1,
            pageSize: 20,
            pages: 1,
            totalKnown: true,
          } as never;
        }
        if (
          options.path === '/platform.module/crm.customer/context' &&
          tenantHeader(options) === 'tenant-a'
        ) {
          await new Promise<void>((resolve) => {
            resolveTenantSession = resolve;
          });
        }
        const moduleAlias = options.path.split('/')[2] ?? 'unknown';
        return runtime(moduleAlias, { tenantRequired: moduleAlias === 'crm.customer' }) as never;
      },
    };
    configureModuleContext({ http });
    const registry = createAssistantSurfaceRegistry();
    registry.activate('page-1');
    const Harness = defineComponent({
      setup() {
        provideAssistantSurfaceHost({ registry, activePageInstanceKey: () => 'page-1' });
        return () => h(ModulePageHost, { descriptor: descriptor('crm.customer') });
      },
    });
    const wrapper = mount(Harness, { global: { stubs: hostStubs } });
    try {
      await flushPromises();
      await flushPromises();
      wrapper.findComponent(tenantExplorerStub).vm.$emit(
        'loaded',
        [
          { id: 'tenant-a', title: '甲租户' },
          { id: 'tenant-b', title: '乙租户' },
        ],
        true,
        2,
      );
      await flushPromises();
      const abort = new AbortController();
      const invocation = registry.invoke(
        { id: 'tenant-1', code: 'scope.select-tenant', input: { title: '甲租户' } },
        registry.snapshot()!.token,
        abort.signal,
      );
      const rejection = expect(invocation).rejects.toMatchObject({
        name: 'AssistantEffectInterruptedError',
        execution: 'effect-applied',
        cause: expect.objectContaining({ name: 'AbortError' }),
      });
      await flushPromises();
      expect(resolveTenantSession).toBeTypeOf('function');
      abort.abort();

      await rejection;
      resolveTenantSession();
      await flushPromises();
    } finally {
      wrapper.unmount();
    }
  });

  it.each(['page-button', 'workbench-refresh'])(
    'keeps tenant scope after a failed switch and retries via %s with a frozen client',
    async (retry) => {
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
        if (retry === 'workbench-refresh') publicHost.refreshList();
        else await wrapper.get('.module-business-session-state button').trigger('click');
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
    },
  );

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

it('keeps the inherited business scope when a record-only reference host takes over the assistant', async () => {
  const http: HttpClient = {
    async request(options) {
      if (options.path.includes('/view/')) return { id: 'target-1', title: '目标' } as never;
      if (options.path.includes('/actions/')) return { actions: [] } as never;
      return runtime('crm.customer', { tenantRequired: true }) as never;
    },
  };
  configureModuleContext({ http });
  const registry = createAssistantSurfaceRegistry();
  registry.activate('page-1');
  const Harness = defineComponent({
    setup() {
      provideAssistantSurfaceHost({ registry, activePageInstanceKey: () => 'page-1' });
      provide(
        assistantBusinessScopeKey,
        computed(() => 'tenant-a'),
      );
      return () =>
        h(ModulePageHost, { descriptor: descriptor('crm.customer'), recordOnly: { recordId: 'target-1' } });
    },
  });
  const wrapper = mount(Harness, { global: { stubs: hostStubs } });
  try {
    await flushPromises();
    await flushPromises();
    expect(JSON.parse(registry.snapshot()!.token.executionScopeKey!)[1]).toBe('tenant-a');
  } finally {
    wrapper.unmount();
  }
});

it('reloads published configuration when idle and defers replacement while a form is open', async () => {
  let title = 'Before';
  configureModuleContext({ http: { request: async () => runtime('crm.customer', { title }) as never } });
  const wrapper = mount(ModulePageHost, {
    props: { descriptor: descriptor('crm.customer') },
    global: { stubs: hostStubs },
  });
  const change = (id: string, moduleAlias = 'crm.customer') =>
    appDataChangeDispatcher.dispatch({
      changeSetId: id,
      changes: [{ type: 'module-page-configuration-changed', moduleAlias }],
    });
  try {
    await flushPromises();
    const uid = controlSessionUid(wrapper);
    title = 'Published';
    await change('publication-1', 'crm.other');
    await flushPromises();
    expect(runtimeContextOf(wrapper).runtime.snapshot()?.title).toBe('Before');
    await change('publication-2');
    await flushPromises();
    expect(runtimeContextOf(wrapper).runtime.snapshot()?.title).toBe('Published');
    expect(controlSessionUid(wrapper)).toBe(uid);
    const business = wrapper.findComponent({ name: 'ModulePageBusinessSession' });
    business.vm.$emit('interaction-state-change', { editing: true, busy: false, dirty: true });
    title = 'Next';
    await change('publication-3');
    await flushPromises();
    expect(runtimeContextOf(wrapper).runtime.snapshot()?.title).toBe('Published');
    expect(wrapper.text()).toContain('当前填写内容已保留');
    business.vm.$emit('interaction-state-change', { editing: false, busy: false, dirty: false });
    await flushPromises();
    expect(runtimeContextOf(wrapper).runtime.snapshot()?.title).toBe('Next');
    expect(wrapper.text()).not.toContain('当前填写内容已保留');
  } finally {
    wrapper.unmount();
  }
});

it('registers an already mounted business page when its workbench identity becomes available', async () => {
  configureModuleContext({
    http: {
      async request(options) {
        if (options.path === '/crm.customer/query')
          return { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true } as never;
        return runtime('crm.customer') as never;
      },
    },
  });
  const registry = createAssistantSurfaceRegistry();
  const pageKey = ref<string>();
  const Harness = defineComponent({
    setup() {
      provideAssistantSurfaceHost({ registry, activePageInstanceKey: () => pageKey.value });
      return () => h(ModulePageHost, { descriptor: descriptor('crm.customer') });
    },
  });
  const wrapper = mount(Harness, { global: { stubs: hostStubs } });
  try {
    await flushPromises();
    await flushPromises();
    const sessionUid = controlSessionUid(wrapper);
    expect(registry.snapshot()).toBeUndefined();
    pageKey.value = 'restored-page';
    registry.activate(pageKey.value);
    await flushPromises();
    await flushPromises();
    expect(registry.snapshot()?.context.facts.moduleAlias).toBe('crm.customer');
    expect(registry.snapshot()?.token.pageInstanceKey).toBe('restored-page');
    expect(controlSessionUid(wrapper)).toBe(sessionUid);
  } finally {
    wrapper.unmount();
    expect(registry.snapshot()).toBeUndefined();
  }
});

it('binds a normal consumer page and its form descriptor to the provider language rather than the global default', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const moduleAlias = 'demo.language_owner';
  const path = '/uiDescriptor/page/detail/editor/fields/0/label';
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/context'))
      return {
        ...runtime(moduleAlias),
        uiDescriptor: {
          ...runtime(moduleAlias).uiDescriptor,
          page: {
            ...runtime(moduleAlias).uiDescriptor.page,
            detail: {
              editor: {
                viewCode: 'form',
                viewKind: 'FORM',
                fields: [{ fieldRef: { fieldName: 'title' }, label: 'Original field', valueType: 'STRING' }],
              },
            },
          },
        },
        textBindings: {
          [path]: {
            source: {
              sourceKind: 'CODE',
              sourceId: 'owner.title',
              sourceVersion: 'v1',
              originalText: '原始字段',
            },
            languages: {
              en: {
                text: 'English field',
                requestedTag: 'en',
                resourceTag: 'en',
                missing: false,
                fallback: false,
              },
            },
          },
        },
      };
    return { records: [], total: 0 };
  });
  configureModuleContext({ http: { request } as HttpClient });
  const Harness = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(ModulePageHost, { descriptor: descriptor(moduleAlias) });
    },
  });
  const wrapper = mount(Harness, { global: { stubs: hostStubs } });
  try {
    await flushPromises();
    const context = runtimeContextOf(wrapper);
    const field = () => displayModuleRuntime(context.runtime)?.uiDescriptor?.page?.detail?.editor?.fields[0];
    expect(field()?.label).toBe('English field');
    const original = context.runtime.snapshot()?.uiDescriptor;
    expect(original?.page?.detail?.editor?.fields[0]?.label).toBe('原始字段');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(field()?.label).toBe('原始字段');
    expect(runtimeContextOf(wrapper)).toBe(context);
    expect(context.runtime.snapshot()?.uiDescriptor).toBe(original);
    language.setLanguage('en');
    await flushPromises();
    expect(field()?.label).toBe('English field');
  } finally {
    wrapper.unmount();
  }
});

it('refreshes a declared enhancement drawer heading without reactivating the page or reopening the drawer', async () => {
  const { configureModulePageEnhancementContributions } =
    await import('@/dynamic-page-runtime/modulePageEnhancements');
  const language = createLanguageContext({ languageTag: 'en' });
  const moduleAlias = 'demo.entry_language';
  const activate = vi.fn();
  const Body = defineComponent({ name: 'EntryTestBody', props: ['context'], template: '<p>业务原值</p>' });
  configureModulePageEnhancementContributions('entry-language-test', [
    {
      id: 'entry-language-test',
      target: { moduleAlias },
      activate,
      list: {
        actions: [
          {
            key: 'entry-open',
            title: '设置账号',
            titleKey: 'platform.entry.set-up-account',
            run({ openDrawer }) {
              openDrawer({
                title: '设置账号',
                titleKey: 'platform.entry.set-up-account',
                dismissal: 'dismissible',
                component: Body,
              });
            },
          },
        ],
      },
    },
  ]);
  const request = vi.fn(async (options: HttpRequestOptions) =>
    options.path.endsWith('/context') ? runtime(moduleAlias) : { records: [], total: 0 },
  );
  configureModuleContext({ http: { request } as HttpClient });
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(ModulePageHost, { descriptor: descriptor(moduleAlias) });
      },
    }),
    {
      global: {
        stubs: {
          ...hostStubs,
          RecordDetailDrawer: {
            name: 'RecordDetailDrawer',
            props: ['title', 'open', 'dismissal'],
            template: '<aside :aria-label="title"><slot /></aside>',
          },
        },
      },
    },
  );
  try {
    await flushPromises();
    const session = wrapper.findComponent({ name: 'ModulePageHostRuntime' }).props('session');
    session.handleListAction({ key: 'entry-open' });
    await flushPromises();
    const drawer = wrapper.findAllComponents({ name: 'RecordDetailDrawer' }).at(-1)!;
    expect(drawer.props('title')).toBe('Set up account');
    expect(drawer.props('dismissal')).toBe('dismissible');
    const body = wrapper.findComponent(Body).vm;
    const uid = controlSessionUid(wrapper);
    const count = request.mock.calls.length;
    const activations = activate.mock.calls.length;
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(drawer.props('title')).toBe('设置账号');
    language.setLanguage('en');
    await flushPromises();
    expect(drawer.props('title')).toBe('Set up account');
    expect(wrapper.findComponent(Body).vm).toBe(body);
    expect(controlSessionUid(wrapper)).toBe(uid);
    expect(request.mock.calls.length).toBe(count);
    expect(activate).toHaveBeenCalledTimes(activations);
    const drawerContext = wrapper.findComponent(Body).props('context');
    const guard = vi.fn(() => false);
    drawerContext.setBeforeClose(guard);
    drawerContext.setCloseBlocked(true);
    drawer.vm.$emit('close');
    await flushPromises();
    expect(guard).not.toHaveBeenCalled();
    expect(drawer.props('open')).toBe(true);
    drawerContext.setCloseBlocked(false);
    drawer.vm.$emit('close');
    await flushPromises();
    expect(guard).toHaveBeenCalledOnce();
    expect(drawer.props('open')).toBe(true);
    expect(wrapper.findComponent(Body).vm).toBe(body);
    drawerContext.setBeforeClose(() => true);
    drawer.vm.$emit('close');
    await flushPromises();
    expect(drawer.props('open')).toBe(false);
  } finally {
    wrapper.unmount();
    configureModulePageEnhancementContributions('entry-language-test', []);
  }
});

it('renders and dispatches enhancement actions in an empty flat management explorer', async () => {
  const { configureModulePageEnhancementContributions } =
    await import('@/dynamic-page-runtime/modulePageEnhancements');
  const moduleAlias = 'demo.flat_action';
  const run = vi.fn();
  configureModulePageEnhancementContributions('flat-action-test', [
    {
      id: 'flat-action-test',
      target: { moduleAlias },
      list: { actions: [{ key: 'configure', title: '编码工作台', run }] },
    },
  ]);
  configureModuleContext({
    http: {
      async request(options) {
        return (
          options.path.endsWith('/context')
            ? runtime(moduleAlias, { template: 'FLAT_MANAGEMENT' })
            : { records: [], total: 0 }
        ) as never;
      },
    },
  });
  const wrapper = mount(ModulePageHost, {
    props: { descriptor: descriptor(moduleAlias) },
    global: {
      stubs: {
        ...hostStubs,
        StaticManagementLayout: {
          template: '<section><slot name="explorer-actions" /><slot name="explorer" /></section>',
        },
        CrudRecordListExplorer: true,
        RecordDataExchangeSurface: true,
      },
    },
  });
  try {
    await flushPromises();
    const action = wrapper.findAll('button').find((item) => item.text().replace(/\s+/g, '') === '编码工作台');
    expect(action).toBeDefined();
    await action!.trigger('click');
    await flushPromises();
    expect(run).toHaveBeenCalledOnce();
  } finally {
    wrapper.unmount();
    configureModulePageEnhancementContributions('flat-action-test', []);
  }
});

it('refreshes extension actions on an already open module page without reloading its business session', async () => {
  const { configureModulePageEnhancementContributions } =
    await import('@/dynamic-page-runtime/modulePageEnhancements');
  const moduleAlias = 'demo.live_extensions';
  const owner = 'live-extension-test';
  const request = vi.fn(async (options: HttpRequestOptions) =>
    options.path.endsWith('/context')
      ? runtime(moduleAlias, { template: 'FLAT_MANAGEMENT' })
      : { records: [], total: 0 },
  );
  configureModuleContext({ http: { request } as HttpClient });
  const wrapper = mount(ModulePageHost, {
    props: { descriptor: descriptor(moduleAlias) },
    global: {
      stubs: {
        ...hostStubs,
        StaticManagementLayout: {
          template: '<section><slot name="explorer-actions" /><slot name="explorer" /></section>',
        },
        CrudRecordListExplorer: true,
        RecordDataExchangeSurface: true,
      },
    },
  });
  try {
    await flushPromises();
    const uid = controlSessionUid(wrapper);
    const calls = request.mock.calls.length;
    const run = vi.fn();
    configureModulePageEnhancementContributions(owner, [
      {
        id: owner,
        target: { moduleAlias },
        list: { actions: [{ key: 'configure', title: '审批流程', run }] },
      },
    ]);
    await flushPromises();
    const action = wrapper.findAll('button').find((item) => item.text().replace(/\s+/g, '') === '审批流程');
    expect(action).toBeDefined();
    await action!.trigger('click');
    expect(run).toHaveBeenCalledOnce();
    expect(controlSessionUid(wrapper)).toBe(uid);
    expect(request.mock.calls.length).toBe(calls);
  } finally {
    wrapper.unmount();
    configureModulePageEnhancementContributions(owner, []);
  }
});
