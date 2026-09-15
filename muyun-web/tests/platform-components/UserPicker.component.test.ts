import { shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import UserPicker from '@/platform-components/UserPicker.vue';
import type { ReferencePickerProvider } from '@/platform-components/referencePickerModel';

it('adapts user candidates and source-owned navigation into the common reference picker', async () => {
  const navigation = {
    showTenantNavigation: true,
    tenants: [{ id: 'tenant-a', title: '租户 A' }],
    organizations: [{ id: 'org-a', title: '华东机构', tenantId: 'tenant-a' }],
    departments: [{ id: 'department-a', title: '研发部', tenantId: 'tenant-a', organizationId: 'org-a' }],
  };
  const searchPage = vi.fn().mockResolvedValue({ records: [], total: 0, navigation });
  const resolveUsers = vi.fn().mockResolvedValue([]);
  const wrapper = shallowMount(UserPicker, {
    props: {
      value: 'user-1',
      searchPage,
      resolveUsers,
      sourceIdentity: {
        targetModuleAlias: 'iam.user',
        source: { kind: 'businessPurpose', id: 'login-log-operator' },
      },
    },
  });
  const picker = wrapper.findComponent({ name: 'ReferencePicker' });
  expect(picker.props('columns')).toEqual([
    { key: 'account', title: '用户账号', width: 140 },
    { key: 'employeeName', title: '职员姓名', width: 140 },
    { key: 'organizationName', title: '所属机构', width: 150 },
    { key: 'departmentName', title: '所属部门', width: 150 },
  ]);
  const referenceProvider = picker.props('provider') as Pick<
    ReferencePickerProvider,
    'searchPage' | 'identity'
  >;
  expect(referenceProvider.identity).toEqual({
    targetModuleAlias: 'iam.user',
    source: { kind: 'businessPurpose', id: 'login-log-operator' },
  });
  await referenceProvider.searchPage({
    keyword: '',
    pageNum: 1,
    pageSize: 20,
    scope: { selections: [] },
  });
  const page = await referenceProvider.searchPage({
    keyword: '',
    pageNum: 1,
    pageSize: 20,
    scope: { selections: [{ axisId: 'department', itemId: 'department-a' }] },
  });
  expect(searchPage).toHaveBeenLastCalledWith({
    keyword: '',
    pageNum: 1,
    pageSize: 20,
    scope: { tenantId: 'tenant-a', organizationId: 'org-a', departmentId: 'department-a' },
  });
  expect(page.navigation?.[1]?.items).toEqual([{ id: 'org-a', title: '华东机构', tenantId: 'tenant-a' }]);

  picker.vm.$emit('validity-change', { valid: false, status: 'editing', message: '请完成用户选择' });
  expect(wrapper.emitted('validity-change')).toEqual([
    [{ valid: false, status: 'editing', message: '请完成用户选择' }],
  ]);
});
