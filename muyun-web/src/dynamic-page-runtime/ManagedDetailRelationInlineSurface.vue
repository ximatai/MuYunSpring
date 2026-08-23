<script setup lang="ts">
import { computed, onMounted, ref, toRaw, watch } from 'vue';
import {
  RecordFormFields,
  RecordSelectionCheckbox,
  RecordStatusTag,
  UiModal,
  recordPickerModeOf,
  resolveRecordDetailDisplayValue,
  applyReferenceDependencyClears,
  resolveRecordFormFieldState,
  resolveRecordFormFields,
  resolveRecordBooleanStatusValue,
  type QueryListRecord,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldValue,
  type RecordFormRecord,
  type RecordPickerRecord,
} from '@muyun/platform-components';
import type {
  ResolvedDetailRelationDescriptor,
  ResolvedModuleUiDescriptor,
  WebPageResponse,
} from '@muyun/web-contracts';
import { createModuleContext, createReferenceResolveClient, type ModuleContext } from '@muyun/web-core';
import { RelationFormComputeCoordinator } from './relationFormComputeCoordinator';

defineOptions({ name: 'ManagedDetailRelationInlineSurface' });

const props = withDefaults(
  defineProps<{
    sourceContext: ModuleContext<QueryListRecord>;
    uiDescriptor: ResolvedModuleUiDescriptor;
    relation: ResolvedDetailRelationDescriptor;
    parentRecord: QueryListRecord;
    reloadKey?: number;
    addRequestKey?: number;
    removeRequestKey?: number;
    undoRemoveRequestKey?: number;
    recycleBinRequestKey?: number;
    mutationEnabled?: boolean;
    density?: 'default' | 'compact';
    validationRequestKey?: number;
  }>(),
  {
    density: 'default',
    reloadKey: undefined,
    addRequestKey: undefined,
    removeRequestKey: undefined,
    undoRemoveRequestKey: undefined,
    recycleBinRequestKey: undefined,
    validationRequestKey: undefined,
  },
);

const emit = defineEmits<{
  'validity-change': [valid: boolean];
  'selection-change': [selectedCount: number];
  'removed-count-change': [removedCount: number];
  'recycle-bin-availability-change': [available: boolean];
  'records-change': [records: QueryListRecord[]];
}>();

type DraftRow = QueryListRecord & { __draftKey: string; __recycleSourceId?: string };

const rows = ref<DraftRow[]>([]);
const removed = ref<QueryListRecord[]>([]);
const selectedKeys = ref(new Set<string>());
const fieldValidity = ref<Record<string, Record<string, boolean>>>({});
const recycleBinOpen = ref(false);
const recycleBinLoading = ref(false);
const recycleBinRecords = ref<QueryListRecord[]>([]);
const recycleBinSourceRecords = ref<QueryListRecord[]>([]);
const recycleBinSelectedIds = ref(new Set<string>());
const recoveredSourceIds = ref(new Set<string>());
let draftSequence = 0;
let recycleBinRequestSequence = 0;

