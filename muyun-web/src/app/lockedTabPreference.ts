import type { MenuTab } from '@muyun/web-contracts';
import type { UserPreferenceStore } from '@muyun/web-core';
import { normalizePersistedWorkbenchTabs } from './workbenchSessionTabs';

export const lockedTabPreferenceKey = 'workbench.locked-tabs';

export async function restoreLockedTabPreference(store: UserPreferenceStore): Promise<MenuTab[]> {
  const restored = await store.restore(lockedTabPreferenceKey, [], { persistence: 'backend' });
  return normalizeLockedTabs(restored);
}

export async function saveLockedTabPreference(store: UserPreferenceStore, tabs: MenuTab[]): Promise<void> {
  await store.set(lockedTabPreferenceKey, tabs, { persistence: 'backend' });
}

export function normalizeLockedTabs(value: unknown): MenuTab[] {
  return normalizePersistedWorkbenchTabs(value);
}
