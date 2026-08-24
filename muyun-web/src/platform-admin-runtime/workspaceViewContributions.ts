import type { WorkspaceViewDefinition, WorkspaceViewInput } from './workspaceViewContract';
import { roleDetailWorkspaceView } from '../views/roleDetailWorkspaceView';
import { roleAuthorizationWorkspaceView } from '../views/roleAuthorizationWorkspaceView';

/** Application assembly for restorable workspace views. */
export const workspaceViewContributions: readonly WorkspaceViewDefinition<WorkspaceViewInput>[] = [
  roleDetailWorkspaceView,
  roleAuthorizationWorkspaceView,
];
