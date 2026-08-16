import { flushPromises, shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import RecordQueryListPanel, {
  type QueryListRecord,
  type RecordQueryListColumn,
} from '@/platform-components/RecordQueryListPanel.vue';
import type { ModuleContext } from '@muyun/web-core';
import type { WebQueryRequest } from '@muyun/web-contracts';

describe('RecordQueryListPanel', () => {
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
