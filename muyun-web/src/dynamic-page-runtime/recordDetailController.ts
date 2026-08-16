import { ref } from 'vue';

export type RecordDetailMode = 'create' | 'edit' | 'view';

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

  function beginLoad(next: TRecord, nextMode: Extract<RecordDetailMode, 'edit' | 'view'>) {
    formSessionKey.value += 1;
    record.value = next;
    draft.value = undefined;
    mode.value = nextMode;
    open.value = true;
    loading.value = true;
    loadFailed.value = false;
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

  function beginCreate(initial: TRecord) {
    formSessionKey.value += 1;
    record.value = undefined;
    draft.value = { ...initial };
    mode.value = 'create';
    open.value = true;
    loading.value = false;
    loadFailed.value = false;
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
      open.value = false;
      record.value = undefined;
      draft.value = undefined;
      mode.value = 'view';
      loading.value = false;
      loadFailed.value = false;
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
    formSessionKey.value += 1;
  }

  function clearDeleted() {
    record.value = undefined;
    draft.value = undefined;
    mode.value = 'view';
    loading.value = false;
    loadFailed.value = false;
  }

  function close() {
    open.value = false;
    if (mode.value === 'create') {
      record.value = undefined;
      draft.value = undefined;
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
