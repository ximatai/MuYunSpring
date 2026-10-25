import { expect, it } from 'vitest';
import { recordMutationPayload } from '@/dynamic-page-runtime/recordMutationPayload';

it('omits computed and other constant read-only values without losing mutation identity', () => {
  const record = { id: 'row-1', version: 3, quantity: 2, amount: '11.00', guarded: 'manual' };
  expect(
    recordMutationPayload(record, [
      { fieldRef: { fieldName: 'id' }, readOnly: { constant: true } },
      { fieldRef: { fieldName: 'version' }, readOnly: { constant: true } },
      { fieldRef: { fieldName: 'amount' }, readOnly: { constant: true } },
      { fieldRef: { fieldName: 'guarded' }, readOnly: { constant: false } },
    ]),
  ).toEqual({ id: 'row-1', version: 3, quantity: 2, guarded: 'manual' });
  expect(record.amount).toBe('11.00');
});

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

it('excludes record display resources while retaining original configuration values and nested business JSON', () => {
  const business = { $texts: { literal: '业务值' } };
  expect(
    recordMutationPayload({ id: 'one', version: 2, title: '原文', $texts: { title: {} }, business }),
  ).toEqual({ id: 'one', version: 2, title: '原文', business });
});

it('keeps permission read facts out of ordinary saves even when the page does not compose them', () => {
  const business = { authUserId: 'literal JSON value' };
  const record = {
    id: 'one',
    version: 2,
    title: '原文',
    customerId: 'customer-1',
    business,
    authUserId: 'owner',
    authAssigneeIds: 'assignee',
    authMemberIds: 'member',
    authOrganizationId: 'organization',
    authDepartmentId: 'department',
    authModuleAlias: 'sales.order',
  };
  expect(recordMutationPayload(record)).toEqual({
    id: 'one',
    version: 2,
    title: '原文',
    customerId: 'customer-1',
    business,
  });
  expect(record.authUserId).toBe('owner');
  expect(record.business).toBe(business);
});

it('omits uncomposed approval and storage facts without erasing same-named fields outside that capability', () => {
  const record = {
    id: 'one',
    version: 2,
    title: '原文',
    enabled: true,
    sortOrder: 10,
    tenantId: 'tenant',
    deleted: false,
    deletedAt: null,
    deletedBy: null,
    createdBy: 'creator',
    createdAt: '2026-10-15T00:00:00Z',
    updatedBy: 'updater',
    updatedAt: '2026-10-15T00:00:00Z',
    approvalInstanceId: 'instance',
    approvalStatus: 'approved',
    approvalSubmittedBy: 'submitter',
    approvalSubmittedAt: '2026-10-15T00:00:00Z',
    approvalCompletedAt: '2026-10-15T01:00:00Z',
  };
  expect(recordMutationPayload(record, [], ['APPROVAL'])).toEqual({
    id: 'one',
    version: 2,
    title: '原文',
    enabled: true,
    sortOrder: 10,
  });
  expect(recordMutationPayload(record).approvalStatus).toBe('approved');
  expect(record.approvalInstanceId).toBe('instance');
});

it('never submits disposable business reading metadata as an ordinary business field', () => {
  const record = {
    id: 'one',
    version: 2,
    title: '原文',
    $contentTexts: { title: { originalText: '原文', readings: { en: 'Reading' } } },
    businessJson: { $contentTexts: 'business fact' },
  };
  expect(recordMutationPayload(record)).toEqual({
    id: 'one',
    version: 2,
    title: '原文',
    businessJson: { $contentTexts: 'business fact' },
  });
  expect(record.title).toBe('原文');
});

it('submits the scheduled action execution tenant while still stripping system tenant ownership', () => {
  expect(
    recordMutationPayload({ tenantId: 'untrusted', executionTenantId: 'demo', identityMode: 'SYSTEM' }, [
      { fieldRef: { fieldName: 'executionTenantId' }, readOnly: { constant: false } },
    ]),
  ).toEqual({ executionTenantId: 'demo', identityMode: 'SYSTEM' });
});
