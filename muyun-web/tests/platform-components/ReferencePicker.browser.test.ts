import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';
import { expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import ReferencePicker from '@/platform-components/ReferencePicker.vue';
import type { ReferencePickerProvider } from '@/platform-components/referencePickerModel';

const provider: ReferencePickerProvider = {
  identity: { targetModuleAlias: 'demo.record', source: { kind: 'sourceField', id: 'ownerId' } },
  searchPage: async () => ({ records: [], total: 0 }),
  resolve: async () => [],
};

const selected = Array.from({ length: 24 }, (_, index) => ({
  id: `selected-${index + 1}`,
  title: `Selected person ${index + 1}`,
  subtitle: `Department ${index + 1}`,
}));

it.each(['dialog', 'dropdown', 'tree'] as const)(
  'folds many selected names within a narrow %s control',
  async (kind) => {
    const wrapper = mount(ReferencePicker, {
      props: {
        provider: {
          ...provider,
          resolve: async () => selected,
          ...(kind === 'tree' ? { loadTree: async () => selected.map((record) => ({ record })) } : {}),
        },
        multiple: true,
        mode: kind === 'dialog' ? 'dialog' : 'dropdown',
        value: selected.map((item) => item.id),
      },
      attrs: { style: 'width: 320px' },
      attachTo: document.body,
    });
    try {
      await expect.poll(() => wrapper.find('.ant-select-selection-overflow-item-rest').exists()).toBe(true);
      const control = wrapper.get('.ant-select-selector').element;
      await expect.poll(() => control.getBoundingClientRect().height).toBeLessThan(45);
      expect(control.getBoundingClientRect().width).toBeLessThanOrEqual(320);
      expect(control.getBoundingClientRect().width).toBeGreaterThan(300);
      await expect.poll(() => wrapper.text()).toContain('Selected person 1');
      expect(wrapper.get('.ant-select-selection-overflow-item-rest').text()).toMatch(/\+\s*\d+/);
    } finally {
      wrapper.unmount();
    }
  },
);

it('reviews page-outside selections in a narrow dialog and keeps removals draft until confirmation', async () => {
  await page.viewport(640, 800);
  const searchPage = vi.fn<ReferencePickerProvider['searchPage']>(async () => ({ records: [], total: 0 }));
  const wrapper = mount(ReferencePicker, {
    global: { stubs: { transition: false, 'transition-group': false } },
    props: {
      provider: { ...provider, searchPage, resolve: async () => selected },
      multiple: true,
      value: selected.map((item) => item.id),
    },
    attrs: { style: 'width: 320px' },
    attachTo: document.body,
  });
  try {
    await expect.poll(() => wrapper.text()).toContain('Selected person 1');
    await page.getByRole('combobox').click();
    await page.getByRole('combobox').fill('person');
    await userEvent.keyboard('{Enter}');
    await page.getByPlaceholder('搜索已选内容').fill('Department 24');
    await page.getByRole('button', { name: '移除 Selected person 24 · Department 24', exact: true }).click();
    expect(searchPage).toHaveBeenCalledTimes(1);
    expect(wrapper.emitted('update:value')).toBeUndefined();
    const content = document.querySelector<HTMLElement>('.ui-modal-scroll-content')!;
    expect(content.scrollWidth).toBeLessThanOrEqual(content.clientWidth + 1);
    expect(document.querySelector('.ant-modal-footer')!.getBoundingClientRect().bottom).toBeLessThanOrEqual(
      800,
    );
    await page.getByRole('button', { name: /取\s*消/ }).click();
    expect(wrapper.findComponent({ name: 'UiModal' }).props('open')).toBe(false);
    await expect
      .poll(() => (wrapper.get('input[role="combobox"]').element as HTMLInputElement).value)
      .toBe('');
    await expect
      .poll(() => {
        const modal = document.querySelector<HTMLElement>('.ant-modal-wrap');
        return modal ? getComputedStyle(modal).display : 'none';
      })
      .toBe('none');
    await page.getByRole('combobox').click();
    expect((wrapper.get('input[role="combobox"]').element as HTMLInputElement).value).toBe('');
    await page.getByRole('button', { name: '选择引用记录', exact: true }).click();
    await page.getByPlaceholder('搜索已选内容').fill('Department 24');
    await expect
      .element(page.getByRole('button', { name: '移除 Selected person 24 · Department 24', exact: true }))
      .toBeVisible();
    await page.getByRole('button', { name: '移除 Selected person 24 · Department 24', exact: true }).click();
    await page.getByRole('button', { name: /确\s*认/ }).click();
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual([selected.slice(0, 23).map((item) => item.id)]);
  } finally {
    wrapper.unmount();
    await page.viewport(1280, 800);
  }
});

it('fills the width allocated by its form layout', async () => {
  const Fixture = defineComponent({
    setup() {
      return () => h('div', { style: 'width: 640px' }, [h(ReferencePicker, { provider })]);
    },
  });
  const wrapper = mount(Fixture, { attachTo: document.body });
  try {
    await nextTick();
    const picker = wrapper.get('.reference-picker').element;
    const input = wrapper.get('.object-picker-input-control').element;

    expect(Math.round(picker.getBoundingClientRect().width)).toBe(640);
    expect(Math.round(input.getBoundingClientRect().width)).toBe(640);
  } finally {
    wrapper.unmount();
  }
});

it('loads beyond the first dropdown page through the real popup scroll event', async () => {
  const searchPage = vi.fn<ReferencePickerProvider['searchPage']>(async ({ pageNum, pageSize }) => ({
    records: Array.from({ length: pageNum === 1 ? pageSize : 10 }, (_, index) => {
      const id = String((pageNum - 1) * pageSize + index + 1);
      return { id, title: `Candidate ${id}` };
    }),
    total: 60,
  }));
  const wrapper = mount(ReferencePicker, {
    props: { provider: { ...provider, searchPage }, mode: 'dropdown', pageSize: 50 },
    attachTo: document.body,
  });
  try {
    await expect.poll(() => searchPage.mock.calls.length).toBe(1);
    await page.getByRole('combobox').click();
    const popup = document.querySelector<HTMLElement>('.ant-select-dropdown .rc-virtual-list-holder');
    expect(popup).not.toBeNull();
    popup!.scrollTo({ top: popup!.scrollHeight });
    await expect.poll(() => searchPage.mock.calls.length).toBe(2);
    expect(searchPage.mock.calls[1]![0].pageNum).toBe(2);
    await nextTick();
    popup!.scrollTo({ top: popup!.scrollHeight });
    await page.getByText('Candidate 60', { exact: true }).click();
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual(['60']);
  } finally {
    wrapper.unmount();
  }
});
