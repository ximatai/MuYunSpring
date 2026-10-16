import { computed, ref } from 'vue';
import type { LanguageContext } from './language';

export const businessContentLanguages = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'en', label: 'English' },
  { value: 'de', label: 'Deutsch' },
] as const;
export type BusinessContentLanguageContext = Pick<
  LanguageContext,
  'languageTag' | 'requestedLanguageTag' | 'selectionRevision' | 'setLanguage'
>;
const contexts = new WeakMap<LanguageContext, BusinessContentLanguageContext>();

/** Reading intent belongs to this interface scope; it never changes the interface or original language. */
export function businessContentLanguageContext(language: LanguageContext): BusinessContentLanguageContext {
  const existing = contexts.get(language);
  if (existing) return existing;
  const requested = ref<string>();
  const revision = ref(0);
  const context: BusinessContentLanguageContext = {
    languageTag: computed(() => requested.value ?? language.languageTag.value),
    requestedLanguageTag: computed(() => requested.value),
    selectionRevision: computed(() => revision.value),
    setLanguage(tag) {
      const next = businessContentLanguages.some((value) => value.value === tag) ? tag : undefined;
      requested.value = next;
      // A repeated explicit choice also invalidates a delayed preference restore.
      revision.value++;
    },
  };
  contexts.set(language, context);
  return context;
}
