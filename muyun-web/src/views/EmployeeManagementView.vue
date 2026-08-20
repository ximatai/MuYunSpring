<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, type ComponentPublicInstance, watch } from 'vue';
import {
  ManagementWorkspace,
  ManagementExplorerColumn,
  CrudRecordListExplorer,
  RecordActionBar,
  RecordDetailDrawer,
  RecordDetailPanel,
  RecordExpandedSubtable,
  RecordExplorerPanel,
  RecordQueryListPanel,
  RecordStatusSwitch,
  TreeRecordExplorer,
  createSoftDeletedConflictErrorHandler,
  createScopedTreeModuleContext,
  type QueryListRecord,
  type RecycleBinExplorerMode,
  type RecordActionItem,
  type RecordExplorerItemDescriptor,
  type RecordFormFieldFallback,
  type RecordFormFieldPickerConfig,
  type RecordPickerRecord,
  type ResolvedRecordActionItem,
  useRecycleBinExplorerMode,
  executeStaticFormSave,
  executeStaticRecordAction,
  handlePlatformActionSuccess,
  normalizeRecordDraft,
  presentPlatformError,
  presentPlatformMessage,
  resolveRecordFormFields,
  resolveRecordFormFieldState,
} from '@muyun/platform-components';
import { UiButton, confirmAction } from '@muyun/vue-ui-antdv';
import type {
  Department,
  Employee,
  EmployeeAccount,
  EmployeeAccountProvisionResponse,
  Organization,
  Tenant,
  UserAccount,
  WebActionResultEnvelope,
  WebQueryRequest,
} from '@muyun/web-contracts';
import { actionResultData, platformErrorCodes, useModuleContext, type ModuleContext } from '@muyun/web-core';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import {
  usePageDataChange,
  usePageRecordExternalChange,
  useRealtimeRefreshQueue,
} from '../platform-admin-runtime/pageRealtime';
import { useWorkspaceViewHost } from '../platform-admin-runtime/workspaceViewHost';
import { useWorkspaceViewPromotion } from '../platform-admin-runtime/useWorkspaceViewPromotion';
import {
  canSwitchEmployeeDetailContext,
  isEmployeeFormDisabled,
  shouldCommitEmployeeDetailRequest,
  shouldCloseEmployeeDetailOnCancel,
  shouldShowEmployeeDetailContent,
  validateEmployeeRequiredFormFields,
  type EmployeeDetailMode,
} from './employeeDetailStateModel';
import EmployeeEmploymentDrawer from './EmployeeEmploymentDrawer.vue';
import EmployeeDetailContent from './EmployeeDetailContent.vue';
import { employeeDetailWorkspaceView } from './employeeDetailWorkspaceView';
import {
  handOffEmployeeDetailWorkspaceSession,
  registerEmployeeDetailWorkspaceHandoffRecipient,
  takeEmployeeDetailWorkspaceSession,
  type EmployeeDetailWorkspaceSession,
} from './employeeDetailWorkspaceSession';
import { useEmployeeEmploymentRows } from './useEmployeeEmploymentRows';

defineOptions({ name: 'EmployeeManagementView' });

const props = defineProps<{
  recordId?: string;
  mode?: 'view' | 'edit';
}>();

type EmployeeFormFieldName =
  'organizationId' | 'departmentId' | 'employeeNo' | 'title' | 'gender' | 'mobile' | 'email' | 'enabled';
type EmployeeFormPickerFieldName = 'departmentId';

const employeeRequiredFormFieldNames = [
  'departmentId',
  'employeeNo',
  'title',
] as const satisfies readonly EmployeeFormFieldName[];
const workspaceViewHost = useWorkspaceViewHost();

const organizationContext = useModuleContext<Organization>({ moduleAlias: 'iam.organization' });
const departmentContext = useModuleContext<Department>({ moduleAlias: 'iam.department' });
const employeeContext = useModuleContext<Employee>({ moduleAlias: 'iam.employee' });
const userContext = useModuleContext<UserAccount>({ moduleAlias: 'iam.user' });
const tenantContext = useModuleContext<Tenant>({ moduleAlias: 'iam.tenant' });
const currentUser = useCurrentUserContext();
const employeeFormFieldDefinitions = ref(resolveRecordFormFields(undefined));
const pageHost = ref<ComponentPublicInstance | null>(null);
const pageRoot = computed(() => (pageHost.value?.$el instanceof HTMLElement ? pageHost.value.$el : null));
const isWorkspaceView = computed(() => Boolean(props.recordId));
const isDrawerWorkspaceView = computed(
  () => isWorkspaceView.value && workspaceViewHost?.presentation === 'drawer',
);
const shouldRenderEmployeeDetailDrawer = computed(
  () => !isWorkspaceView.value || isDrawerWorkspaceView.value,
);
const organizationSearchKeyword = ref('');
const organizationReloadKey = ref(0);
const employeeReloadKey = ref(0);
const tenantSearchKeyword = ref('');
const tenantReloadKey = ref(0);
const selectedTenant = ref<Tenant>();
const selectedOrganization = ref<Organization>();
const selectedEmployeeKey = ref<string>();
const selectedEmployee = ref<Employee>();
const employeeDetailOpen = ref(false);
const employeeDetailMode = ref<EmployeeDetailMode>('view');
const loadingEmployeeDetail = ref(false);
const employeeDetailLoadFailed = ref(false);
const savingEmployee = ref(false);
const employeeDetailRequestSeq = ref(0);
const employeeDraft = ref<Partial<Employee>>(createEmployeeDraft(undefined));
const employeeDetailDepartment = ref<Department>();
const employeeAccount = ref<EmployeeAccount>();
const employeeAccountUser = ref<UserAccount>();
const employeeEmploymentDrawerOpen = ref(false);
const employeeEmploymentDrawerEmployee = ref<Employee>();
const loadingEmployeeAccounts = ref(false);
const savingEmployeeAccount = ref(false);
const employeeAccountsLoadFailed = ref(false);
const showAccountProvisionForm = ref(false);
const accountProvisionDraft = ref<Partial<UserAccount>>(createAccountProvisionDraft(undefined));
const employeeRecycleBinExplorer = useRecycleBinExplorerMode({
  context: () => employeeContext as unknown as ModuleContext<QueryListRecord>,
  listReloadKey: employeeReloadKey,
  canChange: canLeaveEmployeeDetailContext,
  resetSelection: resetEmployeeListSelection,
});
const {
  expandedEmployeeKeys,
  employmentRowState,
  handleEmployeeRowExpand,
  loadEmployeeEmploymentRows,
  resetEmployeeEmploymentRows,
} = useEmployeeEmploymentRows({
  context: employeeContext,
  source: 'employee-management',
  pathOf: (employeeId) =>
    employeeRecycleBinExplorer.active.value
      ? `/iam.employee/recycle-bin/${encodeURIComponent(employeeId)}/employment-view`
      : `/iam.employee/${encodeURIComponent(employeeId)}/employment-view`,
});
const employeeExternalChange = usePageRecordExternalChange({
  moduleAlias: 'iam.employee',
  recordId: () => selectedEmployee.value?.id,
  editing: () => employeeDetailMode.value === 'edit',
  saving: () => savingEmployee.value,
});
const employeeRealtimeRefreshQueue = useRealtimeRefreshQueue<string>({
  delay: 80,
  load: async (run) => {
    employeeReloadKey.value += 1;
    const currentDetailId = selectedEmployee.value?.id;
    if (
      employeeDetailOpen.value &&
      employeeDetailMode.value === 'view' &&
      currentDetailId &&
      run.keys.includes(currentDetailId)
    ) {
      await openEmployeeDetail({ ...employeeDraft.value, id: currentDetailId } as QueryListRecord, 'view');
    }
  },
});
usePageDataChange({
  moduleAlias: 'iam.employee',
  handler: (_changeSet, changes) => {
    const changedRecordIds = changes
      .map((change) => change.recordId)
      .filter((recordId): recordId is string => Boolean(recordId));
    employeeRealtimeRefreshQueue.enqueue(changedRecordIds.length > 0 ? changedRecordIds : '__collection__');
  },
});

