import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import UiSelect from '@/vue-ui-antdv/components/UiSelect.vue';
import 'ant-design-vue/dist/reset.css';

it.each([true, false])(
  'searches visible labels locally and preserves remote candidates (filterOption=%s)',
  async (filterOption) => {
    const wrapper = mount(UiSelect, {
      props: {
        options: [
          { value: 'quantity', label: '数量' },
          { value: 'subject', label: '主题' },
        ],
        showSearch: true,
        filterOption,
        ariaLabel: '试算来源',
      },
      attachTo: document.body,
    });
    try {
      const input = page.getByRole('combobox');
      await input.fill('数量');
      await expect.element(page.getByText('数量', { exact: true })).toBeVisible();
      if (filterOption) {
        await expect.element(page.getByText('主题', { exact: true })).not.toBeInTheDocument();
      } else {
        await expect.element(page.getByText('主题', { exact: true })).toBeVisible();
      }
      expect(wrapper.emitted('search')).toContainEqual(['数量']);
    } finally {
      wrapper.unmount();
    }
  },
);
