import { assert, it } from 'vitest';
import { createWorkspaceViewDescriptor } from '@/platform-admin-runtime/workspaceViews.ts';
import { moduleActionManagementWorkspaceView } from '@/views/moduleActionManagementWorkspaceView.ts';

it('creates one closable action-management tab per governed module', () => {
  const descriptor = createWorkspaceViewDescriptor(moduleActionManagementWorkspaceView, {
    moduleAlias: 'education.teacher',
    moduleTitle: '教师',
  });

  assert.equal(descriptor.title, '动作：教师');
  assert.equal(descriptor.layout, 'workspace');
  assert.equal(descriptor.target.route, '/_platform/workspace/platform.module.actions');
  assert.equal(descriptor.target.moduleAlias, 'platform.module');
  assert.deepEqual(descriptor.target.query, {
    workspaceView: 'platform.module.actions',
    workspacePresentation: 'tab',
    moduleAlias: 'education.teacher',
    moduleTitle: '教师',
  });
  assert.equal(descriptor.tabPolicy.identity, 'by-params');
});
