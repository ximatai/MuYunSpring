import { mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { describe, expect, it } from 'vitest';
import StandardFlatFormSurface from '@/dynamic-page-runtime/StandardFlatFormSurface.vue';

describe('standard flat form surface', () => {
  it('applies field policy and aggregates descriptor and contribution validity', async () => {
    const Contribution = defineComponent({
      name: 'Contribution',
      props: { context: { type: Object, required: true } },
      template: '<section />',
    });
    const RecordFormFieldsStub = defineComponent({
      name: 'RecordFormFields',
      props: {
        fieldNames: { type: Array, default: () => [] },
        fileTransferContext: { type: Object, required: false, default: undefined },
      },
      emits: ['validity-change'],
      template: '<section />',
    });
    const wrapper = mount(StandardFlatFormSurface, {
      props: {
        record: { title: '租户', hidden: '不显示' },
        fields: new Map([
          ['title', { fieldRef: { fieldName: 'title' }, uiType: 'input' }],
          ['hidden', { fieldRef: { fieldName: 'hidden' }, uiType: 'input' }],
        ]),
        mode: 'edit',
        formSessionKey: 1,
        optionContext: {} as never,
        fileTransferContext: {} as never,
        pickerConfigs: {},
        disabled: false,
        contributions: [
          {
            key: 'brand',
            component: Contribution,
            location: { surface: 'flat-main', section: 'before-fields' },
          },
        ],
        fieldPolicies: [{ fieldName: 'hidden', visible: () => false }],
      },
      global: { stubs: { RecordFormFields: RecordFormFieldsStub } },
    });
    const form = wrapper.findComponent(RecordFormFieldsStub);
    const contribution = wrapper.findComponent(Contribution);

    expect(form.props('fieldNames')).toEqual(['title']);
    expect(form.props('fileTransferContext')).toEqual({});
    contribution.props('context').reportValidity({ valid: false });
    await nextTick();
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: false }]);

    contribution.props('context').reportValidity({ valid: true });
    form.vm.$emit('validity-change', { valid: false });
    await nextTick();
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: false }]);

    form.vm.$emit('validity-change', { valid: true });
    await nextTick();
    expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true }]);
  });
});
