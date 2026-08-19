<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  RecordDetailDrawer,
  RecordPicker,
  createScopedTreeModuleContext,
  enabledOnly,
  handlePlatformActionSuccess,
  presentPlatformError,
  presentPlatformMessage,
} from '@muyun/platform-components';
import { UiButton, UiDataTable, UiDropdown, UiError, UiSpin, confirmAction } from '@muyun/vue-ui-antdv';
import type { UiDataTableColumn, UiDataTableRecord, UiDropdownItem } from '@muyun/vue-ui-antdv';
import type {
  Department,
  Employee,
  EmployeePosition,
  EmploymentSelectorItem,
  Organization,
  Position,
} from '@muyun/web-contracts';
import { useModuleContext } from '@muyun/web-core';

defineOptions({ name: 'EmployeeEmploymentDrawer' });

const props = defineProps<{
  open: boolean;
  container: HTMLElement | null;
  employee?: Employee;
}>();

const emit = defineEmits<{
  close: [];
  saved: [employeeId: string];
}>();

const employeeContext = useModuleContext<Employee>({ moduleAlias: 'iam.employee' });
const organizationContext = useModuleContext<Organization>({ moduleAlias: 'iam.organization' });
const baseDepartmentContext = useModuleContext<Department>({ moduleAlias: 'iam.department' });
const positionContext = useModuleContext<Position>({ moduleAlias: 'iam.position' });

const rows = ref<EmploymentSelectorItem[]>([]);
const loading = ref(false);
const loadFailed = ref(false);
const saving = ref(false);
const editorOpen = ref(false);
const draft = ref<Partial<EmployeePosition>>(createDraft());

const employmentColumns: UiDataTableColumn[] = [
  { key: 'position', title: '岗位', width: 150 },
  { key: 'organization', title: '机构', width: 140 },
  { key: 'department', title: '部门', width: 140 },
  { key: 'primaryPosition', title: '主岗位', width: 80 },
];

const title = computed(() => `任职管理 - ${employeeTitle(props.employee)}`);
const employmentTableRows = computed(() => rows.value as unknown as UiDataTableRecord[]);
const departmentContext = computed(() =>
  createScopedTreeModuleContext(baseDepartmentContext, {
    scopeFieldName: 'organizationId',
    scopeValue: draft.value.organizationId,
    treePath: '/iam.department/tree',
    sortPath: '/iam.department/sort',
  }),
);
const canBePrimary = computed(() => {
  const employee = props.employee;
  return Boolean(
    employee?.organizationId &&
    employee?.departmentId &&
    employee.organizationId === draft.value.organizationId &&
    employee.departmentId === draft.value.departmentId,
  );
});

watch(
  () => [props.open, props.employee?.id] as const,
  ([open]) => {
    if (open) {
      void loadEmployments();
    } else {
      resetEditor();
    }
  },
  { immediate: true },
);

function createDraft(): Partial<EmployeePosition> {
  return { primaryPosition: false, enabled: true };
}

function employeeTitle(employee: Employee | undefined) {
  return String(employee?.title ?? employee?.employeeNo ?? employee?.id ?? '职员');
}

