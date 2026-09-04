import { onUnmounted } from 'vue';
import { useWorkspaceViewHost } from './workspaceViewHost';

/**
 * Lets a workspace panel declare unsaved local state without exposing or
 * duplicating its draft in the workbench shell.
 */
export function useWorkspaceViewUnsavedState(source: string, isDirty: () => boolean) {
  const host = useWorkspaceViewHost();
  const unregister = host?.registerUnsavedState(source, isDirty);
  if (unregister) onUnmounted(unregister);
}
