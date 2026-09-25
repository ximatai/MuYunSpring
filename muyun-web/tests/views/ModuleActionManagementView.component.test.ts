import { flushPromises, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { configureModuleContext, type HttpRequestOptions } from '@/web-core';
import ModuleActionManagementView from '@/views/ModuleActionManagementView.vue';

it.each(['static', 'dynamic'] as const)(
  'creates an unbound %s action after selecting a managed action',
  async (moduleKind) => {
    configureModuleContext({
      http: {
        request: async <T>(request: HttpRequestOptions) => {
          if (request.path.endsWith('/context'))
            return {
              moduleAlias: 'platform.module_action',
              capabilities: [],
              actions: ['create', 'update', 'query'].map((actionCode) => ({
                actionCode,
                authorized: true,
                actionLevel: 'ANY',
              })),
            } as T;
          if (request.path.endsWith('/action-executors')) return [] as T;
          return { records: [], total: 0 } as T;
        },
      },
    });
    const wrapper = mount(ModuleActionManagementView, {
      props: { moduleAlias: 'sales.entry', moduleKind },
      global: {
        stubs: {
          StaticManagementLayout: {
            template:
              '<div><slot name="explorer-actions"/><slot name="explorer"/><slot name="detail-actions"/><slot/></div>',
          },
          CrudRecordListExplorer: { name: 'CrudRecordListExplorer', template: '<div/>' },
          RecordMetaSection: true,
        },
      },
    });
    await flushPromises();
    wrapper
      .findComponent({ name: 'CrudRecordListExplorer' })
      .vm.$emit('loaded', [{ id: 'menu', actionCode: 'menu', title: '菜单访问', systemManaged: true }]);
    await flushPromises();
    const create = wrapper.get('button[title="新建动作"]');
    expect(create.attributes('disabled')).toBeUndefined();
    await create.trigger('click');
    expect(wrapper.find('.managed-action-editor').exists()).toBe(false);
    const form = wrapper.get('.static-record-form');
    expect(form.text()).toContain('可以先保存动作');
    expect(form.findAll('input').some((input) => !input.attributes('disabled'))).toBe(true);
    expect(form.text()).toContain(moduleKind === 'dynamic' ? '待绑定' : '静态模块通过同编码的代码动作声明');
    wrapper.unmount();
  },
);

it.each([true, false])(
  'gates action sorting by permission (%s), search and editing, and persists scoped drops',
  async (authorized) => {
    const requests: HttpRequestOptions[] = [];
    const records = [
      { id: 'menu', moduleAlias: 'sales.entry', actionCode: 'menu', title: '菜单访问', systemManaged: true },
      { id: 'view', moduleAlias: 'sales.entry', actionCode: 'view', title: '查看', systemManaged: true },
    ];
    configureModuleContext({
      http: {
        request: async <T>(request: HttpRequestOptions) => {
          requests.push(request);
          if (request.path.endsWith('/context'))
            return {
              moduleAlias: 'platform.module_action',
              capabilities: ['CRUD', 'SORT'],
              sortPartitionFields: ['moduleAlias'],
              actions: ['create', 'update', 'query', 'sort'].map((actionCode) => ({
                actionCode,
                authorized: actionCode === 'sort' ? authorized : true,
                actionLevel: 'ANY',
              })),
            } as T;
          if (request.path.endsWith('/action-executors')) return [] as T;
          if (request.path.includes('/sort/')) return 1 as T;
          if (request.path.endsWith('/query-schema'))
            return {
              scopeName: 'actions',
              fields: [],
              externalCriteria: [],
              defaultSorts: [],
              quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
            } as T;
          return { records, total: 2, pages: 1, totalKnown: true } as T;
        },
      },
    });
    const wrapper = mount(ModuleActionManagementView, {
      props: { moduleAlias: 'sales.entry', moduleKind: 'dynamic' },
      global: {
        stubs: {
          StaticManagementLayout: {
            name: 'StaticManagementLayout',
            template:
              '<div><slot name="explorer-actions"/><slot name="explorer"/><slot name="detail-actions"/><slot/></div>',
          },
          RecordListExplorer: { name: 'RecordListExplorer', template: '<div/>' },
          RecordMetaSection: true,
        },
      },
    });
    await flushPromises();
    const toggle = wrapper.find('button[aria-label="调整排序"]');
    expect(toggle.exists()).toBe(authorized);
    const explorer = wrapper.findComponent({ name: 'CrudRecordListExplorer' });
    expect(explorer.props('sorting')).toBe(false);
    if (authorized) {
      await toggle.trigger('click');
      expect(explorer.props('sorting')).toBe(true);
      wrapper.findComponent({ name: 'RecordListExplorer' }).vm.$emit('sort', {
        dragRecord: records[0],
        dropRecord: records[1],
        position: 1,
      });
      await flushPromises();
      expect(requests).toContainEqual({
        method: 'POST',
        path: '/platform.module/sales.entry/actions/sort/menu',
        body: { previousId: 'view', nextId: null },
      });
      wrapper
        .findComponent({ name: 'StaticManagementLayout' })
        .vm.$emit('update:explorer-search-keyword', '菜单');
      await flushPromises();
      expect(explorer.props('sorting')).toBe(false);
      wrapper
        .findComponent({ name: 'StaticManagementLayout' })
        .vm.$emit('update:explorer-search-keyword', '');
      await flushPromises();
      expect(explorer.props('sorting')).toBe(true);
      await wrapper.get('button[title="新建动作"]').trigger('click');
      expect(explorer.props('sorting')).toBe(false);
    }
    wrapper.unmount();
  },
);
