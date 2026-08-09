import { assert, it } from 'vitest';
import {
  handOffSystemUserDetailWorkspaceSession,
  takeSystemUserDetailWorkspaceSession,
} from '@/views/systemUserDetailWorkspaceSession.ts';

it('system user detail workspace hand-off preserves an edit draft and consumes it once', async () => {
  const input = { recordId: 'system-user-1' } as const;
  await handOffSystemUserDetailWorkspaceSession(input, {
    selectedUser: { id: 'system-user-1', username: 'admin', enabled: true },
    draft: { id: 'system-user-1', username: 'admin', enabled: false },
    mode: 'edit',
    password: '',
  });

  const restored = takeSystemUserDetailWorkspaceSession(input);
  assert.equal(restored?.selectedUser.username, 'admin');
  assert.equal(restored?.draft.enabled, false);
  assert.equal(restored?.mode, 'edit');
  assert.equal(takeSystemUserDetailWorkspaceSession(input), undefined);
});
