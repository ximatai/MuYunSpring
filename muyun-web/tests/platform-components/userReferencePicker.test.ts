import { expect, it, vi } from 'vitest';
import { createUserReferencePickerProvider } from '@/platform-components/userReferencePicker';

it('keeps tenant navigation and direct department selection inside the supplied identity hierarchy', async () => {
  const navigation = {
    showTenantNavigation: true,
    tenants: [
      { id: 'a', title: '甲' },
      { id: 'b', title: '乙' },
    ],
    organizations: [
      { id: 'oa', title: '甲机构', tenantId: 'a' },
      { id: 'ob', title: '乙机构', tenantId: 'b' },
    ],
    departments: [
      { id: 'da', title: '甲部门', tenantId: 'a', organizationId: 'oa' },
      { id: 'db', title: '乙部门', tenantId: 'b', organizationId: 'ob' },
    ],
  };
  const searchPage = vi.fn().mockResolvedValue({ records: [], total: 0, navigation });
  const provider = createUserReferencePickerProvider({
    sourceIdentity: { targetModuleAlias: 'iam.user', source: { kind: 'businessPurpose', id: 'logs' } },
    searchPage,
    resolveUsers: async () => [],
  });
  const request = { keyword: '', pageNum: 1, pageSize: 20, scope: { selections: [] } };
  await provider.searchPage(request);
  const tenantPage = await provider.searchPage({
    ...request,
    scope: { selections: [{ axisId: 'tenant', itemId: 'a' }] },
  });
  expect(
    tenantPage.navigation?.find((axis) => axis.id === 'organization')?.items.map((item) => item.id),
  ).toEqual(['oa']);
  expect(
    tenantPage.navigation?.find((axis) => axis.id === 'department')?.items.map((item) => item.id),
  ).toEqual(['da']);
  await provider.searchPage({ ...request, scope: { selections: [{ axisId: 'department', itemId: 'db' }] } });
  expect(searchPage).toHaveBeenLastCalledWith({
    keyword: '',
    pageNum: 1,
    pageSize: 20,
    scope: { tenantId: 'b', organizationId: 'ob', departmentId: 'db' },
  });
});
