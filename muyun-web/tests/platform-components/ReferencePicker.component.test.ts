import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { reactive } from 'vue';
import { expect, it, vi } from 'vitest';
import { AppError } from '@/web-core';
import { missingReferencePickerDependencies } from '@/platform-components/referencePickerReadError';
import ReferencePicker from '@/platform-components/ReferencePicker.vue';
import type {
  ReferencePickerCandidate,
  ReferencePickerProvider,
} from '@/platform-components/referencePickerModel';

const records = [
  { id: 'record-1', title: '第一条', projections: { code: 'A-01' } },
  { id: 'record-2', title: '第二条', projections: { code: 'A-02' } },
];

function provider(overrides: Partial<ReferencePickerProvider> = {}): ReferencePickerProvider {
  return {
    identity: { targetModuleAlias: 'demo.record', source: { kind: 'sourceField', id: 'ownerId' } },
    searchPage: vi.fn().mockResolvedValue({ records, total: 42 }),
    resolve: vi.fn().mockResolvedValue([]),
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function mountPicker(overrides: Record<string, unknown> = {}) {
  return shallowMount(ReferencePicker, {
    props: { provider: provider(), columns: [{ key: 'code', title: '编码' }], ...overrides },
    global: {
      stubs: {
        UiSelect: {
          name: 'UiSelect',
          props: [
            'value',
            'options',
            'mode',
            'disabled',
            'allowClear',
            'showSearch',
            'filterOption',
            'loading',
          ],
          emits: ['search', 'update:value'],
          template: '<div><slot name="suffixAction" /></div>',
        },
        UiModal: {
          name: 'UiModal',
          props: ['open', 'closable'],
          emits: ['confirm', 'cancel'],
          template: '<section v-if="open"><slot /></section>',
        },
        UiButton: {
          name: 'UiButton',
          emits: ['click'],
          template: '<button @click="$emit(\'click\')"><slot /></button>',
        },
        UiTree: { name: 'UiTree', props: ['nodes'], emits: ['select', 'deselect'], template: '<div />' },
        RecordExplorerPanel: { name: 'RecordExplorerPanel', template: '<section><slot /></section>' },
      },
    },
  });
}

function mountPickerWithInput(overrides: Record<string, unknown> = {}) {
  return mount(ReferencePicker, {
    props: { provider: provider(), columns: [{ key: 'code', title: '编码' }], ...overrides },
    global: {
      stubs: {
        UiSearchInput: {
          name: 'UiSearchInput',
          props: ['value'],
          emits: ['update:value', 'search', 'blur'],
          template: `
            <div>
              <input :value="value" @input="$emit('update:value', $event.target.value)" @blur="$emit('blur', $event)" />
              <button @click="$emit('search', value)">搜索</button>
            </div>
          `,
        },
        UiSelect: { name: 'UiSelect', template: '<div><slot name="suffixAction" /></div>' },
        UiModal: {
          name: 'UiModal',
          props: ['open'],
          emits: ['cancel'],
          template: '<section v-if="open"><slot /></section>',
        },
        UiDataTable: { name: 'UiDataTable', template: '<section />' },
        UiButton: { name: 'UiButton', template: '<button><slot /></button>' },
        UiTree: { name: 'UiTree', template: '<div />' },
        RecordExplorerPanel: { name: 'RecordExplorerPanel', template: '<section><slot /></section>' },
      },
    },
  });
}

it('uses source-owned pagination and commits a single ID only after confirmation', async () => {
  const candidateProvider = provider();
  const wrapper = mountPicker({ provider: candidateProvider });
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '第二');
  await flushPromises();

  expect(candidateProvider.searchPage).toHaveBeenCalledWith({
    keyword: '第二',
    pageNum: 1,
    pageSize: 20,
    scope: { selections: [] },
  });
  const table = wrapper.findComponent({ name: 'UiDataTable' });
  expect(table.props('rows')).toEqual([
    { id: 'record-1', title: '第一条', subtitle: undefined, code: 'A-01' },
    { id: 'record-2', title: '第二条', subtitle: undefined, code: 'A-02' },
  ]);
  table.vm.$emit('rowClick', records[1]);
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await flushPromises();
  expect(wrapper.emitted('update:value')).toEqual([['record-2']]);
  expect(wrapper.emitted('select')).toEqual([[[records[1]]]]);
});

it('keeps identity fields authoritative when a projection uses a reserved field name', async () => {
  const wrapper = mountPicker({
    provider: provider({
      searchPage: vi.fn().mockResolvedValue({
        records: [{ id: 'record-1', title: '正确标题', projections: { id: 'wrong', title: '错误标题' } }],
        total: 1,
      }),
    }),
  });
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toEqual([
    { id: 'record-1', title: '正确标题', subtitle: undefined },
  ]);
});

it('keeps a multiple draft across pages and cancellation never changes external value', async () => {
  const searchPage = vi.fn(async ({ pageNum }: { pageNum: number }) =>
    pageNum === 1 ? { records: [records[0]], total: 40 } : { records: [records[1]], total: 40 },
  );
  const wrapper = mountPicker({ provider: provider({ searchPage }), multiple: true, value: ['record-1'] });
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
  await flushPromises();
  let table = wrapper.findComponent({ name: 'UiDataTable' });
  (table.props('selection') as { onChange(ids: string[]): void }).onChange(['record-1']);
  (table.props('pagination') as { onChange(page: number): void }).onChange(2);
  await flushPromises();
  table = wrapper.findComponent({ name: 'UiDataTable' });
  (table.props('selection') as { onChange(ids: string[]): void }).onChange(['record-1', 'record-2']);
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('cancel');
  await flushPromises();
  expect(wrapper.emitted('update:value')).toBeUndefined();
  expect(searchPage).toHaveBeenLastCalledWith({
    keyword: '',
    pageNum: 2,
    pageSize: 20,
    scope: { selections: [] },
  });
});

it('keeps an initially persisted ID neutral until deferred resolution succeeds', async () => {
  const resolution = deferred<ReferencePickerCandidate[]>();
  const resolve = vi.fn().mockReturnValue(resolution.promise);
  const wrapper = mountPicker({ value: 'gone', provider: provider({ resolve }) });
  await flushPromises();
  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('gone');
  expect(wrapper.findComponent({ name: 'UiError' }).exists()).toBe(false);

  resolution.resolve([{ id: 'gone', title: '已解析记录' }]);
  await flushPromises();
  expect(wrapper.emitted('selection-resolved')).toEqual([[[{ id: 'gone', title: '已解析记录' }]]]);
  expect(wrapper.emitted('select')).toBeUndefined();
  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('已解析记录');
});

it('drops dependent navigation filters while preserving the multiple selection draft', async () => {
  const candidateProvider = provider({
    searchPage: vi.fn().mockResolvedValue({
      records,
      total: 2,
      navigation: [
        { id: 'region', title: '区域', items: [{ id: 'east', title: '华东' }] },
        { id: 'site', title: '站点', dependsOn: ['region'], items: [{ id: 'sh', title: '上海' }] },
      ],
    }),
  });
  const wrapper = mountPicker({ provider: candidateProvider, multiple: true });
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
  await flushPromises();
  wrapper.findComponent({ name: 'UiDataTable' }).props('selection').onChange(['record-1']);
  const trees = wrapper.findAllComponents({ name: 'UiTree' });
  trees[0]!.vm.$emit('select', { key: 'east' });
  await flushPromises();
  wrapper.findAllComponents({ name: 'UiTree' })[1]!.vm.$emit('select', { key: 'sh' });
  await flushPromises();
  wrapper.findAllComponents({ name: 'UiTree' })[0]!.vm.$emit('select', { key: 'east' });
  await flushPromises();
  expect(candidateProvider.searchPage).toHaveBeenLastCalledWith({
    keyword: '',
    pageNum: 1,
    pageSize: 20,
    scope: { selections: [{ axisId: 'region', itemId: 'east' }] },
  });
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('selection').selectedRowKeys).toEqual([
    'record-1',
  ]);
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  expect(wrapper.emitted('update:value')).toEqual([[['record-1']]]);
});

