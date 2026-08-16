import { describe, expect, it, vi } from 'vitest';
import type { UserPreferenceStore } from '@muyun/web-core';
import {
  detailSurfacePreferenceKey,
  normalizeDetailSurfacePreference,
  restoreDetailSurfacePreference,
  saveDetailSurfacePreference,
} from '@/dynamic-page-runtime/detailSurfacePreference';

describe('detail surface preference', () => {
  it('uses a stable module-scoped key and rejects unknown persisted values', () => {
    expect(detailSurfacePreferenceKey('mr.device')).toBe('module-page.detail-surface.mr.device');
    expect(normalizeDetailSurfacePreference('drawer')).toBe('drawer');
    expect(normalizeDetailSurfacePreference('pinned')).toBe('pinned');
    expect(normalizeDetailSurfacePreference('auto')).toBeUndefined();
  });

  it('restores backend state over local fallback and mirrors it locally', async () => {
    const store = {
      get: vi.fn(() => 'drawer'),
      restore: vi.fn(async () => 'pinned'),
      set: vi.fn(async () => undefined),
      remove: vi.fn(async () => undefined),
    };

    await expect(
      restoreDetailSurfacePreference(store as unknown as UserPreferenceStore, 'mr.device'),
    ).resolves.toBe('pinned');
    expect(store.restore).toHaveBeenCalledWith('module-page.detail-surface.mr.device', 'drawer', {
      persistence: 'backend',
    });
    expect(store.set).toHaveBeenCalledWith('module-page.detail-surface.mr.device', 'pinned', {
      persistence: 'local',
    });
  });

  it('writes locally before persisting the account-scoped choice', async () => {
    const store = {
      get: vi.fn(),
      restore: vi.fn(),
      set: vi.fn(async () => undefined),
      remove: vi.fn(),
    };

    await saveDetailSurfacePreference(store as unknown as UserPreferenceStore, 'mr.device', 'drawer');
    expect(store.set.mock.calls).toEqual([
      ['module-page.detail-surface.mr.device', 'drawer', { persistence: 'local' }],
      ['module-page.detail-surface.mr.device', 'drawer', { persistence: 'backend' }],
    ]);
  });
});
