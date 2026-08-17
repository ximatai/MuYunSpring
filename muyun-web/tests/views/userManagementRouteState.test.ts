import { assert, it } from 'vitest';
import { userManagementRouteStateOf } from '@/views/userManagementRouteState.ts';

const instanceKey = '2d81d62b-29ea-4bc0-a68e-4b6f69012511';

it('reads list and every supported user page address', () => {
  assert.deepEqual(userManagementRouteStateOf(undefined, {}), {});
  assert.deepEqual(userManagementRouteStateOf(undefined, { InstanceKey: instanceKey }), {});
  assert.deepEqual(userManagementRouteStateOf(undefined, { userAction: 'add', InstanceKey: instanceKey }), {
    action: 'add',
  });
  assert.deepEqual(userManagementRouteStateOf('user-1', { InstanceKey: instanceKey }), {
    action: 'view',
    userId: 'user-1',
  });
  assert.deepEqual(
    userManagementRouteStateOf('user-1', { InstanceKey: instanceKey, _muyunTitle: '旧标题' }),
    {
      action: 'view',
      userId: 'user-1',
    },
  );
  assert.deepEqual(userManagementRouteStateOf('user-1', { userAction: 'edit', InstanceKey: instanceKey }), {
    action: 'edit',
    userId: 'user-1',
  });
});

it('rejects old, incomplete and unsupported user page addresses before the page loads data', () => {
  assert.match(userManagementRouteStateOf(undefined, { recordId: 'user-1' }).error ?? '', /recordId/);
  assert.match(
    userManagementRouteStateOf(undefined, { userAction: 'add', InstanceKey: 'new-1' }).error ?? '',
    /InstanceKey/,
  );
  assert.match(userManagementRouteStateOf('user-1', {}).error ?? '', /InstanceKey/);
  assert.match(
    userManagementRouteStateOf('user-1', { userAction: 'delete', InstanceKey: instanceKey }).error ?? '',
    /不支持/,
  );
  assert.match(
    userManagementRouteStateOf(undefined, { userAction: 'edit', InstanceKey: instanceKey }).error ?? '',
    /列表地址/,
  );
  assert.match(userManagementRouteStateOf(undefined, { InstanceKey: 'invalid' }).error ?? '', /InstanceKey/);
  assert.match(
    userManagementRouteStateOf('user-1', { tenantId: 'tenant-1', InstanceKey: instanceKey }).error ?? '',
    /tenantId/,
  );
});
