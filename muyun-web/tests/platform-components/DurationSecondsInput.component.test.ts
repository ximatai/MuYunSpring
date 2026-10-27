import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import DurationSecondsInput from '@/platform-components/DurationSecondsInput.vue';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import UiSelect from '@/vue-ui-antdv/components/UiSelect.vue';
import { resolveRecordDetailDisplayValue } from '@/platform-components/recordDetailFieldModel';
import {
  resolveRecordFormFieldState,
  type RecordFormFieldDescriptor,
} from '@/platform-components/recordFormFieldModel';
const descriptor: RecordFormFieldDescriptor = {
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
};
it('shows three days without a write, retains a chosen unit and clears', async () => {
  const wrapper = mount(DurationSecondsInput, { props: { value: 259200 } });
  expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('3');
  expect(wrapper.findComponent(UiSelect).props('value')).toBe(86400);
  expect(wrapper.emitted('update:value')).toBeUndefined();
  wrapper.findComponent(UiSelect).vm.$emit('update:value', 3600);
  await wrapper.vm.$nextTick();
  expect(wrapper.emitted('update:value')?.at(-1)).toEqual([10800]);
  await wrapper.setProps({ value: 10800 });
  expect(wrapper.findComponent(UiSelect).props('value')).toBe(3600);
  await wrapper.get('input[type="number"]').setValue('1.5');
  expect(wrapper.emitted('update:value')?.at(-1)).toEqual([5400]);
  await wrapper.get('input[type="number"]').setValue('');
  expect(wrapper.emitted('update:value')?.at(-1)).toEqual([undefined]);
  wrapper.unmount();
});
it('blocks fractional seconds in the standard form, recovers and formats readonly values', async () => {
  const fields = new Map([['age', descriptor]]);
  const wrapper = mount(RecordFormFields, { props: { fields, record: { age: 90 }, mode: 'edit' } });
  expect(wrapper.findComponent(DurationSecondsInput).exists()).toBe(true);
  expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('90');
  await wrapper.get('input[type="number"]').setValue('0.1');
  expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: false });
  expect(wrapper.emitted('update:field')).toBeUndefined();
  await wrapper.get('input[type="number"]').setValue('120');
  expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: true });
  expect(wrapper.emitted('update:field')?.at(-1)).toEqual(['age', 120]);
  const state = resolveRecordFormFieldState('age', { fields, record: { age: 259200 } });
  expect(resolveRecordDetailDisplayValue(state, { age: 259200 })).toBe('3 天');
  wrapper.unmount();
});
it('disables both controls without changing the persisted value', () => {
  const wrapper = mount(DurationSecondsInput, { props: { value: 259200, disabled: true } });
  expect(wrapper.get('input[type="number"]').attributes('disabled')).toBeDefined();
  expect(wrapper.findComponent(UiSelect).props('disabled')).toBe(true);
  expect(wrapper.emitted('update:value')).toBeUndefined();
  wrapper.unmount();
});

it('retains an invalid stored duration visibly and blocks a form save until corrected', () => {
  const wrapper = mount(RecordFormFields, {
    props: { fields: new Map([['age', descriptor]]), record: { age: -1 }, mode: 'edit' },
  });
  expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('-1');
  expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: false });
  expect(wrapper.emitted('update:field')).toBeUndefined();
  wrapper.unmount();
});

it('emits a changed duration once across input and change events', async () => {
  const wrapper = mount(DurationSecondsInput, { props: { value: 259200 } });
  const input = wrapper.get('input[type="number"]');
  await input.setValue('2');
  await input.trigger('change');
  expect(wrapper.emitted('update:value')).toEqual([[172800]]);
  wrapper.unmount();
});

it('clears an invalid edit when its owner explicitly resets the value to null', async () => {
  const wrapper = mount(DurationSecondsInput, { props: { value: 259200 } });
  await wrapper.get('input[type="number"]').setValue('-1');
  expect(wrapper.get('[role="group"]').attributes('aria-invalid')).toBe('true');
  await wrapper.setProps({ value: null });
  expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('');
  expect(wrapper.get('[role="group"]').attributes('aria-invalid')).toBeUndefined();
  expect(wrapper.emitted('update:value')).toBeUndefined();
  wrapper.unmount();
});

it.each(['hidden', 'readOnly', 'disabled'] as const)(
  'ignores local duration errors while the field is %s and revalidates when editable again',
  async (state) => {
    const wrapper = mount(RecordFormFields, {
      props: { fields: new Map([['age', descriptor]]), record: { age: 90 }, mode: 'edit' },
    });
    try {
      await wrapper.get('input[type="number"]').setValue('0.1');
      expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: false });
      await wrapper.setProps(
        state === 'disabled'
          ? { disabledOf: () => true }
          : {
              fields: new Map([
                [
                  'age',
                  {
                    ...descriptor,
                    [state === 'hidden' ? 'visible' : 'readOnly']: { constant: state !== 'hidden' },
                  },
                ],
              ]),
            },
      );
      expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: true, errors: {} });
      await wrapper.setProps({ fields: new Map([['age', descriptor]]), disabledOf: undefined });
      // Hidden controls remount from the last valid business value; disabled controls retain their edit.
      expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: state === 'hidden' });
      if (state !== 'hidden') {
        await wrapper.get('input[type="number"]').setValue('120');
        expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: true });
      }
      expect(wrapper.props('record')).toEqual({ age: 90 });
    } finally {
      wrapper.unmount();
    }
  },
);
