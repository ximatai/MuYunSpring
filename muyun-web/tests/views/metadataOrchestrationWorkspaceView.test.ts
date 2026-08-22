import { assert, it } from 'vitest';
import { createWorkspaceViewDescriptor } from '@/platform-admin-runtime/workspaceViews.ts';
import { metadataOrchestrationWorkspaceView } from '@/views/metadataOrchestrationWorkspaceView.ts';

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
