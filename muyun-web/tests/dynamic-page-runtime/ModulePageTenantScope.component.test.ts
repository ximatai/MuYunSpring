import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it } from 'vitest';
import { defineComponent, h, ref } from 'vue';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
import { configureModuleContext, createHttpClient } from '@muyun/web-core';
import type { CurrentUser } from '@muyun/web-contracts';

const descriptor = {
  pageType: 'dynamic-module' as const,
  openMode: 'dynamic-runner' as const,
  hostType: 'module-page-host' as const,
  tabPolicy: { identity: 'by-menu' as const },
  target: { moduleAlias: 'crm.customer', pageMode: 'LIST' as const },
};

const tenantExplorerStub = defineComponent({
  name: 'TenantScopeExplorer',
  props: ['context', 'selectedId', 'disabled'],
  emits: ['select', 'deselect', 'loaded', 'refresh'],
  template: '<section />',
});
const listStub = defineComponent({
  name: 'RecordQueryListPanel',
  props: ['context', 'ready'],
  emits: ['action'],
  template: '<section />',
});
const flatListStub = defineComponent({
  name: 'CrudRecordListExplorer',
  props: ['context'],
  template: '<section />',
});
const treeListStub = defineComponent({
  name: 'TreeRecordExplorer',
  props: ['context'],
  template: '<section />',
});
const workspaceStub = defineComponent({
  name: 'ManagementWorkspace',
  props: ['explorerCount'],
  template: '<section><slot /></section>',
});
const explorerColumnStub = defineComponent({
  name: 'ManagementExplorerColumn',
  template: '<aside><slot /></aside>',
});
const flatLayoutStub = defineComponent({
  name: 'StaticManagementLayout',
  props: ['navigatorCount'],
  template: '<section><slot name="navigator" :index="0" /><slot name="explorer" /><slot /></section>',
});
const actionBarStub = defineComponent({
  name: 'RecordActionBar',
  emits: ['action'],
  template: '<section />',
});
const detailActionsStub = defineComponent({
  name: 'ModuleRecordDetailActions',
  emits: ['cancel'],
  template: '<section />',
});

const pageTemplates = ['LIST_DETAIL_CARD', 'FLAT_MANAGEMENT', 'TREE_MANAGEMENT'] as const;
type PageTemplate = (typeof pageTemplates)[number];

function runtime(template: PageTemplate = 'LIST_DETAIL_CARD', actions: Array<Record<string, unknown>> = []) {
  return {
    moduleAlias: 'crm.customer',
    tenantRequired: true,
    capabilities: [],
    abilities: ['crud'],
    actions,
    uiDescriptor: {
      schemaVersion: '1',
      moduleAlias: 'crm.customer',
      page: {
        template,
        list: { fields: { viewCode: 'list', viewKind: 'LIST', fields: [] } },
        detail: { editor: { viewCode: 'form', viewKind: 'FORM', fields: [] } },
      },
    },
  };
}

function render(currentUser: CurrentUser) {
  return mount(
    defineComponent({
      setup: () => {
        provideCurrentUserContext(ref(currentUser));
        return () => h(ModulePageHost, { descriptor });
      },
    }),
    {
      global: {
        stubs: {
          ManagementWorkspace: workspaceStub,
          ManagementExplorerColumn: explorerColumnStub,
          StaticManagementLayout: flatLayoutStub,
          TenantScopeExplorer: tenantExplorerStub,
          RecordQueryListPanel: listStub,
          CrudRecordListExplorer: flatListStub,
          TreeRecordExplorer: treeListStub,
          RecordExplorerPanel: { template: '<section><slot /><slot name="actions" /></section>' },
          RecordActionBar: actionBarStub,
          ModuleRecordDetailActions: detailActionsStub,
          RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
        },
      },
    },
  );
}

