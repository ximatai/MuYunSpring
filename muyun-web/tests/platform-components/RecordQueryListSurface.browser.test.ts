import { mount } from '@vue/test-utils';
import { h, ref } from 'vue';
import RecordQueryListCell from '@/platform-components/RecordQueryListCell.vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import RecordQueryListSurface from '@/platform-components/RecordQueryListSurface.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

it('keeps long summaries and non-fixed actions inside a compact management list', async () => {
  await page.viewport(992, 814);
  const wrapper = mount(RecordQueryListSurface, {
    attachTo: document.body,
    attrs: { style: 'width: 900px; height: auto' },
    props: {
      columns: [
        { key: 'name', title: '业务名称', width: '22%' },
        { key: 'summary', title: '摘要' },
        { key: 'enabled', title: '启用状态', width: '96px' },
        { key: 'state', title: '变更状态', width: '120px' },
      ],
      rows: [
        {
          id: 'rule',
          name: '计算采购金额',
          summary: '字段引用及计算条件'.repeat(30),
          enabled: '启用',
          state: '未应用',
        },
      ],
      fillHeight: false,
      horizontalScroll: false,
      showActionColumn: true,
      actionColumnWidth: 120,
      actionColumnFixed: false,
    },
    slots: {
      cell: ({ column, record }) =>
        h(RecordQueryListCell, {
          column: {
            ...column,
            width: column.width === undefined ? undefined : String(column.width),
            maxDisplayLines: 2,
          },
          record,
        }),
      rowActions: () => '编辑 删除',
    },
  });
  try {
    const bounds = wrapper.element.getBoundingClientRect();
    expect(wrapper.get('table').element.getBoundingClientRect().width).toBeLessThanOrEqual(bounds.width);
    for (const cell of wrapper.findAll('th')) {
      expect(cell.element.getBoundingClientRect().right).toBeLessThanOrEqual(bounds.right);
    }
    expect(wrapper.findAll('th')).toHaveLength(5);
  } finally {
    wrapper.unmount();
  }
});

it('keeps shared pagination muted, compact, interactive, and inside a narrow list surface', async () => {
  await page.viewport(992, 814);
  const wrapper = mount(RecordQueryListSurface, {
    attachTo: document.body,
    attrs: { style: 'width: 393px; height: 260px' },
    props: {
      headerVisible: false,
      columns: [],
      rows: [],
      tableVisible: false,
      pageable: true,
      total: 41,
      pageNum: 2,
      pages: 3,
      pageSize: 20,
      pageSizeOptions: [10, 20, 50],
    },
  });
  try {
    const footer = wrapper.get('.record-query-list-pagination').element;
    const controls = wrapper.get('.record-query-list-pagination-controls').element;
    const total = [...footer.querySelectorAll('span')].find((element) => element.textContent === '共 41 条')!;
    const mutedProbe = document.createElement('span');
    mutedProbe.style.color = 'var(--muyun-text-muted)';
    document.body.append(mutedProbe);
    try {
      expect(getComputedStyle(total).fontSize).toBe('13px');
      expect(getComputedStyle(total).color).toBe(getComputedStyle(mutedProbe).color);
    } finally {
      mutedProbe.remove();
    }
    expect(getComputedStyle(controls).gap).toBe('8px');
    expect(footer.scrollWidth).toBeLessThanOrEqual(footer.clientWidth + 1);
    expect(wrapper.element.scrollWidth).toBeLessThanOrEqual(wrapper.element.clientWidth + 1);
    const navigation = wrapper.get('.record-query-list-page-navigation').element;
    expect(navigation.scrollWidth).toBe(navigation.clientWidth);
    expect(wrapper.get('[aria-label="下一页"]').element.getBoundingClientRect().top).toBeCloseTo(
      wrapper.get('[aria-label="上一页"]').element.getBoundingClientRect().top,
      0,
    );
    expect(navigation.getBoundingClientRect().top).toBeLessThanOrEqual(total.getBoundingClientRect().bottom);
    expect(navigation.getBoundingClientRect().bottom).toBeGreaterThanOrEqual(
      total.getBoundingClientRect().top,
    );

    wrapper.element.style.width = '250px';
    await wrapper.vm.$nextTick();
    expect(footer.scrollWidth).toBeLessThanOrEqual(footer.clientWidth + 1);
    expect(navigation.scrollWidth).toBe(navigation.clientWidth);
    expect(wrapper.get('[aria-label="下一页"]').element.getBoundingClientRect().top).toBeCloseTo(
      wrapper.get('[aria-label="上一页"]').element.getBoundingClientRect().top,
      0,
    );

    await page.elementLocator(wrapper.get('[aria-label="下一页"]').element).click();
    expect(wrapper.emitted('pageChange')).toEqual([[3]]);
  } finally {
    wrapper.unmount();
  }
});

