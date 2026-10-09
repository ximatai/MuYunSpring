import { inject, onUnmounted, provide, type InjectionKey } from 'vue';

/** A UI host can protect drafts without coupling reusable record panels to the workbench implementation. */
export interface WorkspaceUnsavedStateRegistrar {
  registerUnsavedState(source: string, isDirty: () => boolean, isBusy?: () => boolean): () => void;
}
const registrarKey: InjectionKey<WorkspaceUnsavedStateRegistrar | undefined> =
  Symbol('workspace-unsaved-state');
export function provideWorkspaceUnsavedStateRegistrar(registrar: WorkspaceUnsavedStateRegistrar | undefined) {
  provide(registrarKey, registrar);
}
export function useWorkspaceViewUnsavedState(source: string, isDirty: () => boolean, isBusy?: () => boolean) {
  const registrar = inject(registrarKey, undefined);
  const unregister = registrar?.registerUnsavedState(source, isDirty, isBusy);
  if (unregister) onUnmounted(unregister);
}
