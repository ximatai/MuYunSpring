import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import {
  configureModuleContext,
  createHttpClient,
  createLanguageContext,
  provideLanguageContext,
} from '@muyun/web-core';
import RoleAuthorizationView from '@/views/RoleAuthorizationView.vue';

afterEach(() => vi.unstubAllGlobals());

it('updates data-grant column titles while retaining the edited authorization matrix', async () => {
  const requests: string[] = [];
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const path = new URL(String(input)).pathname;
    requests.push(path);
    if (path.startsWith('/iam.role/view/')) {
      return Response.json({ id: 'role-1', title: '业务角色原名', roleKind: 'dataGrant' });
    }
    if (path.includes('/dataGrantActionMatrix/')) {
      return Response.json({
        actions: [{ actionCode: 'view', title: '业务动作原名', configured: false, dataScopePolicy: 'all' }],
      });
    }
    if (path.includes('/dataScopePolicyCatalog/')) {
      return Response.json({ options: [], referenceDependencies: [] });
    }
    return Response.json({ moduleAlias: 'iam.role', capabilities: [], actions: [], uiDescriptor: {} });
  });
  configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
  const language = createLanguageContext({ languageTag: 'en' });
  const table = defineComponent({
    name: 'UiDataTable',
    props: ['columns', 'rows'],
    setup(props, { slots }) {
      return () => h('section', slots.cell?.({ column: { key: 'configured' }, record: props.rows[0] }));
    },
  });
  const Harness = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(RoleAuthorizationView, { roleId: 'role-1' });
    },
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        RecordDetailPanel: { template: '<section><slot /></section>' },
        RecordExplorerPanel: { template: '<section><slot /></section>' },
        UiDataTable: table,
        UiSwitch: {
          emits: ['change'],
          template: '<button @click="$emit(\'change\', true)">edit</button>',
        },
        UiButton: { template: '<button><slot /></button>' },
      },
    },
  });
  try {
    await flushPromises();
    await flushPromises();
    const grid = wrapper.findComponent(table);
    expect(grid.exists()).toBe(true);
    const titles = () => grid.props('columns').map((column: { title: string }) => column.title);
    expect(titles()).toEqual(['Standard action', 'Enable template', 'Data scope']);
    const rows = grid.props('rows');
    await grid.find('button').trigger('click');
    const loadedRequests = [...requests];
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(titles()).toEqual(['标准动作', '启用模板', '数据范围']);
    expect(grid.props('rows')).toBe(rows);
    expect(rows[0]).toMatchObject({ actionCode: 'view', title: '业务动作原名', configured: true });
    language.setLanguage('en');
    await flushPromises();
    expect(titles()).toEqual(['Standard action', 'Enable template', 'Data scope']);
    expect(grid.props('rows')).toBe(rows);
    expect(requests).toEqual(loadedRequests);
  } finally {
    wrapper.unmount();
  }
});
