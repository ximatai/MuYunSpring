import { computed, ref } from 'vue';
import { WORKSPACE_NAVIGATION_DISABLED } from '@/platform-components/managementWorkspaceContext';
import { config, flushPromises, shallowMount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import RecordQueryListPanel, {
  type QueryListRecord,
  type RecordQueryListColumn,
} from '@/platform-components/RecordQueryListPanel.vue';
import type { ModuleContext } from '@muyun/web-core';
import type { WebQueryRequest } from '@muyun/web-contracts';

const originalStubs = config.global.stubs;
beforeEach(() => {
  config.global.stubs = { ...originalStubs, RecordQueryListSurface: false };
});
afterEach(() => {
  config.global.stubs = originalStubs;
});

describe('RecordQueryListPanel', () => {
  it('reloads the query schema before querying records after a schema failure', async () => {
    const context = createContext({ id: '1' });
    const schema = await context.crud.querySchema();
    const querySchema = vi
      .fn()
      .mockRejectedValueOnce(new Error('schema unavailable'))
      .mockResolvedValue(schema);
    const query = vi.spyOn(context.crud, 'query');
    context.crud.querySchema = querySchema;
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: { context, title: '记录', columns: [] },
    });
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('schema unavailable');
    expect(query).not.toHaveBeenCalled();
    wrapper.find('[role="alert"]').findComponent({ name: 'UiButton' }).vm.$emit('click');
    await flushPromises();
    expect(querySchema).toHaveBeenCalledTimes(2);
    expect(query).toHaveBeenCalledOnce();
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'UiDataTable' }).exists()).toBe(true);
    wrapper.unmount();
  });

  it('disables the complete list surface while its workspace is editing', async () => {
    const editing = ref(true);
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: { context: createContext({ id: '1' }), title: '记录', columns: [] },
      global: { provide: { [WORKSPACE_NAVIGATION_DISABLED as symbol]: computed(() => editing.value) } },
    });
    await flushPromises();
    expect(wrapper.attributes('inert')).toBeDefined();
    expect(wrapper.attributes('aria-disabled')).toBe('true');
    editing.value = false;
    await flushPromises();
    expect(wrapper.attributes('inert')).toBeUndefined();
    wrapper.unmount();
  });

  it('does not load options for a reference column that only has a companion title field', async () => {
    const request = vi.fn();
    const context = createContext({
      id: 'exam-1',
      classroomId: 'demo_classroom_g1a',
      classroomIdTitle: '高一（1）班',
    });
    Object.assign(context, { http: { request } });

    shallowMount(RecordQueryListPanel, {
      props: {
        context,
        title: '考试',
        columns: [{ key: 'classroomId', title: '教学班', titleField: 'classroomIdTitle' }],
      },
    });

    await flushPromises();

    expect(request).not.toHaveBeenCalled();
  });

  it('renders a color-picker list projection as a color swatch', async () => {
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context: createContext({ id: 'tag-1', color: '#1677ff' }),
        title: '标签',
        columns: [{ key: 'color', title: '颜色', type: 'colorPicker' }],
      },
      global: {
        stubs: {
          RecordQueryListCell: false,
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
          RecordQueryListCell: false,
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
          RecordQueryListCell: false,
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

  it('keeps pagination requests in the panel lifecycle when the shared surface changes page or size', async () => {
    const requests: WebQueryRequest[] = [];
    const context = createContext({ id: 'note-1' }, requests);
    context.crud.query = async (request?: WebQueryRequest) => {
      requests.push(request ?? {});
      return {
        records: [{ id: 'note-1' }],
        total: 41,
        pages: 3,
        totalKnown: true,
        pageNum: request?.page?.pageNum ?? 1,
        pageSize: request?.page?.pageSize ?? 20,
      };
    };
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: { context, title: '备注' },
    });

    await vi.waitFor(() => expect(requests).toHaveLength(1));
    const surface = wrapper.findComponent({ name: 'RecordQueryListSurface' });
    expect(surface.props()).toMatchObject({ total: 41, pageNum: 1, pages: 3, pageSize: 20 });

    surface.vm.$emit('pageChange', 2);
    await vi.waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]?.page).toEqual({ pageNum: 2, pageSize: 20 });

    surface.vm.$emit('pageSizeChange', 50);
    await vi.waitFor(() => expect(requests).toHaveLength(3));
    expect(requests[2]?.page).toEqual({ pageNum: 1, pageSize: 50 });
    wrapper.unmount();
  });

  it('refreshes footer summaries from each complete-result query response', async () => {
    const context = createContext({ id: 'note-1' });
    let requestCount = 0;
    context.crud.query = async () => ({
      records: [{ id: 'note-1' }],
      total: 1,
      pages: 1,
      totalKnown: true,
      pageNum: 1,
      pageSize: 20,
      summaries: [{ key: 'amount', value: ++requestCount * 100 }],
    });
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context,
        title: '备注',
        querySummaries: [{ key: 'amount', title: '金额合计', source: 'SUM', fieldName: 'amount' }],
      },
    });

    await vi.waitFor(() => expect(wrapper.text()).toContain('100'));
    await wrapper.vm.refresh();
    await vi.waitFor(() => expect(wrapper.text()).toContain('200'));
    wrapper.unmount();
  });

  it('replaces grouped results after refresh without merging same labels or null values', async () => {
    const context = createContext({ id: 'note-1' });
    let revision = 0;
    context.crud.query = async () => ({
      records: [{ id: 'note-1' }],
      total: 1,
      pages: 1,
      totalKnown: true,
      pageNum: 1,
      pageSize: 20,
      summaries: [
        {
          key: 'supplier',
          value: {
            kind: 'GROUPED',
            rows: revision++
              ? [{ value: null, label: '未填写', count: 3 }]
              : [
                  { value: 'a', label: '同名', count: 1 },
                  { value: 'b', label: '同名', count: 2 },
                  { value: null, label: '未填写', count: 1 },
                ],
          },
        },
      ],
    });
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: {
        context,
        title: '备注',
        querySummaries: [
          {
            key: 'supplier',
            title: '供应商',
            source: 'GROUPED',
            groupByField: 'supplierId',
            groupByTitle: '供应商',
          },
        ],
      },
      global: {
        stubs: {
          QueryGroupedSummary: false,
          UiButton: { name: 'UiButton', template: '<button><slot /></button>' },
        },
      },
    });
    await flushPromises();
    await vi.waitFor(() => expect(wrapper.text()).toContain('查看分组'));
    await wrapper
      .findComponent({ name: 'QueryGroupedSummary' })
      .findComponent({ name: 'UiButton' })
      .vm.$emit('click');
    expect(wrapper.findAll('tbody tr').filter((row) => row.text().includes('同名'))).toHaveLength(2);
    expect(wrapper.text()).toContain('未填写');
    await wrapper.vm.refresh();
    await vi.waitFor(() => expect(wrapper.text()).toContain('3'));
    expect(wrapper.text()).not.toContain('同名');
    wrapper.unmount();
  });

  it('keeps shared pagination visible but disabled while the list awaits its query scope', async () => {
    const wrapper = shallowMount(RecordQueryListPanel, {
      props: { context: createContext({ id: 'note-1' }), title: '备注', ready: false },
    });

    await flushPromises();

    expect(wrapper.findComponent({ name: 'RecordQueryListSurface' }).props()).toMatchObject({
      pageable: true,
      total: 0,
      tableVisible: false,
      paginationDisabled: true,
    });
    wrapper.unmount();
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
