import { expect, it } from 'vitest';
import { useInputValidationActionStatus } from '@/dynamic-page-runtime/inputValidationActionStatus';

it('invalidates an earlier success as soon as the same draft is retried', () => {
  const status = useInputValidationActionStatus();
  const first = status.begin('test', { kind: 'draft', fingerprint: 'draft-v1' });
  status.succeed(first);
  expect(status.isDraftValidated('test', 'draft-v1')).toBe(true);

  status.begin('test', { kind: 'draft', fingerprint: 'draft-v1' });

  expect(status.isDraftValidated('test', 'draft-v1')).toBe(false);
});

it('does not validate a record version that replaced the tested snapshot', () => {
  const status = useInputValidationActionStatus();
  const attempt = status.begin('test', {
    kind: 'record',
    recordId: 'record-1',
    fingerprint: 'record-v1',
  });

  status.succeed(attempt, 'record-v2');

  expect(status.isRecordValidated('test', 'record-1', 'record-v2')).toBe(false);
});

it('retains a successful record validation only for the tested snapshot', () => {
  const status = useInputValidationActionStatus();
  const attempt = status.begin('test', {
    kind: 'record',
    recordId: 'record-1',
    fingerprint: 'record-v1',
  });
  status.succeed(attempt, 'record-v1');

  expect(status.isRecordValidated('test', 'record-1', 'record-v1')).toBe(true);
  expect(status.isRecordValidated('test', 'record-1', 'record-v2')).toBe(false);

  status.invalidateRecord('record-1');
  expect(status.isRecordValidated('test', 'record-1', 'record-v1')).toBe(false);
});
