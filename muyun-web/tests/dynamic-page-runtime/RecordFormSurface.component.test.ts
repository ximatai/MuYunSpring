import { mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import RecordFormSurface from '@/dynamic-page-runtime/RecordFormSurface.vue';

describe('record form surface', () => {
  it('owns the standard record-card grid so every shell keeps the same field gaps', () => {
    const source = readFileSync(
      resolve(import.meta.dirname, '../../src/dynamic-page-runtime/RecordFormSurface.vue'),
      'utf8',
    );

    expect(source).toMatch(/grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/);
    expect(source).toMatch(/column-gap: 12px/);
    expect(source).toMatch(/row-gap: 16px/);
  });

  it('applies field policy, honours excluded fields and aggregates descriptor and contribution validity', async () => {
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
    const wrapper = mount(RecordFormSurface, {
      props: {
        record: { title: '租户', hidden: '不显示', enabled: true },
        fields: new Map([
          ['title', { fieldRef: { fieldName: 'title' }, uiType: 'input' }],
          ['hidden', { fieldRef: { fieldName: 'hidden' }, uiType: 'input' }],
          ['enabled', { fieldRef: { fieldName: 'enabled' }, uiType: 'enabledStatus' }],
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
            location: { surface: 'record-card', section: 'before-fields' },
          },
        ],
        fieldPolicies: [{ fieldName: 'hidden', visible: () => false }],
        excludeFieldNames: ['enabled'],
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
