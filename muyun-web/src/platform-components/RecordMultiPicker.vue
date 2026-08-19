<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { UiSelect, UiTreeSelect } from '@muyun/vue-ui-antdv';
import { normalizeError, type ModuleContext } from '@muyun/web-core';
import type { WebTreeNode } from '@muyun/web-contracts';
import type { PickerConstraint, RecordPickerRecord } from './recordPickerConstraints';
import { firstConstraintMessage } from './recordPickerConstraints';
import { resolveRecordPickerMode, type RecordPickerMode } from './recordPickerModel';
import { defaultTreeRecordTitle, flattenTreeRecords } from './treeRecordModel';

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

const emit = defineEmits<{ 'update:value': [value: string[]] }>();
const loading = ref(false);
const tree = ref<WebTreeNode<RecordPickerRecord>[]>([]);
const records = ref<RecordPickerRecord[]>([]);
const actualMode = ref<Exclude<RecordPickerMode, 'auto'>>('list');
const error = ref<string>();
const pickerContext = computed(() => ({ records: records.value }));

const treeData = computed<UiTreeSelectNode[]>(() => tree.value.map(toTreeNode));
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
    const response = await props.context.crud.query({ page: { pageNum: 1, pageSize: 100 } });
    tree.value = [];
    records.value = response.records;
  } catch (cause) {
    error.value = normalizeError(cause).message;
  } finally {
    loading.value = false;
  }
}

function recordTitle(record: RecordPickerRecord) {
  return props.titleOf?.(record) ?? defaultTreeRecordTitle(record);
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
  emit(
    'update:value',
    values.filter((item): item is string => typeof item === 'string'),
  );
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
    :loading="loading"
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
    :loading="loading"
    @update:value="updateValue"
  />
</template>
