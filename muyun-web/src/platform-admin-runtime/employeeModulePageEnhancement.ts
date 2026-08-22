import type { ModulePageEnhancement } from '@muyun/dynamic-page-runtime';
import EmployeeAccountDrawer from './employee/EmployeeAccountDrawer.vue';

/** The employee page remains platform-owned; only account provisioning needs a domain drawer. */
export const employeeModulePageEnhancement: ModulePageEnhancement = {
  id: 'iam-employee-account-drawer',
  target: { moduleAlias: 'iam.employee' },
  detail: {
    actions: [
      {
        key: 'employee-account',
        title: '设置账号',
        state: () => ({ visible: true }),
        run({ module, record, openDrawer }) {
          const id = record.id == null ? undefined : String(record.id);
          if (!id || module.can('employeeAccounts', id) === false) return;
          openDrawer({ title: '设置账号', width: 520, component: EmployeeAccountDrawer });
        },
      },
    ],
  },
};
