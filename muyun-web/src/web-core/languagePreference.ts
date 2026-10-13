import type { LanguageContext } from './language';
import { userPreferences, type UserPreferenceStore } from './userPreferences';

const LANGUAGE_PREFERENCE_KEY = 'interface.language';

/** Scope changes and user selection invalidate delayed restores; queued writes never cross accounts. */
export function createLanguagePreferenceController(
  context: LanguageContext,
  store: UserPreferenceStore = userPreferences,
) {
  let scope: string | undefined;
  let revision = 0;
  let disposed = false;
  let writes = Promise.resolve();
  const options = (identity?: string) => ({
    persistence: identity ? ('backend' as const) : ('local' as const),
  });
  return {
    async restore(identity?: string) {
      scope = identity;
      const currentRevision = ++revision;
      const fallback = store.get(LANGUAGE_PREFERENCE_KEY, context.catalog.value.defaultTag);
      context.setLanguage(typeof fallback === 'string' ? fallback : context.catalog.value.defaultTag);
      const selectionRevision = context.selectionRevision.value;
      let value: string;
      try {
        value = await store.restore(LANGUAGE_PREFERENCE_KEY, fallback, options(identity));
      } catch (failure) {
        if (!disposed && currentRevision === revision && scope === identity) throw failure;
        return;
      }
      if (
        !disposed &&
        currentRevision === revision &&
        scope === identity &&
        context.selectionRevision.value === selectionRevision
      )
        context.setLanguage(typeof value === 'string' ? value : fallback);
    },
    async save(tag: string) {
      const identity = scope;
      const currentRevision = ++revision;
      const previous = context.languageTag.value;
      context.setLanguage(tag);
      const selected = context.languageTag.value;
      writes = writes
        .catch(() => undefined)
        .then(async () => {
          if (disposed || identity !== scope) return;
          await store.set(LANGUAGE_PREFERENCE_KEY, selected, options(identity));
        });
      try {
        await writes;
      } catch (failure) {
        if (!disposed && currentRevision === revision && scope === identity) context.setLanguage(previous);
        if (!disposed && currentRevision === revision && scope === identity) throw failure;
      }
    },
    dispose() {
      disposed = true;
      revision++;
    },
  };
}
