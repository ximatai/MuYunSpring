import { shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import EmployeePicker from '@/platform-components/EmployeePicker.vue';

it('keeps employee IDs and source-owned providers distinct while using ReferencePicker', () => {
  const searchPage = vi.fn().mockResolvedValue({ records: [], total: 0 });
  const resolveEmployees = vi.fn().mockResolvedValue([]);
  const wrapper = shallowMount(EmployeePicker, {
    props: { value: 'employee-1', multiple: true, searchPage, resolveEmployees },
  });
  const picker = wrapper.findComponent({ name: 'ReferencePicker' });
  expect(picker.props()).toMatchObject({
    value: 'employee-1',
    multiple: true,
    title: '选择职员',
    placeholder: '搜索并选择职员',
  });
  expect(
    (picker.props('provider') as { identity: { targetModuleAlias: string } }).identity.targetModuleAlias,
  ).toBe('iam.employee');
  picker.vm.$emit('validity-change', { valid: false, status: 'editing', message: '请完成职员选择' });
  expect(wrapper.emitted('validity-change')).toEqual([
    [{ valid: false, status: 'editing', message: '请完成职员选择' }],
  ]);
});