it('allows replacing a single selected value when maxSelection is one', async () => {
  const wrapper = mountPicker({ value: 'record-1', maxSelection: 1 });
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
  await flushPromises();
  wrapper.findComponent({ name: 'UiDataTable' }).vm.$emit('rowDblclick', records[1]);
  expect(wrapper.emitted('update:value')).toEqual([['record-2']]);
});

it('isolates late pages when reloadKey changes and exposes full pagination from a dropdown', async () => {
  let finishOld: ((page: { records: typeof records; total: number }) => void) | undefined;
  const searchPage = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<{ records: typeof records; total: number }>((resolve) => {
          finishOld = resolve;
        }),
    )
    .mockResolvedValue({ records: [records[1]], total: 40 });
  const wrapper = mountPicker({ provider: provider({ searchPage }), reloadKey: 'a', mode: 'dropdown' });
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('search', '旧请求');
  await flushPromises();
  await wrapper.setProps({ reloadKey: 'b' });
  await flushPromises();
  finishOld!({ records, total: 2 });
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toEqual([
    { value: 'record-2', label: '第二条', disabled: false },
  ]);
  await wrapper.find('button').trigger('click');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(true);
});

it('loads a dropdown on mount and resets its remembered projection when source identity changes', async () => {
  const candidateProvider = reactive(
    provider({
      searchPage: vi
        .fn()
        .mockResolvedValueOnce({ records: [records[0]], total: 1 })
        .mockResolvedValueOnce({ records: [records[1]], total: 1 }),
    }),
  );
  const wrapper = mountPicker({ provider: candidateProvider, mode: 'dropdown' });
  await flushPromises();
  expect(candidateProvider.searchPage).toHaveBeenCalledTimes(1);
  candidateProvider.identity.source.id = 'changed-source';
  await flushPromises();
  expect(candidateProvider.searchPage).toHaveBeenCalledTimes(2);
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toEqual([
    { value: 'record-2', label: '第二条', disabled: false },
  ]);
});

