import type { ComputedRef, InjectionKey } from 'vue';

export interface ManagementWorkspaceExplorerRegistration {
  id: string;
  title: string;
  hasSelection: boolean;
}

export interface ManagementWorkspaceContext {
  registerExplorer: (registration: ManagementWorkspaceExplorerRegistration) => void;
  unregisterExplorer: (id: string) => void;
  isExplorerCollapsed: (id: string) => boolean;
  toggleExplorer: (id: string) => void;
  collapsedExplorerOffset: (id: string) => number;
  hasCollapsedExplorers: ComputedRef<boolean>;
}

export const MANAGEMENT_WORKSPACE_CONTEXT: InjectionKey<ManagementWorkspaceContext> =
  Symbol('management-workspace');
