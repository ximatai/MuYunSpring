import { defineAsyncComponent } from 'vue';
import { defineWorkspaceView } from '../platform-admin-runtime/workspaceViewContract';
export interface CodeConfigurationWorkspaceInput {
  moduleAlias?: string;
  moduleTitle?: string;
}
export const codeConfigurationWorkspaceView = defineWorkspaceView<CodeConfigurationWorkspaceInput>({
  type: 'platform.code.configuration',
  route: '/_platform/workspace/platform.code.configuration',
  moduleAlias: 'platform.code_rule',
  component: defineAsyncComponent(() => import('./CodeConfigurationView.vue')),
  layout: 'workspace',
  routeTitle: '业务编码',
  routeTitleKey: 'platform.code.config.title',
  presentations: ['tab'],
  titleKey: 'platform.code.config.workspace-title',
  titleArgsOf: (input) => ({ module: input.moduleTitle ?? input.moduleAlias ?? '全部模块' }),
  titleOf: (input) => `业务编码：${input.moduleTitle ?? input.moduleAlias ?? '全部模块'}`,
  tabIdentityParamsOf: (input) => ({ moduleAlias: input.moduleAlias ?? 'all' }),
  parentRouteQueryOf: () => ({}),
  parse(query) {
    return {
      ...(typeof query.moduleAlias === 'string' && query.moduleAlias
        ? { moduleAlias: query.moduleAlias }
        : {}),
      ...(typeof query.moduleTitle === 'string' && query.moduleTitle
        ? { moduleTitle: query.moduleTitle }
        : {}),
    };
  },
});