const parentId = computed(() => (props.parentRecord.id == null ? undefined : String(props.parentRecord.id)));
const embeddedField = computed(() => props.relation.embeddedField);
const embedded = computed(() => Boolean(embeddedField.value));
const mutation = computed(() => props.relation.mutationContract);
const createAllowed = computed(() => {
  if (embedded.value) return props.mutationEnabled === true;
  const contract = mutation.value;
  return Boolean(
    contract?.createAllowed &&
    contract.createActionCode &&
    props.sourceContext.can(contract.createActionCode) === true,
  );
});
const updateAllowed = computed(() => {
  if (embedded.value) return props.mutationEnabled === true;
  const contract = mutation.value;
  return Boolean(
    contract?.updateAllowed &&
    contract.updateActionCode &&
    props.sourceContext.can(contract.updateActionCode) === true,
  );
});
const deleteAllowed = computed(() => {
  if (embedded.value) return props.mutationEnabled === true;
  const contract = mutation.value;
  return Boolean(
    contract?.deleteAllowed &&
    contract.deleteActionCode &&
    props.sourceContext.can(contract.deleteActionCode) === true,
  );
});
const editingEnabled = computed(() =>
  embedded.value
    ? props.mutationEnabled === true
    : createAllowed.value || updateAllowed.value || deleteAllowed.value,
);
const formFields = computed(() =>
  resolveRecordFormFields(props.uiDescriptor, props.relation.targetEntityAlias),
);
const columns = computed(
  () => props.relation.listProjection?.fields ?? props.relation.queryContract?.listProjection?.fields ?? [],
);
const relationFormCompute = computed(
  () => new RelationFormComputeCoordinator(props.relation.formComputeRules),
);
function pickerConfigsOf(row: DraftRow): Record<string, RecordFormFieldPickerConfig> {
  const result: Record<string, RecordFormFieldPickerConfig> = {};
  for (const field of formFields.value.values()) {
    const reference = field.reference;
    if (!reference) continue;
    const fieldName = field.fieldRef.fieldName;
    const usesSourceReferenceResolver = reference.candidateDelivery === 'SOURCE_FIELD';
    const pickerRecord = (item: {
      id: string;
      title?: string;
      projections?: Record<string, unknown>;
      affectPatch?: Record<string, unknown>;
    }): RecordPickerRecord => ({
      id: item.id,
      title: item.title,
      ...(item.projections ?? {}),
      affectPatch: item.affectPatch,
    });
    const sourceReferencePickerConfig: Pick<
      RecordFormFieldPickerConfig,
      'loadOptions' | 'loadTree' | 'resolveOptions'
    > = {};
    if (usesSourceReferenceResolver) {
      const referenceResolver = createReferenceResolveClient(
        props.sourceContext.http,
        props.relation.targetModuleAlias,
        reference.resolvePath,
      );
      const formValues = () => ({
        ...props.parentRecord,
        ...row,
      });
      const source = () =>
        props.parentRecord.id == null ? undefined : { recordId: String(props.parentRecord.id) };
      sourceReferencePickerConfig.loadOptions = async (keyword: string) => {
        const response = await referenceResolver.resolve(fieldName, {
          mode: 'QUERY',
          fuzzy: keyword || undefined,
          page: { pageNum: 1, pageSize: 50 },
          formValues: formValues(),
          source: source(),
        });
        return response.options.map(pickerRecord);
      };
      sourceReferencePickerConfig.loadTree = async () => {
        const response = await referenceResolver.resolve(fieldName, {
          mode: 'TREE',
          formValues: formValues(),
          source: source(),
        });
        return response.tree ?? [];
      };
      sourceReferencePickerConfig.resolveOptions = async (values: string[]) => {
        const response = await referenceResolver.resolve(fieldName, {
          mode: 'TRANSLATE',
          values,
          formValues: formValues(),
          source: source(),
        });
        return response.results.flatMap((result) => (result.item ? [pickerRecord(result.item)] : []));
      };
    }
    const pickerContext = createModuleContext<RecordPickerRecord>({
      http: props.sourceContext.http,
      moduleAlias: reference.targetModuleAlias,
    });
    result[fieldName] = {
      context: pickerContext,
      mode: recordPickerModeOf(reference.pickerMode),
      allowClear: !field.required?.constant,
      ...sourceReferencePickerConfig,
    };
  }
  return result;
}
const valid = computed(() => {
  for (const row of rows.value) {
    if (blankNewRow(row)) continue;
    const states = fieldValidity.value[row.__draftKey] ?? {};
    if (Object.values(states).some((value) => value === false)) return false;
    for (const field of formFields.value.values()) {
      if (!fieldRequired(field.fieldRef.fieldName, row)) continue;
      const value = row[field.fieldRef.fieldName];
      if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) return false;
    }
  }
  return true;
});
const selectableRows = computed(() => rows.value.filter((row) => row.id == null || deleteAllowed.value));
const allSelected = computed(
  () =>
    selectableRows.value.length > 0 &&
    selectableRows.value.every((row) => selectedKeys.value.has(row.__draftKey)),
);
const someSelected = computed(
  () => !allSelected.value && selectableRows.value.some((row) => selectedKeys.value.has(row.__draftKey)),
);

function fieldRequired(fieldName: string, row: QueryListRecord = {}) {
  return resolveRecordFormFieldState(fieldName, { fields: formFields.value, record: row }).required;
}