it('keeps a resolver-authorized selected label in dropdown options when it is outside the current page', async () => {
  const wrapper = mountPicker({
    value: 'record-1',
    mode: 'dropdown',
    provider: provider({
      resolve: vi.fn().mockResolvedValue([records[0]]),
      searchPage: vi.fn().mockResolvedValue({ records: [records[1]], total: 40 }),
    }),
  });
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toEqual([
    { value: 'record-2', label: '第二条', disabled: false },
    { value: 'record-1', label: '第一条', disabled: false },
  ]);
});

it('keeps a rejected historical resolution neutral even when a page later contains its ID', async () => {
  const resolution = deferred<ReferencePickerCandidate[]>();
  const wrapper = mountPicker({
    value: 'record-1',
    mode: 'dropdown',
    provider: provider({
      resolve: vi.fn().mockReturnValue(resolution.promise),
      searchPage: vi.fn().mockResolvedValue({ records: [records[0]], total: 1 }),
    }),
  });
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toEqual([
    { value: 'record-1', label: 'record-1', disabled: true },
  ]);

  resolution.reject(new Error('历史投影不可读'));
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toEqual([
    { value: 'record-1', label: 'record-1', disabled: true },
  ]);
  expect(wrapper.findComponent({ name: 'UiError' }).props('message')).toBe('历史投影不可读');
  expect(wrapper.text()).toContain('重试');
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('update:value', 'new-unresolved-id');
  await flushPromises();
  expect(wrapper.emitted('update:value')).toBeUndefined();
});

