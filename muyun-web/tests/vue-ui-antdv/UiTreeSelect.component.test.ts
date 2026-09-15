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
