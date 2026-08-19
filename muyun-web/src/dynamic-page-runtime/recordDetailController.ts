import { ref } from 'vue';

export type RecordDetailMode = 'create' | 'edit' | 'view';

export interface RecordDetailCreateOptions<TRecord> {
  /**
   * The already selected detail to reinstate if the user abandons this new
   * draft. It is deliberately held outside `record`: while creating, the
   * detail surface must not accidentally expose actions for the prior record.
   */
  restoreRecord?: TRecord;
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

  function beginLoad(next: TRecord, nextMode: Extract<RecordDetailMode, 'edit' | 'view'>) {
    formSessionKey.value += 1;
    record.value = next;
    draft.value = undefined;
    mode.value = nextMode;
    open.value = true;
    loading.value = true;
    loadFailed.value = false;
    createRestoreRecord.value = undefined;
  }

  function resolveLoad(next: TRecord) {
    record.value = next;
    draft.value = { ...next };
    loadFailed.value = false;
  }

  function failLoad() {
    draft.value = undefined;
    loadFailed.value = true;
  }

  function finishLoad() {
    loading.value = false;
  }

  function beginCreate(initial: TRecord, options: RecordDetailCreateOptions<TRecord> = {}) {
    formSessionKey.value += 1;
    record.value = undefined;
    draft.value = { ...initial };
    mode.value = 'create';
    open.value = true;
    loading.value = false;
    loadFailed.value = false;
    createRestoreRecord.value = options.restoreRecord;
  }

  function beginEdit() {
    if (!record.value) return false;
    draft.value = { ...record.value };
    mode.value = 'edit';
    formSessionKey.value += 1;
    return true;
  }

  function cancelEdit() {
    if (mode.value === 'create') {
      const restoreRecord = createRestoreRecord.value;
      open.value = Boolean(restoreRecord);
      record.value = restoreRecord;
      draft.value = restoreRecord ? { ...restoreRecord } : undefined;
      mode.value = 'view';
      loading.value = false;
      loadFailed.value = false;
      createRestoreRecord.value = undefined;
      formSessionKey.value += 1;
      return;
    }
    draft.value = record.value ? { ...record.value } : undefined;
    mode.value = 'view';
    loading.value = false;
    loadFailed.value = false;
    formSessionKey.value += 1;
  }

  function applySaved(next: TRecord) {
    record.value = next;
    draft.value = { ...next };
    mode.value = 'view';
    createRestoreRecord.value = undefined;
    formSessionKey.value += 1;
  }

  function clearDeleted() {
    record.value = undefined;
    draft.value = undefined;
    mode.value = 'view';
    loading.value = false;
    loadFailed.value = false;
    createRestoreRecord.value = undefined;
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
