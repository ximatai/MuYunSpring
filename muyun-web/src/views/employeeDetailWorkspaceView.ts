import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';

export interface EmployeeDetailWorkspaceViewInput {
  recordId: string;
}

export const employeeDetailWorkspaceView = defineWorkspaceView<EmployeeDetailWorkspaceViewInput>({
  type: 'iam.employee.detail',
  route: '/iam/employee',
  moduleAlias: 'iam.employee',
  component: defineAsyncComponent(() => import('./EmployeeDetailWorkspaceView.vue')),
  layout: 'workspace',
  routeTitle: '职员管理',
  presentations: ['drawer', 'tab'],
  titleOf: () => '职员详情',
  parse(query) {
    const recordId = query.recordId;
    if (typeof recordId !== 'string' || !recordId) return undefined;
    return { recordId };
  },
});