function displayValue(row: DraftRow | QueryListRecord, fieldName: string) {
  const field = resolveRecordFormFieldState(fieldName, {
    fields: formFields.value,
    record: row as RecordFormRecord,
  });
  const value = resolveRecordDetailDisplayValue(field, row as RecordFormRecord);
  return value === 'true' ? '是' : value === 'false' ? '否' : value;
}

function statusField(fieldName: string, row: DraftRow | QueryListRecord) {
  const field = resolveRecordFormFieldState(fieldName, {
    fields: formFields.value,
    record: row as RecordFormRecord,
  });
  return field.controlType === 'enabledStatus' || field.controlType === 'booleanStatus' ? field : undefined;
}

function statusFieldValue(fieldName: string, row: DraftRow | QueryListRecord) {
  const field = statusField(fieldName, row);
  const value = row[fieldName];
  return field?.controlType === 'booleanStatus' ? resolveRecordBooleanStatusValue(value) : value !== false;
}

function columnRequired(fieldName: string) {
  return rows.value.length > 0
    ? rows.value.some((row) => fieldRequired(fieldName, row))
    : fieldRequired(fieldName);
}

function requiredValueMissing(row: DraftRow, fieldName: string) {
  if (!fieldRequired(fieldName, row)) return false;
  const value = row[fieldName];
  return value == null || value === '' || (Array.isArray(value) && value.length === 0);
}

function cellInvalid(row: DraftRow, fieldName: string) {
  if (blankNewRow(row)) return false;
  return requiredValueMissing(row, fieldName) || fieldValidity.value[row.__draftKey]?.[fieldName] === false;
}

function blankNewRow(row: DraftRow) {
  if (row.id != null) return false;
  return columns.value.every((column) => blankValue(row[column.fieldName]));
}

function blankValue(value: unknown) {
  return (
    value == null ||
    (typeof value === 'string' && value.trim() === '') ||
    (Array.isArray(value) && value.length === 0)
  );
}

async function load() {
  const recycleRequest = ++recycleBinRequestSequence;
  const field = embeddedField.value;
  if (!embedded.value || !field) throw new Error('inline relation requires an embedded child field');
  const records = Array.isArray(props.parentRecord[field]) ? props.parentRecord[field] : [];
  rows.value = records.map((record) => toDraftRow(record as QueryListRecord));
  removed.value = [];
  selectedKeys.value = new Set();
  fieldValidity.value = {};
  recoveredSourceIds.value = new Set();
  recycleBinOpen.value = false;
  recycleBinLoading.value = false;
  recycleBinSelectedIds.value = new Set();
  publishDraft();
  recycleBinSourceRecords.value = [];
  recycleBinRecords.value = [];
  emit('recycle-bin-availability-change', false);
  if (props.relation.editing?.recycleBinEnabled && parentId.value && editingEnabled.value) {
    try {
      await refreshRecycleBin(recycleRequest);
    } catch {
      // Availability discovery is optional UI enrichment. A failed probe keeps the entry hidden.
    }
  }
}

function toDraftRow(record: QueryListRecord): DraftRow {
  return { ...cloneRecord(record), __draftKey: `persisted:${String(record.id)}` };
}

function cloneRecord(record: QueryListRecord): QueryListRecord {
  const clone = structuredClone(toRaw(record));
  delete clone.__draftKey;
  delete clone.__recycleSourceId;
  return clone;
}

function recoveredDraft(record: QueryListRecord): QueryListRecord {
  return Object.fromEntries(
    [...formFields.value.keys()]
      .filter((fieldName) => record[fieldName] !== undefined)
      .map((fieldName) => [fieldName, structuredClone(toRaw(record[fieldName]))]),
  );
}

async function openRecycleBin() {
  if (!props.relation.editing?.recycleBinEnabled || !parentId.value || !editingEnabled.value) return;
  recycleBinOpen.value = true;
  recycleBinLoading.value = true;
  recycleBinSelectedIds.value = new Set();
  const recycleRequest = ++recycleBinRequestSequence;
  try {
    await refreshRecycleBin(recycleRequest);
  } catch {
    if (recycleRequest === recycleBinRequestSequence) {
      recycleBinSourceRecords.value = [];
      recycleBinRecords.value = [];
      recycleBinOpen.value = false;
      emit('recycle-bin-availability-change', false);
    }
  } finally {
    if (recycleRequest === recycleBinRequestSequence) recycleBinLoading.value = false;
  }
}

