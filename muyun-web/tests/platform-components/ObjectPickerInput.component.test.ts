import { shallowMount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import ObjectPickerInput from '@/platform-components/ObjectPickerInput.vue';

it('uses the standard compact search entry for candidate browsing', () => {
  const wrapper = shallowMount(ObjectPickerInput, { props: { value: '演示租户管理员 (demo_admin)' } });
  const input = wrapper.findComponent({ name: 'UiSearchInput' });

  expect(input.props('searchIconOnly')).toBe(true);
  expect(input.classes()).toContain('object-picker-input-control');
  input.vm.$emit('search', '演示租户管理员 (demo_admin)');
  expect(wrapper.emitted('browse')).toEqual([['']]);
});

it('clears a selected value without treating the clear affordance as an empty browse', async () => {
  const wrapper = shallowMount(ObjectPickerInput, { props: { value: 'user-1' } });
  const input = wrapper.findComponent({ name: 'UiSearchInput' });

  input.vm.$emit('update:value', '');
  await wrapper.vm.$nextTick();
  input.vm.$emit('search', '');

  expect(wrapper.emitted('clear')).toEqual([[]]);
  expect(wrapper.emitted('browse')).toBeUndefined();
});
