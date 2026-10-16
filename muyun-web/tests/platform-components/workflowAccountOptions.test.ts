import { expect, it, vi } from 'vitest';
import { workflowAccountOptions } from '@/platform-components/workflowAccountOptions';

it('loads accounts from the IAM selector without relying on a management navigator', async () => {
  const records = [{ id: 'user', username: 'teacher' }];
  const request = vi.fn(async () => ({ records }));
  expect(await workflowAccountOptions({ request } as never)('teacher')).toEqual(records);
  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/iam.user/selector/query',
    body: { keyword: 'teacher', enabledOnly: true, page: { pageNum: 1, pageSize: 50 } },
  });
});
