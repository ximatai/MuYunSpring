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

it('emits the dragged and dropped records with the requested boundary position', async () => {
  const records = [
    { id: 'application-first', title: '第一个应用' },
    { id: 'application-middle', title: '中间应用' },
    { id: 'application-last', title: '最后一个应用' },
  ];
  const wrapper = mount(RecordListExplorer, {
    props: { records, sorting: true, sortPartitionOf: () => 'applications' },
  });
  const items = wrapper.findAll('li');
  const firstItem = items[0];
  const lastItem = items[2];
  Object.defineProperty(firstItem.element, 'getBoundingClientRect', {
    value: () => ({ top: 0, height: 100 }),
  });
  Object.defineProperty(lastItem.element, 'getBoundingClientRect', {
    value: () => ({ top: 0, height: 100 }),
  });

  await lastItem.trigger('dragstart');
  await firstItem.trigger('dragover', { clientY: 25 });
  await firstItem.trigger('drop', { clientY: 25 });
  await firstItem.trigger('dragstart');
  await lastItem.trigger('dragover', { clientY: 75 });
  await lastItem.trigger('drop', { clientY: 75 });

  expect(wrapper.emitted('sort')).toEqual([
    [{ dragRecord: records[2], dropRecord: records[0], position: -1 }],
    [{ dragRecord: records[0], dropRecord: records[2], position: 1 }],
  ]);
});

it('rejects non-gap, on-node, and self drops at the flat sorting boundary', () => {
  const records = [{ id: 'first' }, { id: 'second' }];
  const wrapper = mount(RecordListExplorer, {
    props: { records, sorting: true, sortPartitionOf: () => 'applications' },
  });
  const tree = wrapper.findComponent({ name: 'UiTree' });
  const allowDrop = tree.props('allowDrop') as (event: unknown) => boolean;
  const event = (overrides: Record<string, unknown> = {}) => ({
    dragNode: { key: 'first' },
    dropNode: { key: 'second' },
    dropPosition: 1,
    dropToGap: true,
    ...overrides,
  });

  expect(allowDrop(event({ dropToGap: false }))).toBe(false);
  expect(allowDrop(event({ dropPosition: 0 }))).toBe(false);
  expect(allowDrop(event({ dropNode: { key: 'first' } }))).toBe(false);
});

it('disables flat sorting while the list is filtered', () => {
  const wrapper = mount(RecordListExplorer, {
    props: {
      records: [
        { id: 'first', title: '第一个' },
        { id: 'second', title: '第二个' },
      ],
      sorting: true,
      keyword: 'first',
      sortPartitionOf: () => 'applications',
    },
  });
  const tree = wrapper.findComponent({ name: 'UiTree' });

  expect(tree.props('draggable')).toBe(false);
  const allowDrop = tree.props('allowDrop') as (event: unknown) => boolean;
  expect(
    allowDrop({
      dragNode: { key: 'first' },
      dropNode: { key: 'first' },
      dropPosition: 1,
      dropToGap: true,
    }),
  ).toBe(false);
});
