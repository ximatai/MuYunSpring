<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { UiButton, UiEmpty, UiError, UiInput, UiSpin, UiTree, type UiTreeNode } from '@muyun/vue-ui-antdv';
import { normalizeError, type ModuleContext } from '@muyun/web-core';
import type { WebTreeNode } from '@muyun/web-contracts';
import {
  firstConstraintMessage,
  type PickerConstraint,
  type RecordPickerRecord,
} from './recordPickerConstraints';
import { resolveRecordPickerMode, type RecordPickerMode } from './recordPickerModel';
import {
  defaultTreeRecordMatches,
  defaultTreeRecordTitle,
  expandAllTreeRecords,
  filterTreeRecords,
  firstTwoTreeLevels,
  flattenTreeRecords,
} from './treeRecordModel';

defineOptions({ name: 'RecordPicker' });

const props = withDefaults(
  defineProps<{
    context: ModuleContext<RecordPickerRecord>;
    value?: string;
    reloadKey?: number;
    mode?: 'list' | 'tree';
    placeholder?: string;
    disabled?: boolean;
    allowClear?: boolean;
    constraints?: PickerConstraint<RecordPickerRecord>[];
    titleOf?: (record: RecordPickerRecord) => string;
    descriptionOf?: (record: RecordPickerRecord) => string | undefined;
    filterOption?: (record: RecordPickerRecord, keyword: string) => boolean;
  }>(),
  {
    value: undefined,
    reloadKey: undefined,
    mode: 'tree',
    placeholder: '请选择',
    disabled: false,
    allowClear: true,
    constraints: () => [],
    titleOf: undefined,
    descriptionOf: undefined,
    filterOption: undefined,
  },
);

const emit = defineEmits<{
  'update:value': [value: string | undefined];
  select: [record: RecordPickerRecord | undefined];
}>();

const root = ref<HTMLElement>();
const open = ref(false);
const loading = ref(false);
const error = ref<string>();
const keyword = ref('');
const tree = ref<WebTreeNode<RecordPickerRecord>[]>([]);
const records = ref<RecordPickerRecord[]>([]);
const expandedKeys = ref<string[]>([]);
const actualMode = ref<RecordPickerMode>(props.mode);

const selectedRecord = computed(() => records.value.find((record) => record.id === props.value));
const selectedTitle = computed(() =>
  selectedRecord.value ? recordTitle(selectedRecord.value) : props.value ? props.value : '',
);
const filteredRecords = computed(() =>
  records.value.filter((record) => matchesKeyword(record, keyword.value)),
);
const filteredTree = computed(() =>
  filterTreeRecords(tree.value, keyword.value, (record, normalized) => matchesKeyword(record, normalized)),
);
const nodes = computed(() => filteredTree.value.map(toTreeNode));
const pickerContext = computed(() => ({ records: records.value }));
const hasRecords = computed(() =>
  actualMode.value === 'tree' ? nodes.value.length > 0 : filteredRecords.value.length > 0,
);

onMounted(() => {
  document.addEventListener('click', handleDocumentClick);
  document.addEventListener('keydown', handleDocumentKeydown);
  void loadRecords();
});

onBeforeUnmount(() => {
  document.removeEventListener('click', handleDocumentClick);
  document.removeEventListener('keydown', handleDocumentKeydown);
});

watch(
  () => [props.context, props.mode] as const,
  () => loadRecords(),
);

watch(
  () => props.reloadKey,
  () => loadRecords(),
);

watch(keyword, () => {
  if (keyword.value.trim()) {
    expandedKeys.value = filteredTree.value.flatMap(expandAllTreeRecords);
  }
});

async function loadRecords() {
  loading.value = true;
  error.value = undefined;
  try {
    if (props.mode === 'list') {
      actualMode.value = 'list';
      await loadListRecords();
      return;
    }
    await props.context.runtime.ready;
    const treeAbility = props.context.abilities.tryTree();
    actualMode.value = resolveRecordPickerMode(props.mode, Boolean(treeAbility));
    if (actualMode.value === 'tree' && treeAbility) {
      const response = await treeAbility.tree();
      tree.value = response.records;
      records.value = flattenTreeRecords(response.records);
      expandedKeys.value = firstTwoTreeLevels(response.records);
      return;
    }
    await loadListRecords();
  } catch (cause) {
    error.value = normalizeError(cause).message;
  } finally {
    loading.value = false;
  }
}

async function loadListRecords() {
  const response = await props.context.crud.query({ page: { pageNum: 1, pageSize: 100 } });
  tree.value = [];
  records.value = response.records;
}

function recordTitle(record: RecordPickerRecord) {
  return props.titleOf?.(record) ?? defaultTreeRecordTitle(record);
}

function matchesKeyword(record: RecordPickerRecord, value: string) {
  const normalized = value.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  if (props.filterOption) {
    return props.filterOption(record, normalized);
  }
  return defaultTreeRecordMatches(record, normalized, recordTitle);
}

