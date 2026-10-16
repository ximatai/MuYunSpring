/** The module runtime and reusable record panels share the neutral draft-registration bridge. */
export {
  provideWorkspaceUnsavedStateRegistrar as provideModulePageUnsavedStateHost,
  useWorkspaceViewUnsavedState as useModulePageUnsavedState,
} from '@muyun/web-core';
export type { WorkspaceUnsavedStateRegistrar as ModulePageUnsavedStateHost } from '@muyun/web-core';
