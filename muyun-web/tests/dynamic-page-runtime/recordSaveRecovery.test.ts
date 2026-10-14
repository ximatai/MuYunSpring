import { createLanguageContext } from '@/web-core';
import { expect, it } from 'vitest';
import { createRecordSaveRecovery } from '@/dynamic-page-runtime/recordSaveRecovery';

function storage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
    removeItem: (key: string) => {
      values.delete(key);
    },
  };
}
const reference = {
  kind: 'record-save' as const,
  moduleAlias: 'crm.customer',
  requestId: 'original-request-123',
  tenantId: 'tenant-one',
};

it('restores only a query reference in the original identity and execution scope', () => {
  const store = storage();
  const original = createRecordSaveRecovery('user/tenant/menu/navigation', store);
  original.save(reference);
  expect(createRecordSaveRecovery('user/tenant/menu/navigation', store).restore()).toEqual(reference);
  expect(createRecordSaveRecovery('other/tenant/menu/navigation', store).restore()).toBeUndefined();
  expect(createRecordSaveRecovery('user/other/menu/navigation', store).restore()).toBeUndefined();
  expect(createRecordSaveRecovery('user/tenant/other/navigation', store).restore()).toBeUndefined();
});

it('does not overwrite an unresolved request or let a late cleanup remove another request', () => {
  const store = storage();
  const first = createRecordSaveRecovery('scope', store);
  const second = createRecordSaveRecovery('scope', store);
  first.save(reference);
  expect(() => second.save({ ...reference, requestId: 'another-request-123' })).toThrow('原保存结果尚未确定');
  first.clear(reference.requestId);
  second.save({ ...reference, requestId: 'another-request-123' });
  first.clear(reference.requestId);
  expect(second.restore()?.requestId).toBe('another-request-123');
});

it('rejects payloads and fails closed when query storage is unavailable or corrupt', () => {
  const store = storage();
  const recovery = createRecordSaveRecovery('scope', store);
  expect(() => recovery.save({ ...reference, draft: { title: 'private' } } as typeof reference)).toThrow();
  expect(recovery.restore()).toBeUndefined();
  expect(() =>
    createRecordSaveRecovery('scope', {
      ...store,
      setItem: () => {
        throw new Error('storage unavailable');
      },
    }).save(reference),
  ).toThrow('storage unavailable');
  recovery.save(reference);
  const corrupted = { ...store, getItem: () => 'not-json' };
  expect(() => createRecordSaveRecovery('scope', corrupted).restore()).toThrow();
  expect(recovery.restore()).toEqual(reference);
});

it('localizes duplicate-submit protection and retains the original query reference', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const recovery = createRecordSaveRecovery('scope', storage(), language);
  recovery.save(reference);
  const duplicate = () => recovery.save({ ...reference, requestId: 'another-request-123' });
  expect(duplicate).toThrow('The previous save result is unknown');
  language.setLanguage('zh-CN');
  expect(duplicate).toThrow('原保存结果尚未确定');
  expect(recovery.restore()).toEqual(reference);
});
