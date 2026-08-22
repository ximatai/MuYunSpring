<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { UiSelect, UiTreeSelect } from '@muyun/vue-ui-antdv';
import { normalizeError, type ModuleContext } from '@muyun/web-core';
import type { WebTreeNode } from '@muyun/web-contracts';
import type { PickerConstraint, RecordPickerRecord } from './recordPickerConstraints';
import { firstConstraintMessage } from './recordPickerConstraints';
import { resolveRecordPickerMode, type RecordPickerMode } from './recordPickerModel';
import {
  defaultTreeRecordMatches,
  defaultTreeRecordTitle,
  filterTreeRecords,
  flattenTreeRecords,
} from './treeRecordModel';

defineOptions({ name: 'RecordMultiPicker' });

interface UiTreeSelectNode {
  value: string | number;
  title: string;
  disabled?: boolean;
  children?: UiTreeSelectNode[];
}

const props = withDefaults(
  defineProps<{
    context: ModuleContext<RecordPickerRecord>;
    loadOptions?: (keyword: string) => Promise<RecordPickerRecord[]>;
    resolveOptions?: (values: string[]) => Promise<RecordPickerRecord[]>;
    value?: string[];
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
    value: () => [],
    loadOptions: undefined,
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
  'update:value': [value: string[]];
  select: [records: RecordPickerRecord[]];
}>();
const loading = ref(false);
const tree = ref<WebTreeNode<RecordPickerRecord>[]>([]);
const records = ref<RecordPickerRecord[]>([]);
const actualMode = ref<Exclude<RecordPickerMode, 'auto'>>('list');
const error = ref<string>();
const keyword = ref('');
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
    disabled: isDisabled(record),
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
  () => void resolveSelectedOptions(),
);

async function loadRecords() {
  loading.value = true;
  error.value = undefined;
  try {
    await props.context.runtime.ready;
    const treeAbility = props.mode === 'list' ? undefined : props.context.abilities.tryTree();
    actualMode.value = resolveRecordPickerMode(props.mode, Boolean(treeAbility));
    if (actualMode.value === 'tree' && treeAbility) {
      const response = await treeAbility.tree();
      tree.value = response.records;
      records.value = flattenTreeRecords(response.records);
      return;
    }
    tree.value = [];
    if (props.loadOptions) {
      records.value = await props.loadOptions(keyword.value.trim());
    } else {
      const response = await props.context.crud.query({
        page: { pageNum: 1, pageSize: 100 },
        ...(keyword.value.trim() ? { quickSearch: keyword.value.trim() } : {}),
      });
      records.value = response.records;
    }
    await resolveSelectedOptions();
  } catch (cause) {
    error.value = normalizeError(cause).message;
  } finally {
    loading.value = false;
  }
}

async function resolveSelectedOptions() {
  const missing = props.value.filter((value) => !records.value.some((record) => record.id === value));
  if (!props.resolveOptions || missing.length === 0) return;
  const resolved = await props.resolveOptions(missing);
  records.value = [...records.value, ...resolved.filter((record) => record.id !== undefined)];
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

function isDisabled(record: RecordPickerRecord) {
  return (
    record.enabled === false ||
    Boolean(firstConstraintMessage(record, pickerContext.value, props.constraints))
  );
}

function toTreeNode(node: WebTreeNode<RecordPickerRecord>): UiTreeSelectNode {
  return {
    value: node.record.id ?? '',
    title: recordTitle(node.record),
    disabled: isDisabled(node.record),
    children: node.children.map(toTreeNode),
  };
}

function updateValue(value: string | number | (string | number)[] | null) {
  const values = Array.isArray(value) ? value : value == null ? [] : [value];
  const selected = values.filter((item): item is string => typeof item === 'string');
  emit('update:value', selected);
  emit('select', selected.flatMap((id) => records.value.filter((record) => record.id === id)));
}
</script>

<template>
  <UiTreeSelect
    v-if="actualMode === 'tree'"
    :value="value"
    :tree-data="treeData"
    mode="multiple"
    :placeholder="error ?? placeholder"
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
    mode="multiple"
    :placeholder="error ?? placeholder"
    :disabled="disabled"
    :allow-clear="allowClear"
    :show-search="true"
    :filter-option="false"
    :loading="loading"
    @search="keyword = $event"
    @update:value="updateValue"
  />
</template>
