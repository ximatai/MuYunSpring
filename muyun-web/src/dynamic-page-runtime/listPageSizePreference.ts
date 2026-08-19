import type { UserPreferenceStore } from '@muyun/web-core';

const supportedPageSizes = new Set([10, 20, 50]);

export function listPageSizePreferenceKey(moduleAlias: string): string {
  return `module-page.list-page-size.${moduleAlias}`;
}

export function normalizeListPageSize(value: unknown, fallback = 20): number {
  return typeof value === 'number' && supportedPageSizes.has(value) ? value : fallback;
}

/**
 * A module-list preference is presentation-only. It deliberately remains
 * separate from the menu UI config and query template, which continue to own
 * authorized page shape and server-side criteria.
 */
export async function restoreListPageSize(
  store: UserPreferenceStore,
  moduleAlias: string,
  fallback = 20,
): Promise<number> {
  const key = listPageSizePreferenceKey(moduleAlias);
  const local = normalizeListPageSize(store.get<unknown>(key, fallback), fallback);
  const restored = normalizeListPageSize(
    await store.restore<unknown>(key, local, { persistence: 'backend' }),
    local,
  );
  await store.set(key, restored, { persistence: 'local' });
  return restored;
}

export async function saveListPageSize(
  store: UserPreferenceStore,
  moduleAlias: string,
  pageSize: number,
): Promise<void> {
  const normalized = normalizeListPageSize(pageSize);
  const key = listPageSizePreferenceKey(moduleAlias);
  await store.set(key, normalized, { persistence: 'local' });
  await store.set(key, normalized, { persistence: 'backend' });
}
