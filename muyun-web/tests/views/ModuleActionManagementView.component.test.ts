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
