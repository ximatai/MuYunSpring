import { assert, it } from 'vitest';
import { routeUrlWithOpenOptions } from '@/platform-workbench/workbenchNavigation';

it('removes the internal page marker from a public address', () => {
  const url = new URL(
    routeUrlWithOpenOptions('/iam/users/form?action=add&InstanceKey=old', {
      newInstance: true,
      query: { action: 'add' },
    }),
    'http://muyun.local',
  );
  assert.equal(url.searchParams.get('InstanceKey'), null);
  assert.equal(url.searchParams.get('action'), 'add');
});

it('never writes a page marker to a public address', () => {
  assert.equal(
    routeUrlWithOpenOptions('/iam/users/form/user-1?action=view&InstanceKey=old'),
    '/iam/users/form/user-1?action=view',
  );

  assert.equal(routeUrlWithOpenOptions('/iam/users', { newInstance: true }), '/iam/users');
});
