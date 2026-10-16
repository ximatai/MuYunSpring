import type { ModulePageEnhancement, ModulePageRecordActionContribution } from '@muyun/dynamic-page-runtime';
import RoleScopeTree from './role/RoleScopeTree.vue';
import RoleEnumTitleCell from './role/RoleEnumTitleCell.vue';
import RoleAccountGrantDrawerSurface from './role/RoleAccountGrantDrawerSurface.vue';
import RoleEmploymentGrantDrawerSurface from './role/RoleEmploymentGrantDrawerSurface.vue';
import RoleAuthorizationDrawerSurface from './role/RoleAuthorizationDrawerSurface.vue';
import RoleGroupMemberFormField from './role/RoleGroupMemberFormField.vue';

const roleRecordActions: ModulePageRecordActionContribution[] = [
  {
    key: 'role-bind-account',
    actionCode: 'accountRoleGrants',
    title: '绑定',
    titleKey: 'platform.screen.RoleManagementView.assign',
    state: (record) => ({
      visible: record.assignmentType === 'account',
      disabled: record.systemManaged === true,
    }),
    run({ openDrawer }) {
      openDrawer({
        title: '绑定用户',
        titleKey: 'platform.screen.RoleManagementView.assign-to-users',
        width: 'extraWide',
        component: RoleAccountGrantDrawerSurface,
      });
    },
  },
  {
    key: 'role-bind-employment',
    actionCode: 'employmentRoleGrants',
    title: '绑定',
    titleKey: 'platform.screen.RoleManagementView.assign',
    state: (record) => ({
      visible: record.assignmentType !== 'account',
      disabled: record.systemManaged === true,
    }),
    run({ openDrawer }) {
      openDrawer({
        title: '绑定任职',
        titleKey: 'platform.screen.RoleManagementView.assign-to-positions',
        width: 'extraWide',
        component: RoleEmploymentGrantDrawerSurface,
      });
    },
  },
  {
    key: 'role-authorize',
    actionCode: 'rolePermissions',
    title: '授权',
    titleKey: 'platform.page-action.MANAGE_PERMISSIONS.title',
    state: (record) => ({
      visible: record.roleKind !== 'group',
      disabled: record.systemManaged === true,
    }),
    run({ openDrawer }) {
      openDrawer({
        title: '角色授权',
        titleKey: 'platform.unsaved-source.RoleAuthorizationView',
        width: 'wide',
        component: RoleAuthorizationDrawerSurface,
      });
    },
  },
];

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
      },
    },
  },
  list: {
    cellComponents: [
      { key: 'assignmentType', cell: RoleEnumTitleCell },
      { key: 'roleKind', cell: RoleEnumTitleCell },
      { key: 'sharePolicy', cell: RoleEnumTitleCell },
    ],
  },
  form: {
    contributions: [
      {
        key: 'role-group-members',
        component: RoleGroupMemberFormField,
        location: { surface: 'record-card', fieldName: 'memberRoleIds', placement: 'after' },
      },
    ],
    fieldPolicies: [
      {
        fieldName: 'memberRoleIds',
        visible: () => false,
      },
    ],
  },
  recordActions: roleRecordActions,
};