it('labels a cursor-backed lower bound without presenting it as an exact total', () => {
  const wrapper = mount(RecordQueryListSurface, {
    props: {
      columns: [],
      rows: [],
      tableVisible: false,
      pageable: true,
      total: 51,
      totalKnown: false,
    },
  });
  try {
    expect(wrapper.get('.record-query-list-pagination-controls').text()).toContain('至少 51 条');
  } finally {
    wrapper.unmount();
  }
});

it('separates untitled list operations from queries and wraps within a narrow host', async () => {
  await page.viewport(992, 814);
  const { default: UiActionButton } = await import('@/vue-ui-antdv/components/UiActionButton.vue');
  const { default: RecordQueryEnumFilter } = await import('@/platform-components/RecordQueryEnumFilter.vue');
  const dirty = ref(false);
  const wrapper = mount(RecordQueryListSurface, {
    attachTo: document.body,
    attrs: { style: 'width: 900px; height: 620px' },
    props: {
      showTitle: false,
      quickSearchVisible: true,
      columns: [],
      rows: [],
      tableVisible: false,
      pageable: true,
    },
    slots: {
      operations: () => [
        h(UiActionButton, {}, () => '新增规则'),
        h(UiActionButton, {}, () => '试算整组规则'),
        ...(dirty.value
          ? [
              h('span', {}, '未应用 3 项更改'),
              h(UiActionButton, {}, () => '放弃更改'),
              h(UiActionButton, { emphasis: 'primary' }, () => '应用更改'),
            ]
          : []),
      ],
      persistentQueries: () =>
        h(RecordQueryEnumFilter, {
          title: '规则类型',
          value: 'CALCULATION',
          options: [
            { value: 'CALCULATION', label: '字段计算' },
            { value: 'VALIDATION', label: '业务校验' },
          ],
        }),
    },
  });
  try {
    const operations = wrapper.get('.record-query-list-operation-actions').element;
    const queries = wrapper.get('.record-query-list-query-actions').element;
    const header = wrapper.get('.record-query-list-header').element;
    expect(operations.getBoundingClientRect().left).toBeCloseTo(header.getBoundingClientRect().left, 0);
    expect(queries.getBoundingClientRect().right).toBeCloseTo(header.getBoundingClientRect().right, 0);
    expect(operations.getBoundingClientRect().right).toBeLessThan(queries.getBoundingClientRect().left);
    const action = operations.querySelector('button')!;
    const input = queries.querySelector('.record-query-list-search')!;
    const filter = queries.querySelector('.record-query-enum-filter')!;
    expect(filter.getBoundingClientRect().right).toBeLessThanOrEqual(input.getBoundingClientRect().left);
    expect(getComputedStyle(filter.querySelector('label')!).fontSize).toBe('13px');
    expect(input.getBoundingClientRect().top).toBeCloseTo(filter.getBoundingClientRect().top, 0);
    expect(action.getBoundingClientRect().height).toBeCloseTo(input.getBoundingClientRect().height, 0);
    dirty.value = true;
    await wrapper.vm.$nextTick();
    for (const width of [560, 360]) {
      wrapper.element.style.width = `${width}px`;
      await wrapper.vm.$nextTick();
      expect(header.scrollWidth).toBeLessThanOrEqual(header.clientWidth + 1);
      expect(wrapper.element.scrollWidth).toBeLessThanOrEqual(wrapper.element.clientWidth + 1);
      await expect
        .poll(() => queries.getBoundingClientRect().top - operations.getBoundingClientRect().bottom)
        .toBeGreaterThanOrEqual(0);
    }
    const footer = wrapper.get('.record-query-list-pagination').element;
    expect(footer.getBoundingClientRect().bottom).toBeGreaterThan(
      wrapper.element.getBoundingClientRect().bottom - 20,
    );
  } finally {
    wrapper.unmount();
  }
});
