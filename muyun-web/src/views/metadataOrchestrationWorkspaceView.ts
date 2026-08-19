import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';

export interface MetadataOrchestrationWorkspaceViewInput {
  moduleAlias: string;
  moduleTitle?: string;
}

/** A tab-scoped metadata workbench for one dynamic module. */
export const metadataOrchestrationWorkspaceView =
  defineWorkspaceView<MetadataOrchestrationWorkspaceViewInput>({
    type: 'platform.module.metadata-orchestration',
    route: '/_workspace/platform.module.metadata-orchestration',
    moduleAlias: 'platform.module',
    component: defineAsyncComponent(() => import('./MetadataOrchestrationView.vue')),
    layout: 'workspace',
    routeTitle: '模块管理',
    presentations: ['tab'],
    titleOf: (input) => `元数据：${input.moduleTitle ?? input.moduleAlias}`,
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
