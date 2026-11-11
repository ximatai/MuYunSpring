import { expect, it, vi } from 'vitest';
import { workflowAccountPage } from '@/platform-components/workflowAccountOptions';

it('loads accounts from the IAM selector without relying on a management navigator', async () => {
  const records = [{ id: 'user', username: 'teacher' }];
  const request = vi.fn(async () => ({ records }));
  expect(
    (
      await workflowAccountPage({ request } as never)({
        keyword: 'teacher',
        pageNum: 1,
        pageSize: 50,
        scope: { selections: [] },
      })
    ).records,
  ).toEqual(records);
  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/iam.user/selector/query',
    body: { keyword: 'teacher', enabledOnly: true, page: { pageNum: 1, pageSize: 50 } },
  });
});

it('forwards workflow account pagination through the same selector endpoint', async () => {
  const request = vi.fn().mockResolvedValue({ records: [{ id: 'page-two' }], total: 120 });
  expect(
    await workflowAccountPage({ request } as never)({
      keyword: 'teacher',
      pageNum: 2,
      pageSize: 50,
      scope: { selections: [] },
    }),
  ).toEqual({ records: [{ id: 'page-two' }], total: 120 });
  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/iam.user/selector/query',
    body: { keyword: 'teacher', enabledOnly: true, page: { pageNum: 2, pageSize: 50 } },
  });
});
