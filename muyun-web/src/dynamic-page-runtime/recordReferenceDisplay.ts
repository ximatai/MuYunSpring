import type { RecordFormDraftAccess } from './recordFormDraftAccess';
import type { ReferencePickerCandidate } from '@muyun/platform-components';
import { referencePickerDisplayTitle } from '@muyun/platform-components';

/** Editor-owned display observations; never candidates, write inputs or permission. */
export function createRecordReferenceDisplay(
  form: Pick<RecordFormDraftAccess, 'editingRecord' | 'referencePickerConfigs'>,
  sessionKey: () => unknown,
) {
  const displays = new Map<string, { key: string; display: string }>();
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
            !candidate ||
            !candidate.title.trim() ||
            candidate.identifierFallback ||
            candidate.unavailable ||
            candidate.title === candidate.id,
        )
      ) {
        displays.delete(fieldName);
        return;
      }
      const display = selected.map((candidate) => referencePickerDisplayTitle(candidate!)).join('、');
      if (display.length > 2000) {
        displays.delete(fieldName);
        return;
      }
      displays.set(fieldName, { key: key(fieldName), display });
    },
    current(fieldName: string): string | undefined {
      const observed = displays.get(fieldName);
      if (observed?.key === key(fieldName)) return observed.display;
      displays.delete(fieldName);
      return undefined;
    },
  };
}
