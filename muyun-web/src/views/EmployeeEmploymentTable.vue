<script setup lang="ts">
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
const employeeColumns: UiDataTableColumn[] = [
  { key: 'title', title: '职员', width: 160 },
  { key: 'employeeNo', title: '工号', width: 140 },
  { key: 'organizationTitle', title: '所属机构', width: 180 },
  { key: 'employmentCount', title: '任职', width: 88, align: 'right' },
  { key: 'selectedCount', title: '已选', width: 88, align: 'right' },
];
const employmentColumns: UiDataTableColumn[] = [
  { key: 'position', title: '岗位', width: 160 },
  { key: 'organization', title: '机构', width: 160 },
  { key: 'department', title: '部门', width: 160 },
  { key: 'primaryPosition', title: '主岗位', width: 88 },
];

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
  if (column.key === 'employmentCount') return `${employee.positions.length} 项`;
  if (column.key === 'selectedCount') return `${selectedCount(employee)} 项`;
  return '';
}

function employmentCellValue(column: UiDataTableColumn, record: UiDataTableRecord) {
  const employment = record as unknown as EmploymentSelectorItem;
  if (column.key === 'position') return employment.positionTitle ?? employment.positionId;
  if (column.key === 'organization') return employment.organizationTitle ?? employment.organizationId;
  if (column.key === 'department') return employment.departmentTitle ?? employment.departmentId;
  if (column.key === 'primaryPosition') return employment.primaryPosition ? '是' : '否';
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
      <RecordExpandedSubtable title="任职信息">
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
