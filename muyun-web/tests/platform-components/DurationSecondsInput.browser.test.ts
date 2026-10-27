import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import DurationSecondsInput from '@/platform-components/DurationSecondsInput.vue';
import UiInput from '@/vue-ui-antdv/components/UiInput.vue';
import UiInputGroup from '@/vue-ui-antdv/components/UiInputGroup.vue';
import { h } from 'vue';
import '@/vue-ui-antdv/styles.css';
import '@/styles.css';

it.each([180, 310])(
  'renders a joined duration field and keeps focus within its boundary at %s px',
  async (width) => {
    const wrapper = mount(DurationSecondsInput, {
      props: { value: 259200, ariaLabel: '来源状态有效期' },
      attrs: { style: `width: ${width}px; margin: 20px` },
      attachTo: document.body,
    });
    const ordinary = mount(UiInput, { attachTo: document.body });
    try {
      const group = wrapper.get('[role="group"]').element as HTMLElement;
      const input = wrapper.get('input[type="number"]').element as HTMLInputElement;
      const selector = wrapper.get('.ant-select-selector').element as HTMLElement;
      expect(group.getBoundingClientRect().height).toBeCloseTo(
        ordinary.element.getBoundingClientRect().height,
        0,
      );
      expect(getComputedStyle(input).borderTopWidth).toBe('0px');
      expect(getComputedStyle(selector).borderTopWidth).toBe('0px');
      expect(input.getBoundingClientRect().right).toBeLessThanOrEqual(
        selector.getBoundingClientRect().left + 1,
      );
      expect(selector.getBoundingClientRect().right).toBeLessThanOrEqual(group.getBoundingClientRect().right);
      await page.elementLocator(input).click();
      expect(getComputedStyle(group).boxShadow).not.toBe('none');
      expect(group.getAttribute('aria-label')).toBe('来源状态有效期');
      await page.elementLocator(wrapper.get('.ant-select-selector').element).click();
      expect(getComputedStyle(group).boxShadow).not.toBe('none');
      await page.getByText('小时', { exact: true }).click();
      expect(wrapper.emitted('update:value')?.at(-1)).toEqual([10800]);
      await wrapper.setProps({ invalid: true });
      expect(group.getAttribute('aria-invalid')).toBe('true');
      await page.elementLocator(input).click();
      await expect.poll(() => getComputedStyle(group).borderTopColor).toBe('rgb(227, 77, 89)');
      await wrapper.setProps({ disabled: true });
      expect(input.disabled).toBe(true);
      expect(group.getAttribute('aria-disabled')).toBe('true');
    } finally {
      wrapper.unmount();
      ordinary.unmount();
    }
  },
);

it('keeps themed affix wrappers inside the compound field without a second focus frame', async () => {
  const wrapper = mount(UiInputGroup, {
    attrs: { style: 'width: 240px; margin: 20px' },
    slots: {
      default: () => h(UiInput, { value: '3', allowClear: true, type: 'number' }),
      addon: () => h('span', '天'),
    },
    attachTo: document.body,
  });
  try {
    await page.elementLocator(wrapper.get('input').element).click();
    const affix = wrapper.get('.ant-input-affix-wrapper').element;
    expect(getComputedStyle(affix).borderTopWidth).toBe('0px');
    expect(getComputedStyle(affix).boxShadow).toBe('none');
    expect(getComputedStyle(wrapper.element).boxShadow).not.toBe('none');
  } finally {
    wrapper.unmount();
  }
});

it('keeps the unit dropdown open inside the standard form with another required field empty', async () => {
  const { default: RecordFormGrid } = await import('@/platform-components/RecordFormGrid.vue');
  const { default: RecordFormFields } = await import('@/platform-components/RecordFormFields.vue');
  const { defineComponent, ref } = await import('vue');
  const fields = new Map<
    string,
    import('@/platform-components/recordFormFieldModel').RecordFormFieldDescriptor
  >([
    ['provider', { fieldRef: { fieldName: 'provider' }, label: '身份源', required: { constant: true } }],
    [
      'age',
      {
        fieldRef: { fieldName: 'age' },
        label: '状态有效期',
        valueType: 'INTEGER',
        fieldControl: {
          alias: 'duration_seconds',
          rendererType: 'NUMBER',
          valueShape: 'SCALAR',
          properties: {},
          bindings: [],
        },
      },
    ],
  ]);
  const record = ref<Record<string, unknown>>({ age: 259200 });
  const fixture = defineComponent({
    setup: () => () =>
      h(
        RecordFormGrid,
        { style: 'width: 620px; margin: 16px' },
        {
          default: () =>
            h(RecordFormFields, {
              fields,
              record: record.value,
              mode: 'create',
              validationRequestKey: 1,
              'onUpdate:field': (name: string, value: unknown) => {
                record.value = { ...record.value, [name]: value };
              },
            }),
        },
      ),
  });
  const wrapper = mount(fixture, { attachTo: document.body });
  try {
    await page.getByText('身份源', { exact: true }).click();
    expect(document.activeElement).toBe(wrapper.get('input').element);
    await page.elementLocator(wrapper.get('.duration-seconds-input .ant-select-selector').element).click();
    await page.getByText('小时', { exact: true }).click();
    expect(record.value.age).toBe(10800);
    await page.elementLocator(wrapper.get('.duration-seconds-input input[type="number"]').element).click();
    await page.elementLocator(wrapper.get('.duration-seconds-input .ant-select-selector').element).click();
    await page.getByText('分钟', { exact: true }).click();
    expect(record.value.age).toBe(180);
    expect(wrapper.get('.record-form-field-error').text()).toContain('身份源');
  } finally {
    wrapper.unmount();
  }
});
