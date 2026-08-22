import { assert, it } from 'vitest';
import { userManagementRouteStateOf } from '@/views/userManagementRouteState.ts';

it('reads canonical user management addresses without workbench instance state', () => {
  assert.deepEqual(userManagementRouteStateOf('/iam/user', undefined, {}), {});
  assert.deepEqual(userManagementRouteStateOf('/iam/user/form', undefined, { action: 'add' }), {
    action: 'add',
  });
  assert.deepEqual(userManagementRouteStateOf('/iam/user/form/:userId', 'user-1', { action: 'view' }), {
    action: 'view',
    userId: 'user-1',
  });
  assert.deepEqual(userManagementRouteStateOf('/iam/user/form/:userId', 'user-1', { action: 'edit' }), {
    action: 'edit',
    userId: 'user-1',
  });
});

it('rejects incomplete and unsupported user page addresses before the page loads data', () => {
  assert.match(
    userManagementRouteStateOf('/iam/user', undefined, { recordId: 'user-1' }).error ?? '',
    /recordId/,
  );
  assert.match(userManagementRouteStateOf('/iam/user/form', undefined, {}).error ?? '', /action=add/);
  assert.match(
    userManagementRouteStateOf('/iam/user/form/:userId', 'user-1', { action: 'delete' }).error ?? '',
    /action=view 或 action=edit/,
  );
  assert.match(
    userManagementRouteStateOf('/iam/user/form/:userId', undefined, { action: 'view' }).error ?? '',
    /用户编号/,
  );
});
