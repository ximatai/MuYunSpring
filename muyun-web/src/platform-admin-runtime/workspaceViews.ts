import { workspaceViewContributions } from './workspaceViewContributions';
import { configureWorkspaceViewContributions } from '@muyun/platform-workbench';
import { configureModulePageEnhancementContributions } from '@muyun/dynamic-page-runtime';
import {
  platformModuleActionPageEnhancement,
  platformModulePageEnhancement,
} from './platformModulePageEnhancement';
import { passwordPolicyPageEnhancement } from './passwordPolicyPageEnhancement';
import { tenantModulePageEnhancement } from './tenantModulePageEnhancement';
import { employeeModulePageEnhancement } from './employeeModulePageEnhancement';
import { userModulePageEnhancement } from './userModulePageEnhancement';
import { roleModulePageEnhancement } from './roleModulePageEnhancement';

configureWorkspaceViewContributions('platform-admin-runtime', workspaceViewContributions);
// The first-party platform enhancement is a default contribution, not an App.vue
// detail. Consumer workbenches import this module too, so both hosts resolve the
// same action/metadata workspace URLs before restoring tabs.
configureModulePageEnhancementContributions('platform-admin-runtime', [
  platformModulePageEnhancement,
  platformModuleActionPageEnhancement,
  passwordPolicyPageEnhancement,
  tenantModulePageEnhancement,
  employeeModulePageEnhancement,
  userModulePageEnhancement,
  roleModulePageEnhancement,
]);

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
