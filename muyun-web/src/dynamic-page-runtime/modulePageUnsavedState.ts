import { inject, onUnmounted, provide, type InjectionKey } from 'vue';

/**
 * Optional host bridge for a dynamic page rendered as an independent workspace
 * view. The dynamic runtime only reports its local draft fact; the workbench
 * owns registration, confirmation and tab destruction.
 */
export interface ModulePageUnsavedStateHost {
  registerUnsavedState(source: string, isDirty: () => boolean): () => void;
}

const modulePageUnsavedStateHostKey: InjectionKey<ModulePageUnsavedStateHost | undefined> =
  Symbol('module-page-unsaved-state-host');

export function provideModulePageUnsavedStateHost(host: ModulePageUnsavedStateHost | undefined) {
  provide(modulePageUnsavedStateHostKey, host);
}

export function useModulePageUnsavedState(source: string, isDirty: () => boolean) {
  const host = inject(modulePageUnsavedStateHostKey, undefined);
  const unregister = host?.registerUnsavedState(source, isDirty);
  if (unregister) onUnmounted(unregister);
}
