import type { WorkspaceViewDefinition, WorkspaceViewInput } from './workspaceViewContract';
import { roleDetailWorkspaceView } from '../views/roleDetailWorkspaceView';
import { systemUserDetailWorkspaceView } from '../views/systemUserDetailWorkspaceView';
import { roleAuthorizationWorkspaceView } from '../views/roleAuthorizationWorkspaceView';

/** Application assembly for restorable workspace views. */
export const workspaceViewContributions: readonly WorkspaceViewDefinition<WorkspaceViewInput>[] = [
  roleDetailWorkspaceView,
  systemUserDetailWorkspaceView,
  roleAuthorizationWorkspaceView,
];
