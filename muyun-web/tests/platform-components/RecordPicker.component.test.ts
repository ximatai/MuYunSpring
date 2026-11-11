import { flushPromises, mount } from '@vue/test-utils';
import { assert, expect, it, vi } from 'vitest';
import RecordPicker from '@/platform-components/RecordPicker.vue';
import RecordMultiPicker from '@/platform-components/RecordMultiPicker.vue';

it('uses the list query transport for an explicitly LIST reference even when tree capability exists', async () => {
  const query = vi.fn().mockResolvedValue({ records: [{ id: 'department-1', title: '研发部' }] });
  const tree = vi.fn().mockResolvedValue({ records: [] });
  const wrapper = mount(RecordPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
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
  const wrapper = mount(RecordPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
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
  const wrapper = mount(RecordPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
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
  const wrapper = mount(RecordMultiPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
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
  const wrapper = mount(RecordPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
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

it('publishes the resolved edit value with its authorized projections', async () => {
  const resolveOptions = vi
    .fn()
    .mockResolvedValue([{ id: 'platform.module', title: '模块管理', projections: { entryType: 'MODULE' } }]);
  const wrapper = mount(RecordPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
    props: {
      mode: 'list',
      value: 'platform.module',
      loadOptions: vi.fn().mockResolvedValue([]),
      resolveOptions,
      context: {
        runtime: { ready: Promise.resolve() },
        abilities: { tryTree: () => undefined },
        crud: { query: vi.fn() },
      } as never,
    },
  });

  await flushPromises();

  assert.deepEqual(wrapper.emitted('selection-resolved')?.at(-1), [
    { id: 'platform.module', title: '模块管理', projections: { entryType: 'MODULE' } },
  ]);
});

it('filters tree candidates when a multi-value picker is searched', async () => {
  const tree = vi.fn().mockResolvedValue({
    records: [
      { record: { id: 'department-1', title: '研发部' }, children: [] },
      { record: { id: 'department-2', title: '财务部' }, children: [] },
    ],
  });
  const wrapper = mount(RecordMultiPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

it.each([RecordPicker, RecordMultiPicker])(
  'loads subsequent dropdown pages and retains selected titles (%s)',
  async (component) => {
    const query = vi.fn().mockImplementation(async ({ page }) => ({
      records: [{ id: `record-${page.pageNum}`, title: `Title ${page.pageNum}` }],
      total: 101,
    }));
    const wrapper = mount(component, {
      global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
      props: { mode: 'list', context: { moduleAlias: 'demo.record', crud: { query } } as never },
    });
    await flushPromises();
    const select = wrapper.findComponent({ name: 'UiSelect' });
    expect(select.props('allowClear')).toBe(true);
    select.vm.$emit('update:value', component === RecordPicker ? 'record-1' : ['record-1']);
    await flushPromises();
    await wrapper.setProps({ value: component === RecordPicker ? 'record-1' : ['record-1'] } as never);
    await flushPromises();
    select.vm.$emit('reach-end');
    select.vm.$emit('reach-end');
    await flushPromises();
    expect(query.mock.calls.at(-1)?.[0].page).toEqual({ pageNum: 2, pageSize: 50 });
    expect(query).toHaveBeenCalledTimes(2);
    expect(select.props('options').map((option: { value: string }) => option.value)).toEqual([
      'record-2',
      'record-1',
    ]);
    expect(
      select.props('options').find((option: { value: string }) => option.value === 'record-1').label,
    ).toBe('Title 1');
    wrapper.unmount();
  },
);

it('isolates late search and source responses in the compact multi picker', async () => {
  const late = deferred<{ records: { id: string; title: string }[]; total: number }>();
  const query = vi
    .fn()
    .mockImplementation(({ quickSearch }) =>
      quickSearch === 'old'
        ? late.promise
        : Promise.resolve({ records: [{ id: 'new', title: 'New' }], total: 1 }),
    );
  const wrapper = mount(RecordMultiPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
    props: { mode: 'list', context: { moduleAlias: 'demo.record', crud: { query } } as never },
  });
  await flushPromises();
  const select = wrapper.findComponent({ name: 'UiSelect' });
  select.vm.$emit('search', 'old');
  await flushPromises();
  select.vm.$emit('search', 'new');
  await flushPromises();
  late.resolve({ records: [{ id: 'old', title: 'Old' }], total: 1 });
  await flushPromises();
  expect(select.props('options').map((option: { value: string }) => option.value)).toEqual(['new']);

  const oldResolve = deferred<{ id: string; title: string }[]>();
  await wrapper.setProps({ value: ['saved'], resolveOptions: () => oldResolve.promise });
  await flushPromises();
  await wrapper.setProps({
    context: { moduleAlias: 'other.record', crud: { query } } as never,
    resolveOptions: async () => [{ id: 'saved', title: 'Current scope' }],
  });
  await flushPromises();
  oldResolve.resolve([{ id: 'saved', title: 'Previous scope' }]);
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toContainEqual({
    value: 'saved',
    label: 'Current scope',
    disabled: false,
  });
  expect(wrapper.emitted('select')).toBeUndefined();
  wrapper.unmount();
});

it('keeps record projections in user selection and separates historical resolution', async () => {
  const record = { id: 'saved', title: 'Saved', affectPatch: { price: 12 }, projections: { code: 'A' } };
  const wrapper = mount(RecordMultiPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
    props: {
      mode: 'list',
      value: ['saved'],
      context: { crud: { query: vi.fn() } } as never,
      loadOptions: async () => [record],
      resolveOptions: async () => [record],
    },
  });
  await flushPromises();
  expect(wrapper.emitted('selection-resolved')?.at(-1)).toEqual([[record]]);
  expect(wrapper.emitted('select')).toBeUndefined();
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('update:value', ['saved']);
  expect(wrapper.emitted('select')?.at(-1)).toEqual([[record]]);
  wrapper.unmount();
});

it('updates inline presentation without rereading or reemitting the selected record', async () => {
  const loadOptions = vi.fn().mockResolvedValue([{ id: 'saved', title: 'Saved' }]);
  const wrapper = mount(RecordPicker, {
    global: { stubs: { UiSelect: true, UiTreeSelect: true, UiModal: true, UiButton: true } },
    props: {
      mode: 'list',
      value: 'saved',
      context: { crud: {} } as never,
      loadOptions,
      titleOf: () => 'Initial',
    },
  });
  await flushPromises();
  const resolvedCount = wrapper.emitted('selection-resolved')?.length;
  await wrapper.setProps({
    titleOf: () => 'Updated',
    constraints: [{ code: 'disabled', message: 'Disabled', test: () => false }],
  });
  await flushPromises();
  expect(loadOptions).toHaveBeenCalledTimes(1);
  expect(wrapper.emitted('selection-resolved')).toHaveLength(resolvedCount!);
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toEqual([
    { value: 'saved', label: 'Updated', disabled: true },
  ]);
  wrapper.unmount();
});