it('does not offer a local retry for an explicit permission denial', async () => {
  const wrapper = mountPicker({
    value: 'record-1',
    provider: provider({
      resolve: vi.fn().mockRejectedValue(new AppError('无权读取历史引用', { status: 403 })),
    }),
  });
  await flushPromises();

  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('record-1');
  expect(wrapper.findComponent({ name: 'UiError' }).props('message')).toBe('无权读取历史引用');
  expect(wrapper.text()).not.toContain('重试');
});

it('keeps the ID while asking the user to complete a declared dependency', async () => {
  const wrapper = mountPicker({
    value: 'record-1',
    provider: provider({
      resolve: vi.fn().mockRejectedValue(missingReferencePickerDependencies(['classId'])),
    }),
  });
  await flushPromises();

  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('record-1');
  expect(wrapper.findComponent({ name: 'UiError' }).props('message')).toBe('请先补齐引用所需的依赖字段');
  expect(wrapper.text()).not.toContain('重试');
});

it('does not turn a 401 into picker-local feedback', async () => {
  const wrapper = mountPicker({
    value: 'record-1',
    provider: provider({ resolve: vi.fn().mockRejectedValue(new AppError('登录已失效', { status: 401 })) }),
  });
  await flushPromises();

  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('record-1');
  expect(wrapper.findComponent({ name: 'UiError' }).exists()).toBe(false);
});

it('keeps a failed 401 candidate page non-committable without showing local feedback', async () => {
  const wrapper = mountPicker({
    value: 'record-1',
    provider: provider({
      searchPage: vi.fn().mockRejectedValue(new AppError('登录已失效', { status: 401 })),
    }),
  });
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
  await flushPromises();

  expect(wrapper.findComponent({ name: 'UiError' }).exists()).toBe(false);
  expect((wrapper.vm as unknown as { canCommit: () => boolean }).canCommit()).toBe(false);
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  expect(wrapper.emitted('update:value')).toBeUndefined();
});

it('marks an ID unavailable only when a successful resolver response omits it', async () => {
  const resolution = deferred<ReferencePickerCandidate[]>();
  const wrapper = mountPicker({
    value: 'missing',
    mode: 'dropdown',
    provider: provider({
      resolve: vi.fn().mockReturnValue(resolution.promise),
      searchPage: vi.fn().mockResolvedValue({ records: [], total: 0 }),
    }),
  });
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toEqual([
    { value: 'missing', label: 'missing', disabled: true },
  ]);

  resolution.resolve([]);
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toEqual([
    { value: 'missing', label: 'missing（不可用）', disabled: true },
  ]);
  expect(wrapper.emitted('selection-resolved')).toEqual([
    [[{ id: 'missing', title: 'missing', unavailable: true }]],
  ]);
});

it('clears a prior source title while the replacement source resolves', async () => {
  const replacement = deferred<ReferencePickerCandidate[]>();
  const candidateProvider = reactive(
    provider({
      resolve: vi
        .fn()
        .mockResolvedValueOnce([{ id: 'record-1', title: '旧来源标题' }])
        .mockReturnValueOnce(replacement.promise),
    }),
  );
  const wrapper = mountPicker({ value: 'record-1', provider: candidateProvider });
  await flushPromises();
  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('旧来源标题');

  candidateProvider.identity.source.id = 'changed-source';
  await flushPromises();
  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('record-1');

  replacement.resolve([{ id: 'record-1', title: '新来源标题' }]);
  await flushPromises();
  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('新来源标题');
});

