import { workspaceViewContributions } from './workspaceViewContributions';
import { configureWorkspaceViewContributions } from '@muyun/platform-workbench';

configureWorkspaceViewContributions('platform-admin-runtime', workspaceViewContributions);

export {
  createWorkspaceViewDescriptor,
  createWorkspaceViewRegistry,
  dismissWorkspaceViewDescriptor,
  resolveWorkspaceView,
  type ResolvedWorkspaceView,
  type WorkspaceViewRegistry,
} from '@muyun/platform-workbench';
export {
  defineWorkspaceView,
  type WorkspaceViewDefinition,
  type WorkspaceViewInput,
  type WorkspaceViewPresentation,
} from './workspaceViewContract';
