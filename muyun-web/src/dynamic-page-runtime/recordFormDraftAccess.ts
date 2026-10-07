import type {
  RecordFormFieldDescriptor,
  RecordFormFieldPickerConfig,
  RecordFormFieldValue,
  RecordFormRecord,
  ReferencePickerCandidate,
} from '@muyun/platform-components';
import { DraftUpdateRejectedError, OperationUsageError } from '@muyun/web-core';

/** Controlled access to an existing editor, including a row of an aggregate draft. */
export interface RecordFormDraftAccess {
  readonly editorMode: 'view' | 'create' | 'edit';
  readonly editingRecord?: RecordFormRecord;
  readonly selectedRecord?: RecordFormRecord;
  readonly formFields: Map<string, RecordFormFieldDescriptor>;
  readonly referencePickerConfigs: Record<string, RecordFormFieldPickerConfig>;
  /** Current source-resolved display, separate from mutation data. */
  referenceDisplay?(fieldName: string): string | undefined;
  contextRevision(): string;
  relations?(): unknown[];
  updateDraftFields(
    changes: Array<{ fieldName: string; value: RecordFormFieldValue }>,
    source?: 'user' | 'assistant',
  ): void;
  updateDraftReference(
    fieldName: string,
    candidate: ReferencePickerCandidate,
    source?: 'user' | 'assistant',
    changes?: Array<{ fieldName: string; value: RecordFormFieldValue }>,
    /** Pure check of the staged record, before publishing draft or display changes. */
    validate?: (record: RecordFormRecord) => void,
  ): void;
}

/** Called only by the standard editor while its complete update is still staged. */
export function validateStagedDraft(record: RecordFormRecord, validate?: (record: RecordFormRecord) => void) {
  try {
    validate?.(record);
  } catch (failure) {
    if (failure instanceof OperationUsageError) throw new DraftUpdateRejectedError(failure);
    throw failure;
  }
}

/** One standard draft update: reference mappings, then explicit ordinary values. */
export function referenceDraftChanges(
  fieldName: string,
  candidate: ReferencePickerCandidate,
  changes: Array<{ fieldName: string; value: RecordFormFieldValue }> = [],
) {
  if (changes.some((change) => change.fieldName === fieldName))
    throw new Error('Reference identity must come from the selected candidate');
  const values = new Map<string, RecordFormFieldValue>([
    [fieldName, candidate.id],
    ...Object.entries(candidate.affectPatch ?? {})
      .filter(([name]) => name !== fieldName)
      .map(([name, value]) => [name, value as RecordFormFieldValue] as const),
    ...changes.map(({ fieldName: name, value }) => [name, value] as const),
  ]);
  return [...values].map(([name, value]) => ({ fieldName: name, value }));
}
