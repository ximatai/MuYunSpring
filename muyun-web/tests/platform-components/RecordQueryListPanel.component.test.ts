import { flushPromises, shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import RecordQueryListPanel, {
  type QueryListRecord,
  type RecordQueryListColumn,
} from '@/platform-components/RecordQueryListPanel.vue';
import type { ModuleContext } from '@muyun/web-core';
import type { WebQueryRequest } from '@muyun/web-contracts';

describe('RecordQueryListPanel', () => {
  it('renders a color-picker list projection as a color swatch', async () => {
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({ id: 'tag-1', color: '#1677ff' }),
        title: '标签',
        columns: [{ key: 'color', title: '颜色', type: 'colorPicker' }],
      },
      global: {
        stubs: {
          UiDataTable: {
            props: ['columns', 'rows'],
            template: `
              <div>
                <template v-for="row in rows" :key="row.key">
                  <template v-for="column in columns" :key="column.key">
                    <slot name="cell" :record="row" :column="column" />
                  </template>
                </template>
              </div>
            `,
          },
        },
      },
    });

    await flushPromises();

    expect(wrapper.find('.record-query-list-color').text()).toContain('#1677ff');
    expect(wrapper.find('.record-query-list-color i').attributes('style')).toContain(
      'background-color: rgb(22, 119, 255)',
    );
  });

  it('renders a dynamic color_picker descriptor as a color swatch by renderer type', async () => {
    const context = createContext({ id: 'tag-1', color: '#1677ff' });
    Object.assign(context.runtime, {
      ready: Promise.resolve({
        uiDescriptor: {
          page: {
            list: {
              fields: {
                fields: [
                  {
                    fieldRef: { fieldName: 'color' },
                    label: '颜色',
                    uiType: 'color_picker',
                    visible: { constant: true },
                    fieldControl: {
                      alias: 'color_picker',
                      rendererType: 'COLOR_PICKER',
                      valueShape: 'SCALAR',
                    },
                  },
                ],
              },
            },
          },
        },
      }),
    });
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: { context, title: '标签' },
      global: {
        stubs: {
          UiDataTable: {
            props: ['columns', 'rows'],
            template: `
              <div>
                <template v-for="row in rows" :key="row.key">
                  <template v-for="column in columns" :key="column.key">
                    <slot name="cell" :record="row" :column="column" />
                  </template>
                </template>
              </div>
            `,
          },
        },
      },
    });

    await flushPromises();

    expect(wrapper.find('.record-query-list-color').text()).toContain('#1677ff');
    expect(wrapper.find('.record-query-list-color i').attributes('style')).toContain(
      'background-color: rgb(22, 119, 255)',
    );
  });

  it('uses one display line by default and preserves configured multiline limits with the full text tooltip', async () => {
    const columns: RecordQueryListColumn[] = [
      { key: 'summary', title: '摘要' },
      { key: 'description', title: '说明', maxDisplayLines: 3 },
    ];
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({
          id: 'note-1',
          summary: '默认单行文本',
          description: '显示三行以内，全文仍可通过提示查看',
        }),
        title: '备注',
        columns,
      },
      global: {
        stubs: {
          UiDataTable: {
            props: ['columns', 'rows'],
            template: `
              <div>
                <template v-for="row in rows" :key="row.key">
                  <template v-for="column in columns" :key="column.key">
                    <slot name="cell" :record="row" :column="column" />
                  </template>
                </template>
              </div>
            `,
          },
        },
      },
    });

    await flushPromises();

    const cells = wrapper.findAll('.record-query-list-text');
    expect(cells).toHaveLength(2);
    expect(cells[0].attributes('style')).toContain('--record-query-list-max-lines: 1');
    expect(cells[0].attributes('title')).toBe('默认单行文本');
    expect(cells[1].attributes('style')).toContain('--record-query-list-max-lines: 3');
    expect(cells[1].attributes('title')).toBe('显示三行以内，全文仍可通过提示查看');
  });

  it('keeps icon-only pagination controls accessible', async () => {
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({ id: 'note-1' }),
        title: '备注',
      },
    });

    await flushPromises();

    expect(wrapper.find('[aria-label="上一页"]').exists()).toBe(true);
    expect(wrapper.find('[aria-label="下一页"]').exists()).toBe(true);
  });

  it('lets an embedding section own the visible title while preserving refresh access', async () => {
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({ id: 'note-1' }),
        title: '控件属性',
        showTitle: false,
      },
      global: { stubs: { ManagementPanelHeader: false } },
    });

    await flushPromises();

    expect(wrapper.find('.record-query-list-title').exists()).toBe(false);
    expect(wrapper.find('[aria-label="刷新控件属性"]').exists()).toBe(true);
  });

  it('uses the shared management header while keeping title refresh scoped to this list', async () => {
    const requests: WebQueryRequest[] = [];
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: { context: createContext({ id: 'note-1' }, requests), title: '用户管理' },
    });

    await vi.waitFor(() => expect(requests).toHaveLength(1));
    const header = wrapper.findComponent({ name: 'ManagementPanelHeader' });
    expect(header.exists()).toBe(true);
    expect(header.props()).toMatchObject({
      title: '用户管理',
      titleActionIcon: 'reload',
      titleActionTitle: '刷新用户管理',
    });

    header.vm.$emit('titleAction');
    await vi.waitFor(() => expect(requests).toHaveLength(2));
  });

  it('renders embedded read mode without operational chrome or a standalone border', async () => {
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({ id: 'note-1' }),
        title: '控件属性',
        showTitle: false,
        headerVisible: false,
        showRecycleBin: false,
        pageable: false,
        embedded: true,
      },
    });

    await flushPromises();

    expect(wrapper.find('.record-query-list-header').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'RecycleBinModeButton' }).exists()).toBe(false);
    expect(wrapper.find('.record-query-list-panel').classes()).toContain('is-embedded');
    expect(wrapper.find('.record-query-list-panel').classes()).toContain('is-chrome-free');
  });

  it('reloads the central list when an upstream navigator changes its criteria', async () => {
    const requests: WebQueryRequest[] = [];
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({ id: 'note-1' }, requests),
        title: '备注',
        externalQueryValues: { tenantId: 'tenant-a' },
      },
    });

    await vi.waitFor(() => expect(requests).toHaveLength(1));
    await wrapper.setProps({ externalQueryValues: { tenantId: 'tenant-b' } });
    await flushPromises();

    expect(requests).toHaveLength(2);
    expect(requests.at(-1)?.externalQueryValues).toEqual({ tenantId: 'tenant-b' });
  });

  it('applies persistent query controls immediately through the standard query request', async () => {
    const requests: WebQueryRequest[] = [];
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({ id: 'note-1' }, requests),
        title: '备注',
        persistentQueryControls: [
          { externalCriteriaKey: 'onlineOnly', title: '仅在线', uiType: 'SWITCH', defaultValue: false },
        ],
      },
      global: { stubs: { ManagementPanelHeader: false } },
    });

    await vi.waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]?.externalQueryValues).toEqual({ onlineOnly: false });
    const search = wrapper.find('.record-query-list-search').element;
    const control = wrapper.find('.record-query-list-persistent-query-control').element;
    const advanced = wrapper.find('.record-query-list-advanced').element;
    expect(search.compareDocumentPosition(control) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(control.compareDocumentPosition(advanced) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);

    wrapper.findComponent({ name: 'UiCheckbox' }).vm.$emit('change', true);
    await vi.waitFor(() => expect(requests).toHaveLength(2));
    expect(requests.at(-1)?.externalQueryValues).toEqual({ onlineOnly: true });
  });

  it('keeps an embedding-owned external value authoritative if an invalid descriptor key collides', async () => {
    const requests: WebQueryRequest[] = [];
    shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({ id: 'note-1' }, requests),
        title: '备注',
        externalQueryValues: { tenantId: 'tenant-a' },
        persistentQueryControls: [
          { externalCriteriaKey: 'tenantId', title: '无效配置', uiType: 'SWITCH', defaultValue: false },
        ],
      },
    });

    await vi.waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]?.externalQueryValues).toEqual({ tenantId: 'tenant-a' });
  });

  it('loads the signed list projection when a required navigator scope becomes ready', async () => {
    const requests: WebQueryRequest[] = [];
    const context = createContext({ id: 'position-1', code: '001', title: 'Java' }, requests);
    Object.assign(context.runtime, {
      ready: Promise.resolve({
        uiDescriptor: {
          page: {
            list: {
              fields: {
                fields: [
                  { fieldRef: { fieldName: 'code' }, label: '岗位编码', visible: { constant: true } },
                  { fieldRef: { fieldName: 'title' }, label: '岗位名称', visible: { constant: true } },
                ],
              },
            },
          },
        },
      }),
    });
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context,
        title: '岗位管理',
        ready: false,
        externalQueryValues: { categoryId: 'category-1' },
      },
      global: {
        stubs: { UiDataTable: { name: 'UiDataTable', props: ['columns'], template: '<div />' } },
      },
    });

    await flushPromises();
    expect(requests).toHaveLength(0);

    await wrapper.setProps({ ready: true });
    await vi.waitFor(() => expect(requests).toHaveLength(1));

    expect(wrapper.findComponent({ name: 'UiDataTable' }).props('columns')).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ key: 'code', title: '岗位编码' }),
        expect.objectContaining({ key: 'title', title: '岗位名称' }),
      ]),
    );
  });

  it('does not render standard mutation actions that the module does not publish', async () => {
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({ id: 'note-1' }),
        title: '只读记录',
        standardCrudActions: true,
        standardCrudRowActions: true,
      },
    });

    await flushPromises();

    expect(wrapper.text()).not.toContain('新建');
  });

  it('preloads record availability and fails closed for row mutations until it is resolved', async () => {
    const recordActionsBatch = vi.fn().mockResolvedValue([]);
    const context = createContext({
      id: 'managed-note',
      title: '平台托管记录',
    }) as ModuleContext<QueryListRecord>;
    Object.assign(context, {
      runtime: { ready: Promise.resolve({}), snapshot: () => ({}) },
      can: () => true,
      action: (_actionCode: string, recordId?: string) =>
        recordId ? undefined : { actionCode: 'update', available: true },
      recordActionsSnapshot: () => undefined,
      recordActionsBatch,
    });
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context,
        title: '记录',
        standardCrudRowActions: true,
      },
      global: {
        stubs: {
          UiDataTable: { name: 'UiDataTable', props: ['rows'], template: '<div />' },
        },
      },
    });

    await flushPromises();

    expect(recordActionsBatch).toHaveBeenCalledWith(['managed-note']);
    const rows = wrapper.findComponent({ name: 'UiDataTable' }).props('rows') as Array<{
      secondaryActions: Array<{ key: string; disabled: boolean; disabledReason?: string }>;
    }>;
    expect(rows[0].secondaryActions).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ key: 'edit', disabled: true, disabledReason: '正在校验操作可用性' }),
        expect.objectContaining({ key: 'delete', disabled: true, disabledReason: '正在校验操作可用性' }),
      ]),
    );
  });
});

function createContext(
  record: QueryListRecord,
  queryRequests?: WebQueryRequest[],
): ModuleContext<QueryListRecord> {
  return {
    moduleAlias: 'demo.note',
    runtime: { ready: Promise.resolve({}) },
    abilities: { has: () => false },
    can: () => false,
    crud: {
      querySchema: async () => ({
        scopeName: 'demo.note',
        quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
        fields: [],
        externalCriteria: [],
        defaultSorts: [],
      }),
      query: async (request?: WebQueryRequest) => {
        queryRequests?.push(request ?? {});
        return { records: [record], total: 1, pageNum: 1, pageSize: 20 };
      },
    },
  } as unknown as ModuleContext<QueryListRecord>;
}