async function refreshRecycleBin(requestSequence: number) {
  const requestedParentId = parentId.value;
  const requestedRelationCode = props.relation.code;
  if (!requestedParentId) return;
  const response = await props.sourceContext.http.request<WebPageResponse<QueryListRecord>>({
    method: 'POST',
    path: `/${encodeURIComponent(props.sourceContext.moduleAlias)}/view/${encodeURIComponent(requestedParentId)}/relations/${encodeURIComponent(requestedRelationCode)}/recycle-bin/query`,
    body: {},
  });
  if (
    requestSequence !== recycleBinRequestSequence ||
    requestedParentId !== parentId.value ||
    requestedRelationCode !== props.relation.code
  )
    return;
  recycleBinSourceRecords.value = response.records;
  applyRecycleBinAvailability();
}

function applyRecycleBinAvailability() {
  recycleBinRecords.value = recycleBinSourceRecords.value.filter(
    (record) => !recoveredSourceIds.value.has(String(record.id ?? '')),
  );
  emit('recycle-bin-availability-change', recycleBinRecords.value.length > 0);
}

function setRecycleBinSelected(record: QueryListRecord, selected: boolean) {
  const id = String(record.id ?? '');
  if (!id) return;
  const next = new Set(recycleBinSelectedIds.value);
  if (selected) next.add(id);
  else next.delete(id);
  recycleBinSelectedIds.value = next;
}

function recoverSelected() {
  if (recycleBinSelectedIds.value.size === 0) return;
  const recovered = recycleBinRecords.value
    .filter((record) => recycleBinSelectedIds.value.has(String(record.id ?? '')))
    .map((record) => {
      draftSequence += 1;
      const sourceId = String(record.id);
      return {
        ...recoveredDraft(record),
        __draftKey: `recovered:${draftSequence}`,
        __recycleSourceId: sourceId,
      } as DraftRow;
    });
  recoveredSourceIds.value = new Set([
    ...recoveredSourceIds.value,
    ...recovered.map((row) => row.__recycleSourceId!).filter(Boolean),
  ]);
  applyRecycleBinAvailability();
  rows.value = [...rows.value, ...recovered];
  recycleBinOpen.value = false;
  recycleBinSelectedIds.value = new Set();
  publishDraft();
}

function addRow() {
  if (!createAllowed.value) return;
  draftSequence += 1;
  rows.value = [...rows.value, { ...inheritedReferenceDefaults(), __draftKey: `new:${draftSequence}` }];
  publishDraft();
}

/**
 * Organization is the platform-wide parent scope. Aggregate children which carry that same
 * reference start in their parent's organization, but remain independently editable.
 */
function inheritedReferenceDefaults(): QueryListRecord {
  const organizationField = formFields.value.get('organizationId');
  if (!organizationField?.reference || props.parentRecord.organizationId == null) return {};
  return { organizationId: structuredClone(toRaw(props.parentRecord.organizationId)) };
}

function setRowSelected(row: DraftRow, selected: boolean) {
  const next = new Set(selectedKeys.value);
  if (selected) next.add(row.__draftKey);
  else next.delete(row.__draftKey);
  selectedKeys.value = next;
}

function setAllSelected(selected: boolean) {
  selectedKeys.value = selected ? new Set(selectableRows.value.map((row) => row.__draftKey)) : new Set();
}

function removeSelectedRows() {
  if (selectedKeys.value.size === 0) return;
  const selected = rows.value.filter((row) => selectedKeys.value.has(row.__draftKey));
  const persisted = selected.filter((row) => row.id != null);
  if (persisted.length > 0 && !deleteAllowed.value) return;
  removed.value = [...removed.value, ...persisted.map(cloneRecord)];
  const releasedRecycleIds = new Set(
    selected.map((row) => row.__recycleSourceId).filter((value): value is string => Boolean(value)),
  );
  if (releasedRecycleIds.size > 0) {
    recoveredSourceIds.value = new Set(
      [...recoveredSourceIds.value].filter((id) => !releasedRecycleIds.has(id)),
    );
    applyRecycleBinAvailability();
  }
  rows.value = rows.value.filter((row) => !selectedKeys.value.has(row.__draftKey));
  selectedKeys.value = new Set();
  publishDraft();
}

