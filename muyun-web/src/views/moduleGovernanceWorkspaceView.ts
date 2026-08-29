import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';

export const moduleGovernanceTabs = [
  'overview',
  'metadata',
  'capabilities',
  'actions',
  'ui',
  'diagnostics',
] as const;
export type ModuleGovernanceTab = (typeof moduleGovernanceTabs)[number];

export interface ModuleGovernanceWorkspaceViewInput {
  moduleAlias: string;
  moduleTitle?: string;
  governanceTab?: ModuleGovernanceTab;
}

/** The module-scoped home for configuration governance surfaces. */
export const moduleGovernanceWorkspaceView = defineWorkspaceView<ModuleGovernanceWorkspaceViewInput>({
  type: 'platform.module.governance',
  route: '/_platform/workspace/platform.module.governance',
  moduleAlias: 'platform.module',
  component: defineAsyncComponent(() => import('./ModuleGovernanceView.vue')),
  layout: 'workspace',
  routeTitle: '模块管理',
  presentations: ['tab'],
  titleOf: (input) => `模块治理：${input.moduleTitle ?? input.moduleAlias}`,
  parentRouteQueryOf: () => ({}),
  parse(query) {
    const moduleAlias = query.moduleAlias;
    const moduleTitle = query.moduleTitle;
    const governanceTab = query.governanceTab;
    if (typeof moduleAlias !== 'string' || !moduleAlias) return undefined;
    return {
      moduleAlias,
      ...(typeof moduleTitle === 'string' && moduleTitle ? { moduleTitle } : {}),
      ...(isModuleGovernanceTab(governanceTab) ? { governanceTab } : {}),
    };
  },
});

function isModuleGovernanceTab(value: unknown): value is ModuleGovernanceTab {
  return typeof value === 'string' && (moduleGovernanceTabs as readonly string[]).includes(value);
}
