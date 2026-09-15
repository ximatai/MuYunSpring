<script setup lang="ts">
import { computed, getCurrentInstance } from 'vue';
import ReferencePicker from './ReferencePicker.vue';
import type {
  ReferencePickerProvider,
  ReferencePickerSourceIdentity,
  ReferencePickerValidity,
} from './referencePickerModel';
import type {
  EmployeeId,
  EmployeePickerCandidate,
  EmployeePickerPageSearch,
  EmployeePickerResolver,
} from './employeePickerModel';

defineOptions({ name: 'EmployeePicker' });

const props = withDefaults(
  defineProps<{
    value?: EmployeeId | readonly EmployeeId[];
    multiple?: boolean;
    maxSelection?: number;
    placeholder?: string;
    disabled?: boolean;
    allowClear?: boolean;
    pageSize?: number;
    title?: string;
    reloadKey?: string | number;
    sourceIdentity?: ReferencePickerSourceIdentity;
    searchPage: EmployeePickerPageSearch;
    resolveEmployees: EmployeePickerResolver;
  }>(),
  {
    value: undefined,
    multiple: false,
    maxSelection: undefined,
    placeholder: '搜索并选择职员',
    disabled: false,
    allowClear: true,
    pageSize: 20,
    title: '选择职员',
    reloadKey: undefined,
    sourceIdentity: undefined,
  },
);

const emit = defineEmits<{
  'update:value': [value: EmployeeId | EmployeeId[] | undefined];
  select: [employees: EmployeePickerCandidate[]];
  'selection-resolved': [employees: EmployeePickerCandidate[]];
  'validity-change': [validity: ReferencePickerValidity];
}>();

const instanceId = getCurrentInstance()?.uid ?? Math.random().toString(36).slice(2);
const provider = computed<ReferencePickerProvider>(() => ({
  identity: props.sourceIdentity ?? {
    targetModuleAlias: 'iam.employee',
    source: { kind: 'targetReference', id: `legacy-employee-picker-${instanceId}` },
  },
  async searchPage(request) {
    const page = await props.searchPage(request);
    return { records: page.records, total: page.total };
  },
  resolve: props.resolveEmployees,
}));

const columns = [
  { key: 'title', title: '职员' },
  { key: 'subtitle', title: '摘要', width: 220 },
];

function updateValue(value: string | string[] | undefined) {
  emit('update:value', value);
}
</script>

<template>
  <ReferencePicker
    :value="value"
    :multiple="multiple"
    :max-selection="maxSelection"
    :provider="provider"
    :reload-key="reloadKey"
    :columns="columns"
    :placeholder="placeholder"
    :disabled="disabled"
    :allow-clear="allowClear"
    :page-size="pageSize"
    :title="title"
    search-placeholder="按工号、姓名或职员 ID 搜索"
    empty-description="没有可选择的职员"
    selection-noun="职员"
    @update:value="updateValue"
    @select="emit('select', $event)"
    @selection-resolved="emit('selection-resolved', $event)"
    @validity-change="emit('validity-change', $event)"
  />
</template>
