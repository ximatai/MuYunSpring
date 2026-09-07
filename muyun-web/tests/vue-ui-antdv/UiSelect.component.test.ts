import { flushPromises, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
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
