import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { h } from 'vue';
import UiSelect from '@/vue-ui-antdv/components/UiSelect.vue';

it('forwards typed search text so remote reference candidates can refresh', async () => {
  const wrapper = mount(UiSelect, {
    props: { options: [{ value: 'supplier-1', label: '青禾供应商' }], showSearch: true, filterOption: false },
    attachTo: document.body,
  });
  try {
    await wrapper.get('input').setValue('青禾');
    await flushPromises();
    expect(wrapper.emitted('search')).toContainEqual(['青禾']);
    await wrapper.get('input').setValue('');
    await flushPromises();
    expect(wrapper.emitted('search')).toContainEqual(['']);
  } finally {
    wrapper.unmount();
  }
});

it('keeps a suffix action inside the select and closes its dropdown without changing the value', async () => {
  const browse = vi.fn();
  const wrapper = mount(UiSelect, {
    props: { value: 'supplier-1', options: [{ value: 'supplier-1', label: '青禾供应商' }], showSearch: true },
    slots: { suffixAction: () => h('button', { type: 'button', onClick: browse }, '详细选择') },
    attachTo: document.body,
  });
  try {
    await wrapper.get('.ant-select-selector').trigger('mousedown');
    await flushPromises();
    expect(wrapper.get('input').attributes('aria-expanded')).toBe('true');
    const action = wrapper.get('.ui-select-suffix-action button');
    await action.trigger('mousedown');
    await action.trigger('click');
    await flushPromises();
    expect(browse).toHaveBeenCalledOnce();
    expect(wrapper.get('input').attributes('aria-expanded')).toBe('false');
    expect(wrapper.emitted('update:value')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});
