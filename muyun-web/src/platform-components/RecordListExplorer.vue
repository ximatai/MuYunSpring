<script setup lang="ts">
import { computed, ref } from 'vue';
import { UiEmpty, UiRecordExplorerItem, type UiRecordInlineAction } from '@muyun/vue-ui-antdv';
import type { RecordExplorerItemDescriptor } from './recordExplorerItemModel';

defineOptions({ name: 'RecordListExplorer' });

export interface RecordListExplorerRecord {
  [key: string]: unknown;
  id?: string;
  code?: string;
  title?: string;
  name?: string;
  enabled?: boolean;
}

const props = withDefaults(
  defineProps<{
    records: RecordListExplorerRecord[];
    selectedId?: string;
    keyword?: string;
    emptyDescription?: string;
    titleOf?: (record: RecordListExplorerRecord) => string;
    codeOf?: (record: RecordListExplorerRecord) => string | undefined;
    itemOf?: (record: RecordListExplorerRecord) => RecordExplorerItemDescriptor | undefined;
    actionsOf?: (record: RecordListExplorerRecord) => UiRecordInlineAction[];
    filterOption?: (record: RecordListExplorerRecord, normalizedKeyword: string) => boolean;
    tagOf?: (record: RecordListExplorerRecord) => string | undefined;
    mutedOf?: (record: RecordListExplorerRecord) => boolean;
    /** Enables vertical reordering for an already unfiltered flat record list. */
    sorting?: boolean;
    /** Records in different partitions cannot be placed relative to one another. */
    sortPartitionOf?: (record: RecordListExplorerRecord) => string | undefined;
  }>(),
  {
    selectedId: undefined,
    keyword: '',
    emptyDescription: '暂无记录',
    titleOf: undefined,
    codeOf: undefined,
    itemOf: undefined,
    actionsOf: undefined,
    filterOption: undefined,
    tagOf: undefined,
    mutedOf: undefined,
    sorting: false,
    sortPartitionOf: undefined,
  },
);

const emit = defineEmits<{
  select: [record: RecordListExplorerRecord];
  deselect: [];
  action: [action: UiRecordInlineAction, record: RecordListExplorerRecord];
  sort: [
    event: { dragRecord: RecordListExplorerRecord; dropRecord: RecordListExplorerRecord; position: -1 | 1 },
  ];
}>();

const normalizedKeyword = computed(() => props.keyword.trim().toLowerCase());
const sortingEnabled = computed(() => props.sorting && !normalizedKeyword.value);
const draggingId = ref<string>();
const dropTarget = ref<{ id: string; position: -1 | 1 }>();
const filteredRecords = computed(() => {
  if (!normalizedKeyword.value) {
    return props.records;
  }
  return props.records.filter((record) => matchesKeyword(record, normalizedKeyword.value));
});

function recordTitle(record: RecordListExplorerRecord) {
  const item = props.itemOf?.(record);
  return (
    item?.title ??
    props.titleOf?.(record) ??
    record.title ??
    record.name ??
    record.code ??
    record.id ??
    '未命名记录'
  );
}

function recordCode(record: RecordListExplorerRecord) {
  const item = props.itemOf?.(record);
  return item ? item.secondary : props.codeOf ? props.codeOf(record) : (record.code ?? record.id);
}

function recordSecondary(record: RecordListExplorerRecord) {
  const code = recordCode(record);
  return code && code !== recordTitle(record) ? code : undefined;
}

function recordMuted(record: RecordListExplorerRecord) {
  const item = props.itemOf?.(record);
  return item?.muted ?? props.mutedOf?.(record) ?? record.enabled === false;
}

function recordTag(record: RecordListExplorerRecord) {
  const item = props.itemOf?.(record);
  return item?.tag ?? props.tagOf?.(record) ?? (record.enabled === false ? '停用' : undefined);
}

function recordActions(record: RecordListExplorerRecord) {
  const item = props.itemOf?.(record);
  return item?.actions ?? props.actionsOf?.(record);
}

function matchesKeyword(record: RecordListExplorerRecord, keyword: string) {
  if (props.filterOption) {
    return props.filterOption(record, keyword);
  }
  return [recordTitle(record), recordCode(record), record.id].some((value) =>
    value?.toLowerCase().includes(keyword),
  );
}

function handleAction(action: UiRecordInlineAction, record: RecordListExplorerRecord) {
  if (action.disabled) {
    return;
  }
  emit('action', action, record);
}

