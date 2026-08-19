import { describe, expect, it, vi } from 'vitest';
import type { UserPreferenceStore } from '@muyun/web-core';
import {
  listPageSizePreferenceKey,
  normalizeListPageSize,
  restoreListPageSize,
  saveListPageSize,
} from '@/dynamic-page-runtime/listPageSizePreference';

describe('list page-size preference', () => {
  it('uses a stable module-scoped key and accepts only runner-supported page sizes', () => {
    expect(listPageSizePreferenceKey('mr.device')).toBe('module-page.list-page-size.mr.device');
    expect(normalizeListPageSize(10)).toBe(10);
    expect(normalizeListPageSize(20)).toBe(20);
    expect(normalizeListPageSize(50)).toBe(50);
    expect(normalizeListPageSize(100)).toBe(20);
    expect(normalizeListPageSize('50')).toBe(20);
  });

  it('restores account state over the local fallback and mirrors it locally', async () => {
    const store = {
      get: vi.fn(() => 10),
      restore: vi.fn(async () => 50),
      set: vi.fn(async () => undefined),
      remove: vi.fn(async () => undefined),
    };

    await expect(restoreListPageSize(store as unknown as UserPreferenceStore, 'mr.device')).resolves.toBe(50);
    expect(store.restore).toHaveBeenCalledWith('module-page.list-page-size.mr.device', 10, {
      persistence: 'backend',
    });
    expect(store.set).toHaveBeenCalledWith('module-page.list-page-size.mr.device', 50, {
      persistence: 'local',
    });
  });

  it('writes the normalized choice locally before account persistence', async () => {
    const store = {
      get: vi.fn(),
      restore: vi.fn(),
      set: vi.fn(async () => undefined),
      remove: vi.fn(),
    };

    await saveListPageSize(store as unknown as UserPreferenceStore, 'mr.device', 50);
    expect(store.set.mock.calls).toEqual([
      ['module-page.list-page-size.mr.device', 50, { persistence: 'local' }],
      ['module-page.list-page-size.mr.device', 50, { persistence: 'backend' }],
    ]);
  });
});
