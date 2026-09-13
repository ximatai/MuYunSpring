<script setup lang="ts">
import UserPicker from './UserPicker.vue';
import type {
  EmployeeId,
  EmployeePickerCandidate,
  EmployeePickerPageSearch,
  EmployeePickerResolver,
} from './employeePickerModel';

defineOptions({ name: 'EmployeePicker' });

withDefaults(
  defineProps<{
    /** Employee ID for a single picker, or employee IDs for a multiple picker. */
    value?: EmployeeId | readonly EmployeeId[];
    multiple?: boolean;
    maxSelection?: number;
    placeholder?: string;
    disabled?: boolean;
    pageSize?: number;
    title?: string;
    searchPage: EmployeePickerPageSearch;
    resolveEmployees: EmployeePickerResolver;
  }>(),
  {
    value: undefined,
    multiple: false,
    maxSelection: undefined,
    placeholder: '搜索并选择职员',
    disabled: false,
    pageSize: 20,
    title: '选择职员',
  },
);

const emit = defineEmits<{
  'update:value': [value: EmployeeId | EmployeeId[] | undefined];
  select: [employees: EmployeePickerCandidate[]];
}>();

function updateValue(value: string | string[] | undefined) {
  emit('update:value', value);
}

function select(employees: EmployeePickerCandidate[]) {
  emit('select', employees);
}
</script>

<template>
  <UserPicker
    :value="value"
    :multiple="multiple"
    :max-selection="maxSelection"
    :placeholder="placeholder"
    :disabled="disabled"
    :page-size="pageSize"
    :title="title"
    search-placeholder="按工号、姓名或职员 ID 搜索"
    empty-description="没有可选择的职员"
    selection-noun="职员"
    :search-page="searchPage"
    :resolve-users="resolveEmployees"
    @update:value="updateValue"
    @select="select"
  />
</template>
