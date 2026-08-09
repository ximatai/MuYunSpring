import { assert, it } from 'vitest';
import { hasUserDetailUnsavedChanges } from '@/views/userDetailStateModel.ts';

it('user detail promotion dirty state ignores password-only client fields', () => {
  assert.equal(
    hasUserDetailUnsavedChanges(
      { id: 'user-1', username: 'alice', password: undefined },
      { id: 'user-1', username: 'alice' },
    ),
    false,
  );
  assert.equal(
    hasUserDetailUnsavedChanges({ id: 'user-1', username: 'alice-2' }, { id: 'user-1', username: 'alice' }),
    true,
  );
});
