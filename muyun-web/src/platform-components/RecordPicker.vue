<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { UiError, UiSelect, UiTreeSelect, type UiTreeSelectNode } from '@muyun/vue-ui-antdv';
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
  filterTreeRecords,
  flattenTreeRecords,
} from './treeRecordModel';

defineOptions({ name: 'RecordPicker' });

const props = withDefaults(
  defineProps<{
    context: ModuleContext<RecordPickerRecord>;
    loadOptions?: (keyword: string) => Promise<RecordPickerRecord[]>;
    loadTree?: () => Promise<WebTreeNode<RecordPickerRecord>[]>;
    resolveOptions?: (values: string[]) => Promise<RecordPickerRecord[]>;
    value?: string;
    reloadKey?: number;
    mode?: RecordPickerMode;
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
    loadOptions: undefined,
    loadTree: undefined,
    resolveOptions: undefined,
    reloadKey: undefined,
    mode: 'auto',
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
  /**
   * Emits the record resolved for the current value, including during edit restoration.
   * Consumers use it for transient UI state only; `select` retains its user-selection semantics.
   */
  'selection-resolved': [record: RecordPickerRecord | undefined];
}>();
const loading = ref(false);
const error = ref<string>();
const keyword = ref('');
const tree = ref<WebTreeNode<RecordPickerRecord>[]>([]);
const records = ref<RecordPickerRecord[]>([]);
const actualMode = ref<RecordPickerMode>(props.mode);
const pickerContext = computed(() => ({ records: records.value }));
const treeData = computed<UiTreeSelectNode[]>(() =>
  filterTreeRecords(tree.value, keyword.value, (record, normalized) =>
    matchesKeyword(record, normalized),
  ).map(toTreeNode),
);
const listOptions = computed(() =>
  records.value.map((record) => ({
    value: record.id ?? '',
    label: recordTitle(record),
    disabled: !record.id || isRecordDisabled(record),
  })),
);

onMounted(() => void loadRecords());
watch(
  () => [props.context, props.mode, props.reloadKey] as const,
  () => void loadRecords(),
);
watch(keyword, () => {
  if (actualMode.value === 'list') {
    void loadRecords();
  }
});
watch(
  () => props.value,
  () => {
    emitResolvedSelection();
    void resolveSelectedOption();
  },
);

async function loadRecords() {
  loading.value = true;
  error.value = undefined;
  try {
    if (props.mode === 'list') {
      actualMode.value = 'list';
      await loadListRecords();
      await resolveSelectedOption();
      return;
    }
    await props.context.runtime.ready;
    const treeAbility = props.context.abilities.tryTree();
    actualMode.value = resolveRecordPickerMode(props.mode, Boolean(treeAbility));
    if (actualMode.value === 'tree' && treeAbility) {
      tree.value = props.loadTree ? await props.loadTree() : (await treeAbility.tree()).records;
      records.value = flattenTreeRecords(tree.value);
      await resolveSelectedOption();
      return;
    }
    await loadListRecords();
    await resolveSelectedOption();
  } catch (cause) {
    error.value = normalizeError(cause).message;
  } finally {
    loading.value = false;
  }
}

async function resolveSelectedOption() {
  const value = props.value;
  if (!value) {
    emit('selection-resolved', undefined);
    return;
  }
  const existing = records.value.find((record) => record.id === value);
  if (existing) {
    emit('selection-resolved', existing);
    return;
  }
  if (!props.resolveOptions) {
    emit('selection-resolved', undefined);
    return;
  }
  const resolved = await props.resolveOptions([value]);
  // An earlier request may settle after the form has moved to a different record.
  if (props.value !== value) return;
  records.value = [...records.value, ...resolved.filter((record) => record.id !== undefined)];
  emitResolvedSelection();
}

function emitResolvedSelection() {
  const value = props.value;
  emit('selection-resolved', value ? records.value.find((record) => record.id === value) : undefined);
}

async function loadListRecords() {
  const search = keyword.value.trim();
  if (props.loadOptions) {
    tree.value = [];
    records.value = await props.loadOptions(search);
    return;
  }
  const response = await props.context.crud.query({
    page: { pageNum: 1, pageSize: 50 },
    ...(search ? { quickSearch: search } : {}),
  });
  tree.value = [];
  records.value = response.records;
}

function recordTitle(record: RecordPickerRecord) {
  return props.titleOf?.(record) ?? defaultTreeRecordTitle(record);
}
function matchesKeyword(record: RecordPickerRecord, value: string) {
  const normalized = value.trim().toLowerCase();
  if (!normalized) return true;
  return props.filterOption
    ? props.filterOption(record, normalized)
    : defaultTreeRecordMatches(record, normalized, recordTitle);
}
function toTreeNode(node: WebTreeNode<RecordPickerRecord>): UiTreeSelectNode {
  return {
    value: node.record.id ?? '',
    title: recordTitle(node.record),
    disabled: !node.record.id || isRecordDisabled(node.record),
    children: node.children.map(toTreeNode),
  };
}
function isRecordDisabled(record: RecordPickerRecord) {
  return (
    record.enabled === false ||
    Boolean(firstConstraintMessage(record, pickerContext.value, props.constraints))
  );
}
function updateValue(value: string | number | (string | number)[] | null) {
  const id = Array.isArray(value) ? value[0] : value;
  if (id == null) {
    emit('update:value', undefined);
    emit('select', undefined);
    emit('selection-resolved', undefined);
    return;
  }
  const record = records.value.find((item) => item.id === String(id));
  if (!record || isRecordDisabled(record)) return;
  emit('update:value', record.id);
  emit('select', record);
  emit('selection-resolved', record);
}
</script>

<template>
  <UiTreeSelect
    v-if="actualMode === 'tree'"
    :value="value"
    :tree-data="treeData"
    :placeholder="placeholder"
    :disabled="disabled"
    :allow-clear="allowClear"
    :show-search="true"
    :filter-tree-node="false"
    :loading="loading"
    @search="keyword = $event"
    @update:value="updateValue"
  />
  <UiSelect
    v-else
    :value="value"
    :options="listOptions"
    :placeholder="placeholder"
    :disabled="disabled"
    :allow-clear="allowClear"
    :show-search="true"
    :filter-option="false"
    :loading="loading"
    @search="keyword = $event"
    @update:value="updateValue"
  />
  <UiError v-if="error" class="record-picker-error" :message="error" />
</template>

<style scoped>
.record-picker-error {
  margin-top: 6px;
}
</style>