const employeeListContext = computed(
  () =>
    createScopedEmployeeModuleContext(
      employeeContext,
      selectedTenant.value,
    ) as ModuleContext<QueryListRecord>,
);
const canBrowseTenants = computed(() => currentUser?.value?.system === true);
const selectedTenantId = computed(() => selectedTenant.value?.id);
const scopedOrganizationContext = computed(
  () =>
    createScopedTreeModuleContext(organizationContext, {
      scopeFieldName: 'tenantId',
      scopeValue: selectedTenantId.value,
      treePath: '/iam.organization/tree',
    }) as ModuleContext<Organization>,
);
const selectedOrganizationId = computed(() => selectedOrganization.value?.id);
const scopedDepartmentContext = computed(() =>
  createScopedTreeModuleContext(departmentContext, {
    scopeFieldName: 'organizationId',
    scopeValue: selectedOrganizationId.value,
    treePath: '/iam.department/tree',
    sortPath: '/iam.department/sort',
  }),
);
const employeeFormPickerConfigs = computed<Record<EmployeeFormPickerFieldName, RecordFormFieldPickerConfig>>(
  () => ({
    departmentId: {
      context: scopedDepartmentContext.value as unknown as ModuleContext<RecordPickerRecord>,
      reloadKey: organizationReloadKey.value,
      placeholder: '请选择部门',
      titleOf: (record) => departmentTitle(record as Department),
    },
  }),
);
const employeeExternalQueryValues = computed<Record<string, unknown> | undefined>(() => {
  const organizationId = selectedOrganizationId.value;
  if (!organizationId) {
    return undefined;
  }
  return {
    departmentScope: {
      organizationId,
      includeChildren: true,
    },
  };
});
const employeeDetailTitle = computed(() => {
  if (employeeDetailMode.value === 'create') {
    return '新建职员';
  }
  return employeePrimaryTitle(selectedEmployee.value ?? employeeDraft.value);
});
const employeeDetailSubtitle = computed(() =>
  employeeDetailMode.value === 'create'
    ? undefined
    : employeeNoSubtitle(selectedEmployee.value ?? employeeDraft.value),
);
const employeeFormDisabled = computed(() =>
  isEmployeeFormDisabled({
    mode: employeeDetailMode.value,
    loadingDetail: loadingEmployeeDetail.value,
    saving: savingEmployee.value,
    selectedEmployeeId: selectedEmployee.value?.id,
  }),
);
const showEmployeeDetailContent = computed(() =>
  shouldShowEmployeeDetailContent({
    mode: employeeDetailMode.value,
    loadingDetail: loadingEmployeeDetail.value,
    loadFailed: employeeDetailLoadFailed.value,
    selectedEmployeeId: selectedEmployee.value?.id,
  }),
);
const canSaveEmployee = computed(() => {
  if (loadingEmployeeDetail.value) {
    return false;
  }
  if (employeeDetailMode.value === 'create') {
    return Boolean(selectedOrganizationId.value) && employeeContext.can('create') === true;
  }
  return Boolean(selectedEmployee.value?.id) && employeeContext.can('update') === true;
});
const canToggleEmployee = computed(() => {
  if (loadingEmployeeDetail.value || !selectedEmployee.value?.id) {
    return false;
  }
  return employeeContext.can(employeeToggleActionCode(selectedEmployee.value)) === true;
});
const canManageEmployeeAccounts = computed(() => {
  if (!selectedEmployee.value?.id || loadingEmployeeDetail.value || savingEmployee.value) {
    return false;
  }
  return employeeContext.can('employeeAccounts', selectedEmployee.value.id) !== false;
});
const employeeDetailActions = computed<RecordActionItem[]>(() => {
  if (employeeDetailMode.value === 'view') {
    if (!selectedEmployee.value?.id) {
      return [];
    }
    return [
      { key: 'edit', actionCode: 'update', title: '编辑', iconName: 'edit', disabled: savingEmployee.value },
      {
        key: 'delete',
        actionCode: 'delete',
        title: '删除',
        iconName: 'delete',
        danger: true,
        disabled: savingEmployee.value,
      },
    ];
  }
  return [
    { key: 'cancel', title: '取消', iconName: 'close', disabled: savingEmployee.value },
    {
      key: 'save',
      actionCode: employeeDetailMode.value === 'create' ? 'create' : 'update',
      title: '保存',
      iconName: 'save',
      primary: true,
      disabled: !canSaveEmployee.value,
      loading: savingEmployee.value,
    },
  ];
});
const employeeDetailOperationActions = computed(() => employeeDetailActions.value);
const employeeWorkspaceOperationActions = computed<RecordActionItem[]>(() => {
  if (employeeDetailMode.value === 'view') {
    return selectedEmployee.value?.id
      ? [
          {
            key: 'edit',
            actionCode: 'update',
            title: '编辑',
            iconName: 'edit',
            disabled: savingEmployee.value,
          },
        ]
      : [];
  }
  return employeeDetailMode.value === 'edit'
    ? [
        { key: 'cancel', title: '取消', iconName: 'close', disabled: savingEmployee.value },
        {
          key: 'save',
          actionCode: 'update',
          title: '保存',
          iconName: 'save',
          primary: true,
          loading: savingEmployee.value,
          disabled: !canSaveEmployee.value,
        },
      ]
    : [];
});
const employeeDetailPromotion = useWorkspaceViewPromotion({
  view: employeeDetailWorkspaceView,
  input: computed(() => {
    const recordId = selectedEmployee.value?.id;
    const mode = employeeDetailMode.value;
    return recordId && (mode === 'view' || mode === 'edit') ? { recordId } : undefined;
  }),
  title: computed(() => employeePrimaryTitle(selectedEmployee.value)),
  eligibility: computed(() => ({
    hasStableIdentity: Boolean(selectedEmployee.value?.id) && !loadingEmployeeDetail.value,
    busy: savingEmployee.value || savingEmployeeAccount.value || loadingEmployeeAccounts.value,
  })),
  beforePromote: async (input) => {
    const selected = selectedEmployee.value;
    if (!selected) return;
    return (
      (await handOffEmployeeDetailWorkspaceSession(input, {
        selectedEmployee: selected,
        draft: employeeDraft.value,
        organization: selectedOrganization.value,
        department: employeeDetailDepartment.value,
        account: employeeAccount.value,
        accountUser: employeeAccountUser.value,
        showAccountProvisionForm: showAccountProvisionForm.value,
        accountProvisionDraft: accountProvisionDraft.value,
        mode: employeeDetailMode.value === 'edit' ? 'edit' : 'view',
      })) === 'accepted'
    );
  },
  onPromoted: closeEmployeeDetail,
});

