import { computed, ref, type Ref } from 'vue';
import { webDataChangeTypes, type WebDataChange } from '@muyun/web-contracts';

export interface RecordEditorSessionOptions<TRecord, TMode extends string> {
  viewMode: TMode;
  createMode: TMode;
  editMode: TMode;
  emptyDraft: () => TRecord;
  copyRecord?: (record: TRecord) => TRecord;
  recordIdOf?: (record: TRecord) => string | undefined;
}

export interface RecordEditorSessionCreateOptions<TRecord, TMode extends string> {
  mode?: TMode;
  draft?: TRecord | (() => TRecord);
  selectedRecord?: TRecord;
  preserveSelection?: boolean;
}

export interface RecordExternalChangeOptions {
  moduleAlias: string;
  changeTypes?: string[];
}

export function createRecordEditorSessionState<TRecord, TMode extends string>(
  options: RecordEditorSessionOptions<TRecord, TMode>,
) {
  const selected = ref<TRecord>() as Ref<TRecord | undefined>;
  const draft = ref<TRecord>(options.emptyDraft()) as Ref<TRecord>;
  const mode = ref<TMode>(options.viewMode) as Ref<TMode>;
  const externalChangedRecordId = ref<string>();
  const copyRecord = options.copyRecord ?? ((record: TRecord) => ({ ...record }) as TRecord);
  const recordIdOf = options.recordIdOf ?? defaultRecordIdOf;
  const readonly = computed(() => mode.value === options.viewMode);
  const externallyChanged = computed(() => Boolean(externalChangedRecordId.value));

  function select(record: TRecord) {
    selected.value = record;
    draft.value = copyRecord(record);
    mode.value = options.viewMode;
    clearExternalChanged();
  }

  function replaceSelected(record: TRecord | undefined) {
    selected.value = record;
  }

  function clearSelection() {
    selected.value = undefined;
    draft.value = options.emptyDraft();
    clearExternalChanged();
  }

  function createDraft(draftOption: RecordEditorSessionCreateOptions<TRecord, TMode>['draft']) {
    return typeof draftOption === 'function' ? (draftOption as () => TRecord)() : draftOption;
  }

  function startCreate(createOptions: RecordEditorSessionCreateOptions<TRecord, TMode> = {}) {
    const nextMode = createOptions.mode ?? options.createMode;
    if (nextMode === options.viewMode || nextMode === options.editMode) {
      throw new Error('Record editor create mode cannot be view or edit mode');
    }
    if (createOptions.selectedRecord !== undefined) {
      selected.value = createOptions.selectedRecord;
    } else if (!createOptions.preserveSelection) {
      selected.value = undefined;
    }
    draft.value = createDraft(createOptions.draft) ?? options.emptyDraft();
    mode.value = nextMode;
    clearExternalChanged();
  }

  function startEdit() {
    if (!selected.value) {
      return false;
    }
    draft.value = copyRecord(selected.value);
    mode.value = options.editMode;
    clearExternalChanged();
    return true;
  }

  function cancel() {
    draft.value = selected.value ? copyRecord(selected.value) : options.emptyDraft();
    mode.value = options.viewMode;
    clearExternalChanged();
  }

  function markExternalRecordChanged(recordId: string | undefined) {
    const currentRecordId = selected.value ? recordIdOf(selected.value) : undefined;
    if (!recordId || !currentRecordId || recordId !== currentRecordId || mode.value !== options.editMode) {
      return false;
    }
    externalChangedRecordId.value = recordId;
    return true;
  }

  function clearExternalChanged() {
    externalChangedRecordId.value = undefined;
  }

  return {
    selected,
    draft,
    mode,
    readonly,
    externallyChanged,
    externalChangedRecordId,
    select,
    replaceSelected,
    clearSelection,
    startCreate,
    startEdit,
    cancel,
    markExternalRecordChanged,
    clearExternalChanged,
  };
}

export function applyRecordExternalChange(
  session: { markExternalRecordChanged(recordId: string | undefined): boolean },
  change: WebDataChange,
  options: RecordExternalChangeOptions,
) {
  const changeTypes = options.changeTypes ?? [
    webDataChangeTypes.recordUpdated,
    webDataChangeTypes.recordDeleted,
  ];
  if (change.moduleAlias !== options.moduleAlias || !changeTypes.includes(change.type)) {
    return false;
  }
  return session.markExternalRecordChanged(change.recordId);
}

function defaultRecordIdOf<TRecord>(record: TRecord) {
  if (typeof record === 'object' && record !== null && 'id' in record) {
    const id = (record as { id?: unknown }).id;
    return id === undefined || id === null ? undefined : String(id);
  }
  return undefined;
}
