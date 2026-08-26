import type { ModulePageEnhancement } from '@muyun/dynamic-page-runtime';
import RoleScopeTree from './role/RoleScopeTree.vue';
import RoleAccountGrantDrawerSurface from './role/RoleAccountGrantDrawerSurface.vue';
import RoleEmploymentGrantDrawerSurface from './role/RoleEmploymentGrantDrawerSurface.vue';
import RoleAuthorizationDrawerSurface from './role/RoleAuthorizationDrawerSurface.vue';

/**
 * IAM contributes only the role-specific surfaces. The descriptor-driven host
 * remains responsible for the list, standard form, CRUD, pagination and all
 * request transport.
 */
export const roleModulePageEnhancement: ModulePageEnhancement = {
  id: 'iam-role-scope-and-actions',
  target: { moduleAlias: 'iam.role' },
  navigator: {
    bypassListScope: true,
    extension: {
      key: 'iam-role-scope-tree',
      component: RoleScopeTree,
      selection: {
        kind: 'roleScope',
        initialKey: (currentUser) =>
          currentUser?.system === true
            ? 'platform'
            : currentUser?.tenantId
              ? `tenant:${currentUser.tenantId}`
              : undefined,
        initialPresentation: (currentUser) =>
          currentUser?.system === true
            ? { label: '平台角色' }
            : currentUser?.tenantId
              ? { label: currentUser.tenantId }
              : undefined,
      },
    },
  },
  detail: {
    actions: [
      {
        key: 'role-bind-account',
        actionCode: 'accountRoleGrants',
        title: '绑定',
        state: (record) => ({
          visible: record.assignmentType === 'account',
          disabled: record.systemManaged === true,
        }),
        run({ openDrawer }) {
          openDrawer({ title: '绑定用户', width: 960, component: RoleAccountGrantDrawerSurface });
        },
      },
      {
        key: 'role-bind-employment',
        actionCode: 'employmentRoleGrants',
        title: '绑定',
        state: (record) => ({
          visible: record.assignmentType !== 'account',
          disabled: record.systemManaged === true,
        }),
        run({ openDrawer }) {
          openDrawer({ title: '绑定任职', width: 960, component: RoleEmploymentGrantDrawerSurface });
        },
      },
      {
        key: 'role-authorize',
        actionCode: 'rolePermissions',
        title: '授权',
        state: (record) => ({
          visible: record.roleKind !== 'group',
          disabled: record.systemManaged === true,
        }),
        run({ openDrawer }) {
          openDrawer({ title: '角色授权', width: 1080, component: RoleAuthorizationDrawerSurface });
        },
      },
    ],
  },
};