watch(
  selectedEmployee,
  (employee) => {
    if (!isWorkspaceView.value || !employee) return;
    workspaceViewHost?.setTitle(employeePrimaryTitle(employee));
  },
  { immediate: true },
);

let disposeEmployeeWorkspaceHandoffRecipient: (() => void) | undefined;

onMounted(() => {
  void loadEmployeeFormDefinition();
  if (!canBrowseTenants.value && currentUser?.value?.tenantId) {
    selectedTenant.value = { id: currentUser.value.tenantId, title: currentUser.value.tenantId } as Tenant;
  }
  if (!props.recordId) return;
  const input = { recordId: props.recordId } as const;
  if (!isDrawerWorkspaceView.value) {
    disposeEmployeeWorkspaceHandoffRecipient = registerEmployeeDetailWorkspaceHandoffRecipient(
      input,
      receiveEmployeeDetailWorkspaceSession,
    );
  }
  const session = takeEmployeeDetailWorkspaceSession(input);
  if (session) {
    restoreEmployeeDetailWorkspaceSession(session);
    return;
  }
  void openEmployeeDetail({ id: props.recordId }, props.mode ?? 'view');
});

watch(selectedTenantId, () => {
  selectedOrganization.value = undefined;
  resetEmployeeListSelection();
  organizationReloadKey.value += 1;
});

onBeforeUnmount(() => disposeEmployeeWorkspaceHandoffRecipient?.());

function receiveEmployeeDetailWorkspaceSession(session: EmployeeDetailWorkspaceSession) {
  if (employeeDetailMode.value !== 'view' || showAccountProvisionForm.value) return false;
  restoreEmployeeDetailWorkspaceSession(session);
  return true;
}

async function loadEmployeeFormDefinition() {
  try {
    const runtimeContext = await employeeContext.runtime.ready;
    employeeFormFieldDefinitions.value = resolveRecordFormFields(runtimeContext.uiDescriptor);
  } catch (cause) {
    presentPlatformError(cause, { source: 'employee-management', phase: 'load' });
  }
}

function employeeFormField(fieldName: EmployeeFormFieldName) {
  return resolveRecordFormFieldState(fieldName, {
    fields: employeeFormFieldDefinitions.value,
    fallback: employeeFormFieldFallback,
  });
}

function employeeFormLabel(fieldName: string) {
  return employeeFormField(fieldName as EmployeeFormFieldName).label;
}

function employeeFormRequired(fieldName: string) {
  return employeeFormField(fieldName as EmployeeFormFieldName).required;
}

function employeeFormVisible(fieldName: string) {
  return employeeFormField(fieldName as EmployeeFormFieldName).visible;
}

function canLeaveEmployeeDetailContext() {
  return canSwitchEmployeeDetailContext({ saving: savingEmployee.value });
}

function updateEmployeeDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  employeeDraft.value = {
    ...employeeDraft.value,
    [fieldName]: value,
  };
}

function updateAccountProvisionField(fieldName: 'username' | 'password', value: string) {
  accountProvisionDraft.value = { ...accountProvisionDraft.value, [fieldName]: value };
}

function employeeDetailDisplayValue(
  fieldName: string,
  value: unknown,
): string | number | boolean | undefined | null {
  if (fieldName === 'organizationId') {
    return selectedOrganization.value?.title ?? selectedOrganization.value?.id ?? String(value ?? '');
  }
  if (fieldName === 'departmentId') {
    const department = employeeDetailDepartment.value;
    if (department && department.id === value) {
      return departmentTitle(department);
    }
    return undefined;
  }
  return undefined;
}

function handleOrganizationsLoaded(records: Organization[]) {
  if (!selectedTenantId.value) return;
  if (!selectedOrganization.value && records.length > 0) {
    selectedOrganization.value = records[0];
  }
}

function selectTenant(tenant: Tenant) {
  if (!canLeaveEmployeeDetailContext()) return;
  selectedTenant.value = tenant;
}

function tenantItemOf(record: Tenant): RecordExplorerItemDescriptor {
  return {
    title: record.title ?? record.alias ?? record.id ?? '未命名租户',
    secondary: record.alias ?? record.id,
    muted: record.enabled === false,
  };
}

function createScopedEmployeeModuleContext(
  context: ModuleContext<Employee>,
  tenant: Tenant | undefined,
): ModuleContext<Employee> {
  return {
    ...context,
    crud: {
      ...context.crud,
      query: (request) => context.crud.query(scopedEmployeeQuery(request, tenant)),
    },
  };
}

function scopedEmployeeQuery(
  request: WebQueryRequest | undefined,
  tenant: Tenant | undefined,
): WebQueryRequest {
  const conditions = [...(request?.conditions ?? [])];
  if (tenant?.id) {
    conditions.push({ fieldName: 'tenantId', operator: 'EQ', values: [tenant.id] });
  }
  return { ...request, conditions };
}

function selectOrganization(record: Organization) {
  if (!canLeaveEmployeeDetailContext()) {
    return;
  }
  selectedOrganization.value = record;
  selectedEmployeeKey.value = undefined;
  selectedEmployee.value = undefined;
  employeeExternalChange.clearExternalChanged();
  loadingEmployeeDetail.value = false;
  employeeDetailLoadFailed.value = false;
  employeeDetailDepartment.value = undefined;
  resetEmployeeAccountState();
  resetEmployeeEmploymentRows();
  closeEmployeeDetail();
}

function refreshOrganizations() {
  organizationReloadKey.value += 1;
}

function selectEmployee(record: QueryListRecord) {
  if (!canLeaveEmployeeDetailContext()) {
    return;
  }
  const nextKey = String(record.id ?? '');
  const currentDetailId = String(selectedEmployee.value?.id ?? employeeDraft.value.id ?? '');
  selectedEmployeeKey.value = nextKey;
  if (employeeDetailOpen.value && currentDetailId !== nextKey) {
    employeeDetailRequestSeq.value += 1;
    loadingEmployeeDetail.value = false;
    employeeDetailLoadFailed.value = false;
    employeeDetailDepartment.value = undefined;
    resetEmployeeAccountState();
    selectedEmployee.value = undefined;
    employeeDraft.value = createEmployeeDraft(selectedOrganizationId.value);
    employeeExternalChange.clearExternalChanged();
    employeeDetailOpen.value = false;
    employeeDetailMode.value = 'view';
  }
}

function handleEmployeeListAction(action: RecordActionItem) {
  if (!canLeaveEmployeeDetailContext()) {
    return;
  }
  if (action.key === 'create') {
    startCreateEmployee();
  }
}

function changeEmployeeListMode(mode: RecycleBinExplorerMode) {
  if (mode === 'recycleBin') employeeRecycleBinExplorer.enter();
  else employeeRecycleBinExplorer.leave();
}

