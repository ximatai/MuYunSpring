import { inject, provide, type InjectionKey } from 'vue';
import type { WorkspaceViewPresentation } from './workspaceViewContract';

export interface WorkspaceViewHost {
  presentation: WorkspaceViewPresentation;
  setTitle(title: string): void;
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
