import { flushPromises, shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import UserPicker from '@/platform-components/UserPicker.vue';

const users = [
  {
    id: 'user-1',
    title: '张三',
    subtitle: '研发中心 / 研发一部',
    account: 'zhangsan',
    employeeName: '张三',
    organizationId: 'org-rd',
    organizationName: '研发中心',
    departmentId: 'department-rd-1',
    departmentName: '研发一部',
  },
  {
    id: 'user-2',
    title: '李四',
    subtitle: '财务中心 / 财务部',
    account: 'lisi',
    employeeName: '李四',
    organizationId: 'org-finance',
    organizationName: '财务中心',
    departmentId: 'department-finance',
    departmentName: '财务部',
  },
];

function mountPicker(overrides: Record<string, unknown> = {}) {
  return shallowMount(UserPicker, {
    props: {
      searchPage: vi.fn().mockResolvedValue({ records: users, total: users.length }),
      resolveUsers: vi.fn().mockResolvedValue([]),
      ...overrides,
    },
    global: {
      stubs: {
        UiModal: {
          name: 'UiModal',
          props: ['open'],
          emits: ['confirm', 'cancel'],
          template: '<section v-if="open"><slot /></section>',
        },
        UiButton: {
          name: 'UiButton',
          props: ['ariaLabel'],
          emits: ['click'],
          template: '<button @click="$emit(\'click\', $event)"><slot /></button>',
        },
        UiTree: {
          name: 'UiTree',
          props: ['nodes', 'selectedKey'],
          emits: ['select', 'deselect'],
          template:
            '<div><button v-for="node in nodes" :key="node.key" @click="$emit(\'select\', node)">{{ node.title }}</button></div>',
        },
        RecordExplorerPanel: {
          name: 'RecordExplorerPanel',
          template: '<section><slot /></section>',
        },
      },
    },
  });
}

it('opens from the text entry and delegates paged search to the injected provider', async () => {
  const searchPage = vi.fn().mockResolvedValue({ records: users, total: 42 });
  const wrapper = mountPicker({ searchPage });

  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '张');
  await flushPromises();

  expect(searchPage).toHaveBeenCalledWith({ keyword: '张', pageNum: 1, pageSize: 20 });
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(true);
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toEqual(users);
});

it('keeps object-specific search copy injected by a semantic wrapper', async () => {
  const wrapper = mountPicker({
    searchPlaceholder: '按工号、姓名或职员 ID 搜索',
    emptyDescription: '没有可选择的职员',
    selectionNoun: '职员',
  });

  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '张');
  await flushPromises();

  expect(wrapper.findComponent({ name: 'UiSearchInput' }).props('placeholder')).toBe(
    '按工号、姓名或职员 ID 搜索',
  );
  const table = wrapper.findComponent({ name: 'UiDataTable' });
  expect(table.props('emptyDescription')).toBe('没有可选择的职员');
  expect(table.props('columns')).toEqual([
    { key: 'account', title: '用户账号', width: 140 },
    { key: 'employeeName', title: '职员姓名', width: 140 },
    { key: 'organizationName', title: '所属机构', width: 150 },
    { key: 'departmentName', title: '所属部门', width: 150 },
  ]);
  expect(table.props('pagination')).toMatchObject({ current: 1, total: 2, pageSize: 20 });
  expect(table.props('fillHeight')).toBe(true);
});

it('resolves an existing user account ID through the injected authorized resolver', async () => {
  const resolveUsers = vi.fn().mockResolvedValue([users[0]]);
  const wrapper = mountPicker({ value: 'user-1', resolveUsers });
  await flushPromises();

  expect(resolveUsers).toHaveBeenCalledWith(['user-1']);
  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('张三');
});

it('shows the selected user in the search entry while keeping dialog search separate', async () => {
  const searchPage = vi.fn().mockResolvedValue({ records: users, total: users.length });
  const wrapper = mountPicker({
    value: 'user-1',
    resolveUsers: vi.fn().mockResolvedValue([users[0]]),
    searchPage,
  });
  await flushPromises();

  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('张三');
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiSearchInput' }).props('value')).toBe('');
  expect(searchPage).toHaveBeenCalledWith({ keyword: '', pageNum: 1, pageSize: 20 });
});

it('keeps the external value unchanged when the selection draft is cancelled', async () => {
  const wrapper = mountPicker({ value: 'user-1' });
  await flushPromises();
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '李');
  await flushPromises();
  wrapper.findComponent({ name: 'UiDataTable' }).vm.$emit('rowClick', users[1], new MouseEvent('click'));
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('cancel');
  await flushPromises();

  expect(wrapper.emitted('update:value')).toBeUndefined();
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(false);
});

it('commits a single user account ID only after confirmation', async () => {
  const wrapper = mountPicker();
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '李');
  await flushPromises();
  wrapper.findComponent({ name: 'UiDataTable' }).vm.$emit('rowClick', users[1], new MouseEvent('click'));
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await flushPromises();

  expect(wrapper.emitted('update:value')).toEqual([['user-2']]);
  expect(wrapper.emitted('select')).toEqual([[[users[1]]]]);
});

it('keeps single selection focused on the result list and does not render multiple-selection draft UI', async () => {
  const wrapper = mountPicker({ value: 'user-1', resolveUsers: vi.fn().mockResolvedValue([users[0]]) });
  await flushPromises();
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '张');
  await flushPromises();

  expect(wrapper.text()).not.toContain('已选');
  expect(wrapper.text()).not.toContain('清空选择');
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('selectedRowKey')).toBe('user-1');
});

