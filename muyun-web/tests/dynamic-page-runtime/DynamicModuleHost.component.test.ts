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
            views: [{ viewCode: 'default_list', viewKind: 'LIST', fields: [] }],
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
            views: [
              {
                viewCode: 'default_list',
                viewKind: 'LIST',
                fields: [],
                scopedListWorkspace: {
                  scopeModuleAlias: 'mr.knowledge_directory',
                  scopeField: 'directoryId',
                  queryCriteriaKey: 'directoryId',
                  scopeTitle: '知识库目录',
                  scopeSearchPlaceholder: '搜索目录',
                  showScopeItemSubtitle: false,
                  createPolicy: 'REQUIRE_SCOPE',
                },
              },
            ],
          },
        });
      }
      if (request.url.endsWith('/platform.module/mr.knowledge_directory/context')) {
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
});
