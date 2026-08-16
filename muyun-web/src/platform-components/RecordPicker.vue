<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { UiError, UiSelect, UiTreeSelect, type UiTreeSelectNode } from '@muyun/vue-ui-antdv';
import { normalizeError, type ModuleContext } from '@muyun/web-core';
import type { WebTreeNode } from '@muyun/web-contracts';
import { firstConstraintMessage, type PickerConstraint, type RecordPickerRecord } from './recordPickerConstraints';
import { resolveRecordPickerMode, type RecordPickerMode } from './recordPickerModel';
import { defaultTreeRecordMatches, defaultTreeRecordTitle, filterTreeRecords, flattenTreeRecords } from './treeRecordModel';

defineOptions({ name: 'RecordPicker' });

const props = withDefaults(defineProps<{
  context: ModuleContext<RecordPickerRecord>;
  value?: string; reloadKey?: number; mode?: 'list' | 'tree'; placeholder?: string;
  disabled?: boolean; allowClear?: boolean; constraints?: PickerConstraint<RecordPickerRecord>[];
  titleOf?: (record: RecordPickerRecord) => string;
  descriptionOf?: (record: RecordPickerRecord) => string | undefined;
  filterOption?: (record: RecordPickerRecord, keyword: string) => boolean;
}>(), {
  value: undefined, reloadKey: undefined, mode: 'tree', placeholder: '请选择', disabled: false,
  allowClear: true, constraints: () => [], titleOf: undefined, descriptionOf: undefined, filterOption: undefined,
});

const emit = defineEmits<{
  'update:value': [value: string | undefined];
  select: [record: RecordPickerRecord | undefined];
}>();
const loading = ref(false);
const error = ref<string>();
const keyword = ref('');
const tree = ref<WebTreeNode<RecordPickerRecord>[]>([]);
const records = ref<RecordPickerRecord[]>([]);
const actualMode = ref<RecordPickerMode>(props.mode);
const pickerContext = computed(() => ({ records: records.value }));
const treeData = computed<UiTreeSelectNode[]>(() =>
  filterTreeRecords(tree.value, keyword.value, (record, normalized) => matchesKeyword(record, normalized)).map(toTreeNode),
);
const listOptions = computed(() => records.value.filter((record) => matchesKeyword(record, keyword.value)).map((record) => ({
  value: record.id ?? '', label: recordTitle(record), disabled: !record.id || isRecordDisabled(record),
})));

onMounted(() => void loadRecords());
watch(() => [props.context, props.mode, props.reloadKey] as const, () => void loadRecords());

async function loadRecords() {
  loading.value = true;
  error.value = undefined;
  keyword.value = '';
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

function recordTitle(record: RecordPickerRecord) { return props.titleOf?.(record) ?? defaultTreeRecordTitle(record); }
function matchesKeyword(record: RecordPickerRecord, value: string) {
  const normalized = value.trim().toLowerCase();
  if (!normalized) return true;
  return props.filterOption ? props.filterOption(record, normalized) : defaultTreeRecordMatches(record, normalized, recordTitle);
}
function toTreeNode(node: WebTreeNode<RecordPickerRecord>): UiTreeSelectNode {
  return { value: node.record.id ?? '', title: recordTitle(node.record), disabled: !node.record.id || isRecordDisabled(node.record), children: node.children.map(toTreeNode) };
}
function isRecordDisabled(record: RecordPickerRecord) {
  return record.enabled === false || Boolean(firstConstraintMessage(record, pickerContext.value, props.constraints));
}
function updateValue(value: string | number | (string | number)[] | null) {
  const id = Array.isArray(value) ? value[0] : value;
  if (id == null) {
    emit('update:value', undefined);
    emit('select', undefined);
    return;
  }
  const record = records.value.find((item) => item.id === String(id));
  if (!record || isRecordDisabled(record)) return;
  emit('update:value', record.id);
  emit('select', record);
}
</script>

<template>
  <UiTreeSelect
    v-if="actualMode === 'tree'"
    :value="value" :tree-data="treeData" :placeholder="placeholder" :disabled="disabled"
    :allow-clear="allowClear" :show-search="true" :filter-tree-node="false" :loading="loading"
    @search="keyword = $event" @update:value="updateValue"
  />
  <UiSelect
    v-else
    :value="value" :options="listOptions" :placeholder="placeholder" :disabled="disabled"
    :allow-clear="allowClear" :show-search="true" :filter-option="false" :loading="loading"
    @search="keyword = $event" @update:value="updateValue"
  />
  <UiError v-if="error" class="record-picker-error" :message="error" />
</template>

<style scoped>
.record-picker-error { margin-top: 6px; }
</style>
