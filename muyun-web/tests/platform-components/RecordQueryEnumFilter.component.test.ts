import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import RecordQueryEnumFilter from '@/platform-components/RecordQueryEnumFilter.vue';
import UiSelect from '@/vue-ui-antdv/components/UiSelect.vue';

describe('RecordQueryEnumFilter', () => {
  it('associates its title with a controlled non-clearable enum select', () => {
    const wrapper = mount(RecordQueryEnumFilter, {
      props: {
        title: '规则类型',
        value: 'CALCULATION',
        options: [
          { value: 'CALCULATION', label: '字段计算' },
          { value: 'VALIDATION', label: '业务校验' },
        ],
      },
    });

    const select = wrapper.findComponent(UiSelect);
    const label = wrapper.get('label');
    expect(label.text()).toBe('规则类型');
    expect(label.attributes('for')).toBe(select.props('id'));
    expect(select.props()).toMatchObject({
      ariaLabel: '规则类型',
      value: 'CALCULATION',
      allowClear: false,
      disabled: false,
    });
  });

  it('emits a single selected value and normalizes an invalid multi-value result', () => {
    const wrapper = mount(RecordQueryEnumFilter, {
      props: {
        title: '规则类型',
        options: [{ value: 'CALCULATION', label: '字段计算' }],
      },
    });

    const select = wrapper.findComponent(UiSelect);
    select.vm.$emit('update:value', 'CALCULATION');
    select.vm.$emit('update:value', ['CALCULATION']);
    expect(wrapper.emitted('update:value')).toEqual([['CALCULATION'], [null]]);
  });
});
