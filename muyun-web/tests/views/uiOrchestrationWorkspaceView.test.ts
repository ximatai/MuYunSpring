import { assert, it } from 'vitest';
import { createWorkspaceViewDescriptor } from '@/platform-admin-runtime/workspaceViews.ts';
import { uiOrchestrationWorkspaceView } from '@/views/uiOrchestrationWorkspaceView.ts';

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
