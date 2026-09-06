import { inject, onScopeDispose, watch, type ComputedRef, type InjectionKey, type Ref } from 'vue';

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

// Scoped separately so an active detail surface can keep its own child controls usable.
export const WORKSPACE_NAVIGATION_DISABLED: InjectionKey<ComputedRef<boolean>> = Symbol(
  'workspace-navigation-disabled',
);

export const MANAGEMENT_WORKSPACE_CONTEXT: InjectionKey<ManagementWorkspaceContext> =
  Symbol('management-workspace');

export const WORKSPACE_SORTING_BUSY: InjectionKey<ComputedRef<boolean>> = Symbol('workspace-sorting-busy');
export const WORKSPACE_SORT_ACTIVITY: InjectionKey<(active: boolean) => void> =
  Symbol('workspace-sort-activity');

/** Reports an actual sorting request, never the user's sorting toggle. */
export function useWorkspaceSortActivity(active: Ref<boolean>) {
  const report = inject(WORKSPACE_SORT_ACTIVITY, undefined);
  let reported = false;
  watch(
    active,
    (value) => {
      if (reported === value) return;
      reported = value;
      report?.(value);
    },
    { immediate: true, flush: 'sync' },
  );
  onScopeDispose(() => {
    if (reported) report?.(false);
  });
}