it('ignores a stale deferred resolver after a source switch', async () => {
  const staleResolution = deferred<ReferencePickerCandidate[]>();
  const replacement = deferred<ReferencePickerCandidate[]>();
  const candidateProvider = reactive(
    provider({
      resolve: vi.fn().mockReturnValueOnce(staleResolution.promise).mockReturnValueOnce(replacement.promise),
    }),
  );
  const wrapper = mountPicker({ value: 'record-1', provider: candidateProvider });
  await flushPromises();
  candidateProvider.identity.source.id = 'changed-source';
  await flushPromises();

  staleResolution.resolve([{ id: 'record-1', title: '过期来源标题' }]);
  await flushPromises();
  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('record-1');

  replacement.resolve([{ id: 'record-1', title: '当前来源标题' }]);
  await flushPromises();
  expect(wrapper.findComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('当前来源标题');
});

it('allows cancelling a loading dialog and ignores its late candidate page', async () => {
  let finish: ((page: { records: typeof records; total: number }) => void) | undefined;
  const wrapper = mountPicker({
    provider: provider({
      searchPage: vi.fn().mockImplementation(
        () =>
          new Promise<{ records: typeof records; total: number }>((resolve) => {
            finish = resolve;
          }),
      ),
    }),
  });
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', '');
  await flushPromises();
  const modal = wrapper.findComponent({ name: 'UiModal' });
  expect(modal.props('closable')).toBe(true);
  modal.vm.$emit('cancel');
  finish!({ records, total: 2 });
  await flushPromises();
  expect(modal.props('open')).toBe(false);
  expect((wrapper.vm as unknown as { page: { records: unknown[] } }).page.records).toEqual([]);
});

it('clears a prior page before a failed scoped search so stale candidates cannot be committed', async () => {
  const searchPage = vi
    .fn()
    .mockResolvedValueOnce({ records: [records[0]], total: 1 })
    .mockRejectedValueOnce(new Error('新范围不可读取'));
  const wrapper = mountPicker({ provider: provider({ searchPage }) });
  wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse', 'first');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toHaveLength(1);
  wrapper.findComponent({ name: 'UiSearchInput' }).vm.$emit('search', 'second');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toEqual([]);
  expect((wrapper.vm as unknown as { canCommit: () => boolean }).canCommit()).toBe(false);
  expect(wrapper.emitted('update:value')).toBeUndefined();
});

it('resets an open draft to external IDs on reload and applies max selection to existing dropdown values', async () => {
  const candidateProvider = provider({
    resolve: vi.fn().mockResolvedValue([records[0], { id: 'history-2', title: '历史二' }]),
    searchPage: vi.fn().mockResolvedValue({ records: [records[1]], total: 1 }),
  });
  const wrapper = mountPicker({
    provider: candidateProvider,
    mode: 'dropdown',
    multiple: true,
    maxSelection: 2,
    value: ['record-1', 'history-2'],
    reloadKey: 'before',
  });
  await flushPromises();
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('update:value', ['record-1', 'history-2', 'record-2']);
  await flushPromises();
  expect(wrapper.emitted('update:value')).toEqual([[['record-1', 'history-2']]]);
  await wrapper.setProps({ reloadKey: 'after' });
  await flushPromises();
  expect((wrapper.vm as unknown as { draftIds: string[] }).draftIds).toEqual(['record-1', 'history-2']);
});

it('permits removing a persisted multiple value while requiring a current candidate for additions', async () => {
  const wrapper = mountPicker({
    provider: provider({
      resolve: vi.fn().mockResolvedValue([records[0], { id: 'history-2', title: '历史二' }]),
      searchPage: vi.fn().mockResolvedValue({ records: [records[1]], total: 1 }),
    }),
    mode: 'dropdown',
    multiple: true,
    value: ['record-1', 'history-2'],
  });
  await flushPromises();
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('update:value', ['record-1', 'record-2']);
  await flushPromises();
  expect(wrapper.emitted('update:value')).toEqual([[['record-1', 'record-2']]]);
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('update:value', ['missing']);
  await flushPromises();
  expect(wrapper.emitted('update:value')).toEqual([[['record-1', 'record-2']]]);
});

it('refreshes the compact display when confirming the existing reference again', async () => {
  const wrapper = mountPicker({
    value: 'record-1',
    provider: provider({ resolve: vi.fn().mockResolvedValue([records[0]]) }),
  });
  await flushPromises();
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  const version = input.props('selectionVersion');
  input.vm.$emit('browse', '');
  await flushPromises();
  wrapper.findComponent({ name: 'UiDataTable' }).vm.$emit('rowClick', records[0]);
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await flushPromises();
  expect(wrapper.emitted('update:value')).toEqual([['record-1']]);
  expect(input.props('selectionVersion')).toBe(version + 1);
  expect(input.props('value')).toBe('第一条');
});

it('completes one available blurred draft through the standard selection commit', async () => {
  const candidateProvider = provider({
    searchPage: vi.fn().mockResolvedValue({ records: [records[0]], total: 1 }),
    resolve: vi.fn().mockResolvedValue([records[0]]),
  });
  const wrapper = mountPicker({ provider: candidateProvider });
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });

  input.vm.$emit('draft-change', '第一');
  input.vm.$emit('blur', '第一');
  await flushPromises();

  expect(candidateProvider.searchPage).toHaveBeenCalledWith({
    keyword: '第一',
    pageNum: 1,
    pageSize: 20,
    scope: { selections: [] },
  });
  expect(wrapper.emitted('update:value')).toEqual([['record-1']]);
  expect(wrapper.emitted('select')).toEqual([[[records[0]]]]);
  expect(input.props('selectionVersion')).toBe(1);
});

