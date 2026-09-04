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

it('does not emit a sort request across declared sort partitions', async () => {
  const wrapper = mount(RecordListExplorer, {
    props: {
      records: [
        { id: 'system', title: '系统方案', scope: 'system' },
        { id: 'tenant', title: '租户方案', scope: 'tenant' },
      ],
      sorting: true,
      sortPartitionOf: (record) => String((record as { scope?: string }).scope),
    },
  });
  const dataTransfer = { setData: () => undefined, effectAllowed: '' };
  const items = wrapper.findAll('li');

  await items[0].trigger('dragstart', { dataTransfer });
  await items[1].trigger('drop');

  expect(wrapper.emitted('sort')).toBeUndefined();
});

it('does not treat missing partition values as the same partition', async () => {
  const wrapper = mount(RecordListExplorer, {
    props: {
      records: [{ id: 'first' }, { id: 'second' }],
      sorting: true,
      sortPartitionOf: () => undefined,
    },
  });
  const dataTransfer = { setData: () => undefined, effectAllowed: '' };
  const items = wrapper.findAll('li');

  await items[0].trigger('dragstart', { dataTransfer });
  await items[1].trigger('drop');

  expect(wrapper.emitted('sort')).toBeUndefined();
});
