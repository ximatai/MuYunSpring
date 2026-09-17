import { computed, ref } from 'vue';

export type RecordDetailMode = 'create' | 'edit' | 'view';

export type RecordDetailCancelDestination = 'close' | 'restore-view';

export interface RecordDetailTransitionOptions {
  /**
   * The detail surface to return to when the user cancels the operation.
   * A row action starts an independent task and closes; an edit launched from
   * an open detail returns to that detail view.
   */
  cancelDestination?: RecordDetailCancelDestination;
}

/**
 * State transitions shared by descriptor-driven record detail surfaces.
 * Containers own their layout; this controller owns only one record's
 * lifecycle and deliberately contains no drawer, tab or list concerns.
 */
export function useRecordDetailController<TRecord extends Record<string, unknown>>() {
  const record = ref<TRecord>();
  const draft = ref<TRecord>();
  const mode = ref<RecordDetailMode>('view');
  const open = ref(false);
  const loading = ref(false);
  const loadFailed = ref(false);
  const saving = ref(false);
  const togglingEnabled = ref(false);
  const formSessionKey = ref(0);
  const createRestoreRecord = ref<TRecord>();
  const createCancelDestination = ref<RecordDetailCancelDestination>('close');
  const editCancelDestination = ref<RecordDetailCancelDestination>('close');
  const draftBaseline = ref<string>();
  const isDirty = computed(
    () => mode.value !== 'view' && draft.value != null && draftBaseline.value !== stableRecordDraft(draft.value),
  );

  function beginLoad(
    next: TRecord,
    nextMode: Extract<RecordDetailMode, 'edit' | 'view'>,
    options: RecordDetailTransitionOptions = {},
  ) {
    formSessionKey.value += 1;
    record.value = next;
    draft.value = undefined;
    draftBaseline.value = undefined;
    mode.value = nextMode;
    open.value = true;
    loading.value = true;
    loadFailed.value = false;
    createRestoreRecord.value = undefined;
    if (nextMode === 'edit') {
      editCancelDestination.value = options.cancelDestination ?? 'close';
    }
  }

  function resolveLoad(next: TRecord) {
    record.value = next;
    draft.value = { ...next };
    draftBaseline.value = stableRecordDraft(draft.value);
    loadFailed.value = false;
  }

  function failLoad() {
    draft.value = undefined;
    loadFailed.value = true;
  }

  function finishLoad() {
    loading.value = false;
  }

  function beginCreate(initial: TRecord, options: RecordDetailTransitionOptions = {}) {
    const restoreRecord = options.cancelDestination === 'restore-view' ? record.value : undefined;
    formSessionKey.value += 1;
    record.value = undefined;
    draft.value = { ...initial };
    draftBaseline.value = stableRecordDraft(draft.value);
    mode.value = 'create';
    open.value = true;
    loading.value = false;
    loadFailed.value = false;
    createCancelDestination.value = options.cancelDestination ?? 'close';
    createRestoreRecord.value = restoreRecord;
  }

  function beginEdit(options: RecordDetailTransitionOptions = {}) {
    if (!record.value) return false;
    draft.value = { ...record.value };
    draftBaseline.value = stableRecordDraft(draft.value);
    mode.value = 'edit';
    open.value = true;
    loading.value = false;
    loadFailed.value = false;
    editCancelDestination.value = options.cancelDestination ?? 'restore-view';
    formSessionKey.value += 1;
    return true;
  }

  function cancelEdit() {
    if (mode.value === 'create') {
      const restoreRecord =
        createCancelDestination.value === 'restore-view' ? createRestoreRecord.value : undefined;
      open.value = Boolean(restoreRecord);
      record.value = restoreRecord;
      draft.value = restoreRecord ? { ...restoreRecord } : undefined;
      draftBaseline.value = draft.value ? stableRecordDraft(draft.value) : undefined;
      mode.value = 'view';
      loading.value = false;
      loadFailed.value = false;
      createRestoreRecord.value = undefined;
      createCancelDestination.value = 'close';
      formSessionKey.value += 1;
      return;
    }
    if (editCancelDestination.value === 'close') {
      open.value = false;
    }
    draft.value = record.value ? { ...record.value } : undefined;
    draftBaseline.value = draft.value ? stableRecordDraft(draft.value) : undefined;
    mode.value = 'view';
    loading.value = false;
    loadFailed.value = false;
    editCancelDestination.value = 'close';
    formSessionKey.value += 1;
  }

  function applySaved(next: TRecord) {
    record.value = next;
    draft.value = { ...next };
    draftBaseline.value = stableRecordDraft(draft.value);
    mode.value = 'view';
    createRestoreRecord.value = undefined;
    createCancelDestination.value = 'close';
    editCancelDestination.value = 'close';
    formSessionKey.value += 1;
  }

  function clearDeleted() {
    open.value = false;
    record.value = undefined;
    draft.value = undefined;
    draftBaseline.value = undefined;
    mode.value = 'view';
    loading.value = false;
    loadFailed.value = false;
    createRestoreRecord.value = undefined;
    createCancelDestination.value = 'close';
    editCancelDestination.value = 'close';
  }

  function close() {
    open.value = false;
    if (mode.value === 'create') {
      record.value = undefined;
      draft.value = undefined;
      createRestoreRecord.value = undefined;
    } else {
      draft.value = record.value ? { ...record.value } : undefined;
    }
    draftBaseline.value = draft.value ? stableRecordDraft(draft.value) : undefined;
    createCancelDestination.value = 'close';
    editCancelDestination.value = 'close';
    mode.value = 'view';
    loading.value = false;
    loadFailed.value = false;
    formSessionKey.value += 1;
  }

  return {
    record,
    draft,
    mode,
    open,
    loading,
    loadFailed,
    saving,
    togglingEnabled,
    formSessionKey,
    isDirty,
    beginLoad,
    resolveLoad,
    failLoad,
    finishLoad,
    beginCreate,
    beginEdit,
    cancelEdit,
    applySaved,
    clearDeleted,
    close,
  };
}

/** Object key order must not make a form appear dirty after a renderer normalizes a record. */
function stableRecordDraft(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableRecordDraft).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, entry]) => `${JSON.stringify(key)}:${stableRecordDraft(entry)}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}
