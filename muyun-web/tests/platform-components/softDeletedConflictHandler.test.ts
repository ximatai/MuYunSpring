import { assert, it } from 'vitest';
import { AppError, platformErrorCodes } from '@/web-core/index.ts';
import {
  extractSoftDeletedConflict,
  useSoftDeletedConflictHandler,
  createSoftDeletedConflictErrorHandler,
} from '@/platform-components/softDeletedConflictHandler.ts';
import type { UiConfirmOptions } from '@/vue-ui-antdv/index.ts';

it('extractSoftDeletedConflict extracts info from matching error', () => {
  const error = new AppError('Tenant alias is retained by a soft-deleted tenant', {
    code: platformErrorCodes.resourceSoftDeletedConflict,
    status: 409,
    details: {
      resourceModuleAlias: 'iam.tenant',
      resourceRecordId: 'demo',
      deletedAt: '2024-01-15T10:30:00Z',
      recoveryAvailable: true,
    },
  });

  const info = extractSoftDeletedConflict(error);

  assert.ok(info);
  assert.equal(info.moduleAlias, 'iam.tenant');
  assert.equal(info.recordId, 'demo');
  assert.equal(info.deletedAt, '2024-01-15T10:30:00Z');
  assert.equal(info.recoveryAvailable, true);
});

it('extractSoftDeletedConflict returns undefined for non-matching error code', () => {
  const error = new AppError('Some other conflict', {
    code: platformErrorCodes.resourceInUse,
    status: 409,
  });

  assert.equal(extractSoftDeletedConflict(error), undefined);
});

it('extractSoftDeletedConflict returns undefined for plain error', () => {
  assert.equal(extractSoftDeletedConflict(new Error('plain')), undefined);
  assert.equal(extractSoftDeletedConflict('string error'), undefined);
  assert.equal(extractSoftDeletedConflict(null), undefined);
});

it('extractSoftDeletedConflict handles missing optional fields', () => {
  const error = new AppError('Conflict', {
    code: platformErrorCodes.resourceSoftDeletedConflict,
    details: {
      resourceModuleAlias: 'iam.tenant',
      resourceRecordId: 'demo',
    },
  });

  const info = extractSoftDeletedConflict(error);

  assert.ok(info);
  assert.equal(info.deletedAt, undefined);
  assert.equal(info.recoveryAvailable, false);
});

it('useSoftDeletedConflictHandler returns false for non-matching error', async () => {
  const handler = useSoftDeletedConflictHandler({
    confirmAction: async () => true,
  });

  const handled = await handler.handle(new Error('other error'));

  assert.equal(handled, false);
});

it('useSoftDeletedConflictHandler shows modal and returns true for matching error', async () => {
  let confirmOptions: UiConfirmOptions | undefined;
  const handler = useSoftDeletedConflictHandler({
    resourceLabel: '租户',
    confirmAction: async (options) => {
      confirmOptions = options;
      return false;
    },
  });

  const error = new AppError('Conflict', {
    code: platformErrorCodes.resourceSoftDeletedConflict,
    details: {
      resourceModuleAlias: 'iam.tenant',
      resourceRecordId: 'demo',
      recoveryAvailable: true,
    },
  });

  const handled = await handler.handle(error);

  assert.equal(handled, true);
  assert.ok(confirmOptions);
  assert.equal(confirmOptions.title, '该租户已存在于回收站');
  assert.ok(confirmOptions.content?.includes('demo'));
  assert.equal(confirmOptions.okText, '去回收站恢复');
  assert.equal(confirmOptions.cancelText, '知道了');
});

it('useSoftDeletedConflictHandler calls onNavigateToRecycleBin when confirmed', async () => {
  let navigatedInfo: unknown;
  const handler = useSoftDeletedConflictHandler({
    resourceLabel: '租户',
    onNavigateToRecycleBin: (info) => {
      navigatedInfo = info;
    },
    confirmAction: async () => true,
  });

  const error = new AppError('Conflict', {
    code: platformErrorCodes.resourceSoftDeletedConflict,
    details: {
      resourceModuleAlias: 'iam.tenant',
      resourceRecordId: 'demo',
      recoveryAvailable: true,
    },
  });

  await handler.handle(error);

  assert.ok(navigatedInfo);
  assert.equal((navigatedInfo as { recordId: string }).recordId, 'demo');
});

it('useSoftDeletedConflictHandler does not navigate when cancelled', async () => {
  let navigated = false;
  const handler = useSoftDeletedConflictHandler({
    onNavigateToRecycleBin: () => {
      navigated = true;
    },
    confirmAction: async () => false,
  });

  const error = new AppError('Conflict', {
    code: platformErrorCodes.resourceSoftDeletedConflict,
    details: { resourceModuleAlias: 'iam.tenant', resourceRecordId: 'demo' },
  });

  await handler.handle(error);

  assert.equal(navigated, false);
});

it('createSoftDeletedConflictErrorHandler creates handler with correct code', () => {
  const handler = createSoftDeletedConflictErrorHandler({
    confirmAction: async () => true,
  });

  assert.equal(handler.code, platformErrorCodes.resourceSoftDeletedConflict);
  assert.equal(typeof handler.handle, 'function');
});

it('createSoftDeletedConflictErrorHandler handle returns true for matching error', () => {
  const handler = createSoftDeletedConflictErrorHandler({
    confirmAction: async () => true,
  });

  const error = new AppError('Conflict', {
    code: platformErrorCodes.resourceSoftDeletedConflict,
    details: { resourceModuleAlias: 'iam.tenant', resourceRecordId: 'demo' },
  });

  const result = handler.handle(error, {});

  assert.equal(result, true);
});
