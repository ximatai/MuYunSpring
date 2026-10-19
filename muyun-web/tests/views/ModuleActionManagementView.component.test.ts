import { showErrorMessage } from '@muyun/vue-ui-antdv';
import { defineComponent, h } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import {
  createLanguageContext,
  provideLanguageContext,
  configureModuleContext,
  type HttpRequestOptions,
} from '@/web-core';
import ModuleActionManagementView from '@/views/ModuleActionManagementView.vue';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  showErrorMessage: vi.fn(),
}));

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
          return { records, total: 2, pageNum: 1, pageSize: 200, pages: 1, totalKnown: true } as T;
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

it('keeps the action draft and read state when the owner language changes after validation failure', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/context'))
      return {
        moduleAlias: 'platform.module_action',
        capabilities: [],
        actions: ['create', 'update', 'query'].map((actionCode) => ({
          actionCode,
          authorized: true,
          actionLevel: 'ANY',
        })),
      } as never;
    if (options.path.endsWith('/action-executors')) return [] as never;
    return { records: [], total: 0 } as never;
  });
  configureModuleContext({ http: { request } });
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(ModuleActionManagementView, { moduleAlias: 'sales.entry', moduleKind: 'dynamic' });
    },
  });
  const wrapper = mount(Host, {
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
  try {
    await flushPromises();
    wrapper
      .findComponent({ name: 'CrudRecordListExplorer' })
      .vm.$emit('loaded', [{ id: 'menu', actionCode: 'menu', title: '业务动作原名', systemManaged: true }]);
    await flushPromises();
    await wrapper.get('button[title="New action"]').trigger('click');
    await wrapper.get('form.static-record-form').trigger('submit');
    await flushPromises();
    expect(showErrorMessage).toHaveBeenLastCalledWith('The action code is required.');
    const calls = request.mock.calls.length;
    const inputs = wrapper.findAll('input').map((input) => (input.element as HTMLInputElement).value);
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.find('button[title="新建动作"]').exists()).toBe(true);
    expect(wrapper.findAll('input').map((input) => (input.element as HTMLInputElement).value)).toEqual(
      inputs,
    );
    expect(request.mock.calls.length).toBe(calls);
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.find('button[title="New action"]').exists()).toBe(true);
    expect(
      request.mock.calls.some(([options]) => options.method === 'POST' && !options.path.endsWith('/query')),
    ).toBe(false);
  } finally {
    wrapper.unmount();
  }
});
