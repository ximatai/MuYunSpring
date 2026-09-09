import { expect, it } from 'vitest';
import { recordMutationPayload } from '@/dynamic-page-runtime/recordMutationPayload';

it('keeps writable fields while removing list-only reference projections from a record mutation', () => {
  expect(
    recordMutationPayload({
      id: 'exam-1',
      title: '已编辑标题',
      supplierId: 'supplier-2',
      'supplierId.title': '供应商二号',
      'supplierId.organizationId.title': '华东组织',
    }),
  ).toEqual({ id: 'exam-1', title: '已编辑标题', supplierId: 'supplier-2' });
});
