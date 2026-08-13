<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  CrudRecordListExplorer,
  ModuleActionButton,
  parentRecordConstraints,
  RecordActionBar,
  RecordDetailPanel,
  RecordExplorerPanel,
  RecordFormFields,
  RecordMetaSection,
  RecordStatusSwitch,
  TreeRecordExplorer,
  createScopedTreeModuleContext,
  type RecordActionItem,
  type RecordExplorerItemDescriptor,
  type RecordFormFieldFallback,
  type RecordFormFieldPickerConfig,
  type RecordFormRecord,
  type TreeRecordBase,
  presentPlatformError,
  resolveRecordFormFields,
  resolveRecordFormFieldState,
} from '@muyun/platform-components';
import type { Department, Organization, Tenant } from '@muyun/web-contracts';
import { useModuleContext, type ModuleContext } from '@muyun/web-core';
import { confirmAction, UiEmpty, UiInput, type UiRecordInlineAction } from '@muyun/vue-ui-antdv';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import {
  createDepartmentManagementState,
  departmentTitleOf,
  organizationTitleOf,
} from './departmentManagementState';

defineOptions({ name: 'DepartmentManagementView' });

type DepartmentFormFieldName = 'organizationId' | 'parentId' | 'code' | 'title' | 'enabled';
type DepartmentFormPickerFieldName = 'parentId';

const organizationContext = useModuleContext<Organization>({ moduleAlias: 'iam.organization' });
const departmentContext = useModuleContext<Department>({ moduleAlias: 'iam.department' });
const tenantContext = useModuleContext<Tenant>({ moduleAlias: 'iam.tenant' });
const currentUser = useCurrentUserContext();
const departmentFormFieldDefinitions = ref(resolveRecordFormFields(undefined));
const organizationSearchKeyword = ref('');
const departmentSearchKeyword = ref('');
const tenantSearchKeyword = ref('');
const tenantReloadKey = ref(0);
const selectedTenant = ref<Tenant>();
const canBrowseTenants = computed(() => currentUser?.value?.system === true);
const selectedTenantId = computed(() => selectedTenant.value?.id);
const organizationScopeOptions = {
  scopeFieldName: 'tenantId',
  scopeValue: () => selectedTenantId.value,
  treePath: '/iam.organization/tree',
};
const scopedOrganizationContext = createScopedTreeModuleContext(
  organizationContext,
  organizationScopeOptions,
) as ModuleContext<Organization>;
const {
  organizationReloadKey,
  departmentReloadKey,
  selectedOrganization,
  selectedDepartment,
  draft,
  mode,
  saving,
  selectedOrganizationId,
  selectedOrganizationTitle,
  readonly,
  canCreate,
  canToggle,
  cardTitle,
  handleOrganizationsLoaded,
  selectOrganization,
  handleDepartmentsLoaded,
  selectDepartment,
  startCreateRoot,
  startCreateChild,
  startEdit,
  cancelEdit,
  save,
  toggleEnabled,
  removeSelected,
} = createDepartmentManagementState(departmentContext, confirmAction);

const scopedDepartmentContext = computed<ModuleContext<Department>>(() =>
  createScopedTreeModuleContext(departmentContext, {
    scopeFieldName: 'organizationId',
    scopeValue: selectedOrganizationId.value,
    treePath: '/iam.department/tree',
    sortPath: '/iam.department/sort',
  }),
);
const departmentFormPickerConfigs = computed<
  Record<DepartmentFormPickerFieldName, RecordFormFieldPickerConfig>
>(() => ({
  parentId: {
    context: scopedDepartmentContext.value,
    reloadKey: departmentReloadKey.value,
    placeholder: '根部门留空',
    constraints: parentRecordConstraints(draft.value.id),
    titleOf: (record) => departmentTitleOf(record as Department),
  },
}));
const departmentFormDisabled = computed(() => readonly.value || saving.value);

const departmentActions = computed<RecordActionItem[]>(() => {
  if (mode.value !== 'view') {
    return [
      { key: 'cancel', title: '取消', disabled: saving.value },
      {
        key: 'save',
        actionCode: mode.value.startsWith('create') ? 'create' : 'update',
        iconName: 'save',
        title: saving.value ? '保存中' : '保存',
        loading: saving.value,
        primary: true,
      },
    ];
  }
  return [
    { key: 'edit', actionCode: 'update', title: '编辑', disabled: !selectedDepartment.value },
    {
      key: 'create-child',
      actionCode: 'create',
      title: '新建下级',
      disabled: !selectedDepartment.value,
    },
    {
      key: 'delete',
      actionCode: 'delete',
      title: '删除',
      disabled: !selectedDepartment.value,
      loading: saving.value,
      danger: true,
    },
  ];
});