function resetEmployeeListSelection() {
  resetEmployeeEmploymentRows();
  selectedEmployeeKey.value = undefined;
  selectedEmployee.value = undefined;
  closeEmployeeDetail();
}

function handleEmployeeRestored() {
  employeeReloadKey.value += 1;
}

function handleEmployeeRowAction(action: ResolvedRecordActionItem, record: QueryListRecord) {
  if (!canLeaveEmployeeDetailContext()) {
    return;
  }
  if (action.key === 'employment') {
    void openEmployeeEmploymentDrawer(record);
    return;
  }
  if (action.key === 'view') {
    void openEmployeeDetail(record, 'view');
    return;
  }
  if (action.key === 'edit') {
    void openEmployeeDetail(record, 'edit');
    return;
  }
  if (action.key === 'delete') {
    void removeEmployee(record);
  }
}

function employeeExtraRowActionsOf(record: QueryListRecord): RecordActionItem[] {
  return [
    {
      key: 'employment',
      actionCode: 'employeePositions',
      title: '任职',
      after: 'edit',
      visible: Boolean(record.id) && employeeContext.can('employeePositions', String(record.id)) !== false,
    },
  ];
}

async function openEmployeeEmploymentDrawer(record: QueryListRecord) {
  const employeeId = String(record.id ?? '');
  if (!employeeId) return;
  try {
    employeeEmploymentDrawerEmployee.value = await employeeContext.crud.view(employeeId);
    employeeEmploymentDrawerOpen.value = true;
  } catch (cause) {
    presentPlatformError(cause, { source: 'employee-management', phase: 'load' });
  }
}

function closeEmployeeEmploymentDrawer() {
  employeeEmploymentDrawerOpen.value = false;
}

function handleEmployeeEmploymentSaved(employeeId: string) {
  if (expandedEmployeeKeys.value.includes(employeeId)) {
    void loadEmployeeEmploymentRows(employeeId);
  }
}

function handleEmployeeRowDblclick(record: QueryListRecord) {
  if (!canLeaveEmployeeDetailContext()) {
    return;
  }
  void openEmployeeDetail(record, 'view');
}

function startCreateEmployee() {
  if (!canLeaveEmployeeDetailContext()) {
    return;
  }
  if (!selectedOrganizationId.value) {
    presentPlatformMessage('请先选择机构', { phase: 'validation' });
    return;
  }
  employeeDraft.value = createEmployeeDraft(selectedOrganizationId.value);
  selectedEmployee.value = undefined;
  selectedEmployeeKey.value = undefined;
  employeeExternalChange.clearExternalChanged();
  employeeDetailMode.value = 'create';
  loadingEmployeeDetail.value = false;
  employeeDetailLoadFailed.value = false;
  employeeDetailRequestSeq.value += 1;
  employeeDetailDepartment.value = undefined;
  resetEmployeeAccountState();
  employeeDetailOpen.value = true;
}

function closeEmployeeDetail() {
  if (savingEmployee.value) {
    return;
  }
  employeeDetailRequestSeq.value += 1;
  loadingEmployeeDetail.value = false;
  employeeDetailLoadFailed.value = false;
  employeeDetailOpen.value = false;
  employeeDetailMode.value = 'view';
  employeeDetailDepartment.value = undefined;
  employeeExternalChange.clearExternalChanged();
  resetEmployeeAccountState();
  employeeDraft.value = selectedEmployee.value
    ? copyEmployee(selectedEmployee.value)
    : createEmployeeDraft(selectedOrganizationId.value);
  if (isDrawerWorkspaceView.value) {
    workspaceViewHost?.dismiss();
  }
}

function cancelEmployeeDetail() {
  if (savingEmployee.value) {
    return;
  }
  if (
    shouldCloseEmployeeDetailOnCancel({
      mode: employeeDetailMode.value,
      selectedEmployeeId: selectedEmployee.value?.id,
    })
  ) {
    closeEmployeeDetail();
    return;
  }
  employeeDraft.value = copyEmployee(selectedEmployee.value!);
  employeeExternalChange.clearExternalChanged();
  employeeDetailMode.value = 'view';
  loadingEmployeeDetail.value = false;
  employeeDetailLoadFailed.value = false;
}

async function openEmployeeDetail(record: QueryListRecord, mode: EmployeeDetailMode) {
  if (!canLeaveEmployeeDetailContext()) {
    return;
  }
  const id = String(record.id ?? '');
  if (!id) {
    return;
  }
  selectedEmployeeKey.value = id;
  employeeExternalChange.clearExternalChanged();
  employeeDetailOpen.value = true;
  employeeDetailMode.value = mode;
  selectedEmployee.value = undefined;
  employeeDraft.value = copyEmployee(record as Employee);
  employeeDetailDepartment.value = undefined;
  resetEmployeeAccountState();
  loadingEmployeeDetail.value = true;
  employeeDetailLoadFailed.value = false;
  const requestSeq = employeeDetailRequestSeq.value + 1;
  employeeDetailRequestSeq.value = requestSeq;
  const canCommitRequest = () =>
    shouldCommitEmployeeDetailRequest({
      activeRequestSeq: employeeDetailRequestSeq.value,
      requestSeq,
      selectedEmployeeKey: selectedEmployeeKey.value,
      recordId: id,
    });
  try {
    const fullRecord = await employeeContext.crud.view(id);
    if (!canCommitRequest()) {
      return;
    }
    if (!fullRecord?.id) {
      employeeDetailLoadFailed.value = true;
      presentPlatformMessage('未找到指定职员', { source: 'employee-management', phase: 'load' });
      return;
    }
    selectedEmployee.value = fullRecord;
    employeeDraft.value = copyEmployee(fullRecord);
    employeeDetailLoadFailed.value = false;
    await loadEmployeeDetailOrganization(fullRecord, requestSeq);
    await loadEmployeeDetailDepartment(fullRecord, requestSeq);
    void loadEmployeeAccounts(fullRecord, requestSeq);
  } catch (cause) {
    if (canCommitRequest()) {
      employeeDetailLoadFailed.value = true;
      presentPlatformError(cause, { source: 'employee-management', phase: 'load' });
    }
  } finally {
    if (canCommitRequest()) {
      loadingEmployeeDetail.value = false;
    }
  }
}

function handleEmployeeDetailAction(action: RecordActionItem) {
  if (action.key === 'cancel') {
    cancelEmployeeDetail();
    return;
  }
  if (action.key === 'save') {
    void saveEmployee();
    return;
  }
  if (!canLeaveEmployeeDetailContext()) {
    return;
  }
  if (action.key === 'edit') {
    if (!selectedEmployee.value || loadingEmployeeDetail.value) {
      return;
    }
    employeeDraft.value = copyEmployee(selectedEmployee.value);
    employeeExternalChange.clearExternalChanged();
    employeeDetailMode.value = 'edit';
    return;
  }
  if (action.key === 'delete') {
    void removeEmployee(selectedEmployee.value);
  }
}

function retryEmployeeDetail() {
  if (!canLeaveEmployeeDetailContext()) {
    return;
  }
  const id = String(employeeDraft.value.id ?? selectedEmployeeKey.value ?? '');
  if (!id) {
    return;
  }
  const mode = employeeDetailMode.value === 'create' ? 'view' : employeeDetailMode.value;
  void openEmployeeDetail({ ...employeeDraft.value, id } as QueryListRecord, mode);
}