function undoRemove() {
  const row = removed.value.at(-1);
  if (!row) return;
  removed.value = removed.value.slice(0, -1);
  rows.value = [...rows.value, toDraftRow(row)];
  publishDraft();
}

function updateField(row: DraftRow, fieldName: string, value: RecordFormFieldValue) {
  const updatedRows = rows.value.map((candidate) =>
    candidate.__draftKey === row.__draftKey
      ? { ...candidate, ...applyReferenceDependencyClears(candidate, fieldName, value, formFields.value) }
      : candidate,
  );
  rows.value = relationFormCompute.value.applyAfterChange(
    updatedRows,
    row.__draftKey,
    (candidate) => candidate.__draftKey,
    fieldName,
  );
  publishDraft();
}

function updateValidity(row: DraftRow, fieldName: string, value: boolean) {
  fieldValidity.value = {
    ...fieldValidity.value,
    [row.__draftKey]: { ...(fieldValidity.value[row.__draftKey] ?? {}), [fieldName]: value },
  };
}

function publishDraft() {
  emit('records-change', rows.value.filter((row) => !blankNewRow(row)).map(cloneRecord));
}

watch(valid, (value) => emit('validity-change', value), { immediate: true });
watch(selectedKeys, (value) => emit('selection-change', value.size), { immediate: true });
watch(removed, (value) => emit('removed-count-change', value.length), { immediate: true });
watch(
  () => [parentId.value, props.relation.code, props.reloadKey, props.mutationEnabled],
  () => void load(),
);
watch(
  () => props.addRequestKey,
  (value, previous) => {
    if (value != null && value !== previous) addRow();
  },
);
watch(
  () => props.removeRequestKey,
  (value, previous) => {
    if (value != null && value !== previous) removeSelectedRows();
  },
);
watch(
  () => props.undoRemoveRequestKey,
  (value, previous) => {
    if (value != null && value !== previous) undoRemove();
  },
);
watch(
  () => props.recycleBinRequestKey,
  (value, previous) => {
    if (value != null && value !== previous) void openRecycleBin();
  },
);
onMounted(() => void load());
</script>

