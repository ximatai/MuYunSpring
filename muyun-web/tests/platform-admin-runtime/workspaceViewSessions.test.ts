import { assert, it } from 'vitest';
import { reactive, type Component } from 'vue';
import {
  discardWorkspaceViewSession,
  getOrCreateWorkspaceViewSession,
  handOffWorkspaceViewSession,
  registerWorkspaceViewHandoffRecipient,
  takeWorkspaceViewSession,
  workspaceViewInstanceKey,
} from '@/platform-admin-runtime/workspaceViewSessions.ts';
import { defineWorkspaceView } from '@/platform-admin-runtime/workspaceViewContract.ts';

const view = defineWorkspaceView({
  type: 'iam.user.detail.session-test',
  route: '/iam/users',
  moduleAlias: 'iam.user',
  component: {} as Component,
  presentations: ['drawer', 'tab'],
  titleOf: () => '用户详情',
  parse: () => undefined,
});

it('workspace view session identity is stable across input key order', () => {
  const left = workspaceViewInstanceKey(view, { recordId: 'user-1', mode: 'edit' });
  const right = workspaceViewInstanceKey(view, { mode: 'edit', recordId: 'user-1' });
  assert.equal(left, right);
});

it('workspace view session is shared by matching view identity and can be discarded', () => {
  const input = { recordId: 'user-2', mode: 'view' };
  const session = getOrCreateWorkspaceViewSession(view, input, () => ({ draft: 'alice' }));
  assert.equal(
    getOrCreateWorkspaceViewSession(view, input, () => ({ draft: 'bob' })),
    session,
  );
  discardWorkspaceViewSession(view, input);
  assert.deepEqual(
    getOrCreateWorkspaceViewSession(view, input, () => ({ draft: 'bob' })),
    { draft: 'bob' },
  );
});

it('workspace hand-off delivers to a mounted target and leaves no pending session', async () => {
  const input = { recordId: 'user-3', mode: 'view' };
  let received: { draft: string } | undefined;
  const dispose = registerWorkspaceViewHandoffRecipient(view, input, (session) => {
    received = session;
    return true;
  });

  assert.equal(await handOffWorkspaceViewSession(view, input, { draft: 'alice' }), 'accepted');
  assert.deepEqual(received, { draft: 'alice' });
  assert.equal(takeWorkspaceViewSession(view, input), undefined);
  dispose();
});

it('workspace hand-off retains a pending session for a target that has not mounted', async () => {
  const input = { recordId: 'user-4', mode: 'view' };

  assert.equal(await handOffWorkspaceViewSession(view, input, { draft: 'alice' }), 'accepted');
  assert.deepEqual(takeWorkspaceViewSession(view, input), { draft: 'alice' });
});

it('workspace hand-off snapshots Vue reactive drafts before opening the target', async () => {
  const input = { recordId: 'user-reactive', mode: 'view' };
  const draft = reactive({ username: 'alice' });

  assert.equal(await handOffWorkspaceViewSession(view, input, { draft }), 'accepted');
  draft.username = 'bob';
  assert.deepEqual(takeWorkspaceViewSession(view, input), { draft: { username: 'alice' } });
});

it('workspace hand-off reports a mounted target conflict without replacing its state', async () => {
  const input = { recordId: 'user-5', mode: 'view' };
  const dispose = registerWorkspaceViewHandoffRecipient(view, input, () => false);

  assert.equal(await handOffWorkspaceViewSession(view, input, { draft: 'alice' }), 'rejected');
  assert.equal(takeWorkspaceViewSession(view, input), undefined);
  dispose();
});
