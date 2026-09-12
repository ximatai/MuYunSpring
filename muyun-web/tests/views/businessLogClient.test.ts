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
