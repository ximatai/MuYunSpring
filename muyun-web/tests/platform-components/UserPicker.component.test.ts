import { flushPromises, shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import UserPicker from '@/platform-components/UserPicker.vue';

const users = [
  { id: 'user-1', title: '张三', subtitle: '研发部 / 租户 A' },
  { id: 'user-2', title: '李四', subtitle: '财务部 / 租户 A' },
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
      },
    },
  });
}

it('opens from the text entry and delegates paged search to the injected provider', async () => {
  const searchPage = vi.fn().mockResolvedValue({ records: users, total: 42 });
  const wrapper = mountPicker({ searchPage });

  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '张');
  await flushPromises();

  expect(searchPage).toHaveBeenCalledWith({ keyword: '张', pageNum: 1, pageSize: 20 });
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(true);
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toEqual(users);
});

it('keeps object-specific search and count copy injected by a semantic wrapper', async () => {
  const wrapper = mountPicker({
    searchPlaceholder: '按工号、姓名或职员 ID 搜索',
    emptyDescription: '没有可选择的职员',
    selectionNoun: '职员',
  });

  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '张');
  await flushPromises();

  expect(wrapper.findAllComponents({ name: 'UiSearchInput' })[1]!.props('placeholder')).toBe(
    '按工号、姓名或职员 ID 搜索',
  );
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('emptyDescription')).toBe('没有可选择的职员');
  expect(wrapper.text()).toContain('共 2 位职员');
});

it('resolves an existing user account ID through the injected authorized resolver', async () => {
  const resolveUsers = vi.fn().mockResolvedValue([users[0]]);
  const wrapper = mountPicker({ value: 'user-1', resolveUsers });
  await flushPromises();

  expect(resolveUsers).toHaveBeenCalledWith(['user-1']);
  expect(wrapper.text()).toContain('张三');
});

it('keeps the search draft separate from the selected user display', async () => {
  const searchPage = vi.fn().mockResolvedValue({ records: users, total: users.length });
  const wrapper = mountPicker({
    value: 'user-1',
    resolveUsers: vi.fn().mockResolvedValue([users[0]]),
    searchPage,
  });
  await flushPromises();

  expect(wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.props('value')).toBe('');
  expect(wrapper.text()).toContain('张三');
  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '');
  await flushPromises();
  expect(searchPage).toHaveBeenCalledWith({ keyword: '', pageNum: 1, pageSize: 20 });
});

it('keeps the external value unchanged when the selection draft is cancelled', async () => {
  const wrapper = mountPicker({ value: 'user-1' });
  await flushPromises();
  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '李');
  await flushPromises();
  wrapper.findComponent({ name: 'UiDataTable' }).vm.$emit('rowClick', users[1], new MouseEvent('click'));
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('cancel');
  await flushPromises();

  expect(wrapper.emitted('update:value')).toBeUndefined();
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(false);
});

it('commits a single user account ID only after confirmation', async () => {
  const wrapper = mountPicker();
  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '李');
  await flushPromises();
  wrapper.findComponent({ name: 'UiDataTable' }).vm.$emit('rowClick', users[1], new MouseEvent('click'));
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await flushPromises();

  expect(wrapper.emitted('update:value')).toEqual([['user-2']]);
  expect(wrapper.emitted('select')).toEqual([[[users[1]]]]);
});

it('retains multiple selection across pages and commits the IDs once', async () => {
  const searchPage = vi.fn(async ({ pageNum }: { pageNum: number }) =>
    pageNum === 1 ? { records: [users[0]], total: 40 } : { records: [users[1]], total: 40 },
  );
  const wrapper = mountPicker({ multiple: true, searchPage });
  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '');
  await flushPromises();

  const firstSelection = wrapper.findComponent({ name: 'UiDataTable' }).props('selection') as {
    onChange: (ids: string[]) => void;
  };
  firstSelection.onChange(['user-1']);
  await flushPromises();
  const nextPage = wrapper
    .findAllComponents({ name: 'UiButton' })
    .find((button) => button.props('ariaLabel') === '下一页');
  expect(searchPage).toHaveBeenCalledWith({ keyword: '', pageNum: 1, pageSize: 20 });
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toEqual([users[0]]);
  expect(wrapper.text()).toContain('共 40 位用户');
  expect((wrapper.vm as unknown as { pageCount: number }).pageCount).toBe(2);
  expect(nextPage?.props()).toMatchObject({ ariaLabel: '下一页' });
  await nextPage!.trigger('click');
  await flushPromises();

  const secondSelection = wrapper.findComponent({ name: 'UiDataTable' }).props('selection') as {
    onChange: (ids: string[]) => void;
  };
  secondSelection.onChange(['user-1', 'user-2']);
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

  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '张');
  await flushPromises();
  wrapper.findAllComponents({ name: 'UiSearchInput' })[1]!.vm.$emit('search', '李');
  await flushPromises();
  resolveFirst!({ records: [users[0]], total: 1 });
  await flushPromises();

  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toEqual([users[1]]);
  expect(searchPage).toHaveBeenLastCalledWith({ keyword: '李', pageNum: 1, pageSize: 20 });
});
