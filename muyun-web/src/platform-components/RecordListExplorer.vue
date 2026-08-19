<script setup lang="ts">
import { computed } from 'vue';
import { UiEmpty, UiRecordExplorerItem, type UiRecordInlineAction } from '@muyun/vue-ui-antdv';
import type { RecordExplorerItemDescriptor } from './recordExplorerItemModel';

defineOptions({ name: 'RecordListExplorer' });

export interface RecordListExplorerRecord {
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
  },
);

const emit = defineEmits<{
  select: [record: RecordListExplorerRecord];
  deselect: [];
  action: [action: UiRecordInlineAction, record: RecordListExplorerRecord];
}>();

const normalizedKeyword = computed(() => props.keyword.trim().toLowerCase());
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
</script>

<template>
  <UiEmpty v-if="filteredRecords.length === 0" :description="emptyDescription" />
  <ul v-else class="record-list-explorer">
    <li v-for="record in filteredRecords" :key="record.id">
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
</style>