async function loadEmployments() {
  const employeeId = props.employee?.id;
  if (!employeeId) return;

  loading.value = true;
  loadFailed.value = false;
  try {
    const response = await employeeContext.http.request<{ records: EmploymentSelectorItem[] }>({
      path: `/iam.employee/${encodeURIComponent(employeeId)}/employment-view`,
    });
    rows.value = response.records;
  } catch (cause) {
    loadFailed.value = true;
    presentPlatformError(cause, { source: 'employee-employment', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

function resetEditor() {
  editorOpen.value = false;
  draft.value = createDraft();
}

function startCreate() {
  draft.value = {
    organizationId: props.employee?.organizationId,
    departmentId: props.employee?.departmentId,
    primaryPosition: false,
    enabled: true,
  };
  editorOpen.value = true;
}

function startEdit(row: EmploymentSelectorItem) {
  draft.value = { ...row };
  editorOpen.value = true;
}

function updateOrganization(organizationId: string | undefined) {
  const changed = organizationId !== draft.value.organizationId;
  draft.value = {
    ...draft.value,
    organizationId,
    departmentId: changed ? undefined : draft.value.departmentId,
    primaryPosition: changed ? false : draft.value.primaryPosition,
  };
}

function updateDepartment(departmentId: string | undefined) {
  const keepsPrimary = Boolean(
    draft.value.primaryPosition &&
    props.employee?.organizationId === draft.value.organizationId &&
    props.employee?.departmentId === departmentId,
  );
  draft.value = { ...draft.value, departmentId, primaryPosition: keepsPrimary };
}

function updatePrimary(primaryPosition: boolean) {
  if (primaryPosition && !canBePrimary.value) {
    presentPlatformMessage('主岗必须与职员主机构、主部门一致', {
      source: 'employee-employment',
      phase: 'validation',
    });
    return;
  }
  draft.value = { ...draft.value, primaryPosition };
}

function employmentCellValue(column: UiDataTableColumn, record: UiDataTableRecord) {
  const employment = record as unknown as EmploymentSelectorItem;
  if (column.key === 'position') return employment.positionTitle ?? employment.positionId;
  if (column.key === 'organization') return employment.organizationTitle ?? employment.organizationId;
  if (column.key === 'department') return employment.departmentTitle ?? employment.departmentId;
  if (column.key === 'primaryPosition') return employment.primaryPosition ? '是' : '否';
  return '';
}

function employmentRowMuted(record: UiDataTableRecord) {
  return (record as unknown as EmploymentSelectorItem).enabled === false;
}

function employmentMoreActions(row: EmploymentSelectorItem): UiDropdownItem[] {
  return [
    ...(row.primaryPosition ? [] : [{ key: 'primary', title: '设为主岗' }]),
    { key: row.enabled === false ? 'enable' : 'disable', title: row.enabled === false ? '启用' : '停用' },
    { key: 'delete', title: '删除', danger: true },
  ];
}

function handleEmploymentMoreAction(row: EmploymentSelectorItem, action: string) {
  if (action === 'primary' || action === 'enable' || action === 'disable' || action === 'delete') {
    void runEmploymentAction(row, action);
  }
}

async function saveEmployment() {
  const employeeId = props.employee?.id;
  if (!employeeId || !draft.value.organizationId || !draft.value.departmentId || !draft.value.positionId) {
    presentPlatformMessage('请选择机构、部门和岗位', { source: 'employee-employment', phase: 'validation' });
    return;
  }

  saving.value = true;
  try {
    const employmentId = draft.value.id;
    await employeeContext.http.request({
      method: 'POST',
      path: employmentId
        ? `/iam.employee/${encodeURIComponent(employeeId)}/positions/${encodeURIComponent(employmentId)}/update`
        : `/iam.employee/${encodeURIComponent(employeeId)}/positions`,
      body: { ...draft.value, employeeId },
    });
    resetEditor();
    await loadEmployments();
    emit('saved', employeeId);
    await handlePlatformActionSuccess(
      { success: true, message: '任职已保存' },
      { source: 'employee-employment', phase: 'action' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'employee-employment', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function runEmploymentAction(
  row: EmploymentSelectorItem,
  action: 'delete' | 'enable' | 'disable' | 'primary',
) {
  const employeeId = props.employee?.id;
  if (!employeeId) return;
  if (
    action === 'delete' &&
    !(await confirmAction({ title: '删除任职', content: '确认删除该任职？', okText: '删除', danger: true }))
  ) {
    return;
  }

  saving.value = true;
  try {
    await employeeContext.http.request({
      method: 'POST',
      path: `/iam.employee/${encodeURIComponent(employeeId)}/positions/${encodeURIComponent(row.id)}/${action}`,
    });
    await loadEmployments();
    emit('saved', employeeId);
  } catch (cause) {
    presentPlatformError(cause, { source: 'employee-employment', phase: 'action' });
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <RecordDetailDrawer :open="open" :title="title" :container="container" @close="emit('close')">
    <template #operation>
      <UiButton type="text" icon-name="reload" :disabled="loading || saving" @click="loadEmployments">
        刷新
      </UiButton>
    </template>

    <div class="employment-drawer">
      <div class="employment-drawer-header">
        <div>
          <strong>任职信息</strong>
          <span>角色授权按具体任职生效</span>
        </div>
        <UiButton type="primary" icon-name="plus" :disabled="saving" @click="startCreate">新增任职</UiButton>
      </div>

      <form v-if="editorOpen" class="employment-editor" @submit.prevent="saveEmployment">
        <label>
          <span>机构</span>
          <RecordPicker
            :value="draft.organizationId"
            :context="organizationContext"
            placeholder="请选择机构"
            :constraints="[enabledOnly()]"
            :disabled="saving"
            @update:value="updateOrganization"
          />
        </label>
        <label>
          <span>部门</span>
          <RecordPicker
            :value="draft.departmentId"
            :context="departmentContext"
            placeholder="请选择部门"
            :constraints="[enabledOnly()]"
            :disabled="saving || !draft.organizationId"
            @update:value="updateDepartment"
          />
        </label>
        <label>
          <span>岗位</span>
          <RecordPicker
            :value="draft.positionId"
            :context="positionContext"
            mode="list"
            placeholder="请选择岗位"
            :constraints="[enabledOnly()]"
            :disabled="saving"
            @update:value="draft.positionId = $event ? String($event) : undefined"
          />
        </label>
        <label class="employment-primary-field">
          <span>主岗位</span>
          <span>
            <input
              type="checkbox"
              :checked="draft.primaryPosition === true"
              :disabled="saving || !canBePrimary"
              @change="updatePrimary(($event.target as HTMLInputElement).checked)"
            />
            设为主岗
          </span>
          <small v-if="!canBePrimary">主岗必须与职员主机构、主部门一致</small>
        </label>
        <div class="employment-editor-actions">
          <UiButton :disabled="saving" @click="resetEditor">取消</UiButton>
          <UiButton type="primary" html-type="submit" :loading="saving">保存任职</UiButton>
        </div>
      </form>

      <UiSpin v-if="loading" class="employment-state" tip="加载任职" />
      <div v-else-if="loadFailed" class="employment-state">
        <UiError title="任职信息加载失败" message="无法加载任职信息，请重试" />
        <UiButton type="primary" icon-name="reload" @click="loadEmployments">重试</UiButton>
      </div>
      <p v-else-if="rows.length === 0" class="employment-empty">暂无任职信息</p>
      <UiDataTable
        v-else
        class="employment-table"
        :columns="employmentColumns"
        :rows="employmentTableRows"
        row-key="id"
        :pagination="false"
        horizontal-scroll
        show-action-column
        action-column-width="92"
        :row-muted="employmentRowMuted"
      >
        <template #cell="{ column, record }">
          {{ employmentCellValue(column, record) }}
        </template>
        <template #rowActions="{ record }">
          <div class="employment-table-row-actions" @click.stop @dblclick.stop>
            <UiButton
              type="text"
              :disabled="saving"
              @click="startEdit(record as unknown as EmploymentSelectorItem)"
            >
              编辑
            </UiButton>
            <UiDropdown
              :items="employmentMoreActions(record as unknown as EmploymentSelectorItem)"
              trigger="hover"
              @select="handleEmploymentMoreAction(record as unknown as EmploymentSelectorItem, $event)"
            >
              <UiButton type="text" icon-name="down" title="更多" aria-label="更多" :disabled="saving" />
            </UiDropdown>
          </div>
        </template>
      </UiDataTable>
    </div>
  </RecordDetailDrawer>
</template>

<style scoped>
.employment-drawer {
  display: grid;
  gap: 16px;
}

.employment-drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.employment-drawer-header > div,
.employment-editor label {
  display: grid;
  gap: 6px;
}

.employment-drawer-header span,
.employment-editor label,
.employment-primary-field small,
.employment-empty {
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.employment-editor {
  display: grid;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
}

.employment-primary-field > span:last-of-type {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--muyun-text-body);
}

.employment-editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.employment-state,
.employment-empty {
  display: grid;
  place-items: center;
  gap: 10px;
  min-height: 140px;
  margin: 0;
}

.employment-table {
  min-width: 0;
}

.employment-table-row-actions {
  width: 92px;
  text-align: right;
  white-space: nowrap;
}

.employment-table-row-actions :deep(.ui-dropdown) {
  margin-left: 2px;
}

.employment-table-row-actions :deep(.ant-btn) {
  min-width: 0;
  height: 24px;
  padding: 0 4px;
  font-size: 12px;
}
</style>
