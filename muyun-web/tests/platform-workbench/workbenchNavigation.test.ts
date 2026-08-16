import { assert, it } from 'vitest';
import { routeUrlWithOpenOptions } from '@/platform-workbench/workbenchNavigation';

it('generates a fresh UUID only for a new page instance', () => {
  const url = new URL(
    routeUrlWithOpenOptions('/iam/users?InstanceKey=old', {
      newInstance: true,
      query: { userAction: 'add' },
    }),
    'http://muyun.local',
  );
  assert.match(url.searchParams.get('InstanceKey') ?? '', /^[0-9a-f-]{36}$/i);
  assert.equal(url.searchParams.get('userAction'), 'add');
});

it('removes a previous page marker by default and creates one only when requested', () => {
  assert.equal(
    routeUrlWithOpenOptions('/iam/users?InstanceKey=old&userAction=view'),
    '/iam/users?userAction=view',
  );

  assert.ok(
    new URL(
      routeUrlWithOpenOptions('/iam/users', { newInstance: true }),
      'http://muyun.local',
    ).searchParams.get('InstanceKey'),
  );
});