function reloadExternalEmployeeChange() {
  const id = String(
    employeeExternalChange.externalChangedRecordId.value ??
      employeeDraft.value.id ??
      selectedEmployeeKey.value ??
      '',
  );
  if (!id) {
    return;
  }
  employeeExternalChange.clearExternalChanged();
  void openEmployeeDetail({ ...employeeDraft.value, id } as QueryListRecord, 'edit');
}

async function saveEmployee() {
  await executeStaticFormSave<Employee>({
    loading: savingEmployee,
    mode: employeeDetailMode.value === 'edit' ? 'edit' : 'create',
    source: 'employee-management',
    validateContext: () => (selectedOrganizationId.value ? undefined : '请先选择机构'),
    canSave: () => canSaveEmployee.value,
    deniedMessage: '当前用户无权保存职员',
    createRecord: () => normalizedEmployeeDraft(employeeDraft.value, selectedOrganizationId.value ?? ''),
    validateRecord: validateEmployeeDraft,
    save: (draft, mode) =>
      mode === 'edit' && selectedEmployee.value?.id
        ? employeeContext.crud.update(selectedEmployee.value.id, draft)
        : employeeContext.crud.insert(draft),
    actionErrorHandlers: [
      createSoftDeletedConflictErrorHandler({
        resourceLabel: '职员',
        onNavigateToRecycleBin: () => changeEmployeeListMode('recycleBin'),
      }),
      {
        code: platformErrorCodes.conflictVersion,
        handle: (_error, { mode, record }) =>
          mode === 'edit' && employeeExternalChange.markExternalRecordChanged(record.id),
      },
    ],
    onSaved: ({ record }) => {
      const requestSeq = commitEmployeeDetailRecord(record);
      employeeReloadKey.value += 1;
      void loadEmployeeDetailDepartment(record, requestSeq);
    },
  });
}

async function toggleEmployeeEnabled() {
  await executeStaticRecordAction({
    loading: savingEmployee,
    source: 'employee-management',
    record: () => (selectedEmployee.value && selectedEmployee.value.id ? selectedEmployee.value : undefined),
    canExecute: () => canToggleEmployee.value,
    deniedMessage: '当前用户无权变更职员启停状态',
    execute: (employee) =>
      employee.enabled === false
        ? employeeContext.crud.enable(employee.id!, { version: employee.version! })
        : employeeContext.crud.disable(employee.id!, { version: employee.version! }),
    onExecuted: async (_, employee) => {
      const refreshed = await employeeContext.crud.view(employee.id!);
      const requestSeq = commitEmployeeDetailRecord(refreshed);
      await loadEmployeeDetailDepartment(refreshed, requestSeq);
      employeeReloadKey.value += 1;
    },
  });
}

async function removeEmployee(record: Partial<Employee> | QueryListRecord | undefined) {
  await executeStaticRecordAction({
    loading: savingEmployee,
    source: 'employee-management',
    record: () => (record?.id ? record : undefined),
    canExecute: () => employeeContext.can('delete') === true,
    deniedMessage: '当前用户无权删除职员',
    confirm: (target) =>
      confirmAction({
        title: '删除职员',
        content: `确认删除职员「${employeeTitle(target)}」？`,
        okText: '删除',
        danger: true,
      }),
    execute: (target) =>
      employeeContext.crud.delete(String(target.id), { version: (target as { version: number }).version }),
    onExecuted: (_, target) => {
      const id = String(target.id);
      if (selectedEmployeeKey.value === id) {
        selectedEmployeeKey.value = undefined;
        selectedEmployee.value = undefined;
        employeeDraft.value = createEmployeeDraft(selectedOrganizationId.value);
        employeeExternalChange.clearExternalChanged();
        loadingEmployeeDetail.value = false;
        employeeDetailLoadFailed.value = false;
        employeeDetailRequestSeq.value += 1;
        employeeDetailDepartment.value = undefined;
        resetEmployeeAccountState();
        employeeDetailOpen.value = false;
        employeeDetailMode.value = 'view';
      }
      employeeReloadKey.value += 1;
    },
  });
}

function createEmployeeDraft(organizationId: string | undefined): Partial<Employee> {
  return {
    organizationId,
    enabled: true,
    sortOrder: 100,
  };
}

function createAccountProvisionDraft(employee: Partial<Employee> | undefined): Partial<UserAccount> {
  return {
    username: defaultAccountUsername(employee),
    password: '',
    enabled: true,
  };
}

function copyEmployee(record: Partial<Employee>): Partial<Employee> {
  return { ...record };
}

function normalizedEmployeeDraft(draft: Partial<Employee>, organizationId: string): Employee {
  return normalizeRecordDraft<Employee>(draft, {
    organizationId,
    departmentId: draft.departmentId?.trim(),
    employeeNo: draft.employeeNo?.trim(),
    title: draft.title?.trim(),
    gender: draft.gender?.trim() || undefined,
    mobile: draft.mobile?.trim() || undefined,
    email: draft.email?.trim() || undefined,
    enabled: draft.enabled !== false,
  });
}

function validateEmployeeDraft(draft: Employee) {
  return validateEmployeeRequiredFormFields(
    employeeRequiredFormFieldNames.map((fieldName) => {
      const field = employeeFormField(fieldName);
      return {
        fieldName,
        label: field.label,
        required: field.required,
        visible: field.visible,
        value: draft[fieldName],
      };
    }),
  );
}

function commitEmployeeDetailRecord(record: Employee) {
  selectedEmployee.value = record;
  employeeDraft.value = copyEmployee(record);
  selectedEmployeeKey.value = record.id;
  employeeDetailMode.value = 'view';
  employeeExternalChange.clearExternalChanged();
  employeeDetailOpen.value = true;
  loadingEmployeeDetail.value = false;
  employeeDetailLoadFailed.value = false;
  const requestSeq = employeeDetailRequestSeq.value + 1;
  employeeDetailRequestSeq.value = requestSeq;
  return requestSeq;
}

function restoreEmployeeDetailWorkspaceSession(session: EmployeeDetailWorkspaceSession) {
  selectedEmployee.value = session.selectedEmployee;
  selectedEmployeeKey.value = session.selectedEmployee.id;
  selectedOrganization.value = session.organization;
  employeeDetailDepartment.value = session.department;
  employeeDraft.value = session.draft;
  employeeDetailMode.value = session.mode;
  employeeAccount.value = session.account;
  employeeAccountUser.value = session.accountUser;
  showAccountProvisionForm.value = session.showAccountProvisionForm;
  accountProvisionDraft.value = session.accountProvisionDraft;
  employeeDetailOpen.value = true;
  loadingEmployeeDetail.value = false;
  employeeDetailLoadFailed.value = false;
  employeeAccountsLoadFailed.value = false;
  employeeDetailRequestSeq.value += 1;
}

function canCommitEmployeeDetailSideEffect(recordId: string | undefined, requestSeq: number) {
  return (
    Boolean(recordId) &&
    shouldCommitEmployeeDetailRequest({
      activeRequestSeq: employeeDetailRequestSeq.value,
      requestSeq,
      selectedEmployeeKey: selectedEmployeeKey.value,
      recordId: recordId ?? '',
    })
  );
}

