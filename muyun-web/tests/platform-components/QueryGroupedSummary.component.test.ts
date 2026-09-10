import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import QueryGroupedSummary from '@/platform-components/QueryGroupedSummary.vue';

it('hides stale group values during loading, failure and an unresolved query scope', async () => {
  const wrapper = mount(QueryGroupedSummary, {
    props: {
      title: '供应商统计',
      groupByTitle: '供应商',
      sumFieldTitle: '采购金额',
      value: {
        kind: 'GROUPED',
        rows: [
          { value: 'a', label: '同名供应商', count: 1, sum: 10 },
          { value: 'b', label: '同名供应商', count: 2, sum: 20 },
          { value: null, label: '未填写', count: 1, sum: 0 },
        ],
      },
    },
    global: { stubs: { UiButton: { template: '<button><slot /></button>' } } },
  });
  await wrapper.get('button').trigger('click');
  expect(wrapper.get('button').attributes('aria-expanded')).toBe('true');
  expect(wrapper.findAll('tbody tr')).toHaveLength(3);
  expect(wrapper.text()).toContain('采购金额合计');
  await wrapper.setProps({ loading: true });
  expect(wrapper.get('[role="status"]').text()).toContain('正在加载');
  expect(wrapper.find('table').exists()).toBe(false);
  await wrapper.setProps({ loading: false, error: true });
  expect(wrapper.get('[role="alert"]').text()).toContain('加载失败');
  expect(wrapper.text()).not.toContain('同名供应商');
  await wrapper.setProps({ error: false, ready: false });
  expect(wrapper.text()).toContain('请选择查询范围');
  await wrapper.setProps({ ready: true, value: { kind: 'GROUPED', rows: [] } });
  expect(wrapper.text()).toContain('暂无分组结果');
  expect(wrapper.text()).not.toContain('同名供应商');
});
