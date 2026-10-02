import { expect, it, vi } from 'vitest';
import {
  lookupOperationReceipt,
  pagePublicationDigest,
  parseOperationReceiptReference,
  restoreAssistantOperationReceipt,
} from '@/web-core';
import type { OperationReceiptReference } from '@/web-contracts';

const reference: OperationReceiptReference = {
  kind: 'record-save',
  moduleAlias: 'sales.order',
  requestId: 'original-request-123',
  tenantId: 'tenant-a',
  menuId: 'platform.menu.module.platform.application',
};
it('queries the original record request using current authorization without replaying writes', async () => {
  const request = vi.fn(async () => ({ committed: true, recordId: 'record' }));
  expect(await lookupOperationReceipt({ request } as never, reference)).toMatchObject({
    title: '原保存已确认',
  });
  expect(request).toHaveBeenCalledExactlyOnceWith({
    path: '/sales.order/save-receipts/original-request-123',
    headers: {
      'X-MuYun-Tenant-Id': 'tenant-a',
      'X-MuYun-Menu-Id': 'platform.menu.module.platform.application',
    },
  });
  expect(() => parseOperationReceiptReference({ ...reference, payload: { total: 1 } })).toThrow();
  expect(() => parseOperationReceiptReference({ ...reference, moduleAlias: '../outside' })).toThrow();
});
it('matches the full immutable publication content, including an archived revision', async () => {
  const revision = {
    id: 'rev',
    status: 'archived',
    templateAlias: 'management',
    templateVersion: 2,
    uiTreeJson: '{"nodes":[]}',
  };
  const pointer: OperationReceiptReference = {
    kind: 'page-publication',
    variantId: 'variant',
    revisionId: 'rev',
    contentDigest: await pagePublicationDigest(revision),
  };
  const request = vi.fn(async () => revision);
  expect(await lookupOperationReceipt({ request } as never, pointer)).toBeDefined();
  expect(request.mock.calls[0]).toEqual([
    { path: '/platform.presentation-variant/variant/revisions/view/rev' },
  ]);
  revision.templateVersion = 3;
  expect(await lookupOperationReceipt({ request } as never, pointer)).toBeUndefined();
  revision.templateVersion = 2;
  revision.status = 'draft';
  expect(await lookupOperationReceipt({ request } as never, pointer)).toBeUndefined();
});
it('recovered confirmations only query, coalesce clicks and ignore late results after scope change', async () => {
  let scope = true;
  let finish!: (value: { title: string; lines: string[] }) => void;
  const lookup = vi.fn(
    () =>
      new Promise<{ title: string; lines: string[] }>((resolve) => {
        finish = resolve;
      }),
  );
  const restored = restoreAssistantOperationReceipt(reference, lookup, () => scope);
  await restored.confirm();
  expect(lookup).not.toHaveBeenCalled();
  expect(restored.state).toBe('unknown');
  const first = restored.check();
  expect(restored.check()).toBe(first);
  await Promise.resolve();
  scope = false;
  finish({ title: 'saved', lines: [] });
  await first;
  expect(restored.state).toBe('unknown');
  expect(restored.result).toBeUndefined();
  await restored.check();
  expect(lookup).toHaveBeenCalledOnce();
  expect(restored.takeContinuation()).toBeUndefined();
});
it('keeps missing and denied receipts unknown, then can query again in the authorized scope', async () => {
  const lookup = vi
    .fn()
    .mockResolvedValueOnce(undefined)
    .mockRejectedValueOnce(new Error('403'))
    .mockResolvedValueOnce({ title: 'saved', lines: [] });
  const restored = restoreAssistantOperationReceipt(reference, lookup, () => true);
  await restored.check();
  expect(restored.state).toBe('unknown');
  await restored.check();
  expect(restored.state).toBe('unknown');
  await restored.check();
  expect(restored.state).toBe('succeeded');
});

it('queries the original child creation instead of guessing from a same-named relation', async () => {
  const request = vi.fn().mockResolvedValueOnce(null).mockResolvedValueOnce({ relationId: 'created-child' });
  const pointer: OperationReceiptReference = {
    kind: 'child-metadata',
    moduleAlias: 'sales.order',
    relationId: 'main',
    requestId: 'original-request-123',
  };
  expect(await lookupOperationReceipt({ request } as never, pointer)).toBeUndefined();
  expect(await lookupOperationReceipt({ request } as never, pointer)).toMatchObject({
    title: '原明细创建已确认',
  });
  expect(request).toHaveBeenLastCalledWith({
    path: '/platform.module/sales.order/metadata-relations/main/child-metadata-creations/original-request-123',
  });
});