it('completes a single selection immediately when a result row is double-clicked', async () => {
  const wrapper = mountPicker();
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '李');
  await flushPromises();

  wrapper
    .findComponent({ name: 'UiDataTable' })
    .vm.$emit('rowDblclick', users[1], new MouseEvent('dblclick'));
  await flushPromises();

  expect(wrapper.emitted('update:value')).toEqual([['user-2']]);
  expect(wrapper.emitted('select')).toEqual([[[users[1]]]]);
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(false);
});

it('retains multiple selection across pages and commits the IDs once', async () => {
  const searchPage = vi.fn(async ({ pageNum }: { pageNum: number }) =>
    pageNum === 1 ? { records: [users[0]], total: 40 } : { records: [users[1]], total: 40 },
  );
  const wrapper = mountPicker({ multiple: true, searchPage });
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
  await flushPromises();

  const firstSelection = wrapper.findComponent({ name: 'UiDataTable' }).props('selection') as {
    onChange: (ids: string[]) => void;
  };
  firstSelection.onChange(['user-1']);
  await flushPromises();
  const table = wrapper.findComponent({ name: 'UiDataTable' });
  const pagination = table.props('pagination') as { onChange: (page: number) => void };
  expect(searchPage).toHaveBeenCalledWith({ keyword: '', pageNum: 1, pageSize: 20 });
  expect(table.props('rows')).toEqual([users[0]]);
  expect(pagination).toMatchObject({ current: 1, total: 40, pageSize: 20 });
  expect((wrapper.vm as unknown as { pageCount: number }).pageCount).toBe(2);
  pagination.onChange(2);
  await flushPromises();

  const secondSelection = wrapper.findComponent({ name: 'UiDataTable' }).props('selection') as {
    onChange: (ids: string[]) => void;
  };
  secondSelection.onChange(['user-1', 'user-2']);
  await flushPromises();
  expect(wrapper.text()).toContain('已选 2 位用户');
  expect(wrapper.text()).toContain('清空选择');
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await flushPromises();

  expect(wrapper.emitted('update:value')).toEqual([[['user-1', 'user-2']]]);
  expect(searchPage).toHaveBeenLastCalledWith({ keyword: '', pageNum: 2, pageSize: 20 });
});

it('does not allow an older search response to overwrite the current candidates', async () => {
  let resolveFirst: ((value: { records: typeof users; total: number }) => void) | undefined;
  const searchPage = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<{ records: typeof users; total: number }>((resolve) => {
          resolveFirst = resolve;
        }),
    )
    .mockResolvedValueOnce({ records: [users[1]], total: 1 });
  const wrapper = mountPicker({ searchPage });

  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '张');
  await flushPromises();
  wrapper.findComponent({ name: 'UiSearchInput' }).vm.$emit('search', '李');
  await flushPromises();
  resolveFirst!({ records: [users[0]], total: 1 });
  await flushPromises();

  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toEqual([users[1]]);
  expect(searchPage).toHaveBeenLastCalledWith({ keyword: '李', pageNum: 1, pageSize: 20 });
});

it('uses source-provided tenant, organization, and department navigation to narrow the people page', async () => {
  const navigation = {
    showTenantNavigation: true,
    tenants: [{ id: 'tenant-a', title: '租户 A' }],
    organizations: [{ id: 'org-a', title: '华东机构', tenantId: 'tenant-a' }],
    departments: [{ id: 'department-a', title: '研发部', tenantId: 'tenant-a', organizationId: 'org-a' }],
  };
  const searchPage = vi
    .fn()
    .mockResolvedValueOnce({
      records: users,
      total: users.length,
      navigation,
    })
    .mockResolvedValueOnce({ records: [users[0]], total: 1, navigation })
    .mockResolvedValueOnce({ records: users, total: users.length, navigation });
  const wrapper = mountPicker({ searchPage });

  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
  await flushPromises();

  expect(wrapper.text()).toContain('租户 A');
  expect(wrapper.text()).toContain('华东机构');
  expect(wrapper.text()).toContain('研发部');
  const organizationTree = wrapper
    .findAllComponents({ name: 'UiTree' })
    .find((tree) => (tree.props('nodes') as Array<{ key: string }>).some((node) => node.key === 'org-a'))!;
  organizationTree.vm.$emit('select', { key: 'org-a' });
  await flushPromises();

  expect(searchPage).toHaveBeenLastCalledWith({
    keyword: '',
    pageNum: 1,
    pageSize: 20,
    scope: { tenantId: 'tenant-a', organizationId: 'org-a' },
  });

  wrapper
    .findAllComponents({ name: 'UiTree' })
    .find((tree) => (tree.props('nodes') as Array<{ key: string }>).some((node) => node.key === 'org-a'))!
    .vm.$emit('deselect');
  await flushPromises();

  expect(searchPage).toHaveBeenLastCalledWith({
    keyword: '',
    pageNum: 1,
    pageSize: 20,
    scope: { tenantId: 'tenant-a' },
  });
});

it('clears the selected value without opening the picker when the entry clear affordance is used', async () => {
  const wrapper = mountPicker({ value: 'user-1', resolveUsers: vi.fn().mockResolvedValue([users[0]]) });
  await flushPromises();

  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('clear');
  await flushPromises();

  expect(wrapper.emitted('update:value')).toEqual([[undefined]]);
  expect(wrapper.emitted('select')).toEqual([[[]]]);
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(false);
});

it('uses the standard object-picker entry and keeps dialog search explicit', async () => {
  const wrapper = mountPicker();
  const entry = wrapper.findComponent({ name: 'ObjectPickerInput' });
  expect(entry.props('browseLabel')).toBe('选择用户');

  entry.vm.$emit('browse', '');
  await flushPromises();
  const dialogSearch = wrapper.findComponent({ name: 'UiSearchInput' });
  expect(dialogSearch.props('searchIconOnly')).toBe(false);
  expect(dialogSearch.props('searchText')).toBe('搜索');
});