it('restores the compact display when blurred completion resolves to the same ID', async () => {
  const candidateProvider = provider({
    searchPage: vi.fn().mockResolvedValue({ records: [records[0]], total: 1 }),
    resolve: vi.fn().mockResolvedValue([records[0]]),
  });
  const wrapper = mountPicker({ provider: candidateProvider, value: 'record-1' });
  await flushPromises();
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  const previousVersion = input.props('selectionVersion');
  input.vm.$emit('draft-change', 'A-01');
  input.vm.$emit('blur', 'A-01');
  await flushPromises();

  expect(wrapper.emitted('update:value')).toEqual([['record-1']]);
  expect(wrapper.emitted('select')).toEqual([[[records[0]]]]);
  expect(input.props('selectionVersion')).toBe(previousVersion + 1);
  expect(input.props('value')).toBe('第一条');
});

it('opens the queried page for multiple results and never replaces an existing multiple value', async () => {
  const candidateProvider = provider({
    searchPage: vi.fn().mockResolvedValue({ records: [records[1]], total: 2 }),
    resolve: vi.fn().mockResolvedValue([records[0]]),
  });
  const wrapper = mountPicker({ provider: candidateProvider, multiple: true, value: ['record-1'] });
  await flushPromises();
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  input.vm.$emit('draft-change', '第二');
  input.vm.$emit('blur', '第二');
  await flushPromises();

  expect(candidateProvider.searchPage).toHaveBeenCalledTimes(1);
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(true);
  expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toEqual([
    { id: 'record-2', title: '第二条', subtitle: undefined, code: 'A-02' },
  ]);
  expect(wrapper.emitted('update:value')).toBeUndefined();
});

it('marks a zero-result draft without extra message and clears the mark on editing', async () => {
  const candidateProvider = provider({ searchPage: vi.fn().mockResolvedValue({ records: [], total: 0 }) });
  const wrapper = mountPicker({ provider: candidateProvider });
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  input.vm.$emit('draft-change', '不存在');
  input.vm.$emit('blur', '不存在');
  await flushPromises();

  expect(input.props('unmatched')).toBe(true);
  expect(wrapper.text()).not.toContain('未找到匹配的可选记录');
  input.vm.$emit('draft-change', '新输入');
  await flushPromises();
  expect(input.props('unmatched')).toBe(false);
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(false);
  expect(wrapper.emitted('update:value')).toBeUndefined();
});

