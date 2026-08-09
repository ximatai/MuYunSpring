import { assert, it } from 'vitest';
import {
  handOffUserDetailWorkspaceSession,
  registerUserDetailWorkspaceHandoffRecipient,
  takeUserDetailWorkspaceSession,
} from '@/views/userDetailWorkspaceSession.ts';

it('user detail workspace hand-off preserves an edit draft and consumes it once', async () => {
  const input = { recordId: 'user-1' } as const;
  await handOffUserDetailWorkspaceSession(input, {
    selectedUser: { id: 'user-1', username: 'alice', enabled: true },
    draft: { id: 'user-1', username: 'alice-renamed', enabled: true },
    mode: 'edit',
    password: '',
  });

  const restored = takeUserDetailWorkspaceSession(input);
  assert.equal(restored?.draft.username, 'alice-renamed');
  assert.equal(restored?.mode, 'edit');
  assert.equal(takeUserDetailWorkspaceSession(input), undefined);
});

it('user detail workspace sends a hand-off to a mounted recipient', async () => {
  const input = { recordId: 'user-mounted' } as const;
  let received = '';
  const dispose = registerUserDetailWorkspaceHandoffRecipient(input, (session) => {
    received = session.draft.username ?? '';
    return true;
  });
  assert.equal(
    await handOffUserDetailWorkspaceSession(input, {
      selectedUser: { id: input.recordId, username: 'alice', enabled: true },
      draft: { id: input.recordId, username: 'alice-new', enabled: true },
      mode: 'edit',
      password: '',
    }),
    'accepted',
  );
  assert.equal(received, 'alice-new');
  dispose();
});
