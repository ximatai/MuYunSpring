import { assert, it } from 'vitest';
import { createWorkspaceViewDescriptor, tabIdentityKeyOf } from '@/platform-workbench';
import { moduleGovernanceWorkspaceView } from '@/views/moduleGovernanceWorkspaceView';

it('keeps the governance panel in URL state rather than workbench tab identity', () => {
  const overview = createWorkspaceViewDescriptor(moduleGovernanceWorkspaceView, {
    moduleAlias: 'education.exam',
    moduleTitle: '考试管理',
    governanceTab: 'overview',
  });
  const metadata = createWorkspaceViewDescriptor(moduleGovernanceWorkspaceView, {
    moduleAlias: 'education.exam',
    moduleTitle: '考试管理',
    governanceTab: 'metadata',
  });
  const rules = createWorkspaceViewDescriptor(moduleGovernanceWorkspaceView, {
    moduleAlias: 'education.exam',
    moduleTitle: '考试管理',
    governanceTab: 'rules',
  });

  assert.equal(tabIdentityKeyOf(overview), tabIdentityKeyOf(metadata));
  assert.equal(overview.target.query?.governanceTab, 'overview');
  assert.equal(metadata.target.query?.governanceTab, 'metadata');
  assert.equal(rules.target.query?.governanceTab, 'rules');
  assert.equal(tabIdentityKeyOf(overview), tabIdentityKeyOf(rules));
  assert.notProperty(overview.params ?? {}, 'governanceTab');
});