it('keeps the unmatched warning on an actual compact input after cancelling browse', async () => {
  const candidateProvider = provider({ searchPage: vi.fn().mockResolvedValue({ records: [], total: 0 }) });
  const wrapper = mountPickerWithInput({ provider: candidateProvider });
  try {
    const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
    await wrapper.get('input').setValue('不存在');
    await wrapper.get('input').trigger('blur', { relatedTarget: document.body });
    await flushPromises();

    expect(input.props('unmatched')).toBe(true);
    input.vm.$emit('browse', '不存在');
    await flushPromises();
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('cancel');
    await flushPromises();

    expect(input.props('unmatched')).toBe(true);
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
      { valid: false, status: 'unmatched', message: '未找到可选择的记录' },
    ]);
  } finally {
    wrapper.unmount();
  }
});

it('retries a failed blurred completion with its original keyword', async () => {
  const candidateProvider = provider({
    searchPage: vi
      .fn()
      .mockRejectedValueOnce(new Error('候选暂时不可读'))
      .mockResolvedValueOnce({ records: [records[0]], total: 1 }),
  });
  const wrapper = mountPicker({ provider: candidateProvider });
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  input.vm.$emit('draft-change', '第一');
  input.vm.$emit('blur', '第一');
  await flushPromises();
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'error', message: '候选暂时不可读' },
  ]);
  await wrapper.find('.reference-picker-error button').trigger('click');
  await flushPromises();

  expect(candidateProvider.searchPage).toHaveBeenNthCalledWith(2, {
    keyword: '第一',
    pageNum: 1,
    pageSize: 20,
    scope: { selections: [] },
  });
  expect(wrapper.emitted('update:value')).toEqual([['record-1']]);
});

it.each([{ disabled: true }, { unavailable: true }])(
  'does not auto-complete a disabled or unavailable singleton %#',
  async (candidateState) => {
    const candidateProvider = provider({
      searchPage: vi.fn().mockResolvedValue({
        records: [{ ...records[0], ...candidateState }],
        total: 1,
      }),
    });
    const wrapper = mountPicker({ provider: candidateProvider });
    const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
    input.vm.$emit('draft-change', '第一');
    input.vm.$emit('blur', '第一');
    await flushPromises();

    expect(wrapper.emitted('update:value')).toBeUndefined();
    expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(false);
    expect(input.props('unmatched')).toBe(true);
  },
);

it('ignores a stale blurred completion after another draft is entered', async () => {
  const oldPage = deferred<{ records: typeof records; total: number }>();
  const candidateProvider = provider({
    searchPage: vi
      .fn()
      .mockReturnValueOnce(oldPage.promise)
      .mockResolvedValueOnce({ records: [records[1]], total: 1 }),
  });
  const wrapper = mountPicker({ provider: candidateProvider });
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  input.vm.$emit('draft-change', '旧');
  input.vm.$emit('blur', '旧');
  await flushPromises();
  input.vm.$emit('draft-change', '新');
  input.vm.$emit('blur', '新');
  await flushPromises();
  oldPage.resolve({ records: [records[0]], total: 1 });
  await flushPromises();

  expect(wrapper.emitted('update:value')).toEqual([['record-2']]);
  expect(wrapper.emitted('select')).toEqual([[[records[1]]]]);
});

it('invalidates a pending blurred completion when its provider changes or unmounts', async () => {
  const pending = deferred<{ records: typeof records; total: number }>();
  const candidateProvider = provider({ searchPage: vi.fn().mockReturnValue(pending.promise) });
  const wrapper = mountPicker({ provider: candidateProvider });
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  input.vm.$emit('draft-change', '旧');
  input.vm.$emit('blur', '旧');
  await flushPromises();
  await wrapper.setProps({ provider: provider() });
  wrapper.unmount();
  pending.resolve({ records: [records[0]], total: 1 });
  await flushPromises();

  expect(wrapper.emitted('update:value')).toBeUndefined();
});

