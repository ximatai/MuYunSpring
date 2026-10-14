import type { ModulePageEnhancement } from '@muyun/dynamic-page-runtime';
import UserOnlineStatusCell from './user/UserOnlineStatusCell.vue';
import UserPasswordDrawer from './user/UserPasswordDrawer.vue';
import UserSessionListExpansion from './user/UserSessionListExpansion.vue';

/**
 * IAM-specific behavior around the platform-owned user CRUD page.
 * Sessions and password operations remain IAM features; the module runner only
 * provides their controlled list cell, row-expansion and action boundaries.
 */
export const userModulePageEnhancement: ModulePageEnhancement = {
  id: 'iam-user-standard-page-enhancement',
  target: { moduleAlias: 'iam.user' },
  navigator: {
    emptyListScope: ({ currentUser, selectedNavigatorRecords }) => {
      if (currentUser?.system !== true || Object.values(selectedNavigatorRecords).some(Boolean)) {
        return undefined;
      }
      return [{ fieldName: 'tenantId', operator: 'NULL', values: [] }];
    },
  },
  list: {
    rowExpansion: {
      key: 'iam-user-sessions',
      component: UserSessionListExpansion,
    },
    columns: [
      {
        key: 'onlineStatus',
        title: '在线状态',
        titleKey: 'platform.entry.online-status',
        width: '112px',
        align: 'center',
        before: 'enabled',
        cell: UserOnlineStatusCell,
      },
    ],
  },
  detail: {
    actions: [
      {
        key: 'iam-user-password',
        actionCode: 'changePassword',
        title: '密码管理',
        titleKey: 'platform.entry.password-management',
        iconName: 'lock',
        state: (record) => ({ visible: record.id != null }),
        run({ openDrawer }) {
          openDrawer({
            title: '密码管理',
            titleKey: 'platform.entry.password-management',
            width: 'narrow',
            component: UserPasswordDrawer,
          });
        },
      },
    ],
  },
};
