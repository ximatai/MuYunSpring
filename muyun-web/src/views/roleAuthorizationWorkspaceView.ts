import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';

/** Role identity is sufficient: the authorization APIs validate ownership server-side. */
export interface RoleAuthorizationWorkspaceViewInput {
  roleId: string;
}

export const roleAuthorizationWorkspaceView = defineWorkspaceView<RoleAuthorizationWorkspaceViewInput>({
  type: 'iam.role.authorization',
  route: '/iam/role/authorization',
  moduleAlias: 'iam.role',
  component: defineAsyncComponent(() => import('./RoleAuthorizationView.vue')),
  layout: 'workspace',
  routeTitle: '角色授权',
  drawerProfile: 'wide-work',
  presentations: ['drawer', 'tab'],
  titleOf: () => '角色授权',
  parse(query) {
    const roleId = query.roleId;
    return typeof roleId === 'string' && roleId ? { roleId } : undefined;
  },
});
