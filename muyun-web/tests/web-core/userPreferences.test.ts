import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { configureUserPreferenceBackend, userPreferences } from '@/web-core/userPreferences.ts';

const storage = new Map<string, string>();

beforeEach(() => {
  storage.clear();
  configureUserPreferenceBackend(undefined);
  vi.stubGlobal('window', {
    localStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    },
  });
});

afterEach(() => {
  configureUserPreferenceBackend(undefined);
  vi.unstubAllGlobals();
});

describe('userPreferences', () => {
  it('restores a preference through the platform facade', async () => {
    await userPreferences.set('workbench.expanded-menu-depth', 3);

    expect(userPreferences.get('workbench.expanded-menu-depth', 1)).toBe(3);
  });

  it('accepts an explicit local persistence route without requiring a backend bridge', async () => {
    await userPreferences.set('workbench.expanded-menu-depth', 2, { persistence: 'local' });

    await expect(
      userPreferences.restore('workbench.expanded-menu-depth', 1, { persistence: 'local' }),
    ).resolves.toBe(2);
  });

  it('uses the supplied fallback when no preference exists', () => {
    expect(userPreferences.get('workbench.expanded-menu-depth', 1)).toBe(1);
  });

  it('does not silently downgrade a backend-only preference', async () => {
    await expect(
      userPreferences.set('workbench.expanded-menu-depth', 2, { persistence: 'backend' }),
    ).rejects.toThrow('Backend user preference persistence is not configured');
  });

  it('routes a backend-only preference through the configured platform bridge', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    configureUserPreferenceBackend({ load: vi.fn(), save, remove: vi.fn() });

    await userPreferences.set('workbench.menu-display-depth', { depth: 2 }, { persistence: 'backend' });

    expect(save).toHaveBeenCalledWith('workbench.menu-display-depth', '{"depth":2}');
  });

  it('restores a backend-only preference through the configured platform bridge', async () => {
    const load = vi.fn().mockResolvedValue('{"depth":3}');
    configureUserPreferenceBackend({ load, save: vi.fn(), remove: vi.fn() });

    await expect(
      userPreferences.restore('workbench.menu-display-depth', { depth: 1 }, { persistence: 'backend' }),
    ).resolves.toEqual({ depth: 3 });
    expect(load).toHaveBeenCalledWith('workbench.menu-display-depth');
  });

  it('keeps backend-only preferences out of the device-local namespace', async () => {
    storage.set('muyun.preference.workbench.menu-display-depth', '1');
    configureUserPreferenceBackend({ load: vi.fn(), save: vi.fn(), remove: vi.fn() });

    await userPreferences.set('workbench.menu-display-depth', 3, { persistence: 'backend' });

    expect(userPreferences.get('workbench.menu-display-depth', 0)).toBe(1);
  });

  it('removes a backend-only preference through the platform facade', async () => {
    storage.set('muyun.preference.workbench.menu-display-depth', '3');
    const remove = vi.fn().mockResolvedValue(undefined);
    configureUserPreferenceBackend({ load: vi.fn(), save: vi.fn(), remove });

    await userPreferences.remove('workbench.menu-display-depth', { persistence: 'backend' });

    expect(remove).toHaveBeenCalledWith('workbench.menu-display-depth');
    expect(userPreferences.get('workbench.menu-display-depth', 1)).toBe(3);
  });

  it('does not reuse backend data when the next account has no stored preference', async () => {
    const load = vi.fn().mockResolvedValueOnce('{"depth":3}').mockResolvedValueOnce(undefined);
    configureUserPreferenceBackend({ load, save: vi.fn(), remove: vi.fn() });

    await expect(
      userPreferences.restore('workbench.menu-display-depth', { depth: 1 }, { persistence: 'backend' }),
    ).resolves.toEqual({ depth: 3 });
    await expect(
      userPreferences.restore('workbench.menu-display-depth', { depth: 1 }, { persistence: 'backend' }),
    ).resolves.toEqual({ depth: 1 });

    expect(storage.has('muyun.preference.workbench.menu-display-depth')).toBe(false);
  });
});
