import { expect, it, vi } from 'vitest';
import type { HttpClient } from '@/web-core';
import { createBusinessLogClient } from '@/views/businessLogClient';

it('keeps the log identity projection nested when loading a detail', async () => {
  const request = vi.fn(async () => ({
    event: {
      eventId: 'event-1',
      eventType: 'ACTION',
      operatorId: 'user-1',
      operatorOrganizationId: 'organization-history',
      operatorIdentity: {
        employeeName: '张三',
        username: 'zhangsan',
        organizationId: 'organization-history',
        organizationName: '华东机构',
        departmentId: 'department-1',
        departmentName: '研发部',
      },
    },
  }));
  const client = createBusinessLogClient({ request } as HttpClient, 'activity');

  const event = await client.detail('event-1');

  expect(request).toHaveBeenCalledWith({ path: '/platform.business_activity_log/event-1' });
  expect(event.operatorIdentity).toEqual({
    employeeName: '张三',
    username: 'zhangsan',
    organizationId: 'organization-history',
    organizationName: '华东机构',
    departmentId: 'department-1',
    departmentName: '研发部',
  });
});

it('normalizes action attribution from both the stable projection and historical details shape', async () => {
  const request = vi.fn(async () => ({
    event: {
      eventId: 'event-1',
      eventType: 'ACTION',
      entityAlias: 'order',
      details: { recordId: 'order-1', mutationSource: 'ACTION' },
    },
  }));
  const client = createBusinessLogClient({ request } as HttpClient, 'activity');

  const event = await client.detail('event-1');

  expect(event.entityAlias).toBe('order');
  expect(event.recordId).toBe('order-1');
  expect(event.mutationSource).toBe('ACTION');
});

it('uses the safe error response summary and keeps the login account distinct from the operator', async () => {
  const request = vi
    .fn()
    .mockResolvedValueOnce({
      event: {
        eventId: 'error-1',
        eventType: 'REQUEST_ERROR',
        details: { responseSummary: { value: '订单保存失败' }, errorCode: 'ORDER_CONFLICT' },
      },
    })
    .mockResolvedValueOnce({
      event: {
        eventId: 'login-1',
        eventType: 'LOGIN',
        operatorId: 'user-123',
        details: { claimedAccount: 'submitted-account', confirmedAccount: 'canonical-account' },
      },
    });
  const errorClient = createBusinessLogClient({ request } as HttpClient, 'request-error');
  const loginClient = createBusinessLogClient({ request } as HttpClient, 'login');

  const error = await errorClient.detail('error-1');
  const login = await loginClient.detail('login-1');

  expect(error.summary).toBe('订单保存失败');
  expect(login.loginAccount).toBe('canonical-account');
  expect(login.loginAccount).not.toBe(login.operatorId);
});

it('loads and restores log operators through the source log module rather than iam.user', async () => {
  const request = vi.fn(async () => ({
    records: [
      {
        id: 'user-1',
        title: '张三 (zhangsan)',
        account: 'zhangsan',
        employeeName: '张三',
        organizationId: 'organization-east',
        organizationName: '华东机构',
        departmentId: 'department-rd',
        departmentName: '研发部',
      },
    ],
    selectedRecords: [{ id: 'user-2', title: 'lisi', account: 'lisi' }],
    total: 1,
  }));
  const client = createBusinessLogClient({ request } as HttpClient, 'request-error');

  const candidates = await client.operatorCandidates({
    keyword: 'zhang',
    pageNum: 2,
    pageSize: 20,
    selectedIds: ['user-2'],
  });

  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/platform.request_error_log/operator-candidates/query',
    body: { keyword: 'zhang', selectedIds: ['user-2'], page: { pageNum: 2, pageSize: 20 } },
  });
  expect(candidates).toEqual({
    records: [
      {
        id: 'user-1',
        title: '张三 (zhangsan)',
        account: 'zhangsan',
        employeeName: '张三',
        organizationId: 'organization-east',
        organizationName: '华东机构',
        departmentId: 'department-rd',
        departmentName: '研发部',
      },
    ],
    selectedRecords: [{ id: 'user-2', title: 'lisi', account: 'lisi' }],
    total: 1,
  });
});
