<script setup lang="ts">
import { computed } from 'vue';
import {
  UiEmpty,
  UiTree,
  type UiRecordInlineAction,
  type UiTreeDropEvent,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';
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
    /** Provides the stable unique identity used by selection, Vue keys and drag commands. */
    keyOf?: (record: RecordListExplorerRecord) => string | undefined;
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
    keyOf: undefined,
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
const filteredRecords = computed(() => {
  if (!normalizedKeyword.value) {
    return props.records;
  }
  return props.records.filter((record) => matchesKeyword(record, normalizedKeyword.value));
});

const treeNodes = computed<UiTreeNode[]>(() =>
  filteredRecords.value.flatMap((record) => {
    const node = toTreeNode(record);
    return node ? [node] : [];
  }),
);

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

function toTreeNode(record: RecordListExplorerRecord): UiTreeNode | undefined {
  const key = recordKeyOf(record);
  if (key === undefined) return undefined;
  return {
    key,
    title: recordTitle(record),
    secondary: recordSecondary(record),
    tag: recordTag(record),
    muted: recordMuted(record),
    actions: recordActions(record),
  };
}

function recordOfNode(node: UiTreeNode) {
  return filteredRecords.value.find((record) => recordKeyOf(record) === node.key);
}

function recordKeyOf(record: RecordListExplorerRecord) {
  const key = props.keyOf?.(record) ?? record.id;
  return key == null || key === '' ? undefined : String(key);
}

function sameSortPartition(left: RecordListExplorerRecord, right: RecordListExplorerRecord) {
  const leftPartition = props.sortPartitionOf?.(left);
  const rightPartition = props.sortPartitionOf?.(right);
  return leftPartition !== undefined && leftPartition === rightPartition;
}

function canDropNode(event: Pick<UiTreeDropEvent, 'dragNode' | 'dropNode' | 'dropPosition' | 'dropToGap'>) {
  if (!sortingEnabled.value || !event.dropToGap || event.dropPosition === 0) return false;
  const dragRecord = recordOfNode(event.dragNode);
  const dropRecord = recordOfNode(event.dropNode);
  return Boolean(
    dragRecord &&
    dropRecord &&
    event.dragNode.key !== event.dropNode.key &&
    sameSortPartition(dragRecord, dropRecord),
  );
}

function canDragNode(node: UiTreeNode) {
  return sortingEnabled.value && Boolean(recordOfNode(node));
}

function handleDrop(event: UiTreeDropEvent) {
  if (!canDropNode(event)) return;
  const dragRecord = recordOfNode(event.dragNode);
  const dropRecord = recordOfNode(event.dropNode);
  if (dragRecord && dropRecord) {
    emit('sort', { dragRecord, dropRecord, position: event.dropPosition as -1 | 1 });
  }
}

function handleNodeSelect(node: UiTreeNode) {
  const record = recordOfNode(node);
  if (record) handleSelect(record);
}

function handleNodeAction(action: UiRecordInlineAction, node: UiTreeNode) {
  const record = recordOfNode(node);
  if (record) handleAction(action, record);
}
</script>

<template>
  <UiEmpty v-if="filteredRecords.length === 0" :description="emptyDescription" />
  <UiTree
    v-else
    class="record-list-explorer"
    display-mode="flat"
    :nodes="treeNodes"
    :selected-key="selectedId"
    :draggable="sortingEnabled"
    :can-drag="canDragNode"
    :allow-drop="canDropNode"
    @select="handleNodeSelect"
    @deselect="emit('deselect')"
    @action="handleNodeAction"
    @drop="handleDrop"
  />
</template>

<style scoped>
.record-list-explorer {
  min-height: 0;
}
</style>
