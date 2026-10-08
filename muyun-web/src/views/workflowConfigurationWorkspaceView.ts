import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';
export interface WorkflowConfigurationInput {
  moduleAlias: string;
  moduleTitle?: string;
}
export const workflowConfigurationWorkspaceView = defineWorkspaceView<WorkflowConfigurationInput>({
  type: 'platform.module.workflows',
  route: '/_platform/workspace/platform.module.workflows',
  moduleAlias: 'platform.module',
  component: defineAsyncComponent(() => import('./WorkflowConfigurationView.vue')),
  layout: 'workspace',
  routeTitle: '模块管理',
  presentations: ['tab'],
  titleOf: (input) => `审批流程：${input.moduleTitle ?? input.moduleAlias}`,
  parentRouteQueryOf: () => ({}),
  parse(query) {
    if (typeof query.moduleAlias !== 'string' || !query.moduleAlias) return undefined;
    return {
      moduleAlias: query.moduleAlias,
      ...(typeof query.moduleTitle === 'string' ? { moduleTitle: query.moduleTitle } : {}),
    };
  },
});