it('synchronously marks a retained ID invalid while its display draft is unconfirmed', async () => {
  const completion = deferred<{ records: typeof records; total: number }>();
  const wrapper = mountPicker({
    value: 'record-1',
    provider: provider({
      resolve: vi.fn().mockResolvedValue([records[0]]),
      searchPage: vi.fn().mockReturnValue(completion.promise),
    }),
  });
  await flushPromises();
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });

  input.vm.$emit('draft-change', '未确认草稿');
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'editing', message: '请完成引用选择' },
  ]);
  expect(wrapper.emitted('update:value')).toBeUndefined();

  input.vm.$emit('blur', '未确认草稿');
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'resolving', message: '正在确认引用选择' },
  ]);
  completion.resolve({ records: [], total: 0 });
  await flushPromises();
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'unmatched', message: '未找到可选择的记录' },
  ]);
});

it('clears an ID only once when deleting its compact display and keeps a forbidden blank invalid', () => {
  const clearable = mountPicker({ value: 'record-1' });
  const clearableInput = clearable.findComponent({ name: 'ObjectPickerInput' });
  clearableInput.vm.$emit('draft-change', '');
  clearableInput.vm.$emit('clear');
  expect(clearable.emitted('update:value')).toEqual([[undefined]]);
  expect(clearable.emitted('select')).toEqual([[[]]]);
  expect(clearable.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, status: 'ready' }]);

  const protectedPicker = mountPicker({ value: 'record-1', allowClear: false });
  protectedPicker.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('draft-change', '');
  expect(protectedPicker.emitted('update:value')).toBeUndefined();
  expect(protectedPicker.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'editing', message: '请完成引用选择' },
  ]);
});

it('restores matching text as ready and does not let dialog cancellation validate another draft', async () => {
  const candidateProvider = provider({
    resolve: vi.fn().mockResolvedValue([records[0]]),
    searchPage: vi.fn().mockResolvedValue({ records, total: 2 }),
  });
  const wrapper = mountPicker({ value: 'record-1', provider: candidateProvider });
  await flushPromises();
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  input.vm.$emit('draft-change', '第一条');
  input.vm.$emit('blur', '第一条');
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, status: 'ready' }]);

  input.vm.$emit('draft-change', '待确认');
  input.vm.$emit('browse', '待确认');
  await flushPromises();
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('cancel');
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'editing', message: '请完成引用选择' },
  ]);
});

it('keeps matching selected text ready through the actual compact input update and blur', async () => {
  const wrapper = mountPickerWithInput({
    value: 'record-1',
    provider: provider({ resolve: vi.fn().mockResolvedValue([records[0]]) }),
  });
  try {
    await flushPromises();
    const validityEventsBeforeEdit = wrapper.emitted('validity-change')?.length;
    await wrapper.get('input').setValue('第一条');
    await wrapper.get('input').trigger('blur', { relatedTarget: document.body });
    expect(wrapper.emitted('validity-change')).toHaveLength(validityEventsBeforeEdit ?? 0);
    expect(wrapper.emitted('update:value')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});

it('preserves an unresolved draft when the parent re-emits the same ID array', async () => {
  const wrapper = mountPicker({ multiple: true, value: ['record-1'] });
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  input.vm.$emit('draft-change', '待确认');
  await wrapper.setProps({ value: ['record-1'] });
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'editing', message: '请完成引用选择' },
  ]);
});

it('returns an invalidated completion to editing when its provider resets', async () => {
  const pending = deferred<{ records: typeof records; total: number }>();
  const wrapper = mountPicker({
    provider: provider({ searchPage: vi.fn().mockReturnValue(pending.promise) }),
  });
  const input = wrapper.findComponent({ name: 'ObjectPickerInput' });
  input.vm.$emit('draft-change', '待确认');
  input.vm.$emit('blur', '待确认');
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'resolving', message: '正在确认引用选择' },
  ]);
  await wrapper.setProps({ provider: provider() });
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'editing', message: '请完成引用选择' },
  ]);
});
