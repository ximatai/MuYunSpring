import type {
  ReferencePickerAxisSelection,
  ReferencePickerCandidate,
  ReferencePickerColumn,
  ReferencePickerPage,
  ReferencePickerProvider,
  ReferencePickerSourceIdentity,
} from './referencePickerModel';
import type {
  UserPickerCandidate,
  UserPickerNavigation,
  UserPickerNavigationScope,
  UserPickerPage,
  UserPickerPageSearch,
  UserPickerResolver,
} from './userPickerModel';

/** Identity-specific presentation stays here; the common picker only consumes projections. */
export const userReferencePickerColumns: readonly ReferencePickerColumn[] = [
  { key: 'account', title: '用户账号', width: 140 },
  { key: 'employeeName', title: '职员姓名', width: 140 },
  { key: 'organizationName', title: '所属机构', width: 150 },
  { key: 'departmentName', title: '所属部门', width: 150 },
];

export interface UserReferencePickerConfig {
  sourceIdentity: ReferencePickerSourceIdentity;
  searchPage: UserPickerPageSearch;
  resolveUsers: UserPickerResolver;
}

/**
 * Maps the authorized IAM delivery into the source-neutral picker contract.
 * It does not infer any tenant/organization/department relationship: old
 * source-owned IDs are simply returned to the same source as typed axes.
 */
export function createUserReferencePickerProvider(
  config: UserReferencePickerConfig,
): ReferencePickerProvider {
  let navigation: UserPickerNavigation | undefined;
  let navigationRequestVersion = 0;
  return {
    identity: config.sourceIdentity,
    async searchPage(request) {
      const requestVersion = ++navigationRequestVersion;
      const page = await config.searchPage({
        keyword: request.keyword,
        pageNum: request.pageNum,
        pageSize: request.pageSize,
        ...(scopeOf(request.scope.selections, navigation)
          ? { scope: scopeOf(request.scope.selections, navigation) }
          : {}),
      });
      // The common picker already ignores stale pages. Keep this adapter's
      // navigation snapshot aligned with that same newest request so a late
      // response cannot supply parents for a later direct department choice.
      if (requestVersion === navigationRequestVersion) navigation = page.navigation;
      return pageOf(page, request.scope.selections);
    },
    async resolve(ids) {
      return (await config.resolveUsers(ids)).map(toReferenceCandidate);
    },
  };
}

export function toReferenceCandidate(candidate: UserPickerCandidate): ReferencePickerCandidate {
  return {
    id: candidate.id,
    title: candidate.title,
    subtitle: candidate.subtitle,
    disabled: candidate.disabled,
    unavailable: candidate.unavailable,
    projections: {
      account: candidate.account ?? candidate.id,
      employeeName: candidate.employeeName ?? candidate.title,
      organizationName: candidate.organizationName ?? candidate.organizationId ?? '—',
      departmentName: candidate.departmentName ?? candidate.departmentId ?? '—',
      organizationId: candidate.organizationId,
      departmentId: candidate.departmentId,
    },
  };
}

export function toUserPickerCandidate(candidate: ReferencePickerCandidate): UserPickerCandidate {
  const projection = candidate.projections ?? {};
  return {
    id: candidate.id,
    title: candidate.title,
    subtitle: candidate.subtitle,
    disabled: candidate.disabled,
    unavailable: candidate.unavailable,
    account: stringProjection(projection, 'account'),
    employeeName: stringProjection(projection, 'employeeName'),
    organizationName: stringProjection(projection, 'organizationName'),
    departmentName: stringProjection(projection, 'departmentName'),
    organizationId: stringProjection(projection, 'organizationId'),
    departmentId: stringProjection(projection, 'departmentId'),
  };
}

function stringProjection(projections: Readonly<Record<string, unknown>>, key: string) {
  const value = projections[key];
  return typeof value === 'string' ? value : undefined;
}

function pageOf(
  page: UserPickerPage,
  selections: readonly ReferencePickerAxisSelection[],
): ReferencePickerPage {
  return {
    records: page.records.map(toReferenceCandidate),
    total: page.total,
    ...(page.navigation ? { navigation: axesOf(page.navigation, selections) } : {}),
  };
}

function axesOf(navigation: UserPickerNavigation, selections: readonly ReferencePickerAxisSelection[]) {
  const selected = scopeOf(selections, navigation) ?? {};
  const organizationItems = navigation.organizations.filter(
    (item) => !selected.tenantId || item.tenantId === selected.tenantId,
  );
  const departmentItems = navigation.departments.filter(
    (item) =>
      (!selected.tenantId || item.tenantId === selected.tenantId) &&
      (!selected.organizationId || item.organizationId === selected.organizationId),
  );
  return [
    ...(navigation.showTenantNavigation ? [{ id: 'tenant', title: '租户', items: navigation.tenants }] : []),
    { id: 'organization', title: '机构', dependsOn: ['tenant'], items: organizationItems },
    {
      id: 'department',
      title: '部门',
      dependsOn: ['tenant', 'organization'],
      items: departmentItems,
    },
  ];
}

function scopeOf(
  selections: readonly ReferencePickerAxisSelection[],
  navigation?: UserPickerNavigation,
): UserPickerNavigationScope | undefined {
  const itemIdOf = (axisId: string) => selections.find((selection) => selection.axisId === axisId)?.itemId;
  const organization = navigation?.organizations.find((item) => item.id === itemIdOf('organization'));
  const department = navigation?.departments.find((item) => item.id === itemIdOf('department'));
  const scope: UserPickerNavigationScope = {
    tenantId: itemIdOf('tenant') ?? organization?.tenantId ?? department?.tenantId,
    organizationId: itemIdOf('organization') ?? department?.organizationId,
    departmentId: itemIdOf('department'),
  };
  return Object.values(scope).some(Boolean) ? scope : undefined;
}
