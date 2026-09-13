import { expect, it, vi } from 'vitest';
import type { HttpClient } from '@/web-core';
import { createRoleScopeSelectionClient } from '@/platform-admin-runtime/role/roleScopeSelectionClient';

it('uses the role-owned descriptor and candidates facade without reading tenant or organization modules', async () => {
  const request = vi
    .fn()
    .mockResolvedValueOnce({
      directSelections: [{ selectionKey: 'platform', title: '平台角色' }],
      navigations: [{ navigationKey: 'role-scope-tenants', level: 'TENANT', title: '租户' }],
    })
    .mockResolvedValueOnce({
      records: [
        {
          id: 'tenant-1',
          selectionKey: 'tenant:tenant-1',
          title: '演示租户',
          subtitle: 'demo',
          expandable: true,
        },
        {
          id: 'organization-1',
          selectionKey: 'organization:organization-1',
          title: '总院',
          expandable: false,
        },
      ],
      pageNum: 1,
      pages: 2,
      totalKnown: true,
    });
  const client = createRoleScopeSelectionClient({ request } as HttpClient);

  await expect(client.descriptor()).resolves.toEqual({
    directSelections: [{ selectionKey: 'platform', label: '平台角色' }],
    navigations: [{ navigationKey: 'role-scope-tenants', level: 'TENANT', label: '租户' }],
  });
  await expect(
    client.candidates({
      navigationKey: 'tenant:tenant-1',
      level: 'ORGANIZATION',
      keyword: '演示',
      parentId: 'organization-root',
    }),
  ).resolves.toEqual({
    records: [
      {
        id: 'tenant-1',
        selectionKey: 'tenant:tenant-1',
        label: '演示租户',
        secondaryLabel: 'demo',
        expandable: true,
      },
      { id: 'organization-1', selectionKey: 'organization:organization-1', label: '总院', expandable: false },
    ],
    hasMore: true,
    nextPage: 2,
  });

  expect(request).toHaveBeenNthCalledWith(1, { path: '/iam.role/scope-selection/descriptor' });
  expect(request).toHaveBeenNthCalledWith(2, {
    method: 'POST',
    path: '/iam.role/scope-selection/candidates',
    body: {
      navigationKey: 'tenant:tenant-1',
      level: 'ORGANIZATION',
      keyword: '演示',
      parentId: 'organization-root',
      page: { pageNum: 1, pageSize: 100 },
    },
  });
});

it('accepts an empty descriptor without inferring a tenant navigation client-side', async () => {
  const client = createRoleScopeSelectionClient({ request: vi.fn().mockResolvedValue({}) } as HttpClient);

  await expect(client.descriptor()).resolves.toEqual({ directSelections: [], navigations: [] });
});
