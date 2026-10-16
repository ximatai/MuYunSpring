import { defineComponent, h } from 'vue';
import { businessContentLanguageContext, createLanguageContext, provideLanguageContext } from '@/web-core';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import ScopedTreePicker from '@/platform-components/ScopedTreePicker.vue';
import type { ScopedTreePickerProvider } from '@/platform-components/scopedTreePickerModel';

const roots = [{ id: 'organization-1', title: '研发中心', isLeaf: false }];
const children = [{ id: 'department-1', title: '平台研发部', subtitle: '研发中心', isLeaf: true }];

function provider(overrides: Partial<ScopedTreePickerProvider> = {}): ScopedTreePickerProvider {
  return {
    loadRoot: vi.fn().mockResolvedValue({ records: roots }),
    loadChildren: vi.fn().mockResolvedValue({ records: children }),
    resolve: vi.fn().mockResolvedValue([]),
    ...overrides,
  };
}

function mountPicker(
  options: { provider: ScopedTreePickerProvider; value?: string } = { provider: provider() },
) {
  return shallowMount(ScopedTreePicker, {
    props: options,
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
          emits: ['click'],
          template: '<button @click="$emit(\'click\', $event)"><slot /></button>',
        },
      },
    },
  });
}

it('loads source-owned roots from the input keyword without knowing a module path', async () => {
  const source = provider();
  const wrapper = mountPicker({ provider: source });

  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '研发');
  await flushPromises();

  expect(source.loadRoot).toHaveBeenCalledWith(
    expect.objectContaining({ keyword: '研发', signal: expect.any(AbortSignal) }),
  );
  expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(true);
  expect(wrapper.findComponent({ name: 'UiTree' }).props('nodes')).toMatchObject([
    { key: 'organization-1', title: '研发中心', isLeaf: false },
  ]);
});

it('loads children lazily through the injected provider', async () => {
  const source = provider();
  const wrapper = mountPicker({ provider: source });
  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '');
  await flushPromises();

  const tree = wrapper.findComponent({ name: 'UiTree' });
  const loadChildren = tree.props('loadChildren') as (
    node: { key: string },
    request: unknown,
  ) => Promise<unknown>;
  const controller = new AbortController();
  const result = await loadChildren({ key: 'organization-1' }, {
    reason: 'expand',
    signal: controller.signal,
  } as never);

  expect(source.loadChildren).toHaveBeenCalledWith(
    expect.objectContaining({ parent: roots[0], keyword: '', signal: controller.signal }),
  );
  expect(result).toMatchObject({ mode: 'replace', nodes: [{ key: 'department-1' }], hasMore: false });
});

it('keeps the external value unchanged on cancel and only commits the tree draft on confirmation', async () => {
  const source = provider();
  const wrapper = mountPicker({ provider: source, value: 'department-1' });
  await flushPromises();
  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '');
  await flushPromises();
  wrapper.findComponent({ name: 'UiTree' }).vm.$emit('select', { key: 'organization-1' });
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('cancel');
  await flushPromises();

  expect(wrapper.emitted('update:value')).toBeUndefined();

  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '');
  await flushPromises();
  wrapper.findComponent({ name: 'UiTree' }).vm.$emit('select', { key: 'organization-1' });
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await flushPromises();

  expect(wrapper.emitted('update:value')).toEqual([['organization-1']]);
  expect(wrapper.emitted('select')).toEqual([[roots[0]]]);
});

it('resolves persisted values through the same source-owned provider', async () => {
  const source = provider({ resolve: vi.fn().mockResolvedValue(children) });
  const wrapper = mountPicker({ provider: source, value: 'department-1' });
  await flushPromises();

  expect(source.resolve).toHaveBeenCalledWith(['department-1']);
  expect(wrapper.text()).toContain('平台研发部');
});

it('does not let an older root request replace the latest keyword result', async () => {
  let resolveFirst: ((value: { records: typeof roots }) => void) | undefined;
  const source = provider({
    loadRoot: vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<{ records: typeof roots }>((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockResolvedValueOnce({ records: children }),
  });
  const wrapper = mountPicker({ provider: source });

  wrapper.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '旧');
  await flushPromises();
  wrapper.findAllComponents({ name: 'UiSearchInput' })[1]!.vm.$emit('search', '新');
  await flushPromises();
  resolveFirst!({ records: roots });
  await flushPromises();

  expect(wrapper.findComponent({ name: 'UiTree' }).props('nodes')).toMatchObject([
    { key: 'department-1', title: '平台研发部' },
  ]);
});

it('relabels retained lazy nodes without losing expansion, search or the pending selection', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const parent = {
    id: 'parent',
    title: '设备分类',
    isLeaf: false,
    titleContent: { originalText: '设备分类', readings: { en: 'Equipment category', de: 'Gerätekategorie' } },
  };
  const child = {
    id: 'child',
    title: '设备',
    isLeaf: true,
    titleContent: { originalText: '设备', readings: { en: 'Equipment', de: 'Gerät' } },
  };
  const source = provider({
    loadRoot: vi.fn().mockResolvedValue({ records: [parent] }),
    loadChildren: vi.fn().mockResolvedValue({ records: [child] }),
  });
  const wrapper = shallowMount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(ScopedTreePicker, { provider: source });
      },
    }),
    {
      global: { stubs: { ScopedTreePicker: false, UiModal: { template: '<section><slot /></section>' } } },
    },
  );
  try {
    const picker = wrapper.findComponent(ScopedTreePicker);
    picker.findAllComponents({ name: 'UiSearchInput' })[0]!.vm.$emit('search', '');
    await flushPromises();
    const tree = picker.findComponent({ name: 'UiTree' });
    const retained = await tree.props('loadChildren')(
      { key: 'parent' },
      { signal: new AbortController().signal, reason: 'expand' },
    );
    tree.vm.$emit('update:expandedKeys', ['parent']);
    tree.vm.$emit('select', { key: 'child' });
    const search = picker.findAllComponents({ name: 'UiSearchInput' }).at(-1)!;
    search.vm.$emit('update:value', 'draft keyword');
    businessContentLanguageContext(language).setLanguage('de');
    await flushPromises();
    expect(tree.props('nodes')[0].title).toBe('Gerätekategorie');
    expect(retained.nodes[0].title).toBe('Gerät');
    expect(tree.props('expandedKeys')).toEqual(['parent']);
    expect(tree.props('selectedKey')).toBe('child');
    expect(search.props('value')).toBe('draft keyword');
    expect(source.loadRoot).toHaveBeenCalledTimes(1);
    expect(source.loadChildren).toHaveBeenCalledTimes(1);
    expect(picker.emitted('update:value')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});
