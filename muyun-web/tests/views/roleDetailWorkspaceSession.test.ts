import { assert, it } from 'vitest';
import {
  handOffRoleDetailWorkspaceSession,
  takeRoleDetailWorkspaceSession,
} from '@/views/roleDetailWorkspaceSession.ts';

it('role detail workspace hand-off preserves the scope identity and draft once', async () => {
  const input = { recordId: 'role-1', scopeKind: 'organization' as const, scopeId: 'org-1' };
  await handOffRoleDetailWorkspaceSession(input, {
    selectedRole: {
      id: 'role-1',
      title: '机构管理员',
      ownerScopeType: 'organization',
      ownerScopeId: 'org-1',
    },
    draft: { id: 'role-1', title: '机构管理员（调整中）' },
    scope: { kind: 'organization', id: 'org-1', key: 'organization:org-1', title: '研发中心' },
    mode: 'edit',
  });

  const restored = takeRoleDetailWorkspaceSession(input);
  assert.equal(restored?.draft.title, '机构管理员（调整中）');
  assert.equal(restored?.scope.kind, 'organization');
  assert.equal(restored?.scope.id, 'org-1');
  assert.equal(takeRoleDetailWorkspaceSession(input), undefined);
});