describe('ModulePageHost tenant scope', () => {
  const originalFetch = globalThis.fetch;
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  const tenantUsers = [
    {
      label: 'tenant administrator',
      currentUser: {
        userId: 'tenant-admin-1',
        username: 'tenant-admin',
        tenantId: 'tenant-login',
        organizationId: 'org-1',
        system: false,
      },
    },
    {
      label: 'ordinary tenant user',
      currentUser: {
        userId: 'tenant-user-1',
        username: 'tenant-user',
        tenantId: 'tenant-login',
        organizationId: 'org-2',
        system: false,
      },
    },
  ] satisfies Array<{ label: string; currentUser: CurrentUser }>;

  it.each(tenantUsers.flatMap((user) => pageTemplates.map((template) => ({ ...user, template }))))(
    'binds the logged-in tenant for $label on $template without a tenant selector or empty tenant column',
    async ({ currentUser, template }) => {
      const requests: Request[] = [];
      globalThis.fetch = async (input, init) => {
        const request = new Request(input, init);
        requests.push(request);
        if (request.url.endsWith('/platform.module/crm.customer/context'))
          return Response.json(runtime(template));
        if (request.url.endsWith('/crm.customer/query'))
          return Response.json({
            records: [],
            total: 0,
            pageNum: 1,
            pageSize: 20,
            pages: 0,
            totalKnown: true,
          });
        throw new Error(`Unexpected request: ${request.url}`);
      };
      configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
      const wrapper = render(currentUser);
      await flushPromises();

      expect(wrapper.findComponent(tenantExplorerStub).exists()).toBe(false);
      expect(wrapper.findAllComponents(explorerColumnStub)).toHaveLength(
        template === 'TREE_MANAGEMENT' ? 1 : 0,
      );
      if (template === 'FLAT_MANAGEMENT') {
        expect(wrapper.findComponent(flatLayoutStub).props('navigatorCount')).toBe(0);
      } else {
        expect(wrapper.findComponent(workspaceStub).props('explorerCount')).toBe(
          template === 'TREE_MANAGEMENT' ? 1 : 0,
        );
      }

      const querySurface = wrapper.findComponent({
        name:
          template === 'LIST_DETAIL_CARD'
            ? 'RecordQueryListPanel'
            : template === 'FLAT_MANAGEMENT'
              ? 'CrudRecordListExplorer'
              : 'TreeRecordExplorer',
      });
      expect(querySurface.exists()).toBe(true);
      await querySurface.props('context').crud.query();
      expect(
        requests
          .find((request) => request.url.endsWith('/crm.customer/query'))
          ?.headers.get('X-MuYun-Tenant-Id'),
      ).toBe(currentUser.tenantId);
      expect(requests.some((request) => request.url.includes('/iam.tenant/'))).toBe(false);
      wrapper.unmount();
    },
  );

  it('does not replace an editing tenant session and allows the next selection after cancel', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/crm.customer/context'))
        return Response.json(runtime('LIST_DETAIL_CARD', [{ actionCode: 'create', authorized: true }]));
      if (request.url.endsWith('/crm.customer/query'))
        return Response.json({ records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true });
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = render({ userId: 'admin', username: 'admin', system: true });
    await flushPromises();

    let tenant = wrapper.findComponent({ name: 'TenantScopeExplorer' });
    tenant.vm.$emit('select', { id: 'tenant-a', title: '甲租户' });
    await flushPromises();
    tenant = wrapper.findComponent({ name: 'TenantScopeExplorer' });
    wrapper
      .findComponent({ name: 'RecordQueryListPanel' })
      .vm.$emit('action', { key: 'create', actionCode: 'create' });
    await flushPromises();
    expect(tenant.props('disabled')).toBe(true);

    tenant.vm.$emit('select', { id: 'tenant-b', title: '乙租户' });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'TenantScopeExplorer' }).props('selectedId')).toBe('tenant-a');

    wrapper.findComponent({ name: 'ModuleRecordDetailActions' }).vm.$emit('cancel');
    await flushPromises();
    tenant = wrapper.findComponent({ name: 'TenantScopeExplorer' });
    expect(tenant.props('disabled')).toBe(false);
    tenant.vm.$emit('select', { id: 'tenant-b', title: '乙租户' });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'TenantScopeExplorer' }).props('selectedId')).toBe('tenant-b');
    wrapper.unmount();
  });
});
