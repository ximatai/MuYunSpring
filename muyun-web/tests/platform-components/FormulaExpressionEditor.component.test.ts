import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
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

it('inserts fields at the selection and shares server function help without changing source syntax', async () => {
  const value = ref('true');
  const tools = {
    loadFunctions: async () => [
      {
        name: 'PRESENT',
        title: '已填写',
        description: '判断字段是否有值',
        signature: 'PRESENT(value)',
        example: 'PRESENT({amount})',
      },
    ],
    validate: async () => {},
  };
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(FormulaExpressionEditor, {
          value: value.value,
          fields: [
            { name: 'amount', label: '金额' },
            { name: 'secret', label: '不可用', available: false },
          ],
          tools,
          'onUpdate:value': (next: string) => {
            value.value = next;
          },
        }),
    }),
    { attachTo: document.body },
  );
  try {
    const editor = wrapper.findComponent(FormulaExpressionEditor);
    editor.findComponent({ name: 'UiTokenInput' }).vm.$emit('selection', { start: 0, end: 4 });
    await editor
      .findAll('button')
      .find((button) => button.text() === '公式辅助')!
      .trigger('click');
    await flushPromises();
    const selects = editor.findAllComponents({ name: 'UiSelect' });
    selects[0]!.vm.$emit('update:value', 'amount');
    await flushPromises();
    expect(value.value).toBe('{amount}');
    selects[1]!.vm.$emit('update:value', 'PRESENT');
    await flushPromises();
    expect(value.value).toBe('{amount}PRESENT()');
    expect(wrapper.text()).toContain('判断字段是否有值');
    expect(wrapper.text()).toContain('PRESENT(value)');
    expect(wrapper.text()).toContain('PRESENT({amount})');
    expect(selects[0]!.props('options')).toContainEqual(
      expect.objectContaining({ value: 'secret', disabled: true }),
    );
  } finally {
    wrapper.unmount();
  }
});

it('discards validation replies after an edit and shows server errors only for the current input', async () => {
  let finish!: () => void;
  const tools = {
    loadFunctions: async () => [],
    validate: vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    ),
  };
  const wrapper = mount(FormulaExpressionEditor, {
    props: { value: '{amount} > 100', fields: [], tools, booleanResult: true },
  });
  const check = () => wrapper.findAll('button').find((button) => button.text() === '校验公式')!;
  try {
    await check().trigger('click');
    expect(tools.validate).toHaveBeenCalledWith('{amount} > 100');
    await wrapper.setProps({ value: '{amount} =' });
    finish();
    await flushPromises();
    expect(wrapper.find('[role="status"]').exists()).toBe(false);
    tools.validate.mockRejectedValueOnce(new Error('公式缺少右侧表达式'));
    await check().trigger('click');
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain('公式缺少右侧表达式');
    await wrapper.setProps({ value: 'true' });
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    tools.validate.mockResolvedValueOnce();
    await check().trigger('click');
    await flushPromises();
    expect(wrapper.get('[role="status"]').text()).toContain('语法校验通过');
    expect(wrapper.text()).toContain('条件须返回真或假');
    await wrapper.setProps({ disabled: true });
    expect(check().attributes('disabled')).toBeDefined();
  } finally {
    wrapper.unmount();
  }
});

it('invalidates function help on a language switch without changing the formula draft', async () => {
  const language = createLanguageContext({ languageTag: 'zh-CN' });
  let finishOld!: (functions: { name: string; title: string }[]) => void;
  const loadFunctions = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve;
        }),
    )
    .mockResolvedValueOnce([{ name: 'PRESENT', title: 'Has a value' }]);
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () =>
          h(FormulaExpressionEditor, {
            value: '{amount} > 100',
            fields: [],
            tools: { loadFunctions, validate: async () => {} },
          });
      },
    }),
  );
  try {
    const editor = wrapper.findComponent(FormulaExpressionEditor);
    await editor.findAll('button')[0]!.trigger('click');
    language.setLanguage('en');
    await nextTick();
    finishOld([{ name: 'PRESENT', title: '判断字段有值' }]);
    await flushPromises();
    expect(editor.props('value')).toBe('{amount} > 100');
    expect(editor.emitted('update:value')).toBeUndefined();
    await editor.findAll('button')[0]!.trigger('click');
    await flushPromises();
    expect(editor.findAllComponents({ name: 'UiSelect' })[1]!.props('options')).toEqual([
      { value: 'PRESENT', label: 'Has a value' },
    ]);
  } finally {
    wrapper.unmount();
  }
});
