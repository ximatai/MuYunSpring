import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordListExplorer from '@/platform-components/RecordListExplorer.vue';

it('emits deselect when a selected micro-list item is clicked again', () => {
  const wrapper = mount(RecordListExplorer, {
    props: {
      records: [{ id: 'tenant-1', title: '演示租户' }],
      selectedId: 'tenant-1',
    },
  });

  wrapper.findComponent({ name: 'UiRecordExplorerItem' }).vm.$emit('click');

  expect(wrapper.emitted('deselect')).toEqual([[]]);
  expect(wrapper.emitted('select')).toBeUndefined();
});
