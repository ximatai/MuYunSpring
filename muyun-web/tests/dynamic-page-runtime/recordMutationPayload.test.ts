import { expect, it } from 'vitest';
import { recordMutationPayload } from '@/dynamic-page-runtime/recordMutationPayload';

it('removes only declared read outputs and keeps selected IDs and explicitly writable patches', () => {
  const record = {
    id: 'order-1',
    customerId: 'customer-2',
    customerName: '显示名称',
    customerCode: '显式回填',
    title: '业务标题',
  };
  expect(
    recordMutationPayload(record, [
      {
        fieldRef: { fieldName: 'customerId' },
        reference: {
          targetModuleAlias: 'sales.customer',
          cardinality: 'ONE',
          titleField: 'customerName',
          displayProjections: [{ targetField: 'title', outputField: 'customerName' }],
          selectionProjections: [{ path: ['code'] }],
        },
      },
    ]),
  ).toEqual({ id: 'order-1', customerId: 'customer-2', customerCode: '显式回填', title: '业务标题' });
  expect(record.customerName).toBe('显示名称');
});

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
