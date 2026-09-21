import { expect, it, vi } from 'vitest';
import type { HttpClient } from '@/web-core';
import { createBusinessLogRetentionClient } from '@/views/businessLogRetentionClient';

it('loads and updates runtime-managed event-type retention policies', async () => {
  const request = vi
    .fn()
    .mockResolvedValueOnce([
      { eventType: 'ACTION', automaticCleanupEnabled: false, retentionDays: 180, version: 3 },
    ])
    .mockResolvedValueOnce({
      eventType: 'ACTION',
      automaticCleanupEnabled: true,
      retentionDays: 45,
      version: 4,
    });
  const client = createBusinessLogRetentionClient({ request } as HttpClient);

  const policies = await client.policies();
  const updated = await client.update({ ...policies[0]!, automaticCleanupEnabled: true, retentionDays: 45 });

  expect(request).toHaveBeenNthCalledWith(1, { path: '/platform.business_log_retention/policies' });
  expect(request).toHaveBeenNthCalledWith(2, {
    method: 'POST',
    path: '/platform.business_log_retention/policies/ACTION',
    body: { automaticCleanupEnabled: true, retentionDays: 45, version: 3 },
  });
  expect(updated.retentionDays).toBe(45);
  expect(updated.version).toBe(4);
});

it('uses a distinct destructive endpoint for immediate bounded cleanup', async () => {
  const request = vi.fn(async () => ({
    eventType: 'REQUEST_ERROR',
    retentionDays: 30,
    result: { deletedCount: 12, executedBatches: 1, status: 'COMPLETE' },
  }));
  const client = createBusinessLogRetentionClient({ request } as HttpClient);

  const result = await client.purge('REQUEST_ERROR');

  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/platform.business_log_retention/policies/REQUEST_ERROR/purge',
  });
  expect(result.result.deletedCount).toBe(12);
});
