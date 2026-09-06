import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordFieldLabel from '@/platform-components/RecordFieldLabel.vue';

it('expresses required fields consistently without marking optional fields', async () => {
  const wrapper = mount(RecordFieldLabel, { props: { required: true }, slots: { default: '名称' } });
  expect(wrapper.get('[aria-label="必填"]').text()).toBe('*');
  await wrapper.setProps({ required: false });
  expect(wrapper.find('[aria-label="必填"]').exists()).toBe(false);
  wrapper.unmount();
});
