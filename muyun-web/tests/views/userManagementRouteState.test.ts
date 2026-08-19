import { assert, it } from 'vitest';
import { userManagementRouteStateOf } from '@/views/userManagementRouteState.ts';

const instanceKey = '2d81d62b-29ea-4bc0-a68e-4b6f69012511';

it('reads list and every supported user page address', () => {
  assert.deepEqual(userManagementRouteStateOf('/iam/users', undefined, {}), {});
  assert.deepEqual(userManagementRouteStateOf('/iam/users', undefined, { InstanceKey: instanceKey }), {});
  assert.deepEqual(
    userManagementRouteStateOf('/iam/users/form', undefined, { action: 'add', InstanceKey: instanceKey }),
    {
      action: 'add',
    },
  );
  assert.deepEqual(
    userManagementRouteStateOf('/iam/users/form/:userId', 'user-1', {
      action: 'view',
      InstanceKey: instanceKey,
    }),
    {
      action: 'view',
      userId: 'user-1',
    },
  );
  assert.deepEqual(
    userManagementRouteStateOf('/iam/users/form/:userId', 'user-1', {
      action: 'view',
      InstanceKey: instanceKey,
      _muyunTitle: '旧标题',
    }),
    {
      action: 'view',
      userId: 'user-1',
    },
  );
  assert.deepEqual(
    userManagementRouteStateOf('/iam/users/form/:userId', 'user-1', {
      action: 'edit',
      InstanceKey: instanceKey,
    }),
    {
      action: 'edit',
      userId: 'user-1',
    },
  );
});

it('rejects old, incomplete and unsupported user page addresses before the page loads data', () => {
  assert.match(
    userManagementRouteStateOf('/iam/users', undefined, { recordId: 'user-1' }).error ?? '',
    /recordId/,
  );
  assert.match(
    userManagementRouteStateOf('/iam/users/form', undefined, { action: 'add', InstanceKey: 'new-1' }).error ??
      '',
    /InstanceKey/,
  );
  assert.match(userManagementRouteStateOf('/iam/users/form/:userId', 'user-1', {}).error ?? '', /action/);
  assert.match(
    userManagementRouteStateOf('/iam/users/form/:userId', 'user-1', {
      action: 'delete',
      InstanceKey: instanceKey,
    }).error ?? '',
    /action/,
  );
  assert.match(
    userManagementRouteStateOf('/iam/users/form', undefined, { action: 'edit', InstanceKey: instanceKey })
      .error ?? '',
    /action=add/,
  );
  assert.match(
    userManagementRouteStateOf('/iam/users', undefined, { InstanceKey: 'invalid' }).error ?? '',
    /InstanceKey/,
  );
  assert.match(
    userManagementRouteStateOf('/iam/users/form/:userId', 'user-1', {
      action: 'view',
      tenantId: 'tenant-1',
      InstanceKey: instanceKey,
    }).error ?? '',
    /tenantId/,
  );
  assert.match(
    userManagementRouteStateOf('/iam/users', undefined, { action: 'add', InstanceKey: instanceKey }).error ??
      '',
    /列表地址/,
  );
  assert.match(
    userManagementRouteStateOf('/iam/users/form/:userId', undefined, {
      action: 'view',
      InstanceKey: instanceKey,
    }).error ?? '',
    /用户编号/,
  );
  assert.match(
    userManagementRouteStateOf('/iam/users/form/:userId', 'user-1', {
      action: 'add',
      InstanceKey: instanceKey,
    }).error ?? '',
    /action=view 或 action=edit/,
  );
});