async function loadEmployeeDetailDepartment(
  record: Partial<Employee>,
  requestSeq = employeeDetailRequestSeq.value,
) {
  employeeDetailDepartment.value = undefined;
  const employeeId = record.id;
  const departmentId = record.departmentId;
  if (!departmentId) {
    return;
  }
  try {
    const department = await departmentContext.crud.view(departmentId);
    if (canCommitEmployeeDetailSideEffect(employeeId, requestSeq)) {
      employeeDetailDepartment.value = department;
    }
  } catch (cause) {
    if (canCommitEmployeeDetailSideEffect(employeeId, requestSeq)) {
      presentPlatformError(cause, { source: 'employee-management', phase: 'load' });
    }
  }
}

async function loadEmployeeDetailOrganization(
  record: Partial<Employee>,
  requestSeq = employeeDetailRequestSeq.value,
) {
  const employeeId = record.id;
  const organizationId = record.organizationId;
  if (!organizationId) return;
  try {
    const organization = await organizationContext.crud.view(organizationId);
    if (canCommitEmployeeDetailSideEffect(employeeId, requestSeq)) {
      selectedOrganization.value = organization;
    }
  } catch (cause) {
    if (canCommitEmployeeDetailSideEffect(employeeId, requestSeq)) {
      presentPlatformError(cause, { source: 'employee-management', phase: 'load' });
    }
  }
}

async function loadEmployeeAccounts(
  record: Partial<Employee> = selectedEmployee.value ?? employeeDraft.value,
  requestSeq = employeeDetailRequestSeq.value,
) {
  const employeeId = record.id;
  if (!employeeId) {
    resetEmployeeAccountState();
    return;
  }
  loadingEmployeeAccounts.value = true;
  employeeAccountsLoadFailed.value = false;
  try {
    const binding = await employeeContext.http.request<EmployeeAccount | undefined>({
      path: `/iam.employee/${encodeURIComponent(employeeId)}/account`,
    });
    if (!canCommitEmployeeDetailSideEffect(employeeId, requestSeq)) {
      return;
    }
    employeeAccount.value = binding;
    await loadEmployeeAccountUser(binding, employeeId, requestSeq);
  } catch (cause) {
    if (canCommitEmployeeDetailSideEffect(employeeId, requestSeq)) {
      employeeAccountsLoadFailed.value = true;
      presentPlatformError(cause, { source: 'employee-management', phase: 'load' });
    }
  } finally {
    if (canCommitEmployeeDetailSideEffect(employeeId, requestSeq)) {
      loadingEmployeeAccounts.value = false;
    }
  }
}

async function loadEmployeeAccountUser(
  binding: EmployeeAccount | undefined,
  employeeId: string,
  requestSeq: number,
) {
  const userId = binding?.userId;
  if (!userId) {
    if (canCommitEmployeeDetailSideEffect(employeeId, requestSeq)) {
      employeeAccountUser.value = undefined;
    }
    return;
  }
  let user: UserAccount;
  try {
    user = await userContext.crud.view(userId);
  } catch (cause) {
    presentPlatformError(cause, { source: 'employee-management', phase: 'load' });
    user = { id: userId, username: userId } as UserAccount;
  }
  if (canCommitEmployeeDetailSideEffect(employeeId, requestSeq)) {
    employeeAccountUser.value = user;
  }
}

async function provisionEmployeeAccount() {
  const employee = selectedEmployee.value;
  const draft = normalizedAccountProvisionDraft(accountProvisionDraft.value);
  if (!employee?.id || !canManageEmployeeAccounts.value) {
    return;
  }
  const validationError = validateAccountProvisionDraft(draft);
  if (validationError) {
    presentPlatformMessage(validationError, { source: 'employee-management', phase: 'validation' });
    return;
  }
  savingEmployeeAccount.value = true;
  try {
    const result = await employeeContext.http.request<
      WebActionResultEnvelope<EmployeeAccountProvisionResponse>
    >({
      method: 'POST',
      path: `/iam.employee/${encodeURIComponent(employee.id)}/account/provision`,
      body: draft,
    });
    const response = actionResultData(result);
    employeeAccount.value = response.binding;
    employeeAccountUser.value = response.user;
    showAccountProvisionForm.value = false;
    accountProvisionDraft.value = createAccountProvisionDraft(employee);
    await handlePlatformActionSuccess(result, {
      source: 'employee-management',
      phase: 'action',
    });
  } catch (cause) {
    presentPlatformError(cause, { source: 'employee-management', phase: 'action' });
  } finally {
    savingEmployeeAccount.value = false;
  }
}

async function removeEmployeeAccount() {
  const employee = selectedEmployee.value;
  if (!employee?.id || !employeeAccount.value?.id || !canManageEmployeeAccounts.value) {
    return;
  }
  const confirmed = await confirmAction({
    title: '移除账户',
    content: `确认移除账户「${employeeAccountUserTitle()}」？该用户账号会同步删除。`,
    okText: '移除',
    danger: true,
  });
  if (!confirmed) {
    return;
  }
  savingEmployeeAccount.value = true;
  try {
    const result = await employeeContext.http.request<WebActionResultEnvelope<number>>({
      method: 'POST',
      path: `/iam.employee/${encodeURIComponent(employee.id)}/account/delete`,
    });
    await loadEmployeeAccounts(employee, employeeDetailRequestSeq.value);
    await handlePlatformActionSuccess(result, {
      source: 'employee-management',
      phase: 'action',
    });
  } catch (cause) {
    presentPlatformError(cause, { source: 'employee-management', phase: 'action' });
  } finally {
    savingEmployeeAccount.value = false;
  }
}

function resetEmployeeAccountState() {
  employeeAccount.value = undefined;
  employeeAccountUser.value = undefined;
  loadingEmployeeAccounts.value = false;
  savingEmployeeAccount.value = false;
  employeeAccountsLoadFailed.value = false;
  showAccountProvisionForm.value = false;
  accountProvisionDraft.value = createAccountProvisionDraft(selectedEmployee.value ?? employeeDraft.value);
}

function employeeTitle(record: Partial<Employee> | QueryListRecord | undefined) {
  return String(record?.title ?? record?.employeeNo ?? record?.id ?? '职员档案');
}

function employeePrimaryTitle(record: Partial<Employee> | QueryListRecord | undefined) {
  return employeeTitle(record);
}

function employeeNoSubtitle(record: Partial<Employee> | QueryListRecord | undefined) {
  const employeeNo = String(record?.employeeNo ?? '').trim();
  return employeeNo ? `工号：${employeeNo}` : undefined;
}

function employeeToggleActionCode(record: Partial<Employee>) {
  return record.enabled === false ? 'enable' : 'disable';
}

function employeeAccountUserTitle() {
  const binding = employeeAccount.value;
  const user = employeeAccountUser.value;
  return String(user?.username ?? binding?.userId ?? '未设置账号');
}

function employeeAccountUserDescription() {
  const user = employeeAccountUser.value;
  return user?.id ? `账号ID ${user.id}` : '-';
}

