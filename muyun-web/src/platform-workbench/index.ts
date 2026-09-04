export { default as Workbench } from './Workbench.vue';
export { default as WorkbenchMenu } from './WorkbenchMenu.vue';
export { default as WorkbenchOutlet } from './WorkbenchOutlet.vue';
export { default as WorkspaceViewOutlet } from './WorkspaceViewOutlet.vue';
export { syncModulePageWorkspaceViewContributions } from './modulePageWorkspaceViews';
export { provideWorkbenchNavigation, useWorkbenchNavigation } from './workbenchNavigation';
export type { OpenRouteOptions, WorkbenchNavigation, WorkbenchPageOpenResult } from './workbenchNavigation';
export { routeUrlWithOpenOptions } from './workbenchNavigation';
export {
  configureWorkspaceViewContributions,
  createWorkspaceViewDescriptor,
  createWorkspaceViewRegistry,
  dismissWorkspaceViewDescriptor,
  resolveWorkspaceView,
} from './workspaceViews';
export { provideWorkspaceViewHost, useWorkspaceViewHost } from './workspaceViewHost';
export { useWorkspaceViewNavigation } from './workspaceViewNavigation';
export { useWorkspaceViewUnsavedState } from './useWorkspaceViewUnsavedState';
export { defineWorkspaceView } from './workspaceViewContract';
export type { ResolvedWorkspaceView, WorkspaceViewRegistry } from './workspaceViews';
export type {
  WorkspaceViewDefinition,
  WorkspaceViewInput,
  WorkspaceViewPresentation,
  WorkspaceDrawerProfile,
} from './workspaceViewContract';
export type { WorkspaceViewHost } from './workspaceViewHost';
export {
  createMenuTab,
  canonicalDynamicModulePath,
  dynamicModuleAliasFromPath,
  findFirstNavigationMenu,
  getMenuNavigationTarget,
  isTabMenuTarget,
  isWindowMenuTarget,
  pageDescriptorFromUrl,
  pageDescriptorToUrl,
  pageInstanceKeyOf,
  resolvePageDescriptor,
  tabIdentityKeyOf,
  tabKeyOf,
  tryPageDescriptorFromUrl,
  withPageInstanceKey,
} from './menuNavigation';
export type { PageDescriptorResolveOptions, PageDescriptorUrlParseOptions } from './menuNavigation';
export {
  buildWorkbenchMegaMenuModel,
  createWorkbenchMenuNodes,
  filterWorkbenchMenuNodes,
  findWorkbenchMenuNodeById,
  findWorkbenchMenuPath,
  firstDeepRootIdOf,
} from './menuTreeModel';
export type { WorkbenchMegaMenuModel, WorkbenchMenuNode } from './menuTreeModel';
export { presentWorkbenchRealtimeStatus } from './realtimeStatus';
export type { WorkbenchRealtimeStatus, WorkbenchRealtimeStatusPresentation } from './realtimeStatus';
