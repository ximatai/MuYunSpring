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
