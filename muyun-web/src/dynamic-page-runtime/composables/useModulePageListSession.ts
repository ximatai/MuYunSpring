import { ref, type Ref } from 'vue';
import type { QueryListRecord, RecordQueryListMode } from '@muyun/platform-components';

export interface ModulePageListSessionOptions {
  selectedRecord: Ref<QueryListRecord | undefined>;
  saving: Ref<boolean>;
  resetDetail(): void;
  invalidateDetailLoad(): void;
  resetTreeSelection(): void;
  openRecord(record: QueryListRecord): void;
  openRecycleBinRecord(record: QueryListRecord): void;
}

/**
 * Owns list-side state and record selection policy for one module-page session.
 *
 * This boundary deliberately has no HTTP, authorization, bootstrap or template
 * dependencies. The host supplies the already-authorized detail transitions,
 * keeping list/card/drawer selection behaviour reusable and directly testable.
 */
export function useModulePageListSession(options: ModulePageListSessionOptions) {
  const listMode = ref<RecordQueryListMode>('normal');
  const reloadKey = ref(0);
  const cardAssistantRecords = ref<QueryListRecord[]>([]);

  function handleLoaded(records: QueryListRecord[]) {
    if (listMode.value !== 'recycleBin') cardAssistantRecords.value = records;
    if (options.selectedRecord.value) {
      options.selectedRecord.value =
        records.find((record) => record.id === options.selectedRecord.value?.id) ??
        options.selectedRecord.value;
    }
  }

  function handleFlatManagementLoaded(records: QueryListRecord[], recycleBinActive: boolean) {
    // Card assistants must only receive active business records.
    if (recycleBinActive) return;
    cardAssistantRecords.value = records;
    handleLoaded(records);
  }

  function setCardAssistantRecords(records: QueryListRecord[]) {
    cardAssistantRecords.value = records;
  }

  function resetFlatManagementSelection() {
    options.invalidateDetailLoad();
    options.resetDetail();
    options.selectedRecord.value = undefined;
  }

  function handleListModeChange(mode: RecordQueryListMode) {
    if (options.saving.value || listMode.value === mode) return;
    listMode.value = mode;
    options.invalidateDetailLoad();
    options.resetDetail();
    options.selectedRecord.value = undefined;
    options.resetTreeSelection();
  }

  function handleRecycleBinRestore() {
    reloadKey.value += 1;
  }

  function selectRecord(record: QueryListRecord) {
    options.selectedRecord.value = record;
  }

  function selectListDetailRecord(record: QueryListRecord, detailSurfaceUsesDrawer: boolean) {
    selectRecord(record);
    if (detailSurfaceUsesDrawer) return;
    if (listMode.value === 'recycleBin') {
      options.openRecycleBinRecord(record);
      return;
    }
    options.openRecord(record);
  }

  function selectStandaloneListRecord(record: QueryListRecord) {
    selectRecord(record);
    if (listMode.value === 'recycleBin') options.openRecycleBinRecord(record);
  }

  function openListRecord(record: QueryListRecord) {
    if (listMode.value === 'recycleBin') {
      options.openRecycleBinRecord(record);
      return;
    }
    options.openRecord(record);
  }

  return {
    listMode,
    reloadKey,
    cardAssistantRecords,
    handleLoaded,
    handleFlatManagementLoaded,
    setCardAssistantRecords,
    resetFlatManagementSelection,
    handleListModeChange,
    handleRecycleBinRestore,
    selectRecord,
    selectListDetailRecord,
    selectStandaloneListRecord,
    openListRecord,
  };
}
