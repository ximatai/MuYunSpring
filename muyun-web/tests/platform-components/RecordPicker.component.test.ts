import { flushPromises, shallowMount } from '@vue/test-utils';
import { assert, it, vi } from 'vitest';
import RecordPicker from '@/platform-components/RecordPicker.vue';
import RecordMultiPicker from '@/platform-components/RecordMultiPicker.vue';

it('uses the list query transport for an explicitly LIST reference even when tree capability exists', async () => {
  const query = vi.fn().mockResolvedValue({ records: [{ id: 'department-1', title: '研发部' }] });
  const tree = vi.fn().mockResolvedValue({ records: [] });
  const wrapper = shallowMount(RecordPicker, {
    props: {
      mode: 'list',
      context: {
        runtime: { ready: Promise.resolve() },
        abilities: { tryTree: () => ({ tree }) },
        crud: { query },
      } as never,
    },
  });

  await flushPromises();

  assert.equal(query.mock.calls.length, 1);
  assert.equal(tree.mock.calls.length, 0);
  assert.equal(wrapper.find('ui-select-stub').exists(), true);
});

it('delegates list search to the reference query transport instead of filtering a fixed local page', async () => {
  const query = vi.fn().mockResolvedValue({ records: [] });
  const wrapper = shallowMount(RecordPicker, {
    props: {
      mode: 'list',
      context: {
        runtime: { ready: Promise.resolve() },
        abilities: { tryTree: () => undefined },
        crud: { query },
      } as never,
    },
  });

  await flushPromises();
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('search', '研发');
  await flushPromises();

  assert.deepEqual(query.mock.calls.at(-1)?.[0], {
    page: { pageNum: 1, pageSize: 50 },
    quickSearch: '研发',
  });
});

it('uses the source-field candidate loader when one is provided', async () => {
  const loadOptions = vi.fn().mockResolvedValue([{ id: 'customer-1', title: '星云科技' }]);
  const query = vi.fn();
  const wrapper = shallowMount(RecordPicker, {
    props: {
      mode: 'list',
      loadOptions,
      context: {
        runtime: { ready: Promise.resolve() },
        abilities: { tryTree: () => undefined },
        crud: { query },
      } as never,
    },
  });

  await flushPromises();
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('search', '星云');
  await flushPromises();

  assert.deepEqual(loadOptions.mock.calls.at(-1), ['星云']);
  assert.equal(query.mock.calls.length, 0);
});

it('uses the source-field candidate loader for a multi-value reference', async () => {
  const loadOptions = vi.fn().mockResolvedValue([{ id: 'customer-1', title: '星云科技' }]);
  const query = vi.fn();
  const wrapper = shallowMount(RecordMultiPicker, {
    props: {
      mode: 'list',
      loadOptions,
      context: {
        runtime: { ready: Promise.resolve() },
        abilities: { tryTree: () => undefined },
        crud: { query },
      } as never,
    },
  });

  await flushPromises();
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('search', '星云');
  await flushPromises();

  assert.deepEqual(loadOptions.mock.calls.at(-1), ['星云']);
  assert.equal(query.mock.calls.length, 0);
});

it('translates a selected ID missing from the candidate page through the source field', async () => {
  const loadOptions = vi.fn().mockResolvedValue([]);
  const resolveOptions = vi.fn().mockResolvedValue([{ id: 'customer-1', title: '星云科技' }]);
  const wrapper = shallowMount(RecordPicker, {
    props: {
      mode: 'list',
      value: 'customer-1',
      loadOptions,
      resolveOptions,
      context: {
        runtime: { ready: Promise.resolve() },
        abilities: { tryTree: () => undefined },
        crud: { query: vi.fn() },
      } as never,
    },
  });

  await flushPromises();

  assert.deepEqual(resolveOptions.mock.calls, [[['customer-1']]]);
  assert.deepEqual(wrapper.findComponent({ name: 'UiSelect' }).props('options'), [
    { value: 'customer-1', label: '星云科技', disabled: false },
  ]);
});

it('filters tree candidates when a multi-value picker is searched', async () => {
  const tree = vi.fn().mockResolvedValue({
    records: [
      { record: { id: 'department-1', title: '研发部' }, children: [] },
      { record: { id: 'department-2', title: '财务部' }, children: [] },
    ],
  });
  const wrapper = shallowMount(RecordMultiPicker, {
    props: {
      context: {
        runtime: { ready: Promise.resolve() },
        abilities: { tryTree: () => ({ tree }) },
        crud: { query: vi.fn() },
      } as never,
    },
  });

  await flushPromises();
  wrapper.findComponent({ name: 'UiTreeSelect' }).vm.$emit('search', '研发');
  await flushPromises();

  assert.deepEqual(wrapper.findComponent({ name: 'UiTreeSelect' }).props('treeData'), [
    { value: 'department-1', title: '研发部', disabled: false, children: [] },
  ]);
});
