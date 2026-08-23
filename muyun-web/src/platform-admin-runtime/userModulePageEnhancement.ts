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
  list: {
    rowExpansion: {
      key: 'iam-user-sessions',
      component: UserSessionListExpansion,
    },
    columns: [
      {
        key: 'onlineStatus',
        title: '在线状态',
        width: '112px',
        align: 'center',
        before: 'enabled',
        cell: UserOnlineStatusCell,
      },
    ],
  },
  form: {
    contributions: [],
    fieldPolicies: [
      {
        fieldName: 'password',
        visible: ({ mode }) => mode === 'create',
      },
    ],
  },
  detail: {
    actions: [
      {
        key: 'iam-user-password',
        actionCode: 'changePassword',
        title: '密码管理',
        iconName: 'lock',
        state: (record) => ({ visible: record.id != null }),
        run({ openDrawer }) {
          openDrawer({ title: '密码管理', width: 460, component: UserPasswordDrawer });
        },
      },
    ],
  },
};
