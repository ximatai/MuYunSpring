import { inject, provide, type InjectionKey } from 'vue';
import type { RouteQueryValue } from '@muyun/web-contracts';
import type { WorkspaceViewPresentation } from './workspaceViewContract';

export interface WorkspaceViewHost {
  presentation: WorkspaceViewPresentation;
  setTitle(title: string): void;
  /** Replaces URL-restorable state without creating another workbench tab. */
  replaceQuery(query: Record<string, RouteQueryValue | undefined>): void;
  /** Registers a local draft signal used only when the owning workbench tab closes. */
  registerUnsavedState(source: string, isDirty: () => boolean): () => void;
  dismiss(): void;
  /** Closes the current independent workbench view. */
  close(): void;
}

const workspaceViewHostKey: InjectionKey<WorkspaceViewHost> = Symbol('workspace-view-host');

export function provideWorkspaceViewHost(host: WorkspaceViewHost) {
  provide(workspaceViewHostKey, host);
}

export function useWorkspaceViewHost() {
  return inject(workspaceViewHostKey, undefined);
}
