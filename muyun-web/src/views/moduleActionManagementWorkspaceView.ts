import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';

export interface ModuleActionManagementWorkspaceViewInput {
  moduleAlias: string;
  moduleTitle?: string;
  moduleKind?: 'static' | 'dynamic';
}

/** A tab-scoped action directory for exactly one module. */
export const moduleActionManagementWorkspaceView =
  defineWorkspaceView<ModuleActionManagementWorkspaceViewInput>({
    type: 'platform.module.actions',
    route: '/_workspace/platform.module.actions',
    moduleAlias: 'platform.module',
    component: defineAsyncComponent(() => import('./ModuleActionManagementView.vue')),
    layout: 'workspace',
    routeTitle: '模块管理',
    presentations: ['tab'],
    titleOf: (input) => `动作：${input.moduleTitle ?? input.moduleAlias}`,
    parentRouteQueryOf: () => ({}),
    parse(query) {
      const moduleAlias = query.moduleAlias;
      const moduleTitle = query.moduleTitle;
      if (typeof moduleAlias !== 'string' || !moduleAlias) return undefined;
      return {
        moduleAlias,
        ...(typeof moduleTitle === 'string' && moduleTitle ? { moduleTitle } : {}),
        ...(query.moduleKind === 'static' || query.moduleKind === 'dynamic'
          ? { moduleKind: query.moduleKind }
          : {}),
      };
    },
  });
