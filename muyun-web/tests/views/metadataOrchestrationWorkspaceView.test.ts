import { assert, it } from 'vitest';
import { createWorkspaceViewDescriptor } from '@/platform-admin-runtime/workspaceViews.ts';
import { metadataOrchestrationWorkspaceView } from '@/views/metadataOrchestrationWorkspaceView.ts';
import { uiOrchestrationWorkspaceView } from '@/views/uiOrchestrationWorkspaceView.ts';
import { moduleGovernanceWorkspaceView } from '@/views/moduleGovernanceWorkspaceView.ts';

it('uses an independent restorable workspace URL instead of the module-management page route', () => {
  const descriptor = createWorkspaceViewDescriptor(metadataOrchestrationWorkspaceView, {
    moduleAlias: 'crm.customer',
    moduleTitle: '客户',
  });

  assert.equal(descriptor.target.route, '/_platform/workspace/platform.module.metadata-orchestration');
  assert.equal(descriptor.target.moduleAlias, 'platform.module');
  assert.deepEqual(descriptor.target.query, {
    workspaceView: 'platform.module.metadata-orchestration',
    workspacePresentation: 'tab',
    moduleAlias: 'crm.customer',
    moduleTitle: '客户',
  });
});

it('uses a separate restorable workspace URL for module UI orchestration', () => {
  const descriptor = createWorkspaceViewDescriptor(uiOrchestrationWorkspaceView, {
    moduleAlias: 'crm.customer',
    moduleTitle: '客户',
  });

  assert.equal(descriptor.target.route, '/_platform/workspace/platform.module.ui-orchestration');
  assert.deepEqual(descriptor.target.query, {
    workspaceView: 'platform.module.ui-orchestration',
    workspacePresentation: 'tab',
    moduleAlias: 'crm.customer',
    moduleTitle: '客户',
  });
});

it('restores module governance at its requested initial tab', () => {
  const descriptor = createWorkspaceViewDescriptor(moduleGovernanceWorkspaceView, {
    moduleAlias: 'crm.customer',
    moduleTitle: '客户',
    governanceTab: 'metadata',
  });

  assert.equal(descriptor.target.route, '/_platform/workspace/platform.module.governance');
  assert.deepEqual(descriptor.target.query, {
    workspaceView: 'platform.module.governance',
    workspacePresentation: 'tab',
    moduleAlias: 'crm.customer',
    moduleTitle: '客户',
    governanceTab: 'metadata',
  });
});
