export { default as DynamicModuleHost } from './DynamicModuleHost.vue';
export { default as DynamicModulePage } from './DynamicModulePage.vue';
export { default as DynamicModuleWorkspaceDetailView } from './DynamicModuleWorkspaceDetailView.vue';
export { customerDescriptor } from './mockDescriptor';
export {
  configureModulePageEnhancements,
  createModulePageEnhancementRegistry,
  modulePageWorkspaceViews,
  resolveModulePageEnhancement,
} from './modulePageEnhancements';
export { provideModulePageNavigation, useModulePageNavigation } from './modulePageNavigation';
export {
  createModulePageListRefreshRegistry,
  modulePageListRefreshRegistry,
  refreshModulePageList,
} from './modulePageListRefresh';
export type { ModulePageListRefreshRegistry } from './modulePageListRefresh';
export type {
  ModuleListEnhancement,
  ModuleDetailEnhancement,
  ModulePageActionContext,
  ModulePageActionContribution,
  ModulePageActionStateContext,
  ModulePageBatchActionContext,
  ModulePageBatchActionContribution,
  ModulePageColumnContribution,
  ModulePageDetailDrawer,
  ModulePageDetailSection,
  ModulePageDetailSectionContext,
  ModulePageDrawer,
  ModulePageDrawerContext,
  ModulePageEnhancement,
  ModulePageEnhancementRegistry,
  ModulePageEnhancementTarget,
  ModulePageRecordActionContext,
  ModulePageRecordActionContribution,
  ModulePageScopeContext,
  ModulePageWorkspaceView,
  ModulePageWorkspaceViewInput,
} from './modulePageEnhancements';
