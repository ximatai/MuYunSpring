import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import type { RecordFormFieldDescriptor } from '@/platform-components/recordFormFieldModel.ts';

describe('RecordFormFields', () => {
  it('renders one divider between adjacent semantic groups', () => {
    const firstGroup = {
      groupCode: 'identity',
      title: '基本信息',
      fields: [{ fieldName: 'title' }],
    };
    const secondGroup = {
      groupCode: 'branding',
      title: '品牌配置',
      fields: [{ fieldName: 'subtitle' }],
    };
    const fields = new Map<string, RecordFormFieldDescriptor>([
      ['title', { fieldRef: { fieldName: 'title' }, label: '名称', formGroup: firstGroup }],
      ['subtitle', { fieldRef: { fieldName: 'subtitle' }, label: '副标题', formGroup: secondGroup }],
    ]);

    const wrapper = mount(RecordFormFields, {
      props: {
        record: { title: '', subtitle: '' },
        fields,
      },
    });

    expect(wrapper.findAll('.record-form-group-heading').map((heading) => heading.text())).toEqual([
      '基本信息',
      '品牌配置',
    ]);
    // One leading boundary, one shared group boundary, and one trailing boundary.
    expect(wrapper.findAll('.record-form-group-divider')).toHaveLength(3);
  });

  it('uses the numeric input adapter for platform numeric control aliases', () => {
    const fields = new Map<string, RecordFormFieldDescriptor>([
      ['amount', { fieldRef: { fieldName: 'amount' }, label: '金额', uiType: 'amount' }],
    ]);

    const wrapper = mount(RecordFormFields, {
      props: { record: { amount: '12.50' }, fields },
    });

    const input = wrapper.findComponent({ name: 'UiInput' });
    expect(input.props('type')).toBe('number');

    // UiInput deliberately keeps the platform's transport value unchanged. Dynamic record writes
    // continue to submit numeric drafts as strings for the server-side field-type parser.
    input.vm.$emit('update:value', '23.40');
    expect(wrapper.emitted('update:field')).toContainEqual(['amount', '23.40']);
  });
});
