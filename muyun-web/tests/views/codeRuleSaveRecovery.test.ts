import { expect, it } from 'vitest';
import { codeRuleSaveRecovery } from '@/views/codeRuleSaveRecovery';
it('isolates query references and rejects payload or authority restored from storage', () => {
  const values = new Map<string, string>();
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
    removeItem: (key: string) => {
      values.delete(key);
    },
  };
  const recovery = codeRuleSaveRecovery('user-a/tenant-a', storage);
  const reference = {
    requestId: 'request-1234567890',
    moduleAlias: 'demo.order',
    entityAlias: 'order',
    fieldName: 'documentNo',
  };
  recovery.save(reference);
  expect(codeRuleSaveRecovery('user-b/tenant-a', storage).restore()).toBeUndefined();
  expect(codeRuleSaveRecovery('user-a/tenant-b', storage).restore()).toBeUndefined();
  expect(() => recovery.save({ ...reference, requestId: 'request-0987654321' })).toThrow();
  recovery.clear('request-0987654321');
  expect(recovery.restore()).toEqual(reference);
  const key = [...values.keys()][0]!;
  values.set(key, JSON.stringify({ ...reference, authorized: true }));
  expect(() => recovery.restore()).toThrow();
  values.set(key, JSON.stringify({ ...reference, draft: { title: 'private' } }));
  expect(() => recovery.restore()).toThrow();
  values.set(key, JSON.stringify(reference));
  recovery.clear(reference.requestId);
  expect(recovery.restore()).toBeUndefined();
});
