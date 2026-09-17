import { flushPromises, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import { configureModuleContext } from '@muyun/web-core';

it.each([
  { resourceTitle: undefined, expected: '组织' },
  { resourceTitle: '字典项', expected: '字典项' },
])(
  'uses the declared tree title $expected for its panel and refresh action',
  async ({ resourceTitle, expected }) => {
    configureModuleContext({
      http: {
        async request() {
          return {
            moduleAlias: 'demo.tree',
            title: '组织',
            capabilities: ['TREE'],
            abilities: ['tree'],
            actions: [],
            uiDescriptor: {
              schemaVersion: '1',
              moduleAlias: 'demo.tree',
              page: {
                template: 'TREE_MANAGEMENT',
                list: { fields: { viewCode: 'list', viewKind: 'LIST', fields: [] } },
                detail: { editor: { viewCode: 'form', viewKind: 'FORM', fields: [] } },
                treeResource: resourceTitle
                  ? {
                      resource: 'item',
                      scopeNavigatorKey: 'category',
                      scopeField: 'categoryId',
                      title: resourceTitle,
                      emptyDescription: '暂无数据',
                      createTitle: '新建',
                      sortPartitionFields: [],
                    }
                  : undefined,
              },
            },
          } as never;
        },
      },
    });
    const wrapper = mount(ModulePageHost, {
      props: {
        descriptor: {
          title: '组织',
          pageType: 'dynamic-module',
          openMode: 'dynamic-runner',
          hostType: 'module-page-host',
          tabPolicy: { identity: 'by-menu' },
          target: { moduleAlias: 'demo.tree', pageMode: 'LIST' },
        },
      },
      global: {
        stubs: {
          TreeRecordExplorer: true,
          ModuleReferenceRecordDetailBrowser: true,
          RecordDetailPanel: true,
          RecordExplorerPanel: {
            name: 'RecordExplorerPanel',
            props: ['title', 'refreshTitle'],
            template:
              '<section><h2>{{ title }}</h2><button :title="refreshTitle">刷新</button><slot /></section>',
          },
        },
      },
    });
    try {
      await flushPromises();
      expect(wrapper.get('h2').text()).toBe(expected);
      expect(wrapper.get('button').attributes('title')).toBe(`刷新${expected}`);
    } finally {
      wrapper.unmount();
    }
  },
);

it('remounts business content on an immediately resolved reload while retaining its layout', async () => {
  configureModuleContext({
    http: {
      async request() {
        return {
          moduleAlias: 'demo.list',
          capabilities: [],
          abilities: ['crud'],
          actions: [],
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'demo.list',
            page: {
              template: 'LIST_DETAIL_CARD',
              list: { fields: { viewCode: 'list', viewKind: 'LIST', fields: [] } },
              detail: { editor: { viewCode: 'form', viewKind: 'FORM', fields: [] } },
            },
          },
        } as never;
      },
    },
  });
  const wrapper = mount(ModulePageHost, {
    props: {
      descriptor: {
        pageType: 'dynamic-module',
        openMode: 'dynamic-runner',
        hostType: 'module-page-host',
        tabPolicy: { identity: 'by-menu' },
        target: { moduleAlias: 'demo.list', pageMode: 'LIST' },
      },
      reloadKey: 0,
    },
    global: {
      stubs: {
        RecordQueryListPanel: { name: 'RecordQueryListPanel', props: ['context'], template: '<article />' },
        ModuleReferenceRecordDetailBrowser: true,
        RecordDetailPanel: true,
      },
    },
  });
  try {
    await flushPromises();
    const content = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    const layout = wrapper.findComponent({ name: 'ManagementWorkspace' }).element;
    const oldInstance = content.vm;
    const oldContext = content.props('context');
    await wrapper.setProps({ reloadKey: 1 });
    await flushPromises();
    const replacement = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    expect(replacement.vm).not.toBe(oldInstance);
    expect(replacement.props('context')).not.toBe(oldContext);
    expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).element).toBe(layout);
  } finally {
    wrapper.unmount();
  }
});