onMounted(() => {
  void loadDepartmentFormDefinition();
  if (!canBrowseTenants.value && currentUser?.value?.tenantId) {
    selectedTenant.value = { id: currentUser.value.tenantId, title: currentUser.value.tenantId } as Tenant;
  }
});

watch(selectedTenantId, () => {
  handleOrganizationsLoaded([]);
  organizationReloadKey.value += 1;
});

async function loadDepartmentFormDefinition() {
  try {
    const runtimeContext = await departmentContext.runtime.ready;
    departmentFormFieldDefinitions.value = resolveRecordFormFields(runtimeContext.uiDescriptor);
  } catch (cause) {
    presentPlatformError(cause, { source: 'department-management', phase: 'load' });
  }
}

function departmentFormField(fieldName: DepartmentFormFieldName) {
  return resolveRecordFormFieldState(fieldName, {
    fields: departmentFormFieldDefinitions.value,
    fallback: departmentFormFieldFallback,
  });
}

function departmentFormLabel(fieldName: DepartmentFormFieldName) {
  return departmentFormField(fieldName).label;
}

function departmentFormRequired(fieldName: DepartmentFormFieldName) {
  return departmentFormField(fieldName).required;
}

function departmentFormVisible(fieldName: DepartmentFormFieldName) {
  return departmentFormField(fieldName).visible;
}

function updateDepartmentDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  draft.value = {
    ...draft.value,
    [fieldName]: value,
  };
}

function departmentTreeActionsOf(record: Department): UiRecordInlineAction[] {
  const actions: UiRecordInlineAction[] = [];
  if (record.id && departmentContext.can('create') === true) {
    actions.push({ key: 'create-child', title: '新增下级', iconName: 'plus' });
  }
  if (record.id && departmentContext.can('update') === true) {
    actions.push({ key: 'edit', title: '编辑部门', iconName: 'edit' });
  }
  if (record.id && departmentContext.can('delete') === true) {
    actions.push({ key: 'delete', title: '删除部门', iconName: 'delete', danger: true });
  }
  return actions;
}

function organizationItemOf(record: TreeRecordBase): RecordExplorerItemDescriptor {
  return {
    title: organizationTitleOf(record as Organization),
    secondary: record.code ?? record.id,
    muted: record.enabled === false,
  };
}

function selectTenant(tenant: Tenant) {
  selectedTenant.value = tenant;
}

function tenantItemOf(record: Tenant): RecordExplorerItemDescriptor {
  return {
    title: record.title ?? record.alias ?? record.id ?? '未命名租户',
    secondary: record.alias ?? record.id,
    muted: record.enabled === false,
  };
}

function departmentItemOf(record: TreeRecordBase): RecordExplorerItemDescriptor {
  const department = record as Department;
  return {
    title: departmentTitleOf(department),
    secondary: department.code ?? department.id,
    muted: department.enabled === false,
    actions: departmentTreeActionsOf(department),
  };
}

function handleDepartmentTreeAction(action: UiRecordInlineAction, record: Department) {
  selectDepartment(record);
  if (action.key === 'create-child') {
    startCreateChild(record);
    return;
  }
  if (action.key === 'edit') {
    startEdit();
    return;
  }
  if (action.key === 'delete') {
    void removeSelected();
  }
}

function handleDepartmentAction(action: RecordActionItem) {
  if (action.key === 'edit') {
    startEdit();
    return;
  }
  if (action.key === 'create-child') {
    startCreateChild();
    return;
  }
  if (action.key === 'delete') {
    void removeSelected();
    return;
  }
  if (action.key === 'cancel') {
    cancelEdit();
    return;
  }
  if (action.key === 'save') {
    void save();
  }
}

const departmentFormFieldFallback: Record<DepartmentFormFieldName, RecordFormFieldFallback> = {
  organizationId: { label: '所属机构', required: true, readOnly: true, visible: true },
  parentId: {
    label: '上级部门',
    required: false,
    readOnly: false,
    visible: true,
    controlType: 'recordPicker',
  },
  code: {
    label: '部门编码',
    required: true,
    readOnly: false,
    visible: true,
    placeholder: '请输入部门编码',
  },
  title: {
    label: '部门名称',
    required: true,
    readOnly: false,
    visible: true,
    placeholder: '请输入部门名称',
  },
  enabled: {
    label: '启用状态',
    required: false,
    readOnly: false,
    visible: true,
    controlType: 'enabledStatus',
  },
};
</script>

