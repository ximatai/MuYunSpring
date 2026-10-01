import { flushPromises, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import UiTreeSelect from '@/vue-ui-antdv/components/UiTreeSelect.vue';

it('forwards typed tree-search text to the owner', async () => {
  const wrapper = mount(UiTreeSelect, {
    props: {
      showSearch: true,
      filterTreeNode: false,
      treeData: [
        {
          value: 'attendance',
          title: '出勤状态',
          children: [{ value: 'attended', title: '已参加' }],
        },
      ],
    },
    attachTo: document.body,
  });
  try {
    await wrapper.get('input').setValue('已参加');
    await flushPromises();
    expect(wrapper.emitted('search')).toContainEqual(['已参加']);
    await wrapper.get('input').trigger('focusout', { relatedTarget: document.body });
    expect(wrapper.emitted('blur')).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

it('renders native validation state and preserves unmatched errors independently', async () => {
  const wrapper = mount(UiTreeSelect, { props: { treeData: [], invalid: true, unmatched: true } });
  expect(wrapper.find('.ant-select-status-error').exists()).toBe(true);
  await wrapper.setProps({ invalid: false });
  expect(wrapper.find('.ant-select-status-error').exists()).toBe(true);
  await wrapper.setProps({ unmatched: false });
  expect(wrapper.find('.ant-select-status-error').exists()).toBe(false);
});
