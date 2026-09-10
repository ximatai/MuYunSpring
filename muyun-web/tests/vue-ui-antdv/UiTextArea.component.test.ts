import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import UiTextArea from '@/vue-ui-antdv/components/UiTextArea.vue';

describe('UiTextArea', () => {
  it('exposes the current selection and restores focus at a requested range', async () => {
    const wrapper = mount(UiTextArea, {
      attachTo: document.body,
      props: { value: '{quantity} * 10' },
    });
    const textarea = wrapper.find('textarea').element as HTMLTextAreaElement;
    wrapper.vm.focusSelection(2, 6);
    expect(document.activeElement).toBe(textarea);
    expect([textarea.selectionStart, textarea.selectionEnd]).toEqual([2, 6]);
    textarea.focus();
    textarea.setSelectionRange(11, 13);
    await wrapper.find('textarea').trigger('select');

    expect(wrapper.vm.selection()).toEqual({ start: 11, end: 13 });
    wrapper.vm.focusSelection(1, 4);
    expect(document.activeElement).toBe(textarea);
    expect([textarea.selectionStart, textarea.selectionEnd]).toEqual([1, 4]);
    wrapper.unmount();
  });
});
