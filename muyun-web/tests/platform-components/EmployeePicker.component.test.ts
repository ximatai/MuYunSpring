import { shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import EmployeePicker from '@/platform-components/EmployeePicker.vue';

const employees = [{ id: 'employee-1', title: '张三', subtitle: '研发部 / 演示租户' }];

it('keeps employee IDs and source-owned providers distinct while reusing the shared selection panel', () => {
  const searchPage = vi.fn().mockResolvedValue({ records: employees, total: 1 });
  const resolveEmployees = vi.fn().mockResolvedValue(employees);
  const wrapper = shallowMount(EmployeePicker, {
    props: { value: 'employee-1', multiple: true, searchPage, resolveEmployees },
  });

  const sharedPicker = wrapper.findComponent({ name: 'UserPicker' });
  expect(sharedPicker.props()).toMatchObject({
    value: 'employee-1',
    multiple: true,
    title: '选择职员',
    placeholder: '搜索并选择职员',
    searchPlaceholder: '按工号、姓名或职员 ID 搜索',
    emptyDescription: '没有可选择的职员',
    selectionNoun: '职员',
    searchPage,
    resolveUsers: resolveEmployees,
  });

  sharedPicker.vm.$emit('update:value', ['employee-1']);
  sharedPicker.vm.$emit('select', employees);
  expect(wrapper.emitted('update:value')).toEqual([[['employee-1']]]);
  expect(wrapper.emitted('select')).toEqual([[employees]]);
});
