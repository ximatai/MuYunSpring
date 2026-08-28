import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';

export interface UiOrchestrationWorkspaceViewInput {
  moduleAlias: string;
  moduleTitle?: string;
}

/** A tab-scoped UI configuration workbench for one dynamic module. */
export const uiOrchestrationWorkspaceView = defineWorkspaceView<UiOrchestrationWorkspaceViewInput>({
  type: 'platform.module.ui-orchestration',
  route: '/_platform/workspace/platform.module.ui-orchestration',
  moduleAlias: 'platform.module',
  component: defineAsyncComponent(() => import('./UiOrchestrationView.vue')),
  layout: 'workspace',
  routeTitle: '模块管理',
  presentations: ['tab'],
  titleOf: (input) => `UI 编排：${input.moduleTitle ?? input.moduleAlias}`,
  parentRouteQueryOf: () => ({}),
  parse(query) {
    const moduleAlias = query.moduleAlias;
    const moduleTitle = query.moduleTitle;
    if (typeof moduleAlias !== 'string' || !moduleAlias) return undefined;
    return {
      moduleAlias,
      ...(typeof moduleTitle === 'string' && moduleTitle ? { moduleTitle } : {}),
    };
  },
});
