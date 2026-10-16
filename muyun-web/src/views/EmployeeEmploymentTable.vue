<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed, ref } from 'vue';
import { RecordExpandedSubtable } from '@muyun/platform-components';
import { UiDataTable } from '@muyun/vue-ui-antdv';
import type {
  UiDataTableColumn,
  UiDataTableKey,
  UiDataTableRecord,
  UiDataTablePagination,
  UiDataTableSelection,
} from '@muyun/vue-ui-antdv';
import type { EmploymentSelectorItem } from '@muyun/web-contracts';

const language = useLanguageContext();

defineOptions({ name: 'EmployeeEmploymentTable' });

interface EmployeeEmploymentGroup {
  employeeId: string;
  title: string;
  employeeNo?: string;
  organizationTitle?: string;
  positions: EmploymentSelectorItem[];
}

const props = withDefaults(
  defineProps<{
    rows: EmploymentSelectorItem[];
    selectedIds?: Set<string>;
    selectable?: boolean;
    disabled?: boolean;
    pagination?: false | UiDataTablePagination;
    fillHeight?: boolean;
  }>(),
  {
    selectedIds: () => new Set<string>(),
    selectable: false,
    disabled: false,
    pagination: false,
    fillHeight: false,
  },
);

const emit = defineEmits<{ 'update:selectedIds': [value: Set<string>] }>();

const expandedEmployeeKeys = ref<string[]>([]);
const employeeColumns = computed<UiDataTableColumn[]>(() => [
  { key: 'title', title: language.t('platform.screen.EmployeePicker.employee', '职员'), width: 160 },
  {
    key: 'employeeNo',
    title: language.t('platform.screen.EmployeeEmploymentTable.employee-number', '工号'),
    width: 140,
  },
  {
    key: 'organizationTitle',
    title: language.t('platform.user-picker.column.organization-name', '所属机构'),
    width: 180,
  },
  {
    key: 'employmentCount',
    title: language.t('platform.screen.EmployeeEmploymentTable.position-assignments', '任职'),
    width: 88,
    align: 'right',
  },
  {
    key: 'selectedCount',
    title: language.t('platform.screen.EmployeeEmploymentTable.selected', '已选'),
    width: 88,
    align: 'right',
  },
]);
const employmentColumns = computed<UiDataTableColumn[]>(() => [
  {
    key: 'position',
    title: language.t('platform.screen.CurrentUserProfileDialog.position', '岗位'),
    width: 160,
  },
  { key: 'organization', title: language.t('platform.user-picker.axis.organization', '机构'), width: 160 },
  { key: 'department', title: language.t('platform.user-picker.axis.department', '部门'), width: 160 },
  {
    key: 'primaryPosition',
    title: language.t('platform.screen.EmployeeEmploymentTable.primary-position', '主岗位'),
    width: 88,
  },
]);

const employees = computed<EmployeeEmploymentGroup[]>(() => {
  const grouped = new Map<string, EmployeeEmploymentGroup>();
  for (const row of props.rows) {
    const employeeId = row.employeeId ?? row.id;
    const current = grouped.get(employeeId) ?? {
      employeeId,
      title: row.employeeTitle ?? employeeId,
      employeeNo: row.employeeNo,
      organizationTitle: row.organizationTitle,
      positions: [],
    };
    current.positions.push(row);
    grouped.set(employeeId, current);
  }
  return [...grouped.values()];
});
const employeeTableRows = computed(() => employees.value as unknown as UiDataTableRecord[]);

function employeeCellValue(column: UiDataTableColumn, record: UiDataTableRecord) {
  const employee = record as unknown as EmployeeEmploymentGroup;
  if (column.key === 'title') return employee.title;
  if (column.key === 'employeeNo') return employee.employeeNo ?? employee.employeeId;
  if (column.key === 'organizationTitle') return employee.organizationTitle ?? '-';
  if (column.key === 'employmentCount')
    return language.t('platform.screen.EmployeeEmploymentTable.value-items', '{value1} 项', {
      value1: employee.positions.length,
    });
  if (column.key === 'selectedCount')
    return language.t('platform.screen.EmployeeEmploymentTable.value-items', '{value1} 项', {
      value1: selectedCount(employee),
    });
  return '';
}

