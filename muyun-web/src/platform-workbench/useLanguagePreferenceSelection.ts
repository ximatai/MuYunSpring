import { onBeforeUnmount, ref, watch } from 'vue';
import { createLanguagePreferenceController, type LanguageContext } from '@muyun/web-core';

/** Preferences restore with the owner, even when its selection controls are still collapsed. */
export function useLanguagePreferenceSelection(
  context: Pick<
    LanguageContext,
    'languageTag' | 'requestedLanguageTag' | 'selectionRevision' | 'setLanguage'
  >,
  identityScope: () => string | undefined,
  preferenceKey?: string,
) {
  const preferences = createLanguagePreferenceController(context, undefined, preferenceKey);
  const saving = ref(false);
  const restoreFailed = ref(false);
  watch(
    identityScope,
    (scope) => {
      restoreFailed.value = false;
      void preferences.restore(scope).catch(() => {
        restoreFailed.value = true;
      });
    },
    { immediate: true },
  );
  onBeforeUnmount(() => preferences.dispose());
  async function save(tag?: string) {
    saving.value = true;
    try {
      await preferences.save(tag);
    } finally {
      saving.value = false;
    }
  }
  return { saving, restoreFailed, save };
}
