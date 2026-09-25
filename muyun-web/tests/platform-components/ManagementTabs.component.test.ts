import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ManagementTabs from '@/platform-components/ManagementTabs.vue';
import RecordRelationTabs from '@/platform-components/RecordRelationTabs.vue';

const tabs = [
  { key: 'a', title: '已有字段' },
  { key: 'b', title: '组件库' },
];

describe('ManagementTabs', () => {
  it('exposes selection and prevents switching while disabled', async () => {
    const wrapper = mount(ManagementTabs, {
      props: { tabs, activeKey: 'a', label: '字段来源', appearance: 'header', disabled: true },
    });
    expect(wrapper.find('[role="tablist"]').attributes('aria-label')).toBe('字段来源');
    expect(wrapper.findAll('[role="tab"]')[0].attributes('aria-selected')).toBe('true');
    await wrapper.findAll('button')[1].trigger('click');
    expect(wrapper.emitted('update:activeKey')).toBeUndefined();
    await wrapper.setProps({ disabled: false });
    await wrapper.findAll('button')[1].trigger('click');
    expect(wrapper.emitted('update:activeKey')).toEqual([['b']]);
    wrapper.unmount();
  });

  it('preserves relation tab selection and single-tab visibility', async () => {
    const wrapper = mount(RecordRelationTabs, { props: { tabs, activeKey: 'a' } });
    expect(wrapper.find('[role="tablist"]').attributes('aria-label')).toBe('关联记录');
    await wrapper.findAll('button')[1].trigger('click');
    expect(wrapper.emitted('update:activeKey')).toEqual([['b']]);
    await wrapper.setProps({ tabs: tabs.slice(0, 1) });
    expect(wrapper.find('[role="tablist"]').exists()).toBe(false);
    wrapper.unmount();
  });
});
