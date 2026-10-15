import { toRaw } from 'vue';
import { platformLanguageContext } from '@muyun/web-core';
import type { RecordFormDraftAccess } from './recordFormDraftAccess';
import type { ReferencePickerCandidate } from '@muyun/platform-components';
import { referencePickerDisplayTitle } from '@muyun/platform-components';

/** Editor-owned display observations; never candidates, write inputs or permission. */
export function createRecordReferenceDisplay(
  form: Pick<RecordFormDraftAccess, 'editingRecord' | 'referencePickerConfigs' | 'language'>,
  sessionKey: () => unknown,
) {
  const language = form.language ?? platformLanguageContext;
  const displays = new Map<string, { key: string; candidates: readonly ReferencePickerCandidate[] }>();
  const format = (names: readonly string[]) => names.join(language.t('platform.list.separator', '、'));
  const key = (fieldName: string) =>
    JSON.stringify([
      sessionKey(),
      form.referencePickerConfigs[fieldName]?.provider?.identity,
      form.referencePickerConfigs[fieldName]?.reloadKey,
      form.editingRecord?.[fieldName],
    ]);
  return {
    clear(fieldNames: Iterable<string>) {
      for (const name of fieldNames) displays.delete(name);
    },
    observe(fieldName: string, candidates: readonly ReferencePickerCandidate[]) {
      const value = form.editingRecord?.[fieldName];
      const ids = (Array.isArray(value) ? value : value == null || value === '' ? [] : [value]).map(String);
      const selected = ids.map((id) => candidates.find((candidate) => candidate.id === id));
      if (
        !ids.length ||
        selected.some(
          (candidate) =>
            !candidate || !candidate.title.trim() || candidate.identifierFallback || candidate.unavailable,
        )
      ) {
        displays.delete(fieldName);
        return;
      }
      const names = selected.map((candidate) => referencePickerDisplayTitle(candidate!, language));
      if (format(names).length > 2000) {
        displays.delete(fieldName);
        return;
      }
      displays.set(fieldName, {
        key: key(fieldName),
        candidates: selected.map((candidate) => ({
          id: candidate!.id,
          title: candidate!.title,
          subtitle: candidate!.subtitle,
          titleText: candidate!.titleText && structuredClone(toRaw(candidate!.titleText)),
        })),
      });
    },
    current(fieldName: string): string | undefined {
      const observed = displays.get(fieldName);
      if (observed?.key === key(fieldName)) {
        const display = format(
          observed.candidates.map((candidate) => referencePickerDisplayTitle(candidate, language)),
        );
        return display.length <= 2000 ? display : undefined;
      }
      displays.delete(fieldName);
      return undefined;
    },
  };
}