function handleSelect(record: RecordListExplorerRecord) {
  if (record.id != null && String(record.id) === props.selectedId) {
    emit('deselect');
    return;
  }
  emit('select', record);
}

function startDrag(record: RecordListExplorerRecord, event: DragEvent) {
  if (!sortingEnabled.value || record.id == null) return;
  draggingId.value = String(record.id);
  event.dataTransfer?.setData('text/plain', String(record.id));
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
}

function dragOver(record: RecordListExplorerRecord, event: DragEvent) {
  if (!sortingEnabled.value || record.id == null || String(record.id) === draggingId.value) return;
  const dragRecord = props.records.find((candidate) => String(candidate.id) === draggingId.value);
  if (!dragRecord || !sameSortPartition(dragRecord, record)) return;
  event.preventDefault();
  const bounds = (event.currentTarget as HTMLElement).getBoundingClientRect();
  dropTarget.value = {
    id: String(record.id),
    position: event.clientY < bounds.top + bounds.height / 2 ? -1 : 1,
  };
}

function dropRecord(record: RecordListExplorerRecord, event: DragEvent) {
  if (
    !sortingEnabled.value ||
    record.id == null ||
    !draggingId.value ||
    String(record.id) === draggingId.value
  )
    return;
  event.preventDefault();
  const dragRecord = props.records.find((candidate) => String(candidate.id) === draggingId.value);
  if (!dragRecord || !sameSortPartition(dragRecord, record)) {
    clearDrag();
    return;
  }
  const position = dropTarget.value?.id === String(record.id) ? dropTarget.value.position : 1;
  clearDrag();
  if (dragRecord) emit('sort', { dragRecord, dropRecord: record, position });
}

function sameSortPartition(left: RecordListExplorerRecord, right: RecordListExplorerRecord) {
  const leftPartition = props.sortPartitionOf?.(left);
  const rightPartition = props.sortPartitionOf?.(right);
  return leftPartition !== undefined && leftPartition === rightPartition;
}

function clearDrag() {
  draggingId.value = undefined;
  dropTarget.value = undefined;
}
</script>

<template>
  <UiEmpty v-if="filteredRecords.length === 0" :description="emptyDescription" />
  <ul v-else class="record-list-explorer">
    <li
      v-for="record in filteredRecords"
      :key="record.id"
      :draggable="sortingEnabled"
      :class="{
        'record-list-explorer__item--dragging': String(record.id) === draggingId,
        'record-list-explorer__item--drop-before':
          dropTarget?.id === String(record.id) && dropTarget.position < 0,
        'record-list-explorer__item--drop-after':
          dropTarget?.id === String(record.id) && dropTarget.position > 0,
      }"
      @dragstart="startDrag(record, $event)"
      @dragover="dragOver(record, $event)"
      @drop="dropRecord(record, $event)"
      @dragend="clearDrag"
      @dragleave="dropTarget?.id === String(record.id) && (dropTarget = undefined)"
    >
      <UiRecordExplorerItem
        role="button"
        tabindex="0"
        clickable
        :title="recordTitle(record)"
        :secondary="recordSecondary(record)"
        :tag="recordTag(record)"
        :muted="recordMuted(record)"
        :selected="record.id === selectedId"
        :actions="recordActions(record)"
        @click="handleSelect(record)"
        @keydown.enter.prevent="handleSelect(record)"
        @keydown.space.prevent="handleSelect(record)"
        @action="handleAction($event, record)"
      />
    </li>
  </ul>
</template>

<style scoped>
.record-list-explorer {
  display: grid;
  align-content: start;
  gap: 2px;
  min-height: 0;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.record-list-explorer li {
  min-width: 0;
}

.record-list-explorer li[draggable='true'] {
  cursor: grab;
}

.record-list-explorer__item--dragging {
  opacity: 0.45;
}

.record-list-explorer__item--drop-before,
.record-list-explorer__item--drop-after {
  position: relative;
}

.record-list-explorer__item--drop-before::before,
.record-list-explorer__item--drop-after::after {
  position: absolute;
  right: 4px;
  left: 4px;
  z-index: 1;
  height: 2px;
  background: var(--muyun-primary);
  content: '';
}

.record-list-explorer__item--drop-before::before {
  top: -1px;
}

.record-list-explorer__item--drop-after::after {
  bottom: -1px;
}
</style>
