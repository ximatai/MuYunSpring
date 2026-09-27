import type {
  RecordFormFieldDescriptor,
  RecordFormFieldPickerConfig,
  RecordFormFieldValue,
  RecordFormRecord,
  ReferencePickerCandidate,
} from '@muyun/platform-components';

/** Controlled access to an existing editor, including a row of an aggregate draft. */
export interface RecordFormDraftAccess {
  readonly editorMode: 'view' | 'create' | 'edit';
  readonly editingRecord?: RecordFormRecord;
  readonly selectedRecord?: RecordFormRecord;
  readonly formFields: Map<string, RecordFormFieldDescriptor>;
  readonly referencePickerConfigs: Record<string, RecordFormFieldPickerConfig>;
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
  ): void;
}
