import { defineAsyncComponent } from 'vue';
import type { RoleOwnerScopeType } from '@muyun/web-contracts';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';

export interface RoleDetailWorkspaceViewInput {
  recordId: string;
  scopeKind: RoleOwnerScopeType;
  /** Platform-owned roles deliberately have no scope id. */
  scopeId?: string;
}

const roleScopeKinds = new Set<RoleOwnerScopeType>(['platform', 'tenant', 'organization']);

export const roleDetailWorkspaceView = defineWorkspaceView<RoleDetailWorkspaceViewInput>({
  type: 'iam.role.detail',
  route: '/iam/role',
  moduleAlias: 'iam.role',
  component: defineAsyncComponent(() => import('./RoleDetailWorkspaceView.vue')),
  layout: 'workspace',
  routeTitle: '角色管理',
  presentations: ['drawer', 'tab'],
  titleOf: () => '角色详情',
  parentRouteQueryOf(input) {
    return input.scopeKind === 'platform'
      ? { scopeKind: input.scopeKind }
      : { scopeKind: input.scopeKind, scopeId: input.scopeId! };
  },
  parse(query) {
    const recordId = query.recordId;
    const scopeKind = query.scopeKind;
    const scopeId = query.scopeId;
    if (
      typeof recordId !== 'string' ||
      !recordId ||
      typeof scopeKind !== 'string' ||
      !roleScopeKinds.has(scopeKind as RoleOwnerScopeType) ||
      (scopeKind === 'platform' ? scopeId !== undefined : typeof scopeId !== 'string' || !scopeId)
    ) {
      return undefined;
    }
    return {
      recordId,
      scopeKind: scopeKind as RoleOwnerScopeType,
      ...(typeof scopeId === 'string' ? { scopeId } : {}),
    };
  },
});
