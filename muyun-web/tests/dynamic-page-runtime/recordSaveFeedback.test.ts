import { expect, it } from 'vitest';
import { AppError, createLanguageContext } from '@/web-core';
import { isRecordSaveRejection, recordSaveFieldErrors } from '@/dynamic-page-runtime/recordSaveFeedback';

it('binds main-record field errors without marking same-named child or missing fields', () => {
  const error = new AppError('Rejected', {
    status: 422,
    targets: [
      { kind: 'FIELD', fieldName: 'name' },
      { kind: 'field', fieldName: 'amount', relationAlias: 'items', rowIndex: 0 },
      { kind: 'FIELD', fieldName: 'absent' },
    ],
  });
  expect(
    recordSaveFieldErrors(
      error,
      new Map([
        ['name', {}],
        ['amount', {}],
      ]),
      createLanguageContext(),
    ),
  ).toEqual({ name: 'Rejected' });
});

it('keeps authentication and unknown outcomes out of local save-rejection handling', () => {
  for (const status of [401, 403, 404, 500, 503, undefined])
    expect(isRecordSaveRejection(new AppError('failure', { status }))).toBe(false);
  for (const status of [400, 409, 422])
    expect(isRecordSaveRejection(new AppError('rejected', { status }))).toBe(true);
  const handled = new AppError('expired', { status: 400 });
  handled.globallyHandled = true;
  expect(isRecordSaveRejection(handled)).toBe(false);
});