function employmentCellValue(column: UiDataTableColumn, record: UiDataTableRecord) {
  const employment = record as unknown as EmploymentSelectorItem;
  if (column.key === 'position') return employment.positionTitle ?? employment.positionId;
  if (column.key === 'organization') return employment.organizationTitle ?? employment.organizationId;
  if (column.key === 'department') return employment.departmentTitle ?? employment.departmentId;
  if (column.key === 'primaryPosition')
    return employment.primaryPosition
      ? language.t('platform.screen.ManagedDetailRelationInlineSurface.yes', '是')
      : language.t('platform.screen.ManagedDetailRelationInlineSurface.no', '否');
  return '';
}

function selectedCount(employee: EmployeeEmploymentGroup) {
  return employee.positions.filter((position) => props.selectedIds.has(position.id)).length;
}

function selectionOf(employee: EmployeeEmploymentGroup): UiDataTableSelection | undefined {
  if (!props.selectable) return undefined;
  return {
    selectedRowKeys: employee.positions
      .filter((position) => props.selectedIds.has(position.id))
      .map((position) => position.id),
    preserveSelectedRowKeys: true,
    disabledOf: () => props.disabled,
    onChange: (keys) => updateEmployeeSelectedIds(employee, keys),
  };
}

function updateEmployeeSelectedIds(employee: EmployeeEmploymentGroup, keys: UiDataTableKey[]) {
  const next = new Set(props.selectedIds);
  for (const position of employee.positions) next.delete(position.id);
  for (const key of keys) next.add(String(key));
  emit('update:selectedIds', next);
}

function handleEmployeeExpand(record: UiDataTableRecord, expanded: boolean) {
  const employeeId = (record as unknown as EmployeeEmploymentGroup).employeeId;
  expandedEmployeeKeys.value = expanded
    ? Array.from(new Set([...expandedEmployeeKeys.value, employeeId]))
    : expandedEmployeeKeys.value.filter((key) => key !== employeeId);
}

function toggleEmployeeExpanded(record: UiDataTableRecord) {
  const employeeId = (record as unknown as EmployeeEmploymentGroup).employeeId;
  handleEmployeeExpand(record, !expandedEmployeeKeys.value.includes(employeeId));
}
</script>

<template>
  <UiDataTable
    class="employee-employment-table"
    :columns="employeeColumns"
    :rows="employeeTableRows"
    row-key="employeeId"
    :pagination="pagination"
    :expanded-row-keys="expandedEmployeeKeys"
    clickable-rows
    horizontal-scroll
    :fill-height="fillHeight"
    @row-expand="handleEmployeeExpand"
    @row-dblclick="toggleEmployeeExpanded"
  >
    <template #cell="{ column, record }">
      {{ employeeCellValue(column, record) }}
    </template>
    <template #expandedRow="{ record }">
      <RecordExpandedSubtable
        :title="language.t('platform.screen.CurrentUserProfileDialog.employment-details', '任职信息')"
      >
        <UiDataTable
          :columns="employmentColumns"
          :rows="(record as unknown as EmployeeEmploymentGroup).positions as unknown as UiDataTableRecord[]"
          row-key="id"
          :pagination="false"
          :selection="selectionOf(record as unknown as EmployeeEmploymentGroup)"
          horizontal-scroll
          :row-muted="(position) => (position as unknown as EmploymentSelectorItem).enabled === false"
        >
          <template #cell="{ column, record: employment }">
            {{ employmentCellValue(column, employment) }}
          </template>
        </UiDataTable>
      </RecordExpandedSubtable>
    </template>
  </UiDataTable>
</template>

<style scoped>
.employee-employment-table {
  min-width: 0;
}
</style>
