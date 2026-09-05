import { flushPromises, shallowMount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent } from 'vue';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import { configureModuleContext, createHttpClient } from '@muyun/web-core';
import { configureModulePageEnhancements } from '@/dynamic-page-runtime/modulePageEnhancements.ts';
import { refreshModulePageList } from '@/dynamic-page-runtime/modulePageListRefresh.ts';

describe('ModulePageHost', () => {
  const originalFetch = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = originalFetch;
    configureModulePageEnhancements([]);
    window.localStorage.removeItem('muyun.preference.module-page.detail-surface.crm.customer');
    window.localStorage.removeItem('muyun.preference.module-page.list-page-size.crm.customer');
  });

  it('executes a declared detail action block through the standard record action endpoint', async () => {
    const ExtensionDrawer = defineComponent({ name: 'ExtensionDrawer', template: '<section>扩展</section>' });
    configureModulePageEnhancements([
      {
        id: 'customer-flat-detail-extension',
        target: { moduleAlias: 'crm.customer' },
        detail: {
          actions: [
            {
              key: 'configure-extension',
              title: '配置扩展',
              run({ openDrawer }) {
                openDrawer({ title: '配置扩展', component: ExtensionDrawer });
              },
            },
          ],
        },
      },
    ]);
    const requests: Request[] = [];
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      requests.push(request);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [{ actionCode: 'submit', title: '提交', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'crm.customer',
            page: page({ template: 'FLAT_MANAGEMENT' }),
          },
        });
      }
      if (request.url.endsWith('/platform.menu/customer-menu/entry?clientType=WEB')) {
        return Response.json({
          entry: { menuId: 'customer-menu', moduleAlias: 'crm.customer', pageMode: 'LIST' },
          clientType: 'WEB',
          mainEntityAlias: 'customer',
          resolvedConfig: {
            uiFields: [],
            queryItems: [],
            actionBlocks: [{ type: 'action', key: 'submit', actionCode: 'submit', title: '提交' }],
          },
          openApiPath: '/crm.customer/openapi',
        });
      }
      if (request.url.endsWith('/crm.customer/actions/customer-1')) {
        return Response.json({
          recordId: 'customer-1',
          actions: [{ actionCode: 'submit', available: true }],
        });
      }
      if (request.url.endsWith('/crm.customer/view/customer-1')) {
        return Response.json({ id: 'customer-1', title: '客户一', version: 1 });
      }
      if (request.url.endsWith('/crm.customer/submit/customer-1')) {
        return Response.json({ changed: true });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });

    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          menuId: 'customer-menu',
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          StaticManagementLayout: {
            template: '<section><slot name="explorer" /><slot name="detail-actions" /><slot /></section>',
          },
          RecordDetailPanel: { template: '<section><slot name="actions" /><slot /></section>' },
          UiModal: { name: 'UiModal', template: '<section><slot /></section>' },
        },
      },
    });
    await flushPromises();

    wrapper.findComponent({ name: 'CrudRecordListExplorer' }).vm.$emit('select', { id: 'customer-1' });
    await flushPromises();

    const actionBar = wrapper
      .findAllComponents({ name: 'RecordActionBar' })
      .find((item) =>
        (item.props('actions') as Array<{ actionCode?: string }>).some(
          (action) => action.actionCode === 'submit',
        ),
      );
    expect(actionBar).toBeDefined();
    actionBar!.vm.$emit('action', { key: 'page-action-block:entry:submit:0', actionCode: 'submit' });
    await flushPromises();

    expect(requests.some((request) => request.url.endsWith('/crm.customer/submit/customer-1'))).toBe(true);

    const flatActionBar = wrapper
      .findAllComponents({ name: 'RecordActionBar' })
      .find((item) =>
        (item.props('actions') as Array<{ key?: string }>).some(
          (action) => action.key === 'configure-extension',
        ),
      );
    expect(flatActionBar).toBeDefined();
    flatActionBar!.vm.$emit('action', { key: 'configure-extension' });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).props('title')).toBe('配置扩展');
  });

  it('limits flat detail contributions to an active normal record-view lifecycle', async () => {
    const ExtensionDrawer = defineComponent({
      name: 'LifecycleExtensionDrawer',
      template: '<section>扩展</section>',
    });
    configureModulePageEnhancements([
      {
        id: 'customer-flat-lifecycle-extension',
        target: { moduleAlias: 'crm.customer' },
        detail: {
          actions: [
            {
              key: 'configure-extension',
              title: '配置扩展',
              run({ openDrawer }) {
                openDrawer({ title: '配置扩展', component: ExtensionDrawer });
              },
            },
          ],
        },
      },
    ]);
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          capabilities: ['RECYCLE_BIN'],
          actions: [
            { actionCode: 'update', authorized: true },
            { actionCode: 'delete', authorized: true },
            { actionCode: 'recycleBinQuery', authorized: true },
          ],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'crm.customer',
            page: page({ template: 'FLAT_MANAGEMENT' }),
          },
        });
      }
      if (request.url.endsWith('/platform.menu/customer-menu/entry?clientType=WEB')) {
        return Response.json({
          entry: { menuId: 'customer-menu', moduleAlias: 'crm.customer', pageMode: 'LIST' },
          clientType: 'WEB',
          mainEntityAlias: 'customer',
          resolvedConfig: { uiFields: [], queryItems: [], actionBlocks: [] },
          openApiPath: '/crm.customer/openapi',
        });
      }
      if (request.url.endsWith('/crm.customer/actions/customer-1')) {
        return Response.json({ recordId: 'customer-1', actions: [] });
      }
      if (request.url.endsWith('/crm.customer/view/customer-1')) {
        return Response.json({ id: 'customer-1', title: '客户一', version: 1 });
      }
      if (request.url.includes('/crm.customer/recycle-bin/query'))
        return Response.json({ records: [], total: 0 });
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          menuId: 'customer-menu',
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          StaticManagementLayout: {
            template:
              '<section><slot name="explorer-actions" /><slot name="explorer" /><slot name="explorer-footer" /><slot name="detail-actions" /><slot /></section>',
          },
          RecordDetailPanel: { template: '<section><slot name="actions" /><slot /></section>' },
          UiModal: { name: 'UiModal', template: '<section><slot /></section>' },
        },
      },
    });
    await flushPromises();

    const explorer = wrapper.findComponent({ name: 'CrudRecordListExplorer' });
    explorer.vm.$emit('select', { id: 'customer-1' });
    await flushPromises();
    const actionBar = () => wrapper.findComponent({ name: 'RecordActionBar' });
    expect(actionBar().props('actions')).toEqual(
      expect.arrayContaining([expect.objectContaining({ key: 'configure-extension' })]),
    );

    actionBar().vm.$emit('action', { key: 'edit', actionCode: 'update' });
    await flushPromises();
    expect(actionBar().props('actions')).not.toEqual(
      expect.arrayContaining([expect.objectContaining({ key: 'configure-extension' })]),
    );
    actionBar().vm.$emit('action', { key: 'configure-extension' });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);

    actionBar().vm.$emit('action', { key: 'cancel' });
    await flushPromises();
    wrapper.findComponent({ name: 'RecycleBinModeButton' }).vm.$emit('click');
    await flushPromises();
    expect(actionBar().props('actions')).not.toEqual(
      expect.arrayContaining([expect.objectContaining({ key: 'configure-extension' })]),
    );
    actionBar().vm.$emit('action', { key: 'configure-extension' });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);
  });

  it('submits a signed local-edit form with its versioned action contract', async () => {
    const requests: Request[] = [];
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      requests.push(request);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [{ actionCode: 'editBaseInfo', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'crm.customer',
            page: page({ template: 'FLAT_MANAGEMENT' }),
          },
        });
      }
      if (request.url.endsWith('/platform.menu/customer-menu/entry?clientType=WEB')) {
        return Response.json({
          entry: { menuId: 'customer-menu', moduleAlias: 'crm.customer', pageMode: 'LIST' },
          clientType: 'WEB',
          mainEntityAlias: 'customer',
          resolvedConfig: {
            uiFields: [],
            queryItems: [],
            actionBlocks: [
              {
                type: 'localEdit',
                key: 'base',
                actionCode: 'editBaseInfo',
                title: '编辑资料',
                submitPath: '/crm.customer/editBaseInfo/{recordId}',
                localEditForm: {
                  uiConfigId: 'customer-local-form',
                  fields: [{ fieldName: 'name', fieldTitle: '名称', visible: true }],
                  submitContract: {
                    recordRequired: true,
                    recordVersionRequired: true,
                    fieldNamesRequired: true,
                    uiConfigIdPayloadKey: 'uiConfigId',
                  },
                },
              },
            ],
          },
          openApiPath: '/crm.customer/openapi',
        });
      }
      if (request.url.endsWith('/crm.customer/actions/customer-1'))
        return Response.json({
          recordId: 'customer-1',
          actions: [{ actionCode: 'editBaseInfo', available: true }],
        });
      if (request.url.endsWith('/crm.customer/view/customer-1'))
        return Response.json({ id: 'customer-1', title: '客户一', name: '旧名称', version: 7 });
      if (request.url.endsWith('/crm.customer/editBaseInfo/customer-1'))
        return Response.json({ changed: true });
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          menuId: 'customer-menu',
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          StaticManagementLayout: {
            template: '<section><slot name="explorer" /><slot name="detail-actions" /><slot /></section>',
          },
          RecordDetailPanel: { template: '<section><slot name="actions" /><slot /></section>' },
          UiModal: { name: 'UiModal', template: '<section><slot /></section>' },
        },
      },
    });
    await flushPromises();
    wrapper.findComponent({ name: 'CrudRecordListExplorer' }).vm.$emit('select', { id: 'customer-1' });
    await flushPromises();
    const actionBar = wrapper
      .findAllComponents({ name: 'RecordActionBar' })
      .find((item) =>
        (item.props('actions') as Array<{ actionCode?: string }>).some(
          (action) => action.actionCode === 'editBaseInfo',
        ),
      );
    actionBar!.vm.$emit('action', { key: 'page-local-edit:entry:base:0', actionCode: 'editBaseInfo' });
    await flushPromises();
    const localForm = wrapper.findComponent({ name: 'RecordFormFields' });
    localForm.vm.$emit('validity-change', { valid: false, errors: { name: '请输入有效数字' } });
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
    await flushPromises();
    expect(requests.some((request) => request.url.endsWith('/crm.customer/editBaseInfo/customer-1'))).toBe(
      false,
    );

    localForm.vm.$emit('validity-change', { valid: true, errors: {} });
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
    await flushPromises();
    const submit = requests.find((request) => request.url.endsWith('/crm.customer/editBaseInfo/customer-1'));
    expect(submit).toBeDefined();
    expect(await submit!.json()).toEqual({
      recordId: 'customer-1',
      record: { id: 'customer-1', version: 7, values: { name: '旧名称' } },
      fieldNames: ['name'],
      payload: { uiConfigId: 'customer-local-form' },
    });
  });

  it('merges frontend-owned list enhancements into the standard descriptor runner', async () => {
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
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

    const wrapper = shallowMount(ModulePageHost, {
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

    expect(wrapper.find('.module-workspace').classes()).toContain('module-workspace--management');
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
    expect(panel.props('pageSize')).toBe(20);
    panel.vm.$emit('pageSizeChange', 50);
    await flushPromises();
    expect(panel.props('pageSize')).toBe(50);
    expect(window.localStorage.getItem('muyun.preference.module-page.list-page-size.crm.customer')).toBe(
      '50',
    );
  });

  it('forwards recycle-bin mode through the standard module runner and clears the active detail', async () => {
    let resolveDetail: ((record: { id: string; title: string }) => void) | undefined;
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
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

    const wrapper = shallowMount(ModulePageHost, {
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
    const requests: Request[] = [];
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      requests.push(request);
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
                    {
                      fieldRef: { fieldName: 'payload' },
                      label: '扩展信息',
                      visible: { kind: 'CONSTANT', value: true },
                      required: { kind: 'CONSTANT', value: false },
                      readOnly: { kind: 'CONSTANT', value: false },
                      fieldControl: { alias: 'json', rendererType: 'JSON', valueShape: 'SCALAR' },
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

    const wrapper = shallowMount(ModulePageHost, {
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
    expect(
      (explorer.props('itemOf') as (record: { id: string; title: string }) => { secondary?: string })({
        id: 'application-1',
        title: '旧应用',
      }).secondary,
    ).toBeUndefined();
    expect(wrapper.findComponent({ name: 'RecordModeDrawer' }).exists()).toBe(false);

    explorer.vm.$emit('select', { id: 'application-1', title: '旧应用' });
    await flushPromises();
    wrapper.findComponent({ name: 'ModuleActionButton' }).vm.$emit('click');
    await flushPromises();

    const content = wrapper.findComponent({ name: 'ModulePageRecordContent' });
    expect(content.props('formFields')).toEqual(expect.any(Map));
    expect(content.props('formFields').get('title')).toEqual(expect.objectContaining({ label: '应用名称' }));

    const actionBar = wrapper.findComponent({ name: 'RecordActionBar' });
    expect(actionBar.props('recordId')).toBeUndefined();
    expect(actionBar.props('actions')).toEqual(
      expect.arrayContaining([expect.objectContaining({ key: 'save', actionCode: 'create' })]),
    );

    // The shared card content propagates form validity to the host save boundary.
    content.vm.$emit('validity-change', { valid: false, errors: { payload: '请输入有效 JSON' } });
    await flushPromises();
    expect(
      (actionBar.props('actions') as Array<{ key: string; disabled?: boolean }>).find(
        (action) => action.key === 'save',
      )?.disabled,
    ).toBe(false);
    actionBar.vm.$emit('action', { key: 'save', actionCode: 'create' });
    await flushPromises();
    expect(requests.some((request) => request.url.endsWith('/platform.application/create'))).toBe(false);
    expect(content.props('validationRequestKey')).toBe(1);
  });

  it('uses the same record grant for the standard view row action and double-click', async () => {
    let viewRequests = 0;
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
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

    const wrapper = shallowMount(ModulePageHost, {
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

    const wrapper = shallowMount(ModulePageHost, {
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

    const wrapper = shallowMount(ModulePageHost, {
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

  it('keeps a list dormant until every declared navigator scope is selected', async () => {
    window.localStorage.setItem('muyun.preference.module-page.detail-surface.crm.customer', '"drawer"');
    let detailRequests = 0;
    let listPageContextHeader: string | null | undefined;
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
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
      if (request.url.endsWith('/crm.customer/query')) {
        listPageContextHeader = request.headers.get('X-MuYun-Page-Context');
        return Response.json({ records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(ModulePageHost, {
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
    expect(panel.props('ready')).toBe(false);

    panel.vm.$emit('select', { id: 'customer-1', title: '客户一' });
    await flushPromises();
    expect(detailRequests).toBe(0);

    panel.vm.$emit('rowDblclick', { id: 'customer-1', title: '客户一' });
    await flushPromises();
    expect(detailRequests).toBe(1);

    explorers[0].vm.$emit('select', { id: 'tenant-1', title: '甲租户' });
    await flushPromises();
    expect(panel.props('externalQueryValues')).toEqual({ tenantId: 'tenant-1' });
    expect(panel.props('ready')).toBe(false);
    expect(treeExplorer?.props('externalQueryValues')).toEqual({ tenantId: 'tenant-1' });

    treeExplorer?.vm.$emit('select', { id: 'organization-1', title: '总部' });
    await flushPromises();
    expect(panel.props('externalQueryValues')).toEqual({
      tenantId: 'tenant-1',
      organizationId: 'organization-1',
    });
    expect(panel.props('ready')).toBe(true);
    await panel.props('context').crud.query();
    expect(listPageContextHeader).toBe('{"tenant":"tenant-1","organization":"organization-1"}');

    explorers[0].vm.$emit('select', { id: 'tenant-2', title: '乙租户' });
    await flushPromises();
    expect(panel.props('externalQueryValues')).toEqual({ tenantId: 'tenant-2' });
    expect(panel.props('ready')).toBe(false);
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
      if (request.url.endsWith('/iam.organization/view/organization-1')) {
        return Response.json({ id: 'organization-1', title: '总部' });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(ModulePageHost, {
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

  it('selects the first declared navigator record without hiding a multi-record source', async () => {
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
                ],
                levels: [
                  {
                    key: 'tenant',
                    kind: 'MICRO_LIST',
                    sourceModuleAlias: 'iam.tenant',
                    title: '租户',
                    searchPlaceholder: '搜索租户',
                    initialSelectionPolicy: 'FIRST_RECORD',
                    management: {},
                  },
                ],
              },
            }),
          },
        });
      }
      if (request.url.endsWith('/platform.module/iam.tenant/reference-context')) {
        return Response.json({
          moduleAlias: 'iam.tenant',
          capabilities: [],
          actions: [
            { actionCode: 'create', authorized: true },
            { actionCode: 'update', authorized: true },
            { actionCode: 'delete', authorized: true },
          ],
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });

    const wrapper = shallowMount(ModulePageHost, {
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
          RecordExplorerPanel: { template: '<section><slot /><slot name="actions" /></section>' },
        },
      },
    });
    await flushPromises();

    const navigator = wrapper.findComponent({ name: 'CrudRecordListExplorer' });
    const list = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    navigator.vm.$emit('loaded', [
      { id: 'tenant-a', title: '甲租户' },
      { id: 'tenant-b', title: '乙租户' },
    ]);
    await flushPromises();

    expect(list.props('externalQueryValues')).toEqual({ tenantId: 'tenant-a' });
    expect(wrapper.findComponent({ name: 'CrudRecordListExplorer' }).exists()).toBe(true);
    const navigatorActions = wrapper.findAllComponents({ name: 'NavigatorPanelActions' });
    expect(navigatorActions).toHaveLength(1);
    expect(navigatorActions[0].props('context').moduleAlias).toBe('iam.tenant');
    expect(navigatorActions[0].props('createAvailable')).toBe(true);

    navigator.vm.$emit('deselect');
    await flushPromises();
    expect(list.props('externalQueryValues')).toBeUndefined();

    navigator.vm.$emit('loaded', [
      { id: 'tenant-a', title: '甲租户' },
      { id: 'tenant-b', title: '乙租户' },
    ]);
    await flushPromises();
    expect(list.props('externalQueryValues')).toBeUndefined();
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

    const wrapper = shallowMount(ModulePageHost, {
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

    const wrapper = shallowMount(ModulePageHost, {
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

    expect(wrapper.text()).toContain('FORM入口暂未接入模块页面运行器');
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

    const wrapper = shallowMount(ModulePageHost, {
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

    const wrapper = shallowMount(ModulePageHost, {
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

  it('applies a tree-management navigator selection to the primary tree query', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/iam.organization/context')) {
        return Response.json({
          moduleAlias: 'iam.organization',
          capabilities: ['TREE'],
          abilities: ['tree'],
          actions: [{ actionCode: 'create', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'iam.organization',
            page: page({
              template: 'TREE_MANAGEMENT',
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
      if (request.url.endsWith('/iam.organization/view/organization-1')) {
        return Response.json({ id: 'organization-1', title: '总部' });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({
      httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }),
    });

    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'iam.organization', pageMode: 'LIST' },
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
    expect(wrapper.find('.module-workspace').classes()).toContain('module-workspace--management');
    const tenantExplorer = wrapper.findComponent({ name: 'PageNavigatorExplorer' });
    expect(tenantExplorer).toBeDefined();
    expect(
      wrapper
        .findAllComponents({ name: 'TreeRecordExplorer' })
        .find((explorer) => explorer.props('context').moduleAlias === 'iam.organization'),
    ).toBeUndefined();

    tenantExplorer.vm.$emit('select', { id: 'tenant-1', title: '甲租户' });
    await flushPromises();
    const organizationTree = wrapper
      .findAllComponents({ name: 'TreeRecordExplorer' })
      .find((explorer) => explorer.props('context').moduleAlias === 'iam.organization');
    expect(organizationTree).toBeDefined();
    expect(organizationTree?.props('externalQueryValues')).toEqual({ tenantId: 'tenant-1' });

    organizationTree?.vm.$emit('select', { id: 'organization-1', title: '总部' });
    await flushPromises();
    expect(organizationTree?.props('selectedId')).toBe('organization-1');

    tenantExplorer.vm.$emit('select', { id: 'tenant-2', title: '乙租户' });
    await flushPromises();
    expect(organizationTree?.props('selectedId')).toBeUndefined();
    expect(organizationTree?.props('externalQueryValues')).toEqual({ tenantId: 'tenant-2' });
  });

  it('preselects a navigator only from its reference-authorized loaded records and consumes the entry once', async () => {
    const requests: Request[] = [];
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      requests.push(request);
      if (request.url.endsWith('/platform.module/demo.action/context')) {
        return Response.json({
          moduleAlias: 'demo.action',
          capabilities: [],
          actions: [],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'demo.action',
            page: page({
              navigator: {
                contextBindings: [],
                levels: [
                  {
                    key: 'module',
                    kind: 'MICRO_LIST',
                    sourceModuleAlias: 'platform.module',
                    title: '模块',
                    searchPlaceholder: '搜索模块',
                  },
                ],
              },
            }),
          },
        });
      }
      if (request.url.endsWith('/platform.module/platform.module/reference-context')) {
        return Response.json({ moduleAlias: 'platform.module', capabilities: [], actions: [] });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });

    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          tabPolicy: { identity: 'by-params' },
          target: { moduleAlias: 'demo.action', pageMode: 'LIST' },
          params: {
            _muyunNavigatorModuleAlias: 'platform.module',
            _muyunNavigatorRecordId: 'platform.application',
          },
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
    const navigator = wrapper
      .findAllComponents({ name: 'CrudRecordListExplorer' })
      .find((explorer) => explorer.props('context').moduleAlias === 'platform.module');
    expect(navigator).toBeDefined();
    navigator!.vm.$emit('loaded', [
      { id: 'platform.application', title: '平台应用' },
      { id: 'platform.module', title: '平台模块' },
    ]);
    await flushPromises();
    expect(navigator!.props('selectedId')).toBe('platform.application');

    await wrapper.setProps({
      descriptor: {
        ...wrapper.props('descriptor'),
        params: {
          _muyunNavigatorModuleAlias: 'platform.module',
          _muyunNavigatorRecordId: 'platform.module',
        },
      },
    });
    await flushPromises();
    expect(navigator!.props('selectedId')).toBeUndefined();
    expect(requests.some((request) => request.url.includes('/platform.module/view/'))).toBe(false);
  });

  it('does not let a delayed prior entry resolution overwrite a replacement entry', async () => {
    let resolveFirstEntryQuery: ((response: Response) => void) | undefined;
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      if (request.url.endsWith('/platform.module/demo.action/context')) {
        return Response.json({
          moduleAlias: 'demo.action',
          capabilities: [],
          actions: [],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'demo.action',
            page: page({
              navigator: {
                contextBindings: [],
                levels: [
                  {
                    key: 'module',
                    kind: 'MICRO_LIST',
                    sourceModuleAlias: 'platform.module',
                    title: '模块',
                    searchPlaceholder: '搜索模块',
                  },
                ],
              },
            }),
          },
        });
      }
      if (request.url.endsWith('/platform.module/platform.module/reference-context')) {
        return Response.json({ moduleAlias: 'platform.module', capabilities: [], actions: [] });
      }
      if (request.url.endsWith('/platform.module/navigator/reference/query')) {
        const body = (await request.clone().json()) as {
          conditions?: Array<{ values?: string[] }>;
        };
        if (body.conditions?.[0]?.values?.[0] === 'module-a') {
          return new Promise<Response>((resolve) => {
            resolveFirstEntryQuery = resolve;
          });
        }
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });

    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          tabPolicy: { identity: 'by-params' },
          target: { moduleAlias: 'demo.action', pageMode: 'LIST' },
          params: {
            _muyunNavigatorModuleAlias: 'platform.module',
            _muyunNavigatorRecordId: 'module-a',
          },
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
    const navigator = wrapper
      .findAllComponents({ name: 'CrudRecordListExplorer' })
      .find((explorer) => explorer.props('context').moduleAlias === 'platform.module');
    expect(navigator).toBeDefined();
    navigator!.vm.$emit('loaded', [{ id: 'other-module', title: '其他模块' }]);
    await flushPromises();
    expect(resolveFirstEntryQuery).toBeDefined();

    await wrapper.setProps({
      descriptor: {
        ...wrapper.props('descriptor'),
        params: {
          _muyunNavigatorModuleAlias: 'platform.module',
          _muyunNavigatorRecordId: 'module-b',
        },
      },
    });
    navigator!.vm.$emit('loaded', [{ id: 'module-b', title: '模块 B' }]);
    await flushPromises();
    expect(navigator!.props('selectedId')).toBe('module-b');

    resolveFirstEntryQuery!(
      Response.json({
        records: [{ id: 'module-a', title: '模块 A' }],
        total: 1,
        pageNum: 1,
        pageSize: 1,
        pages: 1,
        totalKnown: true,
      }),
    );
    await flushPromises();
    expect(navigator!.props('selectedId')).toBe('module-b');
  });

  it('ignores an entry navigator record absent from the authoritative reference result', async () => {
    const requests: Request[] = [];
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      requests.push(request);
      if (request.url.endsWith('/platform.module/demo.action/context')) {
        return Response.json({
          moduleAlias: 'demo.action',
          capabilities: [],
          actions: [],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'demo.action',
            page: page({
              navigator: {
                contextBindings: [],
                levels: [
                  {
                    key: 'module',
                    kind: 'MICRO_LIST',
                    sourceModuleAlias: 'platform.module',
                    title: '模块',
                    searchPlaceholder: '搜索模块',
                  },
                ],
              },
            }),
          },
        });
      }
      if (request.url.endsWith('/platform.module/platform.module/reference-context')) {
        return Response.json({ moduleAlias: 'platform.module', capabilities: [], actions: [] });
      }
      if (request.url.endsWith('/platform.module/navigator/reference/query')) {
        return Response.json({ records: [], total: 0, pageNum: 1, pageSize: 1, pages: 0, totalKnown: true });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });

    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          tabPolicy: { identity: 'by-params' },
          target: { moduleAlias: 'demo.action', pageMode: 'LIST' },
          params: {
            _muyunNavigatorModuleAlias: 'platform.module',
            _muyunNavigatorRecordId: 'not-authorized',
          },
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
    const navigator = wrapper
      .findAllComponents({ name: 'CrudRecordListExplorer' })
      .find((explorer) => explorer.props('context').moduleAlias === 'platform.module');
    expect(navigator).toBeDefined();
    navigator!.vm.$emit('loaded', [{ id: 'platform.application', title: '平台应用' }]);
    await flushPromises();
    expect(navigator!.props('selectedId')).toBeUndefined();
    const exactReferenceQuery = requests.find((request) =>
      request.url.endsWith('/platform.module/navigator/reference/query'),
    );
    expect(exactReferenceQuery).toBeDefined();
    expect(await exactReferenceQuery!.json()).toMatchObject({
      page: { pageNum: 1, pageSize: 1 },
      conditions: [{ fieldName: 'id', operator: 'EQ', values: ['not-authorized'] }],
      navigatorHostModuleAlias: 'demo.action',
      navigatorTargetLevelKey: 'module',
    });
  });

  it('does not open a manageable downstream navigator until its declared incoming scope is selected', async () => {
    const parentPickerQueryValues: Array<Record<string, unknown> | undefined> = [];
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      if (request.url.endsWith('/platform.module/demo.tree/context')) {
        return Response.json({
          moduleAlias: 'demo.tree',
          capabilities: ['TREE'],
          abilities: ['tree'],
          actions: [{ actionCode: 'create', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'demo.tree',
            page: page({
              template: 'TREE_MANAGEMENT',
              navigator: {
                contextBindings: [
                  {
                    source: 'NAVIGATOR',
                    sourceKey: 'tenant',
                    target: 'NAVIGATOR_QUERY',
                    targetKey: 'tenantId',
                    targetNavigatorLevelKey: 'category',
                  },
                ],
                levels: [
                  { key: 'tenant', kind: 'MICRO_LIST', sourceModuleAlias: 'iam.tenant', title: '租户' },
                  {
                    key: 'category',
                    kind: 'TREE',
                    sourceModuleAlias: 'iam.position_category',
                    title: '岗位分类',
                    management: { editorSurface: 'default_form' },
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
      if (request.url.endsWith('/platform.module/iam.position_category/reference-context')) {
        return Response.json({
          moduleAlias: 'iam.position_category',
          capabilities: ['TREE'],
          abilities: ['tree'],
          actions: [
            { actionCode: 'create', authorized: true },
            { actionCode: 'update', authorized: true },
            { actionCode: 'delete', authorized: true },
          ],
          uiDescriptor: {
            moduleAlias: 'iam.position_category',
            editorSurfaces: [
              {
                key: 'default_form',
                editor: {
                  viewCode: 'default_form',
                  viewKind: 'FORM',
                  fields: [
                    {
                      fieldRef: { fieldName: 'parentId' },
                      label: '上级分类',
                      visible: { constant: true },
                      required: { constant: false },
                      readOnly: { constant: false },
                    },
                  ],
                },
              },
            ],
          },
        });
      }
      if (request.url.endsWith('/iam.position_category/navigator/reference/tree/query')) {
        const body = (await request.json()) as { externalQueryValues?: Record<string, unknown> };
        parentPickerQueryValues.push(body.externalQueryValues);
        return Response.json({
          records: [{ record: { id: 'category-1', title: '技术岗位' }, children: [] }],
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModulePageHost, {
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
          PageNavigatorExplorer: {
            name: 'PageNavigatorExplorer',
            props: ['level', 'ready', 'createDisabled', 'createDisabledReason'],
            emits: ['create', 'select'],
            template: '<section><slot name="editor" /></section>',
          },
        },
      },
    });
    await flushPromises();

    const explorers = wrapper.findAllComponents({ name: 'PageNavigatorExplorer' });
    const tenant = explorers.find((explorer) => explorer.props('level').descriptor.key === 'tenant');
    const category = explorers.find((explorer) => explorer.props('level').descriptor.key === 'category');
    expect(category?.props('createDisabled')).toBe(true);
    expect(category?.props('ready')).toBe(false);
    expect(category?.props('createDisabledReason')).toBe('请先完成上游范围选择');

    category?.vm.$emit('create');
    await flushPromises();
    const categoryEditor = () =>
      wrapper
        .findAllComponents({ name: 'NavigatorManagementEditor' })
        .find((editor) => editor.props('context').moduleAlias === 'iam.position_category');
    expect(categoryEditor()?.props('open')).toBe(false);

    tenant?.vm.$emit('select', { id: 'tenant-1', title: '甲租户' });
    await flushPromises();
    expect(category?.props('createDisabled')).toBe(false);
    expect(category?.props('ready')).toBe(true);
    category?.vm.$emit('create');
    await flushPromises();
    expect(categoryEditor()?.props('open')).toBe(true);
    const parentPicker = (
      categoryEditor()?.props('pickerConfigs') as {
        parentId?: { loadTree?: () => Promise<unknown> };
      }
    ).parentId;
    await parentPicker?.loadTree?.();
    expect(parentPickerQueryValues).toContainEqual({ tenantId: 'tenant-1' });

    tenant?.vm.$emit('select', { id: 'tenant-2', title: '乙租户' });
    await flushPromises();
    expect(categoryEditor()?.props('open')).toBe(false);
  });

  it('routes a navigator-scoped main tree through its declared resource endpoint', async () => {
    const requestedPaths: string[] = [];
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      const path = new URL(request.url).pathname;
      requestedPaths.push(path);
      if (path === '/platform.module/platform.dictionary_category/context') {
        return Response.json({
          moduleAlias: 'platform.dictionary_category',
          capabilities: ['TREE'],
          sortPartitionFields: ['applicationAlias'],
          actions: [
            'item_create',
            'item_view',
            'item_update',
            'item_delete',
            'item_query',
            'item_tree',
            'item_sort',
          ].map((actionCode) => ({ actionCode, authorized: true })),
          uiDescriptor: {
            schemaVersion: 'module-ui.v6',
            moduleAlias: 'platform.dictionary_category',
            page: page({
              template: 'TREE_MANAGEMENT',
              navigator: {
                levels: [
                  {
                    key: 'category',
                    sourceModuleAlias: 'platform.dictionary_category',
                    kind: 'TREE',
                    title: '字典类目',
                  },
                ],
                contextBindings: [],
              },
              treeResource: {
                resource: 'item',
                scopeNavigatorKey: 'category',
                scopeField: 'categoryId',
                scopeRecordField: 'categoryKind',
                scopeRecordEquals: 'dictionary',
                title: '字典项',
                emptyDescription: '暂无字典项',
                createTitle: '新建字典项',
                sortPartitionFields: ['categoryId'],
              },
            }),
            editorContributions: [childEditor('item', 'title')],
          },
        });
      }
      if (path === '/platform.module/platform.dictionary_category/reference-context') {
        return Response.json({
          moduleAlias: 'platform.dictionary_category',
          capabilities: ['TREE'],
          actions: [],
        });
      }
      if (path === '/platform.dictionary_category/tree-resources/item/category-1/tree') {
        return Response.json({ records: [] });
      }
      if (path === '/platform.dictionary_category/actions/item-1') {
        return Response.json({
          recordId: 'item-1',
          actions: [
            { actionCode: 'view', available: false, reason: 'category action must not govern an item' },
            { actionCode: 'item_view', available: true },
            { actionCode: 'item_update', available: true },
          ],
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-target' },
          target: { moduleAlias: 'platform.dictionary_category', pageMode: 'LIST' },
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

    expect(
      wrapper
        .findAllComponents({ name: 'TreeRecordExplorer' })
        .find((explorer) => explorer.props('context').moduleAlias === 'platform.dictionary_category'),
    ).toBeUndefined();
    const categoryNavigator = wrapper.findComponent({ name: 'PageNavigatorExplorer' });
    categoryNavigator.vm.$emit('select', { id: 'folder-1', title: '目录', categoryKind: 'folder' });
    await flushPromises();
    expect(
      wrapper
        .findAllComponents({ name: 'TreeRecordExplorer' })
        .find((explorer) => explorer.props('context').moduleAlias === 'platform.dictionary_category'),
    ).toBeUndefined();
    expect(requestedPaths).not.toContain('/platform.dictionary_category/tree-resources/item/folder-1/tree');

    categoryNavigator.vm.$emit('select', { id: 'category-1', title: '时区', categoryKind: 'dictionary' });
    await flushPromises();

    const tree = wrapper
      .findAllComponents({ name: 'TreeRecordExplorer' })
      .find((explorer) => explorer.props('context').moduleAlias === 'platform.dictionary_category');
    expect(tree).toBeDefined();
    expect(tree!.props('sortPartitionFields')).toEqual(['categoryId']);
    await (
      tree!.props('context') as { abilities: { tree: () => { tree: () => Promise<unknown> } } }
    ).abilities
      .tree()
      .tree();
    const resourceActions = await (
      tree!.props('context') as {
        recordActions: (
          recordId: string,
        ) => Promise<{ actions: Array<{ actionCode: string; available: boolean }> }>;
      }
    ).recordActions('item-1');
    expect(requestedPaths).toContain('/platform.dictionary_category/tree-resources/item/category-1/tree');
    expect(requestedPaths).not.toContain('/platform.dictionary_category/tree');
    expect(resourceActions.actions).toEqual([
      { actionCode: 'view', available: true },
      { actionCode: 'update', available: true },
    ]);
    const firstScopeReloadKey = tree!.props('reloadKey') as number;
    categoryNavigator.vm.$emit('select', { id: 'category-2', title: '语言', categoryKind: 'dictionary' });
    await flushPromises();
    expect(tree!.props('reloadKey')).toBe(firstScopeReloadKey + 1);
    // The host is a navigator, not a tree module itself. TREE_MANAGEMENT still
    // owns a persistent right-side detail card for its tree resource.
    expect(wrapper.findComponent({ name: 'RecordModeDrawer' }).exists()).toBe(false);
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

    const wrapper = shallowMount(ModulePageHost, {
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

  it('mounts one navigator extension beside the standard list without exposing its CRUD context', async () => {
    const ScopeTree = {
      name: 'ScopeTree',
      props: ['context'],
      template: '<aside>范围树</aside>',
    };
    const requestHeaders: Headers[] = [];
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      requestHeaders.push(request.headers);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [{ actionCode: 'create', authorized: true }],
          uiDescriptor: { schemaVersion: '1', moduleAlias: 'crm.customer', page: page() },
        });
      }
      if (request.url.endsWith('/platform.menu/customer-menu/entry?clientType=WEB')) {
        return Response.json({
          entry: { menuId: 'customer-menu', moduleAlias: 'crm.customer', pageMode: 'LIST' },
          clientType: 'WEB',
          mainEntityAlias: 'customer',
          resolvedConfig: { uiFields: [], queryItems: [], actionBlocks: [] },
          openApiPath: '/crm.customer/openapi',
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    configureModulePageEnhancements([
      {
        id: 'customer-scope-tree',
        target: { moduleAlias: 'crm.customer' },
        navigator: {
          extension: {
            key: 'customer-scope-tree',
            component: ScopeTree,
            selection: { kind: 'customerScope', initialKey: () => 'platform' },
          },
        },
      },
    ]);

    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          menuId: 'customer-menu',
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /></section>' },
          ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
          RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
        },
      },
    });
    await flushPromises();

    const extension = wrapper.findComponent({ name: 'ScopeTree' });
    expect(extension.exists()).toBe(true);
    expect(extension.props('context')).toEqual(
      expect.objectContaining({
        moduleAlias: 'crm.customer',
        selectionKey: 'platform',
        selectSelectionKey: expect.any(Function),
        refreshList: expect.any(Function),
        reload: expect.any(Function),
      }),
    );
    expect(extension.props('context')).not.toHaveProperty('module');
    expect(
      requestHeaders.some(
        (headers) => headers.get('X-MuYun-Page-Selection') === '{"kind":"customerScope","key":"platform"}',
      ),
    ).toBe(true);
  });

  it('applies signed form-compute rules through the host draft coordinator after a field edit', async () => {
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/demo.invoice/context')) {
        return Response.json({
          moduleAlias: 'demo.invoice',
          capabilities: [],
          actions: [{ actionCode: 'create', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'demo.invoice',
            page: page({
              detail: {
                emptyDescription: '请选择记录',
                createTitle: '新建记录',
                editor: {
                  viewCode: 'default_form',
                  viewKind: 'FORM',
                  fields: [{ fieldRef: { fieldName: 'quantity' } }, { fieldRef: { fieldName: 'amount' } }],
                  formComputeRules: [
                    {
                      code: 'amount-from-quantity',
                      targetField: 'amount',
                      targetValueType: 'DECIMAL',
                      triggerFields: ['quantity'],
                      writePolicy: 'ALWAYS',
                      program: {
                        schemaVersion: 1,
                        profile: 'FORM_COMPUTE',
                        referencedFields: ['quantity'],
                        root: {
                          kind: 'ASSIGN',
                          operator: '=',
                          arguments: [
                            { kind: 'FIELD', field: 'amount', arguments: [] },
                            {
                              kind: 'BINARY',
                              operator: '*',
                              arguments: [
                                { kind: 'FIELD', field: 'quantity', arguments: [] },
                                { kind: 'VALUE', value: 10, arguments: [] },
                              ],
                            },
                          ],
                        },
                      },
                    },
                  ],
                },
              },
            }),
          },
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'dynamic-module-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'demo.invoice', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /><slot name="detail" /></section>' },
          RecordDetailPanel: { template: '<section><slot name="actions" /><slot /></section>' },
          ModulePageRecordContent: false,
        },
      },
    });
    await flushPromises();

    wrapper.findComponent({ name: 'RecordQueryListPanel' }).vm.$emit('action', { key: 'create' });
    await flushPromises();
    const content = wrapper.findComponent({ name: 'ModulePageRecordContent' });
    content.vm.$emit('update:field', 'quantity', 4);
    await flushPromises();

    expect(content.props('record')).toMatchObject({ quantity: 4, amount: 40 });
  });

  it('renders a source-owned card assistant with a reactive read-only record snapshot', async () => {
    const Assistant = defineComponent({
      name: 'TestCardAssistant',
      props: { context: { type: Object, required: true } },
      template: '<aside>assistant</aside>',
    });
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          capabilities: [],
          actions: [
            { actionCode: 'create', authorized: true },
            { actionCode: 'update', authorized: true },
          ],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'crm.customer',
            page: page({ template: 'FLAT_MANAGEMENT' }),
          },
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    configureModulePageEnhancements([
      {
        id: 'customer-card-assistant',
        target: { moduleAlias: 'crm.customer' },
        card: { assistant: { component: Assistant, placement: { boundary: 'inside', position: 'bottom' } } },
      },
    ]);
    const wrapper = shallowMount(ModulePageHost, {
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
          StaticManagementLayout: {
            template:
              '<section><slot name="explorer-actions" /><slot name="explorer" /><slot name="detail-actions" /><slot name="detail-content-top" /><slot /><slot name="detail-content-bottom" /></section>',
          },
        },
      },
    });
    await flushPromises();

    wrapper.findComponent({ name: 'ModuleActionButton' }).vm.$emit('click');
    await flushPromises();
    const content = wrapper.findComponent({ name: 'ModulePageRecordContent' });
    const assistant = wrapper.findComponent(Assistant);
    expect(assistant.exists()).toBe(true);
    expect(assistant.props('context')).toMatchObject({ mode: 'create', record: {} });

    content.vm.$emit('update:field', 'title', '新客户');
    await flushPromises();
    const updatedContext = wrapper.findComponent(Assistant).props('context') as {
      record: Record<string, unknown>;
    };
    expect(updatedContext.record).toMatchObject({ title: '新客户' });
    expect(Object.isFrozen(updatedContext.record)).toBe(true);
    expect(Reflect.set(updatedContext.record, 'title', '越界修改')).toBe(false);
    expect(content.props('record')).toMatchObject({ title: '新客户' });
  });

  it('loads and saves embedded child relations through the parent standard CRUD contract', async () => {
    const requestedPaths: string[] = [];
    let updatePayload: Record<string, unknown> | undefined;
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      requestedPaths.push(new URL(request.url).pathname);
      if (request.url.endsWith('/platform.module/platform.field_ui_control/context')) {
        return Response.json({
          moduleAlias: 'platform.field_ui_control',
          moduleKind: 'STATIC',
          capabilities: ['RECYCLE_BIN'],
          actions: ['create', 'update', 'recycleBinQuery'].map((actionCode) => ({
            actionCode,
            authorized: true,
          })),
          uiDescriptor: {
            schemaVersion: 'module-ui.v6',
            moduleAlias: 'platform.field_ui_control',
            page: page({ template: 'FLAT_MANAGEMENT' }),
            editorContributions: [
              childEditor('field_ui_control_property', 'attributeAlias'),
              childEditor('field_ui_control_binding', 'valueKey'),
            ],
            detailRelations: [
              embeddedRelation('properties', 'field_ui_control_property', 'attributeAlias'),
              {
                ...embeddedRelation('bindings', 'field_ui_control_binding', 'valueKey'),
                visible: {
                  formula: {
                    expression: "{valueShape} == 'COMPOSITE'",
                    program: {
                      schemaVersion: 1,
                      profile: 'WEB_UI',
                      referencedFields: ['valueShape'],
                      root: {
                        kind: 'BINARY',
                        operator: '==',
                        arguments: [
                          { kind: 'FIELD', field: 'valueShape', arguments: [] },
                          { kind: 'VALUE', value: 'COMPOSITE', arguments: [] },
                        ],
                      },
                    },
                  },
                },
              },
            ],
          },
        });
      }
      if (request.url.endsWith('/platform.field_ui_control/view/select')) {
        return Response.json({
          id: 'select',
          alias: 'select',
          title: '下拉',
          valueShape: 'COMPOSITE',
          version: 1,
          properties: [{ id: 'property-1', attributeAlias: 'placeholder', version: 1 }],
          bindings: [{ id: 'binding-1', valueKey: 'options', version: 1 }],
        });
      }
      if (request.url.endsWith('/platform.field_ui_control/recycle-bin/view/deleted-select')) {
        return Response.json({
          id: 'deleted-select',
          alias: 'deleted-select',
          title: '已删除下拉',
          valueShape: 'COMPOSITE',
          version: 2,
        });
      }
      if (new URL(request.url).pathname.endsWith('/platform.field_ui_control/actions/select')) {
        return Response.json({
          recordId: 'select',
          actions: [
            { actionCode: 'update', available: true },
            { actionCode: 'delete', available: true },
          ],
        });
      }
      if (new URL(request.url).pathname.endsWith('/platform.field_ui_control/update/select')) {
        updatePayload = (await request.clone().json()) as Record<string, unknown>;
        return Response.json(updatePayload);
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          tabPolicy: { identity: 'by-target' },
          target: { moduleAlias: 'platform.field_ui_control', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          StaticManagementLayout: {
            template:
              '<section><slot name="explorer-actions" /><slot name="explorer" /><slot name="explorer-footer" /><slot name="detail-actions" /><slot /></section>',
          },
          ModulePageDetailRelations: false,
          ModulePageRecordContent: false,
          ManagedDetailRelationSurface: false,
          RecordDetailExtensionSection: { template: '<section><slot /></section>' },
          RecordQueryListPanel: {
            name: 'RecordQueryListPanel',
            props: ['context'],
            template:
              '<section><slot name="toolbarActions" /><slot name="rowActions" :record="{ id: \'row-1\', version: 1 }" /></section>',
          },
          UiModal: { name: 'UiModal', props: ['open'], template: '<section><slot v-if="open" /></section>' },
        },
      },
    });
    await flushPromises();
    wrapper.findComponent({ name: 'CrudRecordListExplorer' }).vm.$emit('select', { id: 'select' });
    await flushPromises();

    const relations = wrapper.findComponent({ name: 'ModulePageDetailRelations' });
    expect(relations.exists()).toBe(true);
    expect(relations.props('relations')).toMatchObject([{ code: 'properties' }, { code: 'bindings' }]);
    expect(relations.props('parentRecord')).toMatchObject({ id: 'select', alias: 'select' });

    expect(relations.props('mutationEnabled')).toBe(false);
    expect(wrapper.findAllComponents({ name: 'ManagedDetailRelationInlineSurface' })).toHaveLength(2);

    const createButton = wrapper
      .findAllComponents({ name: 'ModuleActionButton' })
      .find((button) => button.props('actionCode') === 'create');
    expect(createButton).toBeDefined();
    createButton!.vm.$emit('click');
    await flushPromises();

    const createRelations = wrapper.findComponent({ name: 'ModulePageDetailRelations' });
    expect(createRelations.props('mutationEnabled')).toBe(true);
    expect(createRelations.props('parentRecord')).not.toHaveProperty('id');

    wrapper.findComponent({ name: 'RecordActionBar' }).vm.$emit('action', { key: 'cancel' });
    await flushPromises();

    wrapper.findComponent({ name: 'CrudRecordListExplorer' }).vm.$emit('select', { id: 'select' });
    await flushPromises();

    wrapper.findComponent({ name: 'RecordActionBar' }).vm.$emit('action', { key: 'edit' });
    await flushPromises();

    const editableRelations = wrapper.findComponent({ name: 'ModulePageDetailRelations' });
    expect(editableRelations.props('mutationEnabled')).toBe(true);
    editableRelations.vm.$emit('children-change', 'properties', [
      { id: 'property-1', attributeAlias: 'rows', version: 1 },
    ]);
    editableRelations.vm.$emit('children-change', 'bindings', []);
    await flushPromises();
    wrapper.findComponent({ name: 'RecordActionBar' }).vm.$emit('action', { key: 'save' });
    await flushPromises();
    await vi.waitFor(() => expect(updatePayload).toBeDefined());

    const updateRequest = requestedPaths.filter((path) => path.endsWith('/update/select'));
    expect(updateRequest).toHaveLength(1);
    expect(updatePayload).toMatchObject({
      properties: [{ id: 'property-1', attributeAlias: 'rows', version: 1 }],
      bindings: [],
    });
    await vi.waitFor(() =>
      expect(wrapper.findComponent({ name: 'RecordActionBar' }).props('actions')).toEqual(
        expect.arrayContaining([expect.objectContaining({ key: 'edit', disabled: false })]),
      ),
    );
    expect(wrapper.findComponent({ name: 'RecordFormSurface' }).exists()).toBe(false);
    expect(requestedPaths).toContain('/platform.field_ui_control/actions/select');
    expect(requestedPaths.some((path) => path.includes('/relations/'))).toBe(false);

    wrapper.findComponent({ name: 'RecycleBinModeButton' }).vm.$emit('click');
    await flushPromises();
    wrapper.findComponent({ name: 'CrudRecordListExplorer' }).vm.$emit('select', { id: 'deleted-select' });
    await flushPromises();

    expect(wrapper.findComponent({ name: 'ModulePageDetailRelations' }).exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'ManagedDetailRelationInlineSurface' }).exists()).toBe(false);
    expect(requestedPaths.some((path) => path.includes('/relations/'))).toBe(false);
  });

  it('unmounts managed relations when a retained record drawer closes', async () => {
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      width: 700,
      height: 600,
      top: 0,
      left: 0,
      right: 700,
      bottom: 600,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    });
    let relationRequests = 0;
    globalThis.fetch = async (input) => {
      const request = new Request(input);
      if (request.url.endsWith('/platform.module/crm.customer/context')) {
        return Response.json({
          moduleAlias: 'crm.customer',
          moduleKind: 'STATIC',
          capabilities: [],
          actions: [
            { actionCode: 'view', authorized: true },
            { actionCode: 'child_query', authorized: true },
          ],
          uiDescriptor: {
            schemaVersion: 'module-ui.v6',
            moduleAlias: 'crm.customer',
            page: page(),
            editorContributions: [childEditor('child', 'title')],
            detailRelations: [
              {
                ...managedRelation('children', 'child', 'title'),
                sourceModuleAlias: 'crm.customer',
                sourceEntityAlias: 'customer',
                targetModuleAlias: 'crm.customer',
                queryContract: {
                  ...managedRelation('children', 'child', 'title').queryContract,
                  actionCode: 'child_query',
                },
                mutationContract: undefined,
                readOnly: true,
              },
            ],
          },
        });
      }
      if (request.url.endsWith('/crm.customer/actions/customer-1')) {
        return Response.json({
          recordId: 'customer-1',
          actions: [{ actionCode: 'view', available: true }],
        });
      }
      if (request.url.endsWith('/crm.customer/view/customer-1')) {
        return Response.json({ id: 'customer-1', title: '客户一号', version: 1 });
      }
      if (request.url.endsWith('/crm.customer/view/customer-1/relations/children/query')) {
        relationRequests += 1;
        return Response.json({ records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          tabPolicy: { identity: 'by-target' },
          target: { moduleAlias: 'crm.customer', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /></section>' },
          RecordModeDrawer: {
            name: 'RecordModeDrawer',
            props: ['open'],
            emits: ['close'],
            template: '<aside v-if="open"><slot name="view" /></aside>',
          },
          ModulePageDetailRelations: false,
          ManagedDetailRelationSurface: false,
          RecordDetailExtensionSection: { template: '<section><slot /></section>' },
        },
      },
    });
    await flushPromises();

    wrapper.findComponent({ name: 'RecordQueryListPanel' }).vm.$emit('rowDblclick', { id: 'customer-1' });
    await flushPromises();
    const drawer = wrapper.findComponent({ name: 'RecordModeDrawer' });
    expect(drawer.props('open')).toBe(true);
    expect(wrapper.findComponent({ name: 'ModulePageRecordContent' }).exists()).toBe(true);

    drawer.vm.$emit('close');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'ModulePageRecordContent' }).exists()).toBe(false);
    expect(relationRequests).toBe(0);
    rect.mockRestore();
  });

  it('keeps menu identity on the host module and excludes it from navigator source contexts', async () => {
    const requests: Request[] = [];
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      requests.push(request);
      if (request.url.endsWith('/platform.module/platform.module/context')) {
        return Response.json({
          moduleAlias: 'platform.module',
          capabilities: [],
          actions: [],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'platform.module',
            page: page({
              navigator: {
                levels: [
                  {
                    key: 'application',
                    kind: 'LIST',
                    sourceModuleAlias: 'platform.application',
                    title: '应用',
                  },
                ],
                contextBindings: [],
              },
            }),
          },
        });
      }
      if (request.url.endsWith('/platform.module/platform.application/reference-context')) {
        return Response.json({
          moduleAlias: 'platform.application',
          capabilities: [],
          actions: [],
        });
      }
      if (request.url.endsWith('/platform.module/query')) {
        return Response.json({ records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true });
      }
      if (request.url.endsWith('/platform.menu/platform.menu.module.platform.module/entry?clientType=WEB')) {
        return Response.json({
          entry: {
            menuId: 'platform.menu.module.platform.module',
            moduleAlias: 'platform.module',
            pageMode: 'LIST',
          },
          clientType: 'WEB',
          mainEntityAlias: 'module',
          resolvedConfig: { uiFields: [], queryItems: [], actionBlocks: [] },
          openApiPath: '/platform.module/openapi',
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });

    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          tabPolicy: { identity: 'by-menu' },
          menuId: 'platform.menu.module.platform.module',
          target: { moduleAlias: 'platform.module', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /></section>' },
          ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
        },
      },
    });
    await flushPromises();
    await wrapper.findComponent({ name: 'RecordQueryListPanel' }).props('context').crud.query();

    const hostRequest = requests.find((request) => request.url.endsWith('/platform.module/query'));
    const navigatorRequest = requests.find((request) =>
      request.url.endsWith('/platform.module/platform.application/reference-context'),
    );
    expect(hostRequest?.headers.get('X-MuYun-Menu-Id')).toBe('platform.menu.module.platform.module');
    expect(navigatorRequest?.headers.get('X-MuYun-Menu-Id')).toBeNull();
  });

  it('keeps source-field resolution scoped while ordinary reference pickers remain neutral', async () => {
    const requests: Request[] = [];
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      requests.push(request);
      if (request.url.endsWith('/platform.module/platform.module/context')) {
        return Response.json({
          moduleAlias: 'platform.module',
          capabilities: [],
          actions: [{ actionCode: 'create', authorized: true }],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'platform.module',
            page: page({
              detail: {
                emptyDescription: '请选择模块',
                createTitle: '新建模块',
                editor: {
                  viewCode: 'default_form',
                  viewKind: 'FORM',
                  fields: [
                    {
                      fieldRef: { fieldName: 'applicationAlias' },
                      reference: { targetModuleAlias: 'platform.application', cardinality: 'ONE' },
                    },
                    {
                      fieldRef: { fieldName: 'ownerId' },
                      reference: {
                        targetModuleAlias: 'iam.user',
                        cardinality: 'ONE',
                        candidateDelivery: 'SOURCE_FIELD',
                      },
                    },
                  ],
                },
              },
            }),
          },
        });
      }
      if (request.url.endsWith('/platform.menu/platform.menu.module.platform.module/entry?clientType=WEB')) {
        return Response.json({
          entry: {
            menuId: 'platform.menu.module.platform.module',
            moduleAlias: 'platform.module',
            pageMode: 'LIST',
          },
          clientType: 'WEB',
          mainEntityAlias: 'module',
          resolvedConfig: { uiFields: [], queryItems: [], actionBlocks: [] },
          openApiPath: '/platform.module/openapi',
        });
      }
      if (request.url.endsWith('/platform.module/platform.application/reference-context')) {
        return Response.json({
          moduleAlias: 'platform.application',
          capabilities: [],
          actions: [],
        });
      }
      if (request.url.endsWith('/platform.application/navigator/reference/query')) {
        return Response.json({ records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0, totalKnown: true });
      }
      if (request.url.endsWith('/platform.module/references/ownerId/resolve')) {
        return Response.json({
          status: 'OK',
          mode: 'QUERY',
          options: [],
          results: [],
          offset: 0,
          limit: 50,
          total: 0,
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    };
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });

    const wrapper = shallowMount(ModulePageHost, {
      props: {
        descriptor: {
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          tabPolicy: { identity: 'by-menu' },
          menuId: 'platform.menu.module.platform.module',
          target: { moduleAlias: 'platform.module', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          ManagementWorkspace: { template: '<section><slot /></section>' },
          ManagementExplorerColumn: { template: '<aside><slot /></aside>' },
          RecordDetailPanel: { template: '<section><slot /><slot name="actions" /></section>' },
          RecordModeDrawer: { template: '<section><slot name="form" /></section>' },
          ModulePageRecordContent: false,
        },
      },
    });
    await flushPromises();
    wrapper.findComponent({ name: 'RecordQueryListPanel' }).vm.$emit('action', { key: 'create' });
    await flushPromises();

    const pickerConfigs = wrapper
      .findComponent({ name: 'ModulePageRecordContent' })
      .props('pickerConfigs') as {
      applicationAlias: {
        context: { runtime: { ready: Promise<unknown> }; crud: { query: () => Promise<unknown> } };
      };
      ownerId: { loadOptions: (keyword: string) => Promise<unknown> };
    };
    await pickerConfigs.applicationAlias.context.runtime.ready;
    await pickerConfigs.applicationAlias.context.crud.query();
    await pickerConfigs.ownerId.loadOptions('admin');

    const normalRuntimeRequest = requests.find((request) =>
      request.url.endsWith('/platform.module/platform.application/reference-context'),
    );
    const normalQueryRequest = requests.find((request) =>
      request.url.endsWith('/platform.application/navigator/reference/query'),
    );
    const sourceResolverRequest = requests.find((request) =>
      request.url.endsWith('/platform.module/references/ownerId/resolve'),
    );
    expect(normalRuntimeRequest?.headers.get('X-MuYun-Menu-Id')).toBeNull();
    expect(normalQueryRequest?.headers.get('X-MuYun-Menu-Id')).toBeNull();
    expect(sourceResolverRequest?.headers.get('X-MuYun-Menu-Id')).toBe(
      'platform.menu.module.platform.module',
    );
  });
});

function childEditor(resource: string, fieldName: string) {
  return {
    resource,
    editor: {
      viewCode: `${resource}-editor`,
      viewKind: 'FORM',
      fields: [
        {
          fieldRef: { relationCode: resource, fieldName },
          label: fieldName,
          visible: { constant: true },
          required: { constant: true },
          readOnly: { constant: false },
        },
      ],
    },
  };
}

function managedRelation(code: string, resource: string, fieldName: string) {
  return {
    code,
    title: code,
    readOnly: false,
    sourceModuleAlias: 'platform.field_ui_control',
    sourceEntityAlias: 'field_ui_control',
    targetModuleAlias: 'platform.field_ui_control',
    targetEntityAlias: resource,
    parentBinding: 'fieldUiControlAlias',
    refreshOnDetailReload: true,
    queryContract: {
      managedGateway: true,
      actionCode: `${resource}_query`,
      pageable: true,
      queryable: false,
      querySchema: {
        scopeName: resource,
        quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
        fields: [],
        externalCriteria: [],
        defaultSorts: [],
      },
      listProjection: { fields: [{ fieldName, title: fieldName }] },
    },
    mutationContract: {
      createAllowed: true,
      updateAllowed: true,
      deleteAllowed: true,
      createActionCode: `${resource}_create`,
      updateActionCode: `${resource}_update`,
      deleteActionCode: `${resource}_delete`,
    },
  };
}

function embeddedRelation(code: string, resource: string, fieldName: string) {
  return {
    code,
    title: code,
    readOnly: false,
    sourceModuleAlias: 'platform.field_ui_control',
    sourceEntityAlias: 'field_ui_control',
    targetModuleAlias: 'platform.field_ui_control',
    targetEntityAlias: resource,
    parentBinding: 'fieldUiControlAlias',
    embeddedField: code,
    refreshOnDetailReload: false,
    visible: { constant: true },
    listProjection: { fields: [{ fieldName, title: fieldName }] },
    editing: { mode: 'INLINE', saveMode: 'AGGREGATE_DRAFT' },
  };
}

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