function toTreeNode(node: WebTreeNode<RecordPickerRecord>): UiTreeNode {
  const disabledReason = firstConstraintMessage(node.record, pickerContext.value, props.constraints);
  return {
    key: node.record.id ?? '',
    title: recordTitle(node.record),
    disabled: Boolean(disabledReason),
    tag: disabledReason ?? (node.record.enabled === false ? '停用' : undefined),
    muted: Boolean(disabledReason) || node.record.enabled === false,
    children: node.children.map(toTreeNode),
  };
}

function selectRecord(record: RecordPickerRecord) {
  if (!record.id || isRecordDisabled(record)) {
    return;
  }
  emit('update:value', record.id);
  emit('select', record);
  open.value = false;
}

function handleTreeSelect(node: UiTreeNode) {
  const record = records.value.find((item) => item.id === node.key);
  if (record) {
    selectRecord(record);
  }
}

function isRecordDisabled(record: RecordPickerRecord) {
  return (
    record.enabled === false ||
    Boolean(firstConstraintMessage(record, pickerContext.value, props.constraints))
  );
}

function clearValue() {
  emit('update:value', undefined);
  emit('select', undefined);
  open.value = false;
}

function toggleOpen() {
  if (props.disabled) {
    return;
  }
  open.value = !open.value;
}

function handleDocumentClick(event: MouseEvent) {
  if (!root.value?.contains(event.target as Node)) {
    open.value = false;
  }
}

function handleDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    open.value = false;
  }
}
</script>

<template>
  <div ref="root" class="record-picker" :class="{ 'record-picker-disabled': disabled }">
    <div class="record-picker-control">
      <button
        class="record-picker-value"
        type="button"
        :disabled="disabled"
        :aria-expanded="open"
        @click.stop="toggleOpen"
      >
        <span v-if="selectedTitle" class="record-picker-value-text">{{ selectedTitle }}</span>
        <span v-else class="record-picker-placeholder">{{ placeholder }}</span>
        <span class="record-picker-caret" aria-hidden="true"></span>
      </button>
      <UiButton v-if="allowClear && value && !disabled" title="清空" @click="clearValue">清空</UiButton>
    </div>
    <div v-if="open && !disabled" class="record-picker-panel">
      <UiInput v-model:value="keyword" allow-clear placeholder="搜索名称、编码或 ID" />
      <UiSpin v-if="loading" tip="加载可选记录" />
      <UiError v-else-if="error" :message="error" />
      <UiEmpty v-else-if="!hasRecords" description="暂无可选记录" />
      <UiTree
        v-else-if="actualMode === 'tree'"
        v-model:expanded-keys="expandedKeys"
        :nodes="nodes"
        :selected-key="value"
        @select="handleTreeSelect"
      />
      <ul v-else class="record-picker-list">
        <li v-for="record in filteredRecords" :key="record.id">
          <button type="button" :disabled="isRecordDisabled(record)" @click="selectRecord(record)">
            <span>{{ recordTitle(record) }}</span>
            <small v-if="descriptionOf?.(record)">{{ descriptionOf(record) }}</small>
            <small v-else-if="record.code">{{ record.code }}</small>
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.record-picker {
  display: grid;
  gap: 8px;
  min-width: 0;
  position: relative;
}

.record-picker-control {
  display: flex;
  gap: 8px;
  min-width: 0;
}

.record-picker-value {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: 1 1 auto;
  min-width: 0;
  height: 34px;
  overflow: hidden;
  padding: 0 10px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 6px;
  background: var(--muyun-support-surface);
  color: var(--muyun-support-text);
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-picker-value-text,
.record-picker-placeholder {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.record-picker-value:disabled {
  background: var(--muyun-support-disabled);
  color: var(--muyun-support-disabled-text);
  cursor: not-allowed;
}

.record-picker-placeholder {
  color: var(--muyun-support-text-muted);
}

.record-picker-caret {
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  border-right: 1px solid var(--muyun-support-icon);
  border-bottom: 1px solid var(--muyun-support-icon);
  transform: rotate(45deg) translateY(-2px);
}

.record-picker-panel {
  position: absolute;
  z-index: 40;
  top: calc(100% + 4px);
  right: 0;
  left: 0;
  display: grid;
  gap: 10px;
  min-width: 280px;
  max-height: 320px;
  overflow: auto;
  padding: 10px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
}

.record-picker-list {
  display: grid;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.record-picker-list button {
  display: grid;
  width: 100%;
  gap: 2px;
  padding: 7px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muyun-support-text);
  text-align: left;
}

.record-picker-list button:hover:not(:disabled) {
  background: var(--muyun-support-hover);
}

.record-picker-list button:disabled {
  color: var(--muyun-support-disabled-text);
  cursor: not-allowed;
}

.record-picker-list small {
  color: var(--muyun-support-text-muted);
  font-size: 12px;
}
</style>