function employeeAccountStatusTitle() {
  return employeeAccountUser.value?.enabled === false ? '停用' : '启用';
}

function defaultAccountUsername(employee: Partial<Employee> | undefined) {
  return String(employee?.employeeNo ?? employee?.mobile ?? '')
    .trim()
    .toLowerCase();
}

function startAccountProvision() {
  accountProvisionDraft.value = createAccountProvisionDraft(selectedEmployee.value ?? employeeDraft.value);
  showAccountProvisionForm.value = true;
}

function cancelAccountProvision() {
  showAccountProvisionForm.value = false;
  accountProvisionDraft.value = createAccountProvisionDraft(selectedEmployee.value ?? employeeDraft.value);
}

function normalizedAccountProvisionDraft(draft: Partial<UserAccount>): UserAccount {
  return {
    ...draft,
    username: draft.username?.trim(),
    password: draft.password?.trim(),
    enabled: true,
  } as UserAccount;
}

function validateAccountProvisionDraft(draft: Partial<UserAccount>) {
  if (!draft.username) {
    return '请输入账号';
  }
  if (!draft.password) {
    return '请输入初始密码';
  }
  return undefined;
}

function departmentTitle(record: Department) {
  return record.title ?? record.code ?? record.id ?? '未命名部门';
}

function organizationItemOf(record: Organization): RecordExplorerItemDescriptor {
  return {
    title: record.title ?? record.code ?? record.id ?? '未命名机构',
    secondary: record.code ?? record.id,
    muted: record.enabled === false,
  };
}

