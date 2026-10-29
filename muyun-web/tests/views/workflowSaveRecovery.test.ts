import { expect, it } from 'vitest';
import { workflowSaveRecovery } from '@/views/workflowSaveRecovery';

it('retains only an exact query reference within its identity scope', () => {
  const data = new Map<string, string>();
  const storage = {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, value);
    },
    removeItem: (key: string) => {
      data.delete(key);
    },
  };
  const first = workflowSaveRecovery('user-a/tenant-a', storage);
  const reference = { requestId: 'request-1234567890', versionId: 'draft', expectedVersion: 3 };
  first.save(reference);
  expect(workflowSaveRecovery('user-b/tenant-a', storage).restore()).toBeUndefined();
  expect(workflowSaveRecovery('user-a/tenant-b', storage).restore()).toBeUndefined();
  expect(first.restore()).toEqual(reference);
  expect(() => first.save({ ...reference, requestId: 'request-0987654321' })).toThrow();
  first.clear('request-0987654321');
  expect(first.restore()).toEqual(reference);
  const key = [...data.keys()][0];
  data.set(key, JSON.stringify({ ...reference, draft: { nodes: [] } }));
  expect(() => first.restore()).toThrow();
  data.set(key, JSON.stringify(reference));
  first.clear(reference.requestId);
  expect(first.restore()).toBeUndefined();
});