<template>
  <section class="department-workspace" :class="{ 'department-workspace--system': canBrowseTenants }">
    <RecordExplorerPanel
      v-if="canBrowseTenants"
      v-model:search-keyword="tenantSearchKeyword"
      class="tenant-column"
      title="租户"
      search-placeholder="搜索租户"
      @refresh="tenantReloadKey += 1"
    >
      <CrudRecordListExplorer
        :context="tenantContext"
        :selected-id="selectedTenant?.id"
        :reload-key="tenantReloadKey"
        :keyword="tenantSearchKeyword"
        empty-description="暂无租户"
        loading-tip="加载租户"
        fallback-title="未命名租户"
        :item-of="(record) => tenantItemOf(record as Tenant)"
        @select="selectTenant($event as Tenant)"
      />
    </RecordExplorerPanel>
    <RecordExplorerPanel
      v-model:search-keyword="organizationSearchKeyword"
      class="organization-column"
      title="机构树"
      search-placeholder="搜索机构名称、编码或 ID"
      @refresh="organizationReloadKey += 1"
    >
      <TreeRecordExplorer
        :context="scopedOrganizationContext"
        :selected-id="selectedOrganization?.id"
        :reload-key="organizationReloadKey"
        :keyword="organizationSearchKeyword"
        search-mode="none"
        search-placeholder="搜索机构名称、编码或 ID"
        :empty-description="selectedTenantId ? '暂无机构' : '请选择租户'"
        :loading-tip="selectedTenantId ? '加载机构树' : '等待选择租户'"
        fallback-title="未命名机构"
        :item-of="organizationItemOf"
        @loaded="handleOrganizationsLoaded"
        @select="selectOrganization"
      />
    </RecordExplorerPanel>

    <RecordExplorerPanel
      v-model:search-keyword="departmentSearchKeyword"
      class="department-column"
      title="部门"
      search-placeholder="搜索部门名称、编码或 ID"
      @refresh="departmentReloadKey += 1"
    >
      <template #actions>
        <ModuleActionButton
          class="record-panel-create-button"
          :context="departmentContext"
          action-code="create"
          title="新增部门"
          icon-only
          :disabled="!selectedOrganization || saving || !canCreate"
          @click="startCreateRoot"
        />
      </template>
      <UiEmpty v-if="!selectedOrganization" description="请选择机构" />
      <TreeRecordExplorer
        v-else
        :context="scopedDepartmentContext"
        :selected-id="selectedDepartment?.id"
        :reload-key="departmentReloadKey"
        :keyword="departmentSearchKeyword"
        search-mode="none"
        search-placeholder="搜索部门名称、编码或 ID"
        empty-description="当前机构暂无部门"
        loading-tip="加载部门树"
        fallback-title="未命名部门"
        :item-of="departmentItemOf"
        @loaded="handleDepartmentsLoaded"
        @select="selectDepartment"
        @action="handleDepartmentTreeAction"
      />
    </RecordExplorerPanel>

    <RecordDetailPanel :title="cardTitle">
      <template #status>
        <RecordStatusSwitch
          v-if="mode === 'view' && selectedDepartment"
          :enabled="selectedDepartment.enabled"
          :disabled="saving || !canToggle"
          :loading="saving"
          :show-label="false"
          @change="toggleEnabled"
        />
      </template>
      <template #actions>
        <RecordActionBar
          :context="departmentContext"
          :actions="departmentActions"
          @action="handleDepartmentAction"
        />
      </template>
      <UiEmpty v-if="!selectedDepartment && mode === 'view'" description="请选择或新建部门" />
      <form v-else class="department-form" @submit.prevent="save">
        <label v-if="departmentFormVisible('organizationId')">
          <span>
            {{ departmentFormLabel('organizationId') }}
            <strong v-if="departmentFormRequired('organizationId')" aria-hidden="true">*</strong>
          </span>
          <UiInput :value="selectedOrganizationTitle" disabled />
        </label>
        <RecordFormFields
          :record="draft as RecordFormRecord"
          :fields="departmentFormFieldDefinitions"
          :exclude-field-names="['organizationId']"
          :fallback="departmentFormFieldFallback"
          :picker-configs="departmentFormPickerConfigs"
          :disabled="departmentFormDisabled"
          @update:field="updateDepartmentDraftField"
        />
      </form>
      <RecordMetaSection v-if="selectedDepartment || mode !== 'view'" :record="draft" show-sort-order />
    </RecordDetailPanel>
  </section>
</template>

<style scoped>
.department-workspace {
  display: grid;
  grid-template-columns: minmax(220px, 240px) minmax(240px, 260px) minmax(340px, 1fr);
  gap: 12px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.department-workspace--system {
  grid-template-columns: minmax(200px, 220px) minmax(220px, 240px) minmax(240px, 260px) minmax(340px, 1fr);
}

.tenant-column,
.organization-column,
.department-column {
  min-height: 0;
}

.record-panel-create-button {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
}

.department-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.department-form label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-body);
  font-size: 13px;
}

@media (max-width: 1180px) {
  .department-form {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 980px) {
  .department-workspace {
    grid-template-columns: 1fr;
    height: auto;
    overflow: visible;
  }
}
</style>
