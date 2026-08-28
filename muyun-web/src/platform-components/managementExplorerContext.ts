import type { ComputedRef, InjectionKey } from 'vue';

/**
 * Layout-only coordination between a management explorer column and its
 * standard panel header. Business navigators keep their own selection state.
 */
export interface ManagementExplorerColumnContext {
  collapsible: ComputedRef<boolean>;
  collapsed: ComputedRef<boolean>;
  collapse: () => void;
}

export const MANAGEMENT_EXPLORER_COLUMN_CONTEXT: InjectionKey<ManagementExplorerColumnContext> = Symbol(
  'management-explorer-column',
);
