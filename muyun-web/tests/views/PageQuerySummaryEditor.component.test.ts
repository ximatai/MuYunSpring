import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import PageQuerySummaryEditor from '@/views/PageQuerySummaryEditor.vue';

it('hides CONTRIBUTOR when no business metric is registered but retains an existing contributor item', () => {
  const wrapper = mount(PageQuerySummaryEditor, {
    props: {
      summaries: [
        { key: 'sum', label: '金额', source: 'SUM', fieldName: 'amount' },
        { key: 'legacy', label: '旧指标', source: 'CONTRIBUTOR', contributorKey: 'gone' },
      ],
      catalog: { fields: [{ fieldName: 'amount', title: '金额' }], contributors: [] },
    },
  });
  const selects = wrapper.findAllComponents({ name: 'UiSelect' });
  expect(selects[0].props('options')).not.toContainEqual({ value: 'CONTRIBUTOR', label: '业务指标' });
  expect(selects[2].props('options')).toContainEqual({ value: 'CONTRIBUTOR', label: '业务指标' });
});

it('highlights a tree-targeted summary and places validation feedback beside its controls', () => {
  const wrapper = mount(PageQuerySummaryEditor, {
    props: {
      selectedKey: 'supplier',
      summaries: [
        {
          key: 'supplier',
          label: '',
          source: 'GROUPED',
          groupByField: undefined,
        },
      ],
      catalog: {
        fields: [{ fieldName: 'amount', title: '采购金额' }],
        groupFields: [{ fieldName: 'supplierId', title: '供应商', kind: 'REFERENCE' }],
      },
      issues: {
        supplier: {
          groupByField: '请选择分组字段。',
          label: '请填写展示名称。',
        },
      },
      descriptions: { supplier: '按供应商分组 · 记录数 · 采购金额合计' },
    },
  });

  const item = wrapper.get('[data-summary-key="supplier"]');
  expect(item.classes()).toContain('page-query-summary-editor__item--selected');
  expect(item.get('[role="alert"]').text()).toBe('请选择分组字段。');
  expect(item.text()).toContain('请填写展示名称。');
  expect(item.text()).toContain('按供应商分组 · 记录数 · 采购金额合计');
  expect(item.text()).toContain('每组固定显示记录数，可选一个数值字段合计。');
});

it('re-focuses the same summary when the tree requests another locate action', async () => {
  const scrollIntoView = vi.fn();
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: scrollIntoView,
  });
  const wrapper = mount(PageQuerySummaryEditor, {
    attachTo: document.body,
    props: {
      selectedKey: 'count',
      focusRequest: 1,
      summaries: [{ key: 'count', label: '记录数', source: 'MATCHED_COUNT' }],
    },
  });
  await wrapper.vm.$nextTick();
  await wrapper.setProps({ focusRequest: 2 });
  await wrapper.vm.$nextTick();
  expect(scrollIntoView).toHaveBeenCalledTimes(2);
  wrapper.unmount();
});

it('offers numeric totals only when eligible fields exist while retaining an existing total for repair', () => {
  const wrapper = mount(PageQuerySummaryEditor, {
    props: {
      catalog: { fields: [] },
      summaries: [
        { key: 'count', label: '记录数', source: 'MATCHED_COUNT' },
        { key: 'sum', label: '旧合计', source: 'SUM', fieldName: 'removed' },
      ],
    },
  });
  const selects = wrapper.findAllComponents({ name: 'UiSelect' });
  expect(selects[0].props('options')).not.toContainEqual({ value: 'SUM', label: '数值合计' });
  expect(selects[1].props('options')).toContainEqual({ value: 'SUM', label: '数值合计' });
});
