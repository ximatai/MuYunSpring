import { uiThemeSkinById, type UiThemeSkinId } from '@muyun/vue-ui-antdv';
import type { UserPreferenceStore } from '@muyun/web-core';

export const themeSkinPreferenceKey = 'workbench.theme-skin';
export type ThemeSkinPreferenceFailure = 'save-failed';

export async function restoreThemeSkinPreference(store: UserPreferenceStore, fallback: UiThemeSkinId) {
  const restored = await store.restore(themeSkinPreferenceKey, fallback, { persistence: 'backend' });
  const skinId = uiThemeSkinById(restored).id;
  await store.set(themeSkinPreferenceKey, skinId, { persistence: 'local' });
  return skinId;
}

export async function saveThemeSkinPreference(
  store: UserPreferenceStore,
  skinId: UiThemeSkinId,
  previousSkinId: UiThemeSkinId,
) {
  await store.set(themeSkinPreferenceKey, skinId, { persistence: 'local' });
  try {
    await store.set(themeSkinPreferenceKey, skinId, { persistence: 'backend' });
    return { skinId };
  } catch {
    await store.set(themeSkinPreferenceKey, previousSkinId, { persistence: 'local' });
    return { skinId: previousSkinId, failure: 'save-failed' as const };
  }
}
