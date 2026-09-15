import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { defineComponent } from 'vue';
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

it('opens candidate browsing when the input text is double-clicked', () => {
  const wrapper = shallowMount(ObjectPickerInput, { props: { value: '青禾供应商' } });
  const input = wrapper.findComponent({ name: 'UiSearchInput' });

  input.vm.$emit('dblclick', new MouseEvent('dblclick'));
  expect(wrapper.emitted('browse')).toEqual([['']]);
});

it('clears a selected value without treating the clear affordance as an empty browse', async () => {
  const wrapper = shallowMount(ObjectPickerInput, { props: { value: 'user-1' } });
  const input = wrapper.findComponent({ name: 'UiSearchInput' });

  input.vm.$emit('update:value', '');
  await wrapper.vm.$nextTick();
  input.vm.$emit('search', '', 'clear');

  expect(wrapper.emitted('clear')).toEqual([[]]);
  expect(wrapper.emitted('browse')).toBeUndefined();
});

it.each(['', '青禾供应商'])(
  'opens empty browsing after deleting text with initial selection %s',
  async (value) => {
    const wrapper = mount(ObjectPickerInput, { props: { value }, attachTo: document.body });
    try {
      await wrapper.get('input').setValue('');
      await wrapper.get('button').trigger('click');
      await flushPromises();
      expect(wrapper.emitted('browse')).toEqual([['']]);
      expect(wrapper.emitted('clear')).toEqual(value ? [[]] : undefined);
    } finally {
      wrapper.unmount();
    }
  },
);

it('distinguishes the real clear icon from an empty Enter search', async () => {
  const wrapper = mount(ObjectPickerInput, { props: { value: '青禾供应商' }, attachTo: document.body });
  try {
    expect(wrapper.get('button').attributes('tabindex')).toBe('-1');
    expect(wrapper.get('input').element.tabIndex).toBe(0);
    await wrapper.get('input').setValue('');
    await wrapper.get('input').trigger('keydown', { key: 'Enter', keyCode: 13 });
    await flushPromises();
    expect(wrapper.emitted('browse')).toEqual([['']]);
    expect(wrapper.emitted('clear')).toEqual([[]]);
    await wrapper.get('input').setValue('青禾');
    await wrapper.get('.ant-input-clear-icon').trigger('click');
    await flushPromises();
    expect(wrapper.emitted('clear')).toEqual([[], []]);
    expect(wrapper.emitted('browse')).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

it('marks only associated display text as linked and restores draft styling while editing', async () => {
  const wrapper = mount(ObjectPickerInput, { props: { value: '青禾供应商', linked: true } });
  try {
    expect(wrapper.classes()).toContain('ui-search-input--linked');
    await wrapper.get('input').setValue('其他供应商');
    expect(wrapper.classes()).not.toContain('ui-search-input--linked');
    await wrapper.setProps({ value: '其他供应商' });
    expect(wrapper.classes()).toContain('ui-search-input--linked');
    await wrapper.setProps({ linked: false });
    expect(wrapper.classes()).not.toContain('ui-search-input--linked');
    await wrapper.setProps({ value: '', linked: true });
    expect(wrapper.classes()).not.toContain('ui-search-input--linked');
  } finally {
    wrapper.unmount();
  }
});

it('restores the selected display after confirming the same value again', async () => {
  const wrapper = mount(ObjectPickerInput, { props: { value: '青禾供应商', linked: true } });
  try {
    await wrapper.get('input').setValue('');
    await wrapper.setProps({ selectionVersion: 1 });
    expect(wrapper.get('input').element.value).toBe('青禾供应商');
    expect(wrapper.classes()).toContain('ui-search-input--linked');
    await wrapper.get('input').setValue('搜索草稿');
    await wrapper.setProps({ selectionVersion: 2 });
    expect(wrapper.get('input').element.value).toBe('青禾供应商');
  } finally {
    wrapper.unmount();
  }
});

it('keeps a pending draft when an asynchronous selected-title refresh arrives', async () => {
  const wrapper = mount(ObjectPickerInput, { props: { value: '旧标题', preserveDraft: true } });
  try {
    await wrapper.get('input').setValue('待确认草稿');
    await wrapper.get('input').trigger('blur', { relatedTarget: document.body });
    await wrapper.setProps({ value: '异步回显标题' });
    expect(wrapper.get('input').element.value).toBe('待确认草稿');
  } finally {
    wrapper.unmount();
  }
});

it('allows a retained unresolved draft to retry when focus leaves again', async () => {
  const wrapper = mount(ObjectPickerInput, { props: { value: '旧标题', preserveDraft: true } });
  try {
    const input = wrapper.get('input');
    await input.setValue('待确认草稿');
    await input.trigger('blur', { relatedTarget: document.body });
    await input.trigger('blur', { relatedTarget: document.body });
    expect(wrapper.emitted('blur')).toEqual([['待确认草稿'], ['待确认草稿']]);
  } finally {
    wrapper.unmount();
  }
});

it('only reports an edited nonblank draft on a real focus exit', async () => {
  const wrapper = mount(ObjectPickerInput, { props: { value: '青禾供应商' }, attachTo: document.body });
  try {
    const input = wrapper.get('input');
    await input.setValue('青禾');
    await input.trigger('blur', { relatedTarget: document.body });
    expect(wrapper.emitted('blur')).toEqual([['青禾']]);

    await input.setValue('禾');
    await input.trigger('blur', { relatedTarget: wrapper.get('button').element });
    expect(wrapper.emitted('blur')).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

it('still reports the draft before focus moves to an adjacent compact picker', async () => {
  const Pair = defineComponent({
    components: { ObjectPickerInput },
    template: '<div><ObjectPickerInput value="甲" /><ObjectPickerInput value="乙" /></div>',
  });
  const wrapper = mount(Pair, { attachTo: document.body });
  try {
    const [first, second] = wrapper.findAllComponents(ObjectPickerInput);
    await first!.get('input').setValue('甲草稿');
    await first!.get('input').trigger('blur', { relatedTarget: second!.get('input').element });

    expect(first!.emitted('blur')).toEqual([['甲草稿']]);
  } finally {
    wrapper.unmount();
  }
});

it('does not leave a pending blur completion after explicit Enter browsing', async () => {
  const wrapper = mount(ObjectPickerInput, { props: { value: '青禾供应商' }, attachTo: document.body });
  try {
    const input = wrapper.get('input');
    await input.setValue('青禾');
    await input.trigger('keydown', { key: 'Enter', keyCode: 13 });
    await flushPromises();
    await input.trigger('blur', { relatedTarget: document.body });

    expect(wrapper.emitted('browse')).toEqual([['青禾']]);
    expect(wrapper.emitted('blur')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});

it('marks unmatched text accessibly and removes the mark when the owner clears it', async () => {
  const wrapper = mount(ObjectPickerInput, { props: { unmatched: true } });
  try {
    await wrapper.get('input').setValue('未匹配');
    expect(wrapper.classes()).toContain('ui-search-input--unmatched');
    expect(wrapper.get('input').attributes('aria-invalid')).toBe('true');
    await wrapper.setProps({ unmatched: false });
    expect(wrapper.classes()).not.toContain('ui-search-input--unmatched');
    expect(wrapper.get('input').attributes('aria-invalid')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});