<template>
  <section class="managed-relation-inline" :class="`managed-relation-inline--${density}`">
    <div class="managed-relation-inline__scroll">
      <table class="managed-relation-inline__table">
        <colgroup>
          <col v-if="editingEnabled" class="managed-relation-inline__selection-column" />
          <col
            v-for="column in columns"
            :key="column.fieldName"
            :style="column.width == null ? undefined : { width: `${column.width}px` }"
          />
        </colgroup>
        <thead>
          <tr>
            <th v-if="editingEnabled" class="managed-relation-inline__selection">
              <RecordSelectionCheckbox
                :checked="allSelected"
                :indeterminate="someSelected"
                :disabled="selectableRows.length === 0"
                aria-label="选择全部子表记录"
                @update:checked="setAllSelected"
              />
            </th>
            <th v-for="column in columns" :key="column.fieldName">
              {{ column.title ?? column.fieldName }}
              <strong
                v-if="editingEnabled && columnRequired(column.fieldName)"
                class="managed-relation-inline__required"
                >*</strong
              >
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.__draftKey">
            <td v-if="editingEnabled" class="managed-relation-inline__selection">
              <RecordSelectionCheckbox
                :checked="selectedKeys.has(row.__draftKey)"
                :disabled="row.id != null && !deleteAllowed"
                :aria-label="`选择子表记录 ${row.id ?? row.__draftKey}`"
                @update:checked="setRowSelected(row, $event)"
              />
            </td>
            <td
              v-for="column in columns"
              :key="`${column.fieldName}:${validationRequestKey ?? 0}`"
              :class="{
                'managed-relation-inline__cell--validation-pulse':
                  (validationRequestKey ?? 0) > 0 && cellInvalid(row, column.fieldName),
              }"
            >
              <RecordFormFields
                v-if="editingEnabled"
                :record="row as RecordFormRecord"
                :fields="formFields"
                :field-names="[column.fieldName]"
                :picker-configs="pickerConfigsOf(row)"
                :option-context="sourceContext"
                :form-session-key="row.__draftKey"
                :disabled="row.id != null && !updateAllowed"
                :show-labels="false"
                compact
                @update:field="(fieldName, value) => updateField(row, fieldName, value)"
                @validity-change="updateValidity(row, column.fieldName, $event.valid)"
              />
              <RecordStatusTag
                v-else-if="statusField(column.fieldName, row)"
                :enabled="statusFieldValue(column.fieldName, row)"
                :enabled-label="statusField(column.fieldName, row)?.booleanStatus?.trueLabel"
                :disabled-label="statusField(column.fieldName, row)?.booleanStatus?.falseLabel"
                :enabled-tone="statusField(column.fieldName, row)?.booleanStatus?.trueTone"
                :disabled-tone="statusField(column.fieldName, row)?.booleanStatus?.falseTone"
              />
              <span v-else class="managed-relation-inline__value">{{
                displayValue(row, column.fieldName)
              }}</span>
            </td>
          </tr>
          <tr v-if="rows.length === 0">
            <td :colspan="columns.length + (editingEnabled ? 1 : 0)" class="managed-relation-inline__empty">
              暂无关联记录
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
  <UiModal
    :open="recycleBinOpen"
    :title="`${relation.title ?? relation.code}回收站`"
    confirm-text="恢复到当前编辑"
    :confirm-loading="recycleBinLoading"
    :confirm-disabled="recycleBinLoading || recycleBinSelectedIds.size === 0"
    width="720px"
    @confirm="recoverSelected"
    @cancel="recycleBinOpen = false"
  >
    <div v-if="recycleBinLoading" class="managed-relation-inline__recycle-empty">正在加载…</div>
    <table v-else class="managed-relation-inline__table managed-relation-inline__recycle-table">
      <thead>
        <tr>
          <th class="managed-relation-inline__selection"></th>
          <th v-for="column in columns" :key="column.fieldName">{{ column.title ?? column.fieldName }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="record in recycleBinRecords" :key="String(record.id)">
          <td class="managed-relation-inline__selection">
            <RecordSelectionCheckbox
              :checked="recycleBinSelectedIds.has(String(record.id))"
              :aria-label="`选择恢复记录 ${record.id}`"
              @update:checked="setRecycleBinSelected(record, $event)"
            />
          </td>
          <td v-for="column in columns" :key="column.fieldName">
            <RecordStatusTag
              v-if="statusField(column.fieldName, record)"
              :enabled="statusFieldValue(column.fieldName, record)"
              :enabled-label="statusField(column.fieldName, record)?.booleanStatus?.trueLabel"
              :disabled-label="statusField(column.fieldName, record)?.booleanStatus?.falseLabel"
              :enabled-tone="statusField(column.fieldName, record)?.booleanStatus?.trueTone"
              :disabled-tone="statusField(column.fieldName, record)?.booleanStatus?.falseTone"
            />
            <span v-else class="managed-relation-inline__value">{{
              displayValue(record, column.fieldName)
            }}</span>
          </td>
        </tr>
        <tr v-if="recycleBinRecords.length === 0">
          <td :colspan="columns.length + 1" class="managed-relation-inline__empty">暂无可恢复记录</td>
        </tr>
      </tbody>
    </table>
  </UiModal>
</template>

<style scoped>
.managed-relation-inline {
  min-width: 0;
}

.managed-relation-inline__scroll {
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-x: contain;
  scrollbar-gutter: stable;
}

.managed-relation-inline--compact .managed-relation-inline__scroll {
  scrollbar-width: thin;
}

.managed-relation-inline--compact .managed-relation-inline__table {
  min-width: 760px;
}

.managed-relation-inline__table {
  border-collapse: collapse;
  font-size: var(--muyun-detail-relation-body-font-size, 12px);
  min-width: 0;
  table-layout: fixed;
  width: 100%;
}

.managed-relation-inline__table th,
.managed-relation-inline__table td {
  border: 1px solid var(--muyun-border-subtle);
  text-align: left;
  vertical-align: middle;
}

.managed-relation-inline__value {
  display: block;
  overflow: hidden;
  padding: 0 4px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.managed-relation-inline__table th {
  background: var(--muyun-hover-subtle);
  color: var(--muyun-text);
  font-size: var(--muyun-detail-relation-header-font-size, 12px);
  font-weight: 700;
  height: var(--muyun-detail-relation-header-row-height, 30px);
  padding: 0 4px;
}

.managed-relation-inline__table tbody tr {
  height: var(--muyun-detail-relation-body-row-height, 30px);
}

.managed-relation-inline--compact .managed-relation-inline__table th {
  height: 28px;
}

.managed-relation-inline--compact .managed-relation-inline__table tbody tr {
  height: 28px;
}

.managed-relation-inline__table td {
  padding: 0;
  transition:
    background-color 0.15s ease,
    box-shadow 0.15s ease;
}

.managed-relation-inline__table td:hover {
  background: var(--muyun-hover-subtle);
}

.managed-relation-inline__table td:focus-within {
  background: var(--muyun-primary-soft);
  outline: 1px solid var(--muyun-primary);
  outline-offset: -1px;
  position: relative;
  z-index: 1;
}

.managed-relation-inline__required {
  color: var(--muyun-danger-base);
  font-weight: 600;
  margin-left: 2px;
}

.managed-relation-inline__table td.managed-relation-inline__cell--validation-pulse {
  position: relative;
  z-index: 1;
  animation: managed-relation-cell-validation-pulse 720ms ease-out;
}

.managed-relation-inline__table td.managed-relation-inline__cell--validation-pulse:focus-within {
  outline: 0;
}

@keyframes managed-relation-cell-validation-pulse {
  25%,
  75% {
    box-shadow: inset 0 0 0 1px var(--muyun-danger-base);
  }
}

.managed-relation-inline__table td :deep(.record-form-field) {
  min-width: 0;
}

.managed-relation-inline__table td :deep(.ant-input),
.managed-relation-inline__table td :deep(.ant-input-affix-wrapper),
.managed-relation-inline__table td :deep(.ant-select-selector),
.managed-relation-inline__table td :deep(.ant-picker) {
  background: transparent;
  border-color: transparent !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  font-size: var(--muyun-detail-relation-body-font-size, 12px);
}

.managed-relation-inline__table td :deep(.ant-input-affix-wrapper) {
  border: 0 !important;
  min-height: 26px;
  padding: 0;
  width: 100%;
}

.managed-relation-inline__table td :deep(.ant-input:focus),
.managed-relation-inline__table td :deep(.ant-input-focused) {
  border-color: transparent !important;
  box-shadow: none !important;
  outline: 0;
}

.managed-relation-inline__table td :deep(.ant-input) {
  border: 0 !important;
  display: block;
  min-height: 26px;
  padding: 0 4px;
  width: 100%;
}

.managed-relation-inline__table td :deep(.ant-select-selector) {
  min-height: 26px !important;
  padding-inline: 6px !important;
}

.managed-relation-inline__table td :deep(.ant-select-single) {
  height: 26px;
}

.managed-relation-inline__table td :deep(.ant-select-single .ant-select-selection-item),
.managed-relation-inline__table td :deep(.ant-select-single .ant-select-selection-placeholder) {
  line-height: 24px;
}

.managed-relation-inline__table td :deep(.ant-input:disabled),
.managed-relation-inline__table td :deep(.ant-input-affix-wrapper-disabled),
.managed-relation-inline__table td :deep(.ant-select-disabled .ant-select-selector) {
  background: transparent;
}

.managed-relation-inline__actions {
  padding: 0 4px !important;
  text-align: center !important;
  width: 96px;
}

.managed-relation-inline__selection {
  padding: 0 !important;
  text-align: center !important;
  width: 34px;
}

.managed-relation-inline__selection-column {
  width: 34px;
}

.managed-relation-inline__actions :deep(.ant-btn) {
  font-size: var(--muyun-detail-relation-body-font-size, 12px);
  height: 26px;
  padding-inline: 6px;
}

.managed-relation-inline__empty {
  color: var(--muyun-text-muted);
  padding: 24px !important;
  text-align: center !important;
}

.managed-relation-inline__recycle-empty {
  color: var(--muyun-text-muted);
  padding: 24px;
  text-align: center;
}

.managed-relation-inline__recycle-table {
  table-layout: auto;
}
</style>
