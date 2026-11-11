import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import UiInput from '@/vue-ui-antdv/components/UiInput.vue';

describe('UiInput password visibility', () => {
  it('keeps existing password inputs masked unless visibility toggling is enabled', () => {
    const wrapper = mount(UiInput, { props: { type: 'password', value: 'test-password' } });
    expect(wrapper.get('input').attributes('type')).toBe('password');
    expect(wrapper.find('button').exists()).toBe(false);
    wrapper.unmount();
  });

  it('starts masked on every mount and preserves editing, disabled state and input attributes', async () => {
    const props = {
      type: 'password' as const,
      visibilityToggle: true,
      value: 'test-password',
      autocomplete: 'current-password',
      required: true,
      invalid: true,
    };
    const wrapper = mount(UiInput, { props });
    const input = wrapper.get('input');
    expect(input.attributes('type')).toBe('password');
    expect(input.attributes('autocomplete')).toBe('current-password');
    expect(input.attributes('required')).toBeDefined();
    expect(input.attributes('aria-invalid')).toBe('true');
    expect(wrapper.get('button').attributes('type')).toBe('button');
    expect(wrapper.get('button').attributes('aria-label')).toBe('显示密码');
    expect(wrapper.get('button').attributes('aria-pressed')).toBe('false');
    await wrapper.get('button').trigger('click');
    expect(input.attributes('type')).toBe('text');
    expect(wrapper.get('button').attributes('aria-label')).toBe('隐藏密码');
    expect(wrapper.get('button').attributes('aria-pressed')).toBe('true');
    await input.setValue('edited-password');
    expect(wrapper.emitted('update:value')?.at(-1)).toEqual(['edited-password']);
    await wrapper.setProps({ disabled: true });
    expect(wrapper.get('button').attributes('disabled')).toBeDefined();
    await wrapper.get('button').trigger('click');
    expect(input.attributes('type')).toBe('text');
    expect(input.attributes('disabled')).toBeDefined();
    wrapper.unmount();
    const remounted = mount(UiInput, { props });
    expect(remounted.get('input').attributes('type')).toBe('password');
    remounted.unmount();
  });
});
