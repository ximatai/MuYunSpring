export { default as DynamicModuleHost } from './DynamicModuleHost.vue';
export { default as DynamicModulePage } from './DynamicModulePage.vue';
export { customerDescriptor } from './mockDescriptor';
export {
  configureModulePageEnhancements,
  createModulePageEnhancementRegistry,
  modulePageWorkspaceViews,
  resolveModulePageEnhancement,
} from './modulePageEnhancements';
export { provideModulePageNavigation, useModulePageNavigation } from './modulePageNavigation';
export type {
  ModuleListEnhancement,
  ModuleDetailEnhancement,
  ModulePageActionContext,
  ModulePageActionContribution,
  ModulePageBatchActionContext,
  ModulePageBatchActionContribution,
  ModulePageColumnContribution,
  ModulePageDetailSection,
  ModulePageDetailSectionContext,
  ModulePageDrawer,
  ModulePageDrawerContext,
  ModulePageEnhancement,
  ModulePageEnhancementRegistry,
  ModulePageEnhancementTarget,
  ModulePageRecordActionContext,
  ModulePageRecordActionContribution,
  ModulePageWorkspaceView,
  ModulePageWorkspaceViewInput,
} from './modulePageEnhancements';
