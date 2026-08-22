import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';

export interface SystemUserDetailWorkspaceViewInput {
  recordId: string;
}

/** A system account detail has a stable identity and can be resumed independently. */
export const systemUserDetailWorkspaceView = defineWorkspaceView<SystemUserDetailWorkspaceViewInput>({
  type: 'iam.system-user.detail',
  route: '/iam/system-user',
  moduleAlias: 'iam.system_user',
  component: defineAsyncComponent(() => import('./SystemUserDetailWorkspaceView.vue')),
  layout: 'workspace',
  routeTitle: '系统账号管理',
  presentations: ['drawer', 'tab'],
  titleOf: () => '系统账号详情',
  parse(query) {
    const recordId = query.recordId;
    return typeof recordId === 'string' && recordId ? { recordId } : undefined;
  },
});