const employeeFormFieldFallback: Record<EmployeeFormFieldName, RecordFormFieldFallback> = {
  organizationId: { label: '所属机构', required: true, readOnly: true, visible: true },
  departmentId: {
    label: '所属部门',
    required: true,
    readOnly: false,
    visible: true,
    controlType: 'recordPicker',
  },
  employeeNo: {
    label: '职员编号',
    required: true,
    readOnly: false,
    visible: true,
    placeholder: '请输入职员编号',
  },
  title: {
    label: '职员姓名',
    required: true,
    readOnly: false,
    visible: true,
    placeholder: '请输入职员姓名',
  },
  gender: { label: '性别', required: false, readOnly: false, visible: true, placeholder: '请输入性别' },
  mobile: {
    label: '手机号',
    required: false,
    readOnly: false,
    visible: true,
    placeholder: '请输入手机号',
  },
  email: { label: '邮箱', required: false, readOnly: false, visible: true, placeholder: '请输入邮箱' },
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
  <ManagementWorkspace
    v-if="!isWorkspaceView || isDrawerWorkspaceView"
    ref="pageHost"
    class="employee-management-page"
    :explorer-count="canBrowseTenants ? 2 : 1"
  >
    <ManagementExplorerColumn v-if="canBrowseTenants">
      <RecordExplorerPanel
        v-model:search-keyword="tenantSearchKeyword"
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
    </ManagementExplorerColumn>
    <ManagementExplorerColumn>
      <RecordExplorerPanel
        class="employee-scope-panel"
        title="机构树"
        refresh-title="刷新机构树"
        :search-keyword="organizationSearchKeyword"
        search-placeholder="搜索机构名称、编码或 ID"
        @refresh="refreshOrganizations"
        @update:search-keyword="organizationSearchKeyword = $event"
      >
        <TreeRecordExplorer
          :context="scopedOrganizationContext"
          :selected-id="selectedOrganization?.id"
          :reload-key="organizationReloadKey"
          :keyword="organizationSearchKeyword"
          search-mode="none"
          search-trigger="external"
          :empty-description="selectedTenantId ? '暂无机构' : '请选择租户'"
          :loading-tip="selectedTenantId ? '加载机构树' : '等待选择租户'"
          fallback-title="未命名机构"
          :item-of="(record) => organizationItemOf(record as Organization)"
          @loaded="handleOrganizationsLoaded"
          @select="selectOrganization"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <RecordQueryListPanel
      class="employee-list-panel"
      :context="employeeListContext"
      :title="employeeRecycleBinExplorer.active.value ? '职员回收站' : '职员列表'"
      :mode="employeeRecycleBinExplorer.mode.value"
      standard-crud-actions
      standard-crud-row-actions
      :extra-row-actions-of="employeeExtraRowActionsOf"
      :selected-key="selectedEmployeeKey"
      :expanded-row-keys="expandedEmployeeKeys"
      :reload-key="employeeReloadKey"
      :ready="Boolean(selectedOrganization?.id)"
      :external-query-values="employeeExternalQueryValues"
      quick-search-placeholder="搜索编号、姓名、手机号或邮箱"
      empty-description="当前机构暂无职员"
      waiting-description="请选择机构"
      @action="handleEmployeeListAction"
      @row-action="handleEmployeeRowAction"
      @row-dblclick="handleEmployeeRowDblclick"
      @row-expand="handleEmployeeRowExpand"
      @select="selectEmployee"
      @mode-change="changeEmployeeListMode"
      @restored="handleEmployeeRestored"
    >
      <template #expandedRow="{ record }">
        <RecordExpandedSubtable
          title="任职信息"
          :loading="employmentRowState(String(record.id ?? '')).loading"
          :error="employmentRowState(String(record.id ?? '')).error"
          loading-tip="加载任职信息"
          error-title="任职信息加载失败"
        >
          <template #actions>
            <UiButton
              type="text"
              icon-name="reload"
              :disabled="employmentRowState(String(record.id ?? '')).loading"
              @click="loadEmployeeEmploymentRows(String(record.id ?? ''))"
            >
              刷新
            </UiButton>
          </template>
          <p v-if="employmentRowState(String(record.id ?? '')).records.length === 0">暂无任职信息</p>
          <div v-else class="employee-row-employment-list">
            <header class="employee-row-employment-table-header">
              <span>岗位</span>
              <span>机构</span>
              <span>部门</span>
              <span>主岗位</span>
            </header>
            <article
              v-for="employment in employmentRowState(String(record.id ?? '')).records"
              :key="employment.id"
              :class="{ 'is-disabled': employment.enabled === false }"
            >
              <strong>{{ employment.positionTitle ?? employment.positionId }}</strong>
              <span>{{ employment.organizationTitle ?? employment.organizationId }}</span>
              <span>{{ employment.departmentTitle ?? employment.departmentId }}</span>
              <span>{{ employment.primaryPosition ? '是' : '否' }}</span>
            </article>
          </div>
        </RecordExpandedSubtable>
      </template>
    </RecordQueryListPanel>

    <RecordDetailDrawer
      v-if="shouldRenderEmployeeDetailDrawer"
      :open="employeeDetailOpen"
      :title="employeeDetailTitle"
      :container="pageRoot"
      :subtitle="employeeDetailSubtitle"
      :close-on-outside="employeeDetailMode === 'view'"
      :promotion="employeeDetailPromotion"
      @close="closeEmployeeDetail"
    >
      <template #status>
        <RecordStatusSwitch
          v-if="employeeDetailMode === 'view' && selectedEmployee"
          :enabled="selectedEmployee.enabled !== false"
          :disabled="savingEmployee || !canToggleEmployee"
          :loading="savingEmployee"
          :show-label="false"
          @change="toggleEmployeeEnabled"
        />
      </template>
      <template #operation>
        <RecordActionBar
          :context="employeeListContext"
          :actions="employeeDetailOperationActions"
          @action="handleEmployeeDetailAction"
        />
      </template>

      <EmployeeDetailContent
        :mode="employeeDetailMode"
        :draft="employeeDraft"
        :selected-employee="selectedEmployee"
        :selected-organization="selectedOrganization"
        :detail-department="employeeDetailDepartment"
        :loading="loadingEmployeeDetail"
        :load-failed="employeeDetailLoadFailed"
        :form-disabled="employeeFormDisabled"
        :show-content="showEmployeeDetailContent"
        :fields="employeeFormFieldDefinitions"
        :fallback="employeeFormFieldFallback"
        :picker-configs="employeeFormPickerConfigs"
        :display-of="employeeDetailDisplayValue"
        :visible-of="employeeFormVisible"
        :label-of="employeeFormLabel"
        :required-of="employeeFormRequired"
        :externally-changed="employeeExternalChange.externallyChanged.value"
        :account="employeeAccount"
        :account-user="employeeAccountUser"
        :loading-accounts="loadingEmployeeAccounts"
        :accounts-load-failed="employeeAccountsLoadFailed"
        :saving-account="savingEmployeeAccount"
        :can-manage-accounts="canManageEmployeeAccounts"
        :show-account-provision-form="showAccountProvisionForm"
        :account-provision-draft="accountProvisionDraft"
        :account-user-title="employeeAccountUserTitle()"
        :account-user-description="employeeAccountUserDescription()"
        :account-status-title="employeeAccountStatusTitle()"
        :option-context="employeeContext"
        @retry="retryEmployeeDetail"
        @save="saveEmployee"
        @reload-external="reloadExternalEmployeeChange"
        @dismiss-external="employeeExternalChange.clearExternalChanged"
        @update-field="updateEmployeeDraftField"
        @retry-accounts="loadEmployeeAccounts()"
        @start-account-provision="startAccountProvision"
        @cancel-account-provision="cancelAccountProvision"
        @provision-account="provisionEmployeeAccount"
        @remove-account="removeEmployeeAccount"
        @update-account-provision-field="updateAccountProvisionField"
      />
    </RecordDetailDrawer>

    <EmployeeEmploymentDrawer
      :open="employeeEmploymentDrawerOpen"
      :employee="employeeEmploymentDrawerEmployee"
      :container="pageRoot"
      @close="closeEmployeeEmploymentDrawer"
      @saved="handleEmployeeEmploymentSaved"
    />
  </ManagementWorkspace>

  <RecordDetailPanel v-else :title="employeeDetailTitle" :subtitle="employeeDetailSubtitle">
    <template #status>
      <RecordStatusSwitch
        v-if="employeeDetailMode === 'view' && selectedEmployee"
        :enabled="selectedEmployee.enabled !== false"
        :disabled="savingEmployee || !canToggleEmployee"
        :loading="savingEmployee"
        :show-label="false"
        @change="toggleEmployeeEnabled"
      />
    </template>
    <template #operation>
      <RecordActionBar
        :context="employeeContext"
        :actions="employeeWorkspaceOperationActions"
        :record-id="selectedEmployee?.id"
        @action="handleEmployeeDetailAction"
      />
    </template>
    <EmployeeDetailContent
      :mode="employeeDetailMode"
      :draft="employeeDraft"
      :selected-employee="selectedEmployee"
      :selected-organization="selectedOrganization"
      :detail-department="employeeDetailDepartment"
      :loading="loadingEmployeeDetail"
      :load-failed="employeeDetailLoadFailed"
      :form-disabled="employeeFormDisabled"
      :show-content="showEmployeeDetailContent"
      :fields="employeeFormFieldDefinitions"
      :fallback="employeeFormFieldFallback"
      :picker-configs="employeeFormPickerConfigs"
      :display-of="employeeDetailDisplayValue"
      :visible-of="employeeFormVisible"
      :label-of="employeeFormLabel"
      :required-of="employeeFormRequired"
      :externally-changed="employeeExternalChange.externallyChanged.value"
      :account="employeeAccount"
      :account-user="employeeAccountUser"
      :loading-accounts="loadingEmployeeAccounts"
      :accounts-load-failed="employeeAccountsLoadFailed"
      :saving-account="savingEmployeeAccount"
      :can-manage-accounts="canManageEmployeeAccounts"
      :show-account-provision-form="showAccountProvisionForm"
      :account-provision-draft="accountProvisionDraft"
      :account-user-title="employeeAccountUserTitle()"
      :account-user-description="employeeAccountUserDescription()"
      :account-status-title="employeeAccountStatusTitle()"
      :option-context="employeeContext"
      @retry="retryEmployeeDetail"
      @save="saveEmployee"
      @reload-external="reloadExternalEmployeeChange"
      @dismiss-external="employeeExternalChange.clearExternalChanged"
      @update-field="updateEmployeeDraftField"
      @retry-accounts="loadEmployeeAccounts()"
      @start-account-provision="startAccountProvision"
      @cancel-account-provision="cancelAccountProvision"
      @provision-account="provisionEmployeeAccount"
      @remove-account="removeEmployeeAccount"
      @update-account-provision-field="updateAccountProvisionField"
    />
  </RecordDetailPanel>
</template>

<style scoped>
.employee-management-page {
  --muyun-management-explorer-width: 280px;
  --muyun-management-detail-min-width: 560px;

  position: relative;
}

.employee-scope-panel,
.employee-list-panel {
  min-width: 0;
  min-height: 0;
}

.employee-row-employment-section {
  display: grid;
  gap: 10px;
  padding: 12px 16px 14px 46px;
  border-top: 1px solid var(--muyun-border-subtle);
  background: var(--muyun-hover-subtle);
}

.employee-row-employment-section > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.employee-row-employment-section h3,
.employee-row-employment-section p {
  margin: 0;
}

.employee-row-employment-section h3 {
  color: var(--muyun-text);
  font-size: 13px;
  font-weight: 700;
}

.employee-row-employment-section p {
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.employee-row-employment-state {
  min-height: 56px;
}

.employee-row-employment-list {
  display: grid;
  gap: 0;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
  background: var(--muyun-surface);
}

.employee-row-employment-list article {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(140px, 0.8fr) minmax(140px, 0.8fr) minmax(100px, auto);
  gap: 10px;
  align-items: center;
  padding: 9px 12px;
  border-bottom: 1px solid var(--muyun-border-subtle);
  color: var(--muyun-text-body);
  font-size: 12px;
}

.employee-row-employment-table-header {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(140px, 0.8fr) minmax(140px, 0.8fr) minmax(100px, auto);
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--muyun-border-subtle);
  color: var(--muyun-text-muted);
  font-size: 11px;
  font-weight: 600;
}

.employee-row-employment-list article:last-child {
  border-bottom: 0;
}

.employee-row-employment-list article.is-disabled {
  color: var(--muyun-text-muted);
  background: var(--muyun-hover-subtle);
}

.employee-row-employment-list strong {
  color: var(--muyun-text);
  font-size: 13px;
}
</style>
