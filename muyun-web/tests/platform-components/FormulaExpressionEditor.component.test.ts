import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { describe, expect, it } from 'vitest';
import FormulaExpressionEditor from '@/platform-components/FormulaExpressionEditor.vue';

describe('FormulaExpressionEditor', () => {
  it('renders business labels but emits only the original expression string', async () => {
    const value = ref('{amount} = PRESENT({quantity})');
    const wrapper = mount(
      defineComponent({
        setup() {
          return () =>
            h(FormulaExpressionEditor, {
              value: value.value,
              fields: [
                { name: 'amount', label: '金额' },
                { name: 'quantity', label: '数量' },
              ],
              functions: [{ name: 'PRESENT', title: '已填写' }],
              'onUpdate:value': (next: string) => (value.value = next),
            });
        },
      }),
      { attachTo: document.body },
    );
    await nextTick();
    expect(wrapper.text()).toContain('金额');
    expect(wrapper.text()).toContain('已填写');
    const editor = wrapper.findComponent(FormulaExpressionEditor);
    editor.vm.focusSelection(value.value.length);
    expect(editor.vm.selection()).toEqual({ start: value.value.length, end: value.value.length });
    expect(value.value).toBe('{amount} = PRESENT({quantity})');
    wrapper.unmount();
  });
});
