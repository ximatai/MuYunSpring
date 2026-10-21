import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';
export interface WorkflowConfigurationInput {
  moduleAlias: string;
  moduleTitle?: string;
  definitionId: string;
  definitionTitle?: string;
}
export const workflowConfigurationWorkspaceView = defineWorkspaceView<WorkflowConfigurationInput>({
  type: 'platform.module.workflows',
  route: '/_platform/workspace/platform.module.workflows',
  moduleAlias: 'platform.module',
  component: defineAsyncComponent(() => import('./WorkflowConfigurationView.vue')),
  layout: 'workspace',
  routeTitle: '模块管理',
  routeTitleKey: 'platform.workspace-view.modules.route-title',
  presentations: ['tab'],
  tabIdentityParamsOf: (input) => ({ moduleAlias: input.moduleAlias, definitionId: input.definitionId }),
  titleArgsOf: (input) => ({ workflow: input.definitionTitle ?? input.definitionId }),
  titleKey: 'platform.workspace-view.workflow.design-title',
  titleOf: (input) => `流程编排：${input.definitionTitle ?? input.definitionId}`,
  parentRouteQueryOf: () => ({}),
  parse(query) {
    if (
      typeof query.moduleAlias !== 'string' ||
      !query.moduleAlias ||
      typeof query.definitionId !== 'string' ||
      !query.definitionId
    )
      return undefined;
    return {
      moduleAlias: query.moduleAlias,
      definitionId: query.definitionId,
      ...(typeof query.definitionTitle === 'string' ? { definitionTitle: query.definitionTitle } : {}),
      ...(typeof query.moduleTitle === 'string' ? { moduleTitle: query.moduleTitle } : {}),
    };
  },
});
