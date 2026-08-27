import type {
  AccountRoleGrant,
  DataScopePolicy,
  EmploymentRoleGrant,
  EmploymentSelectorItem,
  ManagementScopeType,
  RoleAuthorizationModule,
  RoleDataGrantActionMatrix,
  RoleDataScopePolicyCatalog,
  RolePermissionMatrix,
  UserSelectorItem,
  WebActionResultEnvelope,
  WebListResponse,
  WebPageResponse,
} from '@muyun/web-contracts';
import type { HttpClient } from '@muyun/web-core';

export interface AccountRoleGrantRequest {
  userId: string;
  managementScopeType?: ManagementScopeType;
  managementScopeId?: string;
}

export interface UserSelectorRequest {
  roleId?: string;
  keyword?: string;
  enabledOnly?: boolean;
  page?: {
    pageNum: number;
    pageSize: number;
  };
}

export interface EmploymentSelectorRequest {
  roleId?: string;
  organizationId?: string;
  departmentId?: string;
  enabledOnly?: boolean;
  page?: { pageNum: number; pageSize: number };
}

export interface RoleActionGrantRequest {
  moduleAlias: string;
  actionCode: string;
  dataScopePolicy?: DataScopePolicy;
  referenceFieldId?: string;
  referenceActionCode?: string;
}

export interface RolePermissionMatrixActionRequest extends RoleActionGrantRequest {
  granted: boolean;
}

export interface DataGrantActionRequest {
  actionCode: string;
  dataScopePolicy?: DataScopePolicy;
  enabled: boolean;
}

export function createRoleGrantClient(http: HttpClient) {
  return {
    accountRoleGrants(roleId: string) {
      return http.request<AccountRoleGrant[]>({
        path: `/iam.role/${encodeURIComponent(roleId)}/account-grants`,
      });
    },
    grantAccountRole(roleId: string, request: AccountRoleGrantRequest) {
      return http.request<WebActionResultEnvelope<string> | string>({
        method: 'POST',
        path: `/iam.role/${encodeURIComponent(roleId)}/account-grants`,
        body: request,
      });
    },
    deleteAccountRoleGrant(roleId: string, grantId: string) {
      return http.request<WebActionResultEnvelope<number> | number>({
        method: 'POST',
        path: `/iam.role/${encodeURIComponent(roleId)}/account-grants/${encodeURIComponent(grantId)}/delete`,
      });
    },
    userSelector(request: UserSelectorRequest) {
      return http.request<WebPageResponse<UserSelectorItem>>({
        method: 'POST',
        path: '/iam.user/selector/query',
        body: request,
      });
    },
    accountRoleCandidates(roleId: string, request: Omit<UserSelectorRequest, 'roleId'>) {
      return http.request<WebPageResponse<UserSelectorItem>>({
        method: 'POST',
        path: '/iam.user/account-role-candidates/query',
        body: { roleId, ...request },
      });
    },
    employmentRoleGrants(roleId: string) {
      return http.request<EmploymentRoleGrant[]>({
        path: `/iam.role/${encodeURIComponent(roleId)}/employment-grants`,
      });
    },
    grantEmploymentRole(roleId: string, employeePositionId: string) {
      return http.request<WebActionResultEnvelope<string> | string>({
        method: 'POST',
        path: `/iam.role/${encodeURIComponent(roleId)}/employment-grants`,
        body: { employeePositionId },
      });
    },
    deleteEmploymentRoleGrant(roleId: string, grantId: string) {
      return http.request<WebActionResultEnvelope<number> | number>({
        method: 'POST',
        path: `/iam.role/${encodeURIComponent(roleId)}/employment-grants/${encodeURIComponent(grantId)}/delete`,
      });
    },
    employmentSelector(roleId: string, request: EmploymentSelectorRequest) {
      return http.request<WebPageResponse<EmploymentSelectorItem>>({
        method: 'POST',
        path: `/iam.role/${encodeURIComponent(roleId)}/employment-selector/query`,
        body: request,
      });
    },
    authorizationModules(roleId: string) {
      return http.request<WebListResponse<RoleAuthorizationModule>>({
        path: `/iam.role/authorizationModules/${encodeURIComponent(roleId)}`,
      });
    },
    permissionMatrix(roleId: string, moduleAliases: string[]) {
      return http.request<RolePermissionMatrix>({
        method: 'POST',
        path: `/iam.role/permissionMatrix/${encodeURIComponent(roleId)}`,
        body: { moduleAliases },
      });
    },
    grantAction(roleId: string, request: RoleActionGrantRequest) {
      return http.request<WebActionResultEnvelope<number> | number>({
        method: 'POST',
        path: `/iam.role/grant/${encodeURIComponent(roleId)}`,
        body: request,
      });
    },
    revokeAction(roleId: string, moduleAlias: string, actionCode: string) {
      return http.request<WebActionResultEnvelope<number> | number>({
        method: 'POST',
        path: `/iam.role/revoke/${encodeURIComponent(roleId)}`,
        body: { moduleAlias, actionCode },
      });
    },
    replacePermissionMatrix(roleId: string, actions: RolePermissionMatrixActionRequest[]) {
      return http.request<WebActionResultEnvelope<number> | number>({
        method: 'POST',
        path: `/iam.role/permissionMatrix/${encodeURIComponent(roleId)}/replace`,
        body: { actions },
      });
    },
    dataGrantActionMatrix(roleId: string) {
      return http.request<RoleDataGrantActionMatrix>({
        path: `/iam.role/dataGrantActionMatrix/${encodeURIComponent(roleId)}`,
      });
    },
    dataScopePolicyCatalog(roleId: string, moduleAlias?: string) {
      return http.request<RoleDataScopePolicyCatalog>({
        path: `/iam.role/dataScopePolicyCatalog/${encodeURIComponent(roleId)}`,
        query: moduleAlias ? { moduleAlias } : undefined,
      });
    },
    replaceDataGrantActions(roleId: string, actions: DataGrantActionRequest[]) {
      return http.request<WebActionResultEnvelope<number> | number>({
        method: 'POST',
        path: `/iam.role/dataGrantActions/${encodeURIComponent(roleId)}`,
        body: { actions },
      });
    },
  };
}
