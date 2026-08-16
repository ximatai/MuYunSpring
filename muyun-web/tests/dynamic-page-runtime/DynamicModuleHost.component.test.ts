import { flushPromises, shallowMount } from '@vue/test-utils';
import { afterEach, describe, expect, it } from 'vitest';
import DynamicModuleHost from '@/dynamic-page-runtime/DynamicModuleHost.vue';
import { configureModuleContext, createHttpClient } from '@muyun/web-core';
import { configureModulePageEnhancements } from '@/dynamic-page-runtime/modulePageEnhancements.ts';
import { refreshModulePageList } from '@/dynamic-page-runtime/modulePageListRefresh.ts';

describe('DynamicModuleHost', () => {
  const originalFetch = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = originalFetch;
    configureModulePageEnhancements([]);
    window.localStorage.removeItem('muyun.preference.module-page.detail-surface.crm.customer');
  });

  it('merges frontend-owned list enhancements into the standard descriptor runner', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [{ actionCode: 'crm.customer.conversation', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'crm.customer',
            page: page(),
          },
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });
    configureModulePageEnhancements([
      {
        id: 'customer-conversation',
        target: { moduleAlias: 'crm.customer', viewCode: 'default_list' },
        list: {
          actions: [
            {
              key: 'conversation',
              actionCode: 'crm.customer.conversation',
              title: '对话',
              run: () => undefined,
            },
          ],
          columns: [
            {
              key: 'conversationStatus',
              title: '对话状态',
              cell: { template: '<span>对话状态</span>' },
            },
          ],
          rowActions: [
            {
              key: 'conversation',
              actionCode: 'crm.customer.conversation',
              title: '对话',
              run: () => undefined,
            },
          ],
        },
      },
    ]);

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: { ManagementWorkspace: { template: '<section><slot /></section>' } },
      },
    });

    await flushPromises();

    const panel = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(panel.props('extraActions')).toEqual([
      expect.objectContaining({ key: 'conversation', actionCode: 'crm.customer.conversation' }),
    ]);
    expect(panel.props('additionalColumns')).toEqual([
      expect.objectContaining({ key: 'conversationStatus', title: '对话状态' }),
    ]);
    expect(panel.props('extraRowActionsOf')()).toEqual([
      expect.objectContaining({ key: 'conversation', actionCode: 'crm.customer.conversation' }),
    ]);
  });

  it('forwards recycle-bin mode through the standard module runner and clears the active detail', async () => {
    let resolveDetail: ((record: { id: string; title: string }) => void) | undefined;
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/platform.application/context')) {
        return Response.json({
          moduleAlias: 'platform.application',
          capabilities: ['RECYCLE_BIN'],
          actions: [],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'platform.application',
            page: page(),
          },
        });
      }
      if (request.url.endsWith('/platform.application/view/app-1')) {
        return new Promise<Response>((resolve) => {
          resolveDetail = (record) => resolve(Response.json(record));
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'platform.application', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: { ManagementWorkspace: { template: '<section><slot /></section>' } },
      },
    });
    await flushPromises();

    const panel = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(panel.props('mode')).toBe('normal');
    panel.vm.$emit('rowAction', { key: 'edit' }, { id: 'app-1' });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordDetailPanel' }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'RecordModeDrawer' }).exists()).toBe(false);
    await panel.vm.$emit('modeChange', 'recycleBin');
    expect(panel.props('mode')).toBe('recycleBin');
    expect(wrapper.findComponent({ name: 'RecordModeDrawer' }).exists()).toBe(false);
    resolveDetail?.({ id: 'app-1', title: '平台应用' });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordModeDrawer' }).exists()).toBe(false);
  });

  it('renders the flat management template as an inline explorer and detail workspace', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/platform.application/context')) {
        return Response.json({
          moduleAlias: 'platform.application',
          capabilities: ['RECYCLE_BIN'],
          actions: [
            { actionCode: 'create', authorized: true },
            { actionCode: 'update', authorized: true },
            { actionCode: 'delete', authorized: true },
          ],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'platform.application',
            page: {
              template: 'FLAT_MANAGEMENT',
              explorer: {
                title: '应用列表',
                searchPlaceholder: '搜索应用名称、alias 或 ID',
                emptyDescription: '暂无应用',
                recordLabel: '应用',
                fallbackTitle: '未命名应用',
                titleField: 'title',
                mutedWhenDisabled: true,
              },
              detail: {
                emptyDescription: '请选择应用，或新建应用',
                createTitle: '新建应用',
                editor: {
                  viewCode: 'default_form',
                  viewKind: 'FORM',
                  fields: [
                    {
                      fieldRef: { fieldName: 'title' },
                      label: '应用名称',
                      visible: { kind: 'CONSTANT', value: true },
                      required: { kind: 'CONSTANT', value: true },
                      readOnly: { kind: 'CONSTANT', value: false },
                    },
                  ],
                },
              },
              traits: ['STANDARD_CRUD'],
            },
          },
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'platform.application', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /></section>' },
          ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
          RecordExplorerPanel: {
            template: '<section><slot name="actions" /><slot /><slot name="footer" /></section>',
          },
          RecordDetailPanel: {
            template: '<section><slot name="status" /><slot name="actions" /><slot /></section>',
          },
          StaticManagementLayout: {
            template:
              '<section><slot name="explorer-actions" /><slot name="explorer" /><slot name="explorer-footer" /><slot name="detail-status" /><slot name="detail-actions" /><slot /></section>',
          },
        },
      },
    });
    await flushPromises();

    const explorer = wrapper.findComponent({ name: 'CrudRecordListExplorer' });
    expect(explorer.exists()).toBe(true);
    expect(explorer.props('emptyDescription')).toBe('暂无应用');
    expect(explorer.props('fallbackTitle')).toBe('未命名应用');
    expect(explorer.props('mode')).toBe('normal');
    expect(wrapper.findComponent({ name: 'RecordModeDrawer' }).exists()).toBe(false);

    explorer.vm.$emit('select', { id: 'application-1', title: '旧应用' });
    await flushPromises();
    wrapper.findComponent({ name: 'ModuleActionButton' }).vm.$emit('click');
    await flushPromises();

    expect(wrapper.findComponent({ name: 'RecordFormFields' }).props('fields')).toEqual(expect.any(Map));
    expect(wrapper.findComponent({ name: 'RecordFormFields' }).props('fields').get('title')).toEqual(
      expect.objectContaining({ label: '应用名称' }),
    );

    const actionBar = wrapper.findComponent({ name: 'RecordActionBar' });
    expect(actionBar.props('recordId')).toBeUndefined();
    expect(actionBar.props('actions')).toEqual(
      expect.arrayContaining([expect.objectContaining({ key: 'save', actionCode: 'create' })]),
    );
  });

  it('uses the same record grant for the standard view row action and double-click', async () => {
    let viewRequests = 0;
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [
            { actionCode: 'view', authorized: true },
            { actionCode: 'crm.customer.view_summary', authorized: true },
          ],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'crm.customer',
            page: page(),
          },
        });
      }
      if (request.url.endsWith('/crm.customer/actions/customer-1')) {
        return Response.json({
          actions: [{ actionCode: 'crm.customer.view_summary', available: false, reason: '无权查看' }],
        });
      }
      if (request.url.endsWith('/crm.customer/view/customer-1')) {
        viewRequests += 1;
        return Response.json({ id: 'customer-1' });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });
    configureModulePageEnhancements([
      {
        id: 'customer-summary',
        target: { moduleAlias: 'crm.customer', viewCode: 'default_list' },
        recordView: {
          authorizationActionCode: 'crm.customer.view_summary',
          drawer: { component: { template: '<section>客户摘要</section>' }, loadRecord: false },
        },
      },
    ]);

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
    });

    await flushPromises();

    const panel = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(panel.props('standardCrudRowActionCodes')).toEqual({});
    panel.vm.$emit('rowAction', { key: 'view' }, { id: 'customer-1' });
    await flushPromises();
    panel.vm.$emit('rowDblclick', { id: 'customer-1' });
    await flushPromises();

    expect(viewRequests).toBe(0);
    expect(wrapper.findComponent({ name: 'RecordModeDrawer' }).props('open')).toBe(false);
  });

  it('opens the platform drawer without a generic detail read when the record grant allows it', async () => {
    let viewRequests = 0;
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [
            { actionCode: 'view', authorized: true },
            { actionCode: 'crm.customer.view_summary', authorized: true },
          ],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'crm.customer',
            page: page(),
          },
        });
      }
      if (request.url.endsWith('/crm.customer/actions/customer-1')) {
        return Response.json({
          actions: [{ actionCode: 'crm.customer.view_summary', available: true }],
        });
      }
      if (request.url.endsWith('/crm.customer/view/customer-1')) {
        viewRequests += 1;
        return Response.json({ id: 'customer-1' });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });
    configureModulePageEnhancements([
      {
        id: 'customer-summary',
        target: { moduleAlias: 'crm.customer', viewCode: 'default_list' },
        recordView: {
          authorizationActionCode: 'crm.customer.view_summary',
          drawer: { component: { template: '<section>客户摘要</section>' }, loadRecord: false },
        },
      },
    ]);

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
    });

    await flushPromises();

    const panel = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    panel.vm.$emit('rowDblclick', { id: 'customer-1' });
    await flushPromises();

    expect(viewRequests).toBe(0);
    const drawer = wrapper.findComponent({ name: 'RecordModeDrawer' });
    expect(drawer.exists()).toBe(true);
    expect(drawer.props('editAvailable')).toBe(false);
  });

  it('supplies scoped-list selection to extension action state, execution, and drawers', async () => {
    const stateScopes: Array<{ moduleAlias: string; record?: { id?: string } } | undefined> = [];
    let executedScope: { moduleAlias: string; record?: { id?: string } } | undefined;
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/mr.knowledge_file/context')) {
        return Response.json({
          moduleAlias: 'mr.knowledge_file',
          capabilities: [],
          actions: [{ actionCode: 'mr.knowledge_file.agent_chat_ask', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'mr.knowledge_file',
            page: page({
              navigator: {
                contextBindings: [
                  {
                    source: 'NAVIGATOR',
                    sourceKey: 'directory',
                    target: 'LIST_QUERY',
                    targetKey: 'directoryId',
                  },
                  {
                    source: 'NAVIGATOR',
                    sourceKey: 'directory',
                    target: 'FORM_DEFAULT',
                    targetKey: 'directoryId',
                  },
                ],
                levels: [
                  {
                    key: 'directory',
                    kind: 'TREE',
                    sourceModuleAlias: 'mr.knowledge_directory',
                    title: '知识库目录',
                    searchPlaceholder: '搜索目录',
                  },
                ],
              },
            }),
          },
        });
      }
      if (request.url.endsWith('/platform.module/mr.knowledge_directory/reference-context')) {
        return Response.json({ moduleAlias: 'mr.knowledge_directory', capabilities: [], actions: [] });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });
    const ScopeDrawer = {
      name: 'ScopeDrawer',
      props: ['context'],
      template: '<span class="scope-drawer">{{ context.scope?.record?.id }}</span>',
    };
    configureModulePageEnhancements([
      {
        id: 'knowledge-file-simulation',
        target: { moduleAlias: 'mr.knowledge_file', viewCode: 'default_list' },
        list: {
          actions: [
            {
              key: 'agent-chat',
              actionCode: 'mr.knowledge_file.agent_chat_ask',
              title: '模拟问答',
              state: ({ scope }) => {
                stateScopes.push(scope);
                return { disabled: scope?.record?.id == null };
              },
              run: ({ scope, openDrawer }) => {
                executedScope = scope;
                openDrawer({ title: '模拟问答', component: ScopeDrawer });
              },
            },
          ],
        },
      },
    ]);

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'mr.knowledge_file', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /></section>' },
          ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
          RecordExplorerPanel: { template: '<section><slot /></section>' },
          RecordDetailDrawer: { template: '<section><slot /></section>' },
          ScopeDrawer,
        },
      },
    });

    await flushPromises();

    const panel = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(panel.props('extraActions')).toEqual([
      expect.objectContaining({ key: 'agent-chat', disabled: true }),
    ]);
    expect(stateScopes.at(-1)).toMatchObject({ moduleAlias: 'mr.knowledge_directory' });

    wrapper
      .findComponent({ name: 'CrudRecordListExplorer' })
      .vm.$emit('select', { id: 'directory-1', title: '设备资料' });
    await flushPromises();

    expect(panel.props('externalQueryValues')).toEqual({ directoryId: 'directory-1' });
    expect(panel.props('extraActions')).toEqual([
      expect.objectContaining({ key: 'agent-chat', disabled: false }),
    ]);
    panel.vm.$emit('action', { key: 'agent-chat' });
    await flushPromises();

    expect(executedScope).toMatchObject({
      moduleAlias: 'mr.knowledge_directory',
      record: { id: 'directory-1', title: '设备资料' },
    });
    expect(wrapper.find('.scope-drawer').text()).toBe('directory-1');
  });

  it('applies every selected navigator level to the list without waiting for a downstream selection', async () => {
    window.localStorage.setItem('muyun.preference.module-page.detail-surface.crm.customer', '"drawer"');
    let detailRequests = 0;
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'crm.customer',
            page: page({
              navigator: {
                contextBindings: [
                  { source: 'NAVIGATOR', sourceKey: 'tenant', target: 'LIST_QUERY', targetKey: 'tenantId' },
                  { source: 'NAVIGATOR', sourceKey: 'tenant', target: 'FORM_DEFAULT', targetKey: 'tenantId' },
                  {
                    source: 'NAVIGATOR',
                    sourceKey: 'tenant',
                    target: 'NAVIGATOR_QUERY',
                    targetKey: 'tenantId',
                    targetNavigatorLevelKey: 'organization',
                  },
                  {
                    source: 'NAVIGATOR',
                    sourceKey: 'organization',
                    target: 'LIST_QUERY',
                    targetKey: 'organizationId',
                  },
                  {
                    source: 'NAVIGATOR',
                    sourceKey: 'organization',
                    target: 'FORM_DEFAULT',
                    targetKey: 'organizationId',
                  },
                ],
                levels: [
                  {
                    key: 'tenant',
                    kind: 'MICRO_LIST',
                    sourceModuleAlias: 'iam.tenant',
                    title: '租户',
                    searchPlaceholder: '搜索租户',
                  },
                  {
                    key: 'organization',
                    kind: 'TREE',
                    sourceModuleAlias: 'iam.organization',
                    title: '组织',
                    searchPlaceholder: '搜索组织',
                  },
                ],
              },
            }),
          },
        });
      }
      if (request.url.endsWith('/platform.module/iam.tenant/reference-context')) {
        return Response.json({ moduleAlias: 'iam.tenant', capabilities: [], actions: [] });
      }
      if (request.url.endsWith('/platform.module/iam.organization/reference-context')) {
        return Response.json({ moduleAlias: 'iam.organization', capabilities: ['tree'], actions: [] });
      }
      if (request.url.endsWith('/crm.customer/view/customer-1')) {
        detailRequests += 1;
        return Response.json({ id: 'customer-1', title: '客户一' });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /></section>' },
          ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
          RecordExplorerPanel: { template: '<section><slot /></section>' },
        },
      },
    });
    await flushPromises();

    const explorers = wrapper
      .findAllComponents({ name: 'CrudRecordListExplorer' })
      .filter((explorer) => explorer.props('context').moduleAlias === 'iam.tenant');
    const treeExplorer = wrapper
      .findAllComponents({ name: 'TreeRecordExplorer' })
      .find((explorer) => explorer.props('context').moduleAlias === 'iam.organization');
    const panel = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(explorers).toHaveLength(1);
    expect(treeExplorer).toBeDefined();

    panel.vm.$emit('select', { id: 'customer-1', title: '客户一' });
    await flushPromises();
    expect(detailRequests).toBe(0);

    panel.vm.$emit('rowDblclick', { id: 'customer-1', title: '客户一' });
    await flushPromises();
    expect(detailRequests).toBe(1);

    explorers[0].vm.$emit('select', { id: 'tenant-1', title: '甲租户' });
    await flushPromises();
    expect(panel.props('externalQueryValues')).toEqual({ tenantId: 'tenant-1' });
    expect(treeExplorer?.props('externalQueryValues')).toEqual({ tenantId: 'tenant-1' });

    treeExplorer?.vm.$emit('select', { id: 'organization-1', title: '总部' });
    await flushPromises();
    expect(panel.props('externalQueryValues')).toEqual({
      tenantId: 'tenant-1',
      organizationId: 'organization-1',
    });

    explorers[0].vm.$emit('select', { id: 'tenant-2', title: '乙租户' });
    await flushPromises();
    expect(panel.props('externalQueryValues')).toEqual({ tenantId: 'tenant-2' });
  });

  it('auto-selects and hides a single navigator while retaining its downstream binding', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/demo.position/context')) {
        return Response.json({
          moduleAlias: 'demo.position',
          capabilities: [],
          actions: [],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'demo.position',
            page: page({
              navigator: {
                contextBindings: [
                  { source: 'NAVIGATOR', sourceKey: 'tenant', target: 'LIST_QUERY', targetKey: 'tenantId' },
                  { source: 'NAVIGATOR', sourceKey: 'tenant', target: 'FORM_DEFAULT', targetKey: 'tenantId' },
                ],
                levels: [
                  {
                    key: 'tenant',
                    kind: 'MICRO_LIST',
                    sourceModuleAlias: 'iam.tenant',
                    title: '租户',
                    searchPlaceholder: '搜索租户',
                    singleResultPolicy: 'AUTO_SELECT_AND_HIDE',
                  },
                ],
              },
            }),
          },
        });
      }
      if (request.url.endsWith('/platform.module/iam.tenant/reference-context')) {
        return Response.json({ moduleAlias: 'iam.tenant', capabilities: [], actions: [] });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'demo.position', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /></section>' },
          ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
          RecordExplorerPanel: { template: '<section><slot /></section>' },
        },
      },
    });
    await flushPromises();

    const navigator = wrapper.findComponent({ name: 'CrudRecordListExplorer' });
    const list = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    navigator.vm.$emit('loaded', [{ id: 'xcmg', title: '徐工集团' }]);
    await flushPromises();

    expect(list.props('externalQueryValues')).toEqual({ tenantId: 'xcmg' });
    expect(wrapper.findComponent({ name: 'CrudRecordListExplorer' }).exists()).toBe(false);
  });

  it('blocks every list runner when its menu bootstrap fails', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.menu/organization-menu/entry?clientType=WEB')) {
        return new Response(JSON.stringify({ message: '页面入口不可用' }), {
          status: 500,
          headers: { 'content-type': 'application/json' },
        });
      }
      if (request.url.endsWith('/platform.module/iam.organization/context')) {
        return Response.json({
          moduleAlias: 'iam.organization',
          capabilities: ['TREE'],
          abilities: ['tree'],
          actions: [],
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          menuId: 'organization-menu',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'iam.organization', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          RecordPanelState: {
            props: ['description'],
            template: '<div class="record-panel-state">{{ description }}</div>',
          },
        },
      },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('页面入口不可用');
    expect(wrapper.find('management-workspace-stub').exists()).toBe(false);
    expect(wrapper.find('tree-record-explorer-stub').exists()).toBe(false);
    expect(wrapper.find('record-query-list-panel-stub').exists()).toBe(false);
  });

  it('uses the bootstrap page mode instead of a stale menu snapshot', async () => {
    const requests: string[] = [];
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      requests.push(request.url);
      if (request.url.endsWith('/platform.menu/customer-menu/entry?clientType=WEB')) {
        return Response.json({
          entry: { moduleAlias: 'crm.customer', pageMode: 'FORM' },
          clientType: 'WEB',
          mainEntityAlias: 'customer',
          resolvedConfig: { uiFields: [], queryItems: [] },
          openApiPath: '/crm.customer/openapi',
        });
      }
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({ moduleAlias: 'crm.customer', capabilities: [], actions: [] });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          menuId: 'customer-menu',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('动态FORM入口暂未接入运行器');
    expect(wrapper.find('record-query-list-panel-stub').exists()).toBe(false);
    expect(requests.some((url) => url.endsWith('/crm.customer/query'))).toBe(false);
  });

  it('allows business-owned module triggers to refresh the active list without remounting it', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/mr.knowledge_file/context')) {
        return Response.json({ moduleAlias: 'mr.knowledge_file', capabilities: [], actions: [] });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'mr.knowledge_file', pageMode: 'LIST' },
        },
      },
    });

    await flushPromises();
    const panel = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(panel.props('reloadKey')).toBe(0);
    expect(refreshModulePageList('mr.other_file')).toBe(false);
    expect(refreshModulePageList('mr.knowledge_file')).toBe(true);
    await flushPromises();

    expect(panel.props('reloadKey')).toBe(1);
    expect(wrapper.findComponent({ name: 'RecordQueryListPanel' }).vm).toBe(panel.vm);
    wrapper.unmount();
    expect(refreshModulePageList('mr.knowledge_file')).toBe(false);
  });

  it('refreshes a tree module through its tree list carrier', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/demo.tree/context')) {
        return Response.json({
          moduleAlias: 'demo.tree',
          capabilities: ['TREE'],
          abilities: ['tree'],
          actions: [],
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'demo.tree', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /></section>' },
          ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
          RecordExplorerPanel: { template: '<section><slot /><slot name="actions" /></section>' },
        },
      },
    });

    await flushPromises();
    const explorer = wrapper.findComponent({ name: 'TreeRecordExplorer' });
    expect(explorer.props('reloadKey')).toBe(0);
    expect(refreshModulePageList('demo.tree')).toBe(true);
    await flushPromises();

    expect(explorer.props('reloadKey')).toBe(1);
    wrapper.unmount();
  });

  it('rejects business detail drawer enhancements for tree modules instead of silently ignoring them', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/demo.tree/context')) {
        return Response.json({
          moduleAlias: 'demo.tree',
          capabilities: ['TREE'],
          abilities: ['tree'],
          actions: [{ actionCode: 'demo.tree.view_summary', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'demo.tree',
            page: page(),
          },
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });
    configureModulePageEnhancements([
      {
        id: 'tree-summary',
        target: { moduleAlias: 'demo.tree', viewCode: 'default_list' },
        recordView: {
          authorizationActionCode: 'demo.tree.view_summary',
          drawer: { component: { template: '<section>树节点摘要</section>' } },
        },
      },
    ]);

    const wrapper = shallowMount(DynamicModuleHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'demo.tree', pageMode: 'LIST' },
        },
      },
    });

    await flushPromises();

    expect(wrapper.findComponent({ name: 'RecordPanelState' }).props('description')).toBe(
      '模块页面增强 tree-summary 的业务查看呈现仅支持普通列表模块，不支持树模块',
    );
    expect(wrapper.findComponent({ name: 'TreeRecordExplorer' }).exists()).toBe(false);
    expect(refreshModulePageList('demo.tree')).toBe(false);
  });
});

function page(overrides: Record<string, unknown> = {}) {
  return {
    template: 'LIST_DETAIL_CARD',
    list: {
      searchPlaceholder: '搜索记录',
      fields: { viewCode: 'default_list', viewKind: 'LIST', fields: [] },
    },
    detail: {
      emptyDescription: '请选择记录',
      createTitle: '新建记录',
      editor: { viewCode: 'default_form', viewKind: 'FORM', fields: [] },
    },
    traits: ['STANDARD_CRUD'],
    ...overrides,
  };
}
