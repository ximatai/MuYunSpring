import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordListExplorer from '@/platform-components/RecordListExplorer.vue';

it('emits deselect when a selected micro-list item is clicked again', () => {
  const wrapper = mount(RecordListExplorer, {
    attachTo: document.body,
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
    attachTo: document.body,
    props: {
      records: [
        { id: 'system', title: '系统方案', scope: 'system' },
        { id: 'tenant', title: '租户方案', scope: 'tenant' },
      ],
      sorting: true,
      sortPartitionOf: (record) => String((record as { scope?: string }).scope),
    },
  });
  const items = wrapper.findAll('li');

  await items[0].trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
  await items[1].trigger('mousemove', { buttons: 1, clientX: 40, clientY: 40 });
  await items[1].trigger('mouseup', { clientX: 40, clientY: 40 });

  expect(wrapper.emitted('sort')).toBeUndefined();
});

it('does not treat missing partition values as the same partition', async () => {
  const wrapper = mount(RecordListExplorer, {
    attachTo: document.body,
    props: {
      records: [{ id: 'first' }, { id: 'second' }],
      sorting: true,
      sortPartitionOf: () => undefined,
    },
  });
  const items = wrapper.findAll('li');

  await items[0].trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
  await items[1].trigger('mousemove', { buttons: 1, clientX: 40, clientY: 40 });
  await items[1].trigger('mouseup', { clientX: 40, clientY: 40 });

  expect(wrapper.emitted('sort')).toBeUndefined();
});

it('emits the dragged and dropped records with the requested boundary position', async () => {
  const records = [
    { id: 'application-first', title: '第一个应用' },
    { id: 'application-middle', title: '中间应用' },
    { id: 'application-last', title: '最后一个应用' },
  ];
  const wrapper = mount(RecordListExplorer, {
    attachTo: document.body,
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

  await lastItem.trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
  await firstItem.trigger('mousemove', { buttons: 1, clientX: 20, clientY: 25 });
  await firstItem.trigger('mouseup', { clientY: 25 });
  await firstItem.trigger('mousedown', { button: 0, clientX: 0, clientY: 0 });
  await lastItem.trigger('mousemove', { buttons: 1, clientX: 20, clientY: 75 });
  await lastItem.trigger('mouseup', { clientY: 75 });

  expect(wrapper.emitted('sort')).toEqual([
    [{ dragRecord: records[2], dropRecord: records[0], position: -1 }],
    [{ dragRecord: records[0], dropRecord: records[2], position: 1 }],
  ]);
});

it('rejects non-gap, on-node, and self drops at the flat sorting boundary', () => {
  const records = [{ id: 'first' }, { id: 'second' }];
  const wrapper = mount(RecordListExplorer, {
    attachTo: document.body,
    props: { records, sorting: true, sortPartitionOf: () => 'applications' },
  });
  const tree = wrapper.findComponent({ name: 'UiTree' });
  const allowDrop = tree.props('allowDrop') as (event: unknown) => boolean;
  const event = (overrides: Record<string, unknown> = {}) => ({
    source: { instanceId: 'tree', node: { key: 'first' }, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: { key: 'second' },
      position: 'after' as const,
    },
    operation: 'move' as const,
    ...overrides,
  });

  expect(
    allowDrop(
      event({ target: { kind: 'node', instanceId: 'tree', node: { key: 'second' }, position: 'inside' } }),
    ),
  ).toBe(false);
  expect(
    allowDrop(
      event({ target: { kind: 'node', instanceId: 'tree', node: { key: 'second' }, position: 'inside' } }),
    ),
  ).toBe(false);
  expect(
    allowDrop(
      event({ target: { kind: 'node', instanceId: 'tree', node: { key: 'first' }, position: 'before' } }),
    ),
  ).toBe(false);
});

it('disables flat sorting while the list is filtered', () => {
  const wrapper = mount(RecordListExplorer, {
    attachTo: document.body,
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
      source: { instanceId: 'tree', node: { key: 'first' }, operations: ['move'] as const },
      target: {
        instanceId: 'tree',
        kind: 'node' as const,
        node: { key: 'first' },
        position: 'after' as const,
      },
      operation: 'move' as const,
    }),
  ).toBe(false);
});
