import { expect, it, vi } from 'vitest';
import {
  createModuleMenuReceiptStore,
  type MenuReceiptPointerStorage,
} from '@/platform-admin-runtime/module-menu/moduleMenuReceiptStore';
import type { UserPreferenceStore } from '@/web-core';

function pointer(): MenuReceiptPointerStorage {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => {
      values.set(key, value);
    },
    removeItem: (key) => {
      values.delete(key);
    },
  };
}
function accountStore() {
  const values = new Map<string, unknown>();
  const preferences: UserPreferenceStore = {
    get: (_key, fallback) => fallback,
    restore: async <T>(key: string, fallback: T) => (values.get(key) ?? fallback) as T,
    set: vi.fn(async (key, value) => {
      values.set(key, value);
    }),
    remove: vi.fn(async (key) => {
      values.delete(key);
    }),
  };
  return { preferences, values };
}
const reference = () => ({
  kind: 'record-save' as const,
  moduleAlias: 'platform.menu',
  requestId: crypto.randomUUID(),
  pageContext: { scheme: 'a' },
});

it('keeps each original request across same-tab refresh without other browsers overwriting or clearing it', async () => {
  const { preferences, values } = accountStore();
  const tabA = pointer();
  const tabB = pointer();
  const a = createModuleMenuReceiptStore(
    'user:tenant-a:org-a',
    'club.registration',
    () => true,
    preferences,
    tabA,
  );
  const b = createModuleMenuReceiptStore(
    'user:tenant-a:org-a',
    'club.registration',
    () => true,
    preferences,
    tabB,
  );
  await a.restore();
  await b.restore();
  const original = reference();
  await a.save(original);
  await b.save(reference());
  await b.clear();
  expect(values.size).toBe(1);
  const reopened = createModuleMenuReceiptStore(
    'user:tenant-a:org-a',
    'club.registration',
    () => true,
    preferences,
    tabA,
  );
  expect(await reopened.restore()).toEqual(original);
  const set = vi.mocked(preferences.set);
  expect(set.mock.calls[0][0]).toMatch(/^[a-z][a-z0-9_]*(?:[.-][a-z][a-z0-9_]*)*$/);
  expect(set.mock.calls[0][2]).toEqual({ persistence: 'backend' });
});

it('does not recover another tenant or organization pointer', async () => {
  const { preferences } = accountStore();
  const tab = pointer();
  const a = createModuleMenuReceiptStore(
    'user:tenant-a:org-a',
    'club.registration',
    () => true,
    preferences,
    tab,
  );
  await a.save(reference());
  const b = createModuleMenuReceiptStore(
    'user:tenant-b:org-b',
    'club.registration',
    () => true,
    preferences,
    tab,
  );
  expect(await b.restore()).toBeUndefined();
});

it('does not access another account after identity changes', async () => {
  const restore = vi.fn();
  const store = createModuleMenuReceiptStore(
    'user-a',
    'club.registration',
    () => false,
    { restore } as unknown as UserPreferenceStore,
    pointer(),
  );
  await expect(store.restore()).rejects.toThrow('身份已变化');
  expect(restore).not.toHaveBeenCalled();
});

it('blocks restoration if a remembered reference is temporarily missing from account storage', async () => {
  const { preferences, values } = accountStore();
  const tab = pointer();
  const first = createModuleMenuReceiptStore('user-a', 'club.registration', () => true, preferences, tab);
  await first.save(reference());
  values.clear();
  const reopened = createModuleMenuReceiptStore('user-a', 'club.registration', () => true, preferences, tab);
  await expect(reopened.restore()).rejects.toThrow('查询引用暂不可读取');
});
