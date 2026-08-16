import type { UserPreferenceStore } from '@muyun/web-core';

export type DetailSurfacePreference = 'drawer' | 'pinned';

export function detailSurfacePreferenceKey(moduleAlias: string): string {
  return `module-page.detail-surface.${moduleAlias}`;
}

export function normalizeDetailSurfacePreference(value: unknown): DetailSurfacePreference | undefined {
  return value === 'drawer' || value === 'pinned' ? value : undefined;
}

/**
 * Restores an account-scoped choice while retaining the device-local value as
 * a resilient fallback when the preference service is temporarily unavailable.
 */
export async function restoreDetailSurfacePreference(
  store: UserPreferenceStore,
  moduleAlias: string,
): Promise<DetailSurfacePreference | undefined> {
  const key = detailSurfacePreferenceKey(moduleAlias);
  const local = normalizeDetailSurfacePreference(store.get<unknown>(key, undefined));
  const restored = normalizeDetailSurfacePreference(
    await store.restore<unknown>(key, local, { persistence: 'backend' }),
  );
  if (restored) {
    await store.set(key, restored, { persistence: 'local' });
  }
  return restored;
}

export async function saveDetailSurfacePreference(
  store: UserPreferenceStore,
  moduleAlias: string,
  preference: DetailSurfacePreference,
): Promise<void> {
  const key = detailSurfacePreferenceKey(moduleAlias);
  await store.set(key, preference, { persistence: 'local' });
  await store.set(key, preference, { persistence: 'backend' });
}
