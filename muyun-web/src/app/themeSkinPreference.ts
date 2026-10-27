import { uiThemeSkinById, type UiThemeSkinId } from '@muyun/vue-ui-antdv';
import type { UserPreferenceStore } from '@muyun/web-core';

export const themeSkinPreferenceKey = 'workbench.theme-skin';
export type ThemeSkinPreferenceFailure = 'save-failed';

export async function restoreThemeSkinPreference(
  store: UserPreferenceStore,
  fallback: UiThemeSkinId,
  isCurrent: () => boolean = () => true,
) {
  const restored = await store.restore(themeSkinPreferenceKey, fallback, { persistence: 'backend' });
  if (!isCurrent()) return fallback;
  const skinId = uiThemeSkinById(restored).id;
  await store.set(themeSkinPreferenceKey, skinId, { persistence: 'local' });
  return skinId;
}

export async function saveThemeSkinPreference(
  store: UserPreferenceStore,
  skinId: UiThemeSkinId,
  previousSkinId: UiThemeSkinId,
  isCurrent: () => boolean = () => true,
) {
  await store.set(themeSkinPreferenceKey, skinId, { persistence: 'local' });
  if (!isCurrent()) return { skinId: previousSkinId };
  try {
    await store.set(themeSkinPreferenceKey, skinId, { persistence: 'backend' });
    return { skinId };
  } catch {
    if (!isCurrent()) return { skinId: previousSkinId };
    await store.set(themeSkinPreferenceKey, previousSkinId, { persistence: 'local' });
    return { skinId: previousSkinId, failure: 'save-failed' as const };
  }
}
