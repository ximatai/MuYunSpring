import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { expect, it } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import FormulaExpressionEditor from '@/platform-components/FormulaExpressionEditor.vue';
import '@/styles.css';

it('keeps field decorations and typed formula text in one wrapping editor with exact raw source', async () => {
  await page.viewport(420, 600);
  const value = ref('');
  const Fixture = defineComponent({
    setup() {
      return () =>
        h('div', { style: 'width: 228px; padding: 12px' }, [
          h(FormulaExpressionEditor, {
            value: value.value,
            fields: [
              { name: 'amount', label: '金额' },
              { name: 'quantity', label: '数量' },
              { name: 'unitPrice', label: '单价' },
            ],
            placeholder: '输入公式',
            'onUpdate:value': (next: string) => (value.value = next),
          }),
        ]);
    },
  });
  const wrapper = mount(Fixture, { attachTo: document.body });
  try {
    await nextTick();
    const editor = wrapper.get('[role="textbox"]').element;
    await userEvent.click(page.elementLocator(editor));
    await userEvent.type(page.elementLocator(editor), '{{amount}');
    await userEvent.type(page.elementLocator(editor), ' = ');
    await userEvent.type(page.elementLocator(editor), '{{quantity}');
    await userEvent.type(page.elementLocator(editor), ' * ');
    await userEvent.type(page.elementLocator(editor), '{{unitPrice}');
    await expect.poll(() => value.value).toBe('{amount} = {quantity} * {unitPrice}');
    expect(wrapper.findAll('[data-source]')).toHaveLength(3);
    expect(wrapper.text()).toContain('金额');
    expect(wrapper.text()).toContain('数量');
    expect(wrapper.text()).toContain('单价');
    const bounds = editor.getBoundingClientRect();
    expect(editor.scrollWidth).toBeLessThanOrEqual(editor.clientWidth + 1);
    expect(editor.scrollHeight).toBeGreaterThan(bounds.height / 2);
  } finally {
    wrapper.unmount();
  }
});

it('does not delete a selected visual token for navigation or copy, but replaces it with typed source text', async () => {
  const value = ref('{amount} + 1');
  const wrapper = mount(FormulaExpressionEditor, {
    attachTo: document.body,
    props: {
      value: value.value,
      fields: [{ name: 'amount', label: '金额' }],
      'onUpdate:value': (next: string) => (value.value = next),
    },
  });
  try {
    await nextTick();
    const editor = wrapper.get('[role="textbox"]').element;
    const token = wrapper.get('[data-source="{amount}"]').element as HTMLElement;
    expect(Number.parseFloat(getComputedStyle(token).paddingLeft)).toBeGreaterThan(0);
    expect(getComputedStyle(token).borderStyle).toBe('solid');
    wrapper.vm.focusSelection(0, 8);
    await userEvent.keyboard('{Control>}c{/Control}');
    await expect.poll(() => value.value).toBe('{amount} + 1');
    wrapper.vm.focusSelection(0, 8);
    await userEvent.keyboard('{ArrowRight}');
    await expect.poll(() => value.value).toBe('{amount} + 1');
    wrapper.vm.focusSelection(0, 8);
    await userEvent.type(page.elementLocator(editor), '*');
    await expect.poll(() => value.value).toBe('* + 1');
  } finally {
    wrapper.unmount();
  }
});

it('aligns visual-token text with adjacent formula text on the same baseline', async () => {
  const wrapper = mount(FormulaExpressionEditor, {
    attachTo: document.body,
    props: {
      value: '{amount} + 1',
      fields: [{ name: 'amount', label: '金额' }],
    },
  });
  try {
    await nextTick();
    const editor = wrapper.get('[role="textbox"]').element;
    const token = wrapper.get('[data-source="{amount}"]').element;
    const rawText = Array.from(editor.childNodes).find(
      (node) => node.nodeType === Node.TEXT_NODE && node.textContent?.includes('+ 1'),
    );
    expect(rawText).toBeDefined();
    const range = document.createRange();
    range.selectNodeContents(rawText!);
    const tokenRange = document.createRange();
    tokenRange.selectNodeContents(token);
    expect(
      Math.abs(tokenRange.getBoundingClientRect().bottom - range.getBoundingClientRect().bottom),
    ).toBeLessThanOrEqual(0.5);
  } finally {
    wrapper.unmount();
  }
});

it('preserves Enter as raw newline at the actual source selection', async () => {
  const value = ref('{amount} + 1');
  const wrapper = mount(FormulaExpressionEditor, {
    attachTo: document.body,
    props: {
      value: value.value,
      fields: [{ name: 'amount', label: '金额' }],
      'onUpdate:value': (next: string) => (value.value = next),
    },
  });
  try {
    await nextTick();
    wrapper.vm.focusSelection(8);
    await userEvent.keyboard('{Enter}');
    await expect.poll(() => value.value).toBe('{amount}\n + 1');
  } finally {
    wrapper.unmount();
  }
});

it('continues typing after a field inserted by the host on the next Vue tick', async () => {
  const value = ref('');
  const editorRef = ref<InstanceType<typeof FormulaExpressionEditor>>();
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(FormulaExpressionEditor, {
          ref: editorRef,
          value: value.value,
          fields: [{ name: 'amount', label: '金额' }],
          'onUpdate:value': (next: string) => (value.value = next),
        }),
    }),
    { attachTo: document.body },
  );
  try {
    value.value = '{amount}';
    await nextTick(() => editorRef.value?.focusSelection(8));
    await userEvent.keyboard(' = 100 * 1.13');
    await expect.poll(() => value.value).toBe('{amount} = 100 * 1.13');
    expect(wrapper.findAll('[data-source]')).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

it('moves a visual token inside the editor without inserting its display label', async () => {
  const value = ref('{amount} = 1 + {quantity}');
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(FormulaExpressionEditor, {
          value: value.value,
          fields: [
            { name: 'amount', label: '金额' },
            { name: 'quantity', label: '数量' },
          ],
          'onUpdate:value': (next: string) => (value.value = next),
        }),
    }),
    { attachTo: document.body },
  );
  try {
    await nextTick();
    await userEvent.dragAndDrop(
      wrapper.get('[data-source="{quantity}"]').element,
      wrapper.get('[data-source="{amount}"]').element,
    );
    await expect.poll(() => value.value).not.toBe('{amount} = 1 + {quantity}');
    expect(value.value.match(/\{quantity\}/g)).toHaveLength(1);
    expect(value.value).not.toContain('数量');
  } finally {
    wrapper.unmount();
  }
});
