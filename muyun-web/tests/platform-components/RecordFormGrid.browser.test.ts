import { mount } from '@vue/test-utils';
import { h } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import RecordFormGrid from '@/platform-components/RecordFormGrid.vue';
import UiSelect from '@/vue-ui-antdv/components/UiSelect.vue';
import UiInput from '@/vue-ui-antdv/components/UiInput.vue';
import '@/styles.css';

it('keeps long searchable selections within their form column', async () => {
  await page.viewport(992, 814);
  const wrapper = mount(RecordFormGrid, {
    attrs: { style: 'width: 649px' },
    slots: {
      default: () => [
        h('label', [
          '目标模块',
          h(UiSelect, {
            value: 'purchase',
            showSearch: true,
            style: 'width: 100%',
            options: [{ value: 'purchase', label: '验收·采购申请 · education.accept_purchase_0906' }],
          }),
        ]),
        h('label', ['显示名称', h(UiInput, { value: '验收·采购申请' })]),
      ],
    },
    attachTo: document.body,
  });
  try {
    const column = wrapper.get('label').element.getBoundingClientRect();
    const select = wrapper.get('.ant-select').element.getBoundingClientRect();
    const neighbor = wrapper.findAll('label')[1]!.element.getBoundingClientRect();
    expect(select.width).toBeLessThanOrEqual(column.width + 1);
    expect(select.right).toBeLessThan(neighbor.left);
    await page.elementLocator(wrapper.get('.ant-select-selector').element).click();
    expect(wrapper.get('.ant-select').element.getBoundingClientRect().right).toBeLessThan(neighbor.left);
  } finally {
    wrapper.unmount();
  }
});

it.each([992, 390])('keeps source controls with their own field at viewport %s', async (width) => {
  await page.viewport(width, 814);
  const { default: RecordFormFields } = await import('@/platform-components/RecordFormFields.vue');
  const fields = new Map([
    ['code', { fieldRef: { fieldName: 'code' }, label: 'Code' }],
    [
      'title',
      {
        fieldRef: { fieldName: 'title' },
        label: 'Title',
        content: { key: 'title', policy: 'MANUAL_TRANSLATIONS' as const },
      },
    ],
    [
      'description',
      {
        fieldRef: { fieldName: 'description' },
        label: 'Description',
        uiType: 'textarea',
        columnSpan: 2 as const,
        content: { key: 'description', policy: 'AUTO_TRANSLATE' as const },
      },
    ],
  ]);
  const wrapper = mount(RecordFormGrid, {
    attrs: { style: 'width: min(680px, calc(100vw - 32px)); margin: 16px' },
    slots: {
      default: () =>
        h(RecordFormFields, {
          fields,
          record: { code: 'C001', title: 'Original title', description: 'Original description' },
          mode: 'create',
        }),
    },
    attachTo: document.body,
  });
  try {
    const hosts = wrapper.findAll('.record-form-field-host');
    const title = hosts[1]!.element.getBoundingClientRect();
    const source = wrapper.get('[data-content-field="title"]').element.getBoundingClientRect();
    const input = hosts[1]!.get('input').element.getBoundingClientRect();
    expect(source.left).toBeGreaterThanOrEqual(title.left);
    expect(source.right).toBeLessThanOrEqual(title.right + 1);
    expect(source.top).toBeGreaterThanOrEqual(input.bottom);
    expect(source.bottom).toBeLessThanOrEqual(title.bottom + 1);
    const fullRow = hosts[2]!.element.getBoundingClientRect();
    const grid = wrapper.element.getBoundingClientRect();
    expect(fullRow.width).toBeCloseTo(grid.width, 0);
    expect(fullRow.top).toBeGreaterThanOrEqual(title.bottom);
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width + 1);
  } finally {
    wrapper.unmount();
  }
});
