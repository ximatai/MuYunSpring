<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  RecordActionBar,
  RecordDetailDrawer,
  RecordDetailPanel,
  RecordDetailFields,
  RecordExplorerPanel,
  RecordFormFields,
  RecordMetaSection,
  RecordQueryListPanel,
  RecordStatusSwitch,
  createScopedTreeModuleContext,
  executeStaticFormSave,
  executeStaticRecordAction,
  normalizeRecordDraft,
  presentPlatformError,
  presentPlatformMessage,
  resolveRecordFormFieldState,
  resolveRecordFormFields,
  type QueryListRecord,
  type RecordActionItem,
  type RecordFormFieldFallback,
  type RecordFormRecord,
  type RecordQueryListColumn,
  type ResolvedRecordActionItem,
} from '@muyun/platform-components';
import {
  UiButton,
  UiError,
  UiInput,
  UiSpin,
  UiTree,
  useTreeData,
  type UiTreeLoadRequest,
  type UiTreeNode,
  confirmAction,
} from '@muyun/vue-ui-antdv';
import type {
  Organization,
  Role,
  RoleAssignmentType,
  RoleKind,
  RoleOwnerScopeType,
  RoleSharePolicy,
  Tenant,
  WebTreeNode,
  WebQueryRequest,
} from '@muyun/web-contracts';
import { useModuleContext, type ModuleContext } from '@muyun/web-core';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import { useWorkspaceViewHost } from '../platform-admin-runtime/workspaceViewHost';
import { useWorkspaceViewPromotion } from '../platform-admin-runtime/useWorkspaceViewPromotion';
import RoleAccountGrantDrawer from './RoleAccountGrantDrawer.vue';
import { roleDetailWorkspaceView } from './roleDetailWorkspaceView';
import {
  handOffRoleDetailWorkspaceSession,
  registerRoleDetailWorkspaceHandoffRecipient,
  takeRoleDetailWorkspaceSession,
  type RoleDetailWorkspaceSession,
} from './roleDetailWorkspaceSession';
import RoleEmploymentGrantDrawer from './RoleEmploymentGrantDrawer.vue';
import RoleGroupMemberSelector from './RoleGroupMemberSelector.vue';
import RoleAuthorizationView from './RoleAuthorizationView.vue';

defineOptions({ name: 'RoleManagementView' });

const props = defineProps<{
  recordId?: string;
  scopeKind?: RoleOwnerScopeType;
  scopeId?: string;
}>();

type RoleDetailMode = 'view' | 'create' | 'edit';
type RoleScopeKind = RoleOwnerScopeType;
type RoleFormFieldName =
  | 'title'
  | 'assignmentType'
  | 'roleKind'
  | 'memberRoleIds'
  | 'ownerScopeType'
  | 'ownerScopeId'
  | 'sharePolicy'
  | 'description'
  | 'enabled'
  | 'sortOrder';

interface RoleScope {
  kind: RoleScopeKind;
  id?: string;
  key: string;
  title: string;
  tenant?: Tenant;
  organization?: Organization;
}

const workspaceViewHost = useWorkspaceViewHost();
const tenantContext = useModuleContext<Tenant>({ moduleAlias: 'iam.tenant' });
const organizationContext = useModuleContext<Organization>({ moduleAlias: 'iam.organization' });
const roleContext = useModuleContext<Role>({ moduleAlias: 'iam.role' });
const currentUser = useCurrentUserContext();
const tenantSearchKeyword = ref('');
const pageRoot = ref<HTMLElement | null>(null);
const roleReloadKey = ref(0);
const selectedTenant = ref<Tenant>();
const selectedScope = ref<RoleScope>();
const selectedRoleKey = ref<string>();
const selectedRole = ref<Role>();
const roleDetailOpen = ref(false);
const roleDetailMode = ref<RoleDetailMode>('view');
const loadingRoleDetail = ref(false);
const roleDetailLoadFailed = ref(false);
const savingRole = ref(false);
const bindingRole = ref<Role>();
const bindingDrawerOpen = ref(false);
const employmentBindingDrawerOpen = ref(false);
const authorizationRole = ref<Role>();
const authorizationDrawerOpen = ref(false);
const roleDetailRequestSeq = ref(0);
const roleDraft = ref<Partial<Role>>(createRoleDraft(undefined));
const roleFormFieldDefinitions = ref(resolveRecordFormFields(undefined));
const memberRoleCandidates = ref<Role[]>([]);

const isWorkspaceView = computed(() => Boolean(props.recordId && props.scopeKind));
const isDrawerWorkspaceView = computed(
  () => isWorkspaceView.value && workspaceViewHost?.presentation === 'drawer',
);
const shouldRenderRoleDetailDrawer = computed(() => !isWorkspaceView.value || isDrawerWorkspaceView.value);
const scopeTreeNodes = ref<UiTreeNode[]>([]);
const scopeBranchData = useTreeData({
  nodes: () => scopeTreeNodes.value,
  loader: () => loadScopeTreeChildren,
  version: () => scopeTreeNodes.value,
});
const scopeBranchStates = scopeBranchData.states;

const scopeTreeExpandedKeys = ref<string[]>([]);
const scopeTreeLoading = ref(false);
const scopeTenants = new Map<string, Tenant>();
const scopeOrganizations = new Map<string, Organization>();
const scopeOrganizationTenantIds = new Map<string, string>();
const visibleScopeTreeNodes = computed(() =>
  filterScopeTreeNodes(scopeBranchData.nodes.value, tenantSearchKeyword.value),
);
const canSelectPlatformScope = computed(() => currentUser?.value?.system === true);
const canBrowseTenants = computed(() => currentUser?.value?.system === true);
const currentUserTenant = computed<Tenant | undefined>(() => {
  const tenantId = currentUser?.value?.tenantId;
  if (currentUser?.value?.system === true || !tenantId) {
    return undefined;
  }
  return {
    id: tenantId,
    title: tenantId,
    alias: tenantId,
    enabled: true,
  } as Tenant;
});
const roleListContext = computed(
  () => createScopedRoleModuleContext(roleContext, selectedScope.value) as ModuleContext<QueryListRecord>,
);
const roleListColumns = computed<RecordQueryListColumn[]>(() => [
  { key: 'title', title: '角色名称', width: '22%' },
  {
    key: 'assignmentType',
    title: '授权层级',
    width: '13%',
    render: (record) =>
      optionTitle(
        record,
        'assignmentType',
        assignmentTypeTitle(record.assignmentType as RoleAssignmentType | undefined),
      ),
  },
  {
    key: 'roleKind',
    title: '角色类型',
    width: '13%',
    render: (record) =>
      optionTitle(record, 'roleKind', roleKindTitle(record.roleKind as RoleKind | undefined)),
  },
  {
    key: 'sharePolicy',
    title: '公开策略',
    width: '15%',
    render: (record) =>
      optionTitle(record, 'sharePolicy', sharePolicyTitle(record.sharePolicy as RoleSharePolicy | undefined)),
  },
  {
    key: 'systemManaged',
    title: '系统托管',
    width: '12%',
    render: (record) => booleanTitle(record.systemManaged),
  },
  { key: 'enabled', title: '状态', type: 'enabledStatus', width: '10%' },
]);
const roleListReady = computed(() => Boolean(selectedScope.value));
const roleDetailTitle = computed(() => {
  if (roleDetailMode.value === 'create') {
    return '新建角色';
  }
  return roleTitle(selectedRole.value ?? roleDraft.value);
});
const roleDetailSubtitle = computed(() => selectedScope.value?.title ?? '角色详情');
const roleFormDisabled = computed(() => savingRole.value || loadingRoleDetail.value);
const canSaveRole = computed(() => {
  if (loadingRoleDetail.value || !selectedScope.value) {
    return false;
  }
  if (roleDetailMode.value === 'create') {
    return roleContext.can('create') === true;
  }
  return (
    Boolean(selectedRole.value?.id) &&
    roleContext.can('update') === true &&
    !selectedRole.value?.systemManaged
  );
});
const canToggleRole = computed(() => {
  const role = selectedRole.value;
  return Boolean(
    role?.id && role.systemManaged !== true && roleContext.can(roleToggleActionCode(role)) === true,
  );
});
const roleDetailActions = computed<RecordActionItem[]>(() => {
  if (roleDetailMode.value === 'view') {
    if (!selectedRole.value?.id) {
      return [];
    }
    return [
      {
        key: 'edit',
        actionCode: 'update',
        title: '编辑',
        iconName: 'edit',
        disabled: savingRole.value || selectedRole.value.systemManaged === true,
      },
      {
        key: 'bind',
        actionCode: roleBindingActionCode(selectedRole.value),
        title: roleBindingTitle(selectedRole.value),
        iconName: 'lock',
        visible: canBindRoleRecord(selectedRole.value),
        disabled: savingRole.value || selectedRole.value.systemManaged === true,
      },
      {
        key: 'delete',
        actionCode: 'delete',
        title: '删除',
        iconName: 'delete',
        danger: true,
        disabled: savingRole.value || selectedRole.value.systemManaged === true,
      },
    ];
  }
  return [
    { key: 'cancel', title: '取消', iconName: 'close', disabled: savingRole.value },
    {
      key: 'save',
      actionCode: roleDetailMode.value === 'create' ? 'create' : 'update',
      title: '保存',
      iconName: 'save',
      primary: true,
      loading: savingRole.value,
      disabled: !canSaveRole.value,
    },
  ];
});
const roleDetailOperationActions = computed(() => roleDetailActions.value);
const roleWorkspaceOperationActions = computed<RecordActionItem[]>(() => roleDetailActions.value);
const roleDetailPromotion = useWorkspaceViewPromotion({
  view: roleDetailWorkspaceView,
  input: computed(() => {
    const recordId = selectedRole.value?.id;
    const scope = selectedScope.value;
    const mode = roleDetailMode.value;
    if (!recordId || !scope || (mode !== 'view' && mode !== 'edit')) return undefined;
    return {
      recordId,
      scopeKind: scope.kind,
      ...(scope.kind === 'platform' ? {} : { scopeId: scope.id }),
    };
  }),
  title: computed(() => roleTitle(selectedRole.value)),
  eligibility: computed(() => ({
    hasStableIdentity: Boolean(selectedRole.value?.id && selectedScope.value) && !loadingRoleDetail.value,
    busy: savingRole.value,
  })),
  beforePromote: async (input) => {
    const selected = selectedRole.value;
    const scope = selectedScope.value;
    if (!selected || !scope) return;
    return (
      (await handOffRoleDetailWorkspaceSession(input, {
        selectedRole: selected,
        draft: roleDraft.value,
        scope,
        mode: roleDetailMode.value === 'edit' ? 'edit' : 'view',
      })) === 'accepted'
    );
  },
  onPromoted: closeRoleDetail,
});
const roleFormFieldFallback = computed<Record<RoleFormFieldName, RecordFormFieldFallback>>(() => ({
  title: { label: '角色名称', required: true, visible: true, placeholder: '请输入角色名称' },
  assignmentType: {
    label: '授权层级',
    required: true,
    visible: true,
    controlType: 'select',
    options: assignmentTypeOptions(roleDraft.value.roleKind),
  },
  roleKind: {
    label: '角色类型',
    required: true,
    visible: true,
    controlType: 'select',
    options: roleKindOptions(roleDraft.value.assignmentType),
  },
  memberRoleIds: {
    label: '成员角色',
    visible: roleDraft.value.roleKind === 'group',
  },
  ownerScopeType: {
    label: '归属范围',
    required: true,
    visible: true,
    readOnly: true,
    controlType: 'select',
    options: [
      { label: '平台', value: 'platform' },
      { label: '租户', value: 'tenant' },
      { label: '机构', value: 'organization' },
    ],
  },
  ownerScopeId: { label: '归属对象', visible: true, readOnly: true },
  sharePolicy: {
    label: '公开策略',
    required: true,
    visible: true,
    controlType: 'select',
    options: sharePolicyOptions(selectedScope.value?.kind ?? roleDraft.value.ownerScopeType),
  },
  description: { label: '说明', visible: true, placeholder: '请输入角色说明' },
  enabled: { label: '启用状态', visible: true, controlType: 'enabledStatus' },
  sortOrder: { label: '排序号', visible: true, placeholder: '请输入排序号' },
}));
const rolePrimaryFormFieldNames: RoleFormFieldName[] = ['title', 'assignmentType', 'roleKind'];
const roleSecondaryFormFieldNames: RoleFormFieldName[] = [
  'ownerScopeType',
  'ownerScopeId',
  'sharePolicy',
  'description',
  'enabled',
  'sortOrder',
];
const roleDetailFieldNames = computed<RoleFormFieldName[]>(() => {
  const names: RoleFormFieldName[] = ['title', 'assignmentType', 'roleKind'];
  if (roleDraft.value.roleKind === 'group') {
    names.push('memberRoleIds');
  }
  names.push('ownerScopeType', 'ownerScopeId', 'sharePolicy', 'description', 'enabled', 'sortOrder');
  return names;
});

let disposeRoleWorkspaceHandoffRecipient: (() => void) | undefined;

onMounted(() => {
  void loadRoleFormDefinition();
  if (!isWorkspaceView.value || isDrawerWorkspaceView.value) {
    void loadScopeTree();
  }
  if (!isWorkspaceView.value || !props.recordId || !props.scopeKind) return;
  const input = roleWorkspaceInput();
  if (input && !isDrawerWorkspaceView.value) {
    disposeRoleWorkspaceHandoffRecipient = registerRoleDetailWorkspaceHandoffRecipient(
      input,
      receiveRoleDetailWorkspaceSession,
    );
  }
  void restoreRoleWorkspaceView();
});

onBeforeUnmount(() => disposeRoleWorkspaceHandoffRecipient?.());

function receiveRoleDetailWorkspaceSession(session: RoleDetailWorkspaceSession) {
  if (roleDetailMode.value === 'edit') return false;
  restoreRoleDetailWorkspaceSession(session);
  return true;
}

watch(
  selectedRole,
  (role) => {
    if (!isWorkspaceView.value || !role) return;
    workspaceViewHost?.setTitle(roleTitle(role));
  },
  { immediate: true },
);

watch(currentUserTenant, initializeTenantUserScope, { immediate: true });

watch(selectedScope, () => {
  if (isWorkspaceView.value) {
    return;
  }
  selectedRoleKey.value = undefined;
  selectedRole.value = undefined;
  roleDraft.value = createRoleDraft(selectedScope.value);
  closeRoleDetail();
  roleReloadKey.value += 1;
  void loadMemberRoleCandidates();
});

async function loadRoleFormDefinition() {
  try {
    const runtimeContext = await roleContext.runtime.ready;
    roleFormFieldDefinitions.value = resolveRecordFormFields(runtimeContext.uiDescriptor);
  } catch (cause) {
    presentPlatformError(cause, { source: 'role-management', phase: 'load' });
  }
}

async function loadMemberRoleCandidates(scope = selectedScope.value) {
  if (!scope) {
    memberRoleCandidates.value = [];
    return;
  }
  try {
    const response = await roleContext.crud.query(
      scopedRoleQuery(
        {
          page: { pageNum: 0, pageSize: 500 },
          conditions: [
            { fieldName: 'assignmentType', operator: 'EQ', values: ['employment'] },
            { fieldName: 'roleKind', operator: 'IN', values: ['standard', 'dataGrant'] },
            { fieldName: 'enabled', operator: 'EQ', values: [true] },
          ],
          sorts: [{ field: 'sortOrder' }, { field: 'title' }],
        },
        scope,
      ),
    );
    memberRoleCandidates.value = response.records;
  } catch (cause) {
    memberRoleCandidates.value = [];
    presentPlatformError(cause, { source: 'role-management', phase: 'load' });
  }
}

function createScopedRoleModuleContext(
  context: ModuleContext<Role>,
  scope: RoleScope | undefined,
): ModuleContext<Role> {
  return {
    ...context,
    crud: {
      ...context.crud,
      query: (request) => {
        if (!scope) {
          return context.crud.query(request);
        }
        return context.crud.query(scopedRoleQuery(request, scope));
      },
    },
  };
}

function scopedRoleQuery(request: WebQueryRequest | undefined, scope: RoleScope): WebQueryRequest {
  const conditions = [
    ...(request?.conditions ?? []),
    { fieldName: 'ownerScopeType', operator: 'EQ', values: [scope.kind] },
  ];
  if (scope.kind !== 'platform' && scope.id) {
    conditions.push({ fieldName: 'ownerScopeId', operator: 'EQ', values: [scope.id] });
  }
  return { ...request, conditions };
}

function roleFormFieldDisabled(fieldName: string) {
  if (fieldName === 'ownerScopeType' || fieldName === 'ownerScopeId') {
    return true;
  }
  if (roleDetailMode.value === 'edit' && ['assignmentType', 'roleKind'].includes(fieldName)) {
    return true;
  }
  return selectedRole.value?.systemManaged === true;
}

function updateRoleDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  const next = {
    ...roleDraft.value,
    [fieldName]: value,
  };
  if (fieldName === 'roleKind' && (value === 'group' || value === 'dataGrant')) {
    next.assignmentType = 'employment';
  }
  if (fieldName === 'roleKind' && value !== 'group') {
    next.memberRoleIds = undefined;
  }
  if (fieldName === 'assignmentType' && value === 'account' && roleKindRequiresEmployment(next.roleKind)) {
    next.roleKind = 'standard';
    next.memberRoleIds = undefined;
  }
  roleDraft.value = next;
}

async function loadScopeTree() {
  if (isWorkspaceView.value && !isDrawerWorkspaceView.value) {
    return;
  }
  if (!canBrowseTenants.value) {
    const tenant = currentUserTenant.value;
    if (!tenant?.id) return;
    scopeTenants.set(tenant.id, tenant);
    scopeTreeNodes.value = [tenantTreeNode(tenant)];
    initializeTenantUserScope(tenant);
    return;
  }
  scopeTreeLoading.value = true;
  try {
    await tenantContext.runtime.ready;
    const response = await tenantContext.abilities.crud().query({ page: { pageNum: 1, pageSize: 200 } });
    scopeTenants.clear();
    response.records.forEach((record) => {
      if (record.id) scopeTenants.set(record.id, record);
    });
    scopeTreeNodes.value = response.records.map(tenantTreeNode);
    if (!selectedScope.value && canSelectPlatformScope.value) {
      selectPlatformScope();
    }
  } catch (cause) {
    scopeTreeNodes.value = [];
    presentPlatformError(cause, { source: 'role-management', phase: 'load' });
  } finally {
    scopeTreeLoading.value = false;
  }
}

function tenantTreeNode(tenant: Tenant): UiTreeNode {
  return {
    key: tenantNodeKey(tenant.id),
    title: tenantTitle(tenant),
    secondary: tenant.alias ?? tenant.id,
    muted: tenant.enabled === false,
    isLeaf: false,
  };
}

async function loadScopeTreeChildren(node: UiTreeNode, request: UiTreeLoadRequest) {
  const tenantId = tenantIdFromNodeKey(node.key);
  const tenant = tenantId ? scopeTenants.get(tenantId) : undefined;
  if (!tenantId || !tenant) throw new Error('租户已失效');
  try {
    const scopedContext = createScopedTreeModuleContext(organizationContext, {
      scopeFieldName: 'tenantId',
      scopeValue: tenantId,
      treePath: '/iam.organization/tree',
    });
    await scopedContext.runtime.ready;
    const response = await scopedContext.abilities.tree().tree();
    request.signal.throwIfAborted();
    const children = [
      tenantRootTreeNode(tenant),
      ...response.records.map((record) => organizationTreeNode(record, tenantId)),
    ];
    return { mode: 'replace' as const, nodes: children, hasMore: false };
  } catch (cause) {
    if (!request.signal.aborted) presentPlatformError(cause, { source: 'role-management', phase: 'load' });
    throw cause;
  }
}

function tenantRootTreeNode(tenant: Tenant): UiTreeNode {
  return {
    key: tenantRootNodeKey(tenant.id),
    title: tenantTitle(tenant),
    secondary: '租户本级角色',
    isLeaf: true,
  };
}

function organizationTreeNode(node: WebTreeNode<Organization>, tenantId: string): UiTreeNode {
  const record = node.record;
  if (record.id) {
    scopeOrganizations.set(record.id, record);
    scopeOrganizationTenantIds.set(record.id, tenantId);
  }
  return {
    key: organizationNodeKey(record.id),
    title: organizationTitle(record),
    secondary: record.code ?? record.id,
    muted: record.enabled === false,
    isLeaf: node.children.length === 0,
    children: node.children.map((child) => organizationTreeNode(child, tenantId)),
  };
}

function handleScopeTreeSelect(node: UiTreeNode) {
  const tenantId = tenantIdFromNodeKey(node.key);
  if (tenantId) {
    const tenant = scopeTenants.get(tenantId);
    if (tenant) selectTenant(tenant);
    return;
  }
  const tenantRootId = tenantRootIdFromNodeKey(node.key);
  if (tenantRootId) {
    const tenant = scopeTenants.get(tenantRootId);
    if (tenant) selectTenant(tenant);
    return;
  }
  const organizationId = organizationIdFromNodeKey(node.key);
  if (!organizationId) return;
  const organization = scopeOrganizations.get(organizationId);
  const organizationTenantId = scopeOrganizationTenantIds.get(organizationId);
  if (organization && organizationTenantId) {
    selectedTenant.value = scopeTenants.get(organizationTenantId);
    selectOrganizationScope(organization);
  }
}

function tenantNodeKey(id: string | undefined) {
  return `tenant:${id ?? ''}`;
}
function tenantRootNodeKey(id: string | undefined) {
  return `tenant-root:${id ?? ''}`;
}
function organizationNodeKey(id: string | undefined) {
  return `organization:${id ?? ''}`;
}
function tenantIdFromNodeKey(key: string) {
  return key.startsWith('tenant:') ? key.slice('tenant:'.length) : undefined;
}
function tenantRootIdFromNodeKey(key: string) {
  return key.startsWith('tenant-root:') ? key.slice('tenant-root:'.length) : undefined;
}
function organizationIdFromNodeKey(key: string) {
  return key.startsWith('organization:') ? key.slice('organization:'.length) : undefined;
}

function initializeTenantUserScope(record = currentUserTenant.value) {
  if (!record || canBrowseTenants.value || selectedTenant.value || selectedScope.value) {
    return;
  }
  selectedTenant.value = record;
  selectTenantRootScope(record);
}

function selectPlatformScope() {
  if (!canLeaveRoleDetailContext() || !canSelectPlatformScope.value) {
    return;
  }
  selectedTenant.value = undefined;
  selectedScope.value = {
    kind: 'platform',
    key: 'platform',
    title: '平台角色',
  };
}

/** The absence of a tree selection is the platform-role scope, not a tree node. */
function clearScopeSelection() {
  if (!canLeaveRoleDetailContext()) {
    return;
  }
  if (!canSelectPlatformScope.value) {
    selectTenantRootScope();
    return;
  }
  selectPlatformScope();
}

function selectTenant(record: Tenant) {
  if (!canLeaveRoleDetailContext()) {
    return;
  }
  selectedTenant.value = record;
  selectTenantRootScope(record);
}

function selectTenantRootScope(record = selectedTenant.value) {
  if (!record?.id || !canLeaveRoleDetailContext()) {
    return;
  }
  selectedScope.value = {
    kind: 'tenant',
    id: record.id,
    key: `tenant:${record.id}`,
    title: `${tenantTitle(record)} / 租户本级`,
    tenant: record,
  };
}

function selectOrganizationScope(record: Organization) {
  if (!record.id || !canLeaveRoleDetailContext()) {
    return;
  }
  selectedScope.value = {
    kind: 'organization',
    id: record.id,
    key: `organization:${record.id}`,
    title: organizationTitle(record),
    tenant: selectedTenant.value,
    organization: record,
  };
}

function handleRoleListAction(action: RecordActionItem) {
  if (action.key === 'create') {
    startCreateRole();
  }
}

function roleExtraRowActionsOf(record: QueryListRecord): RecordActionItem[] {
  return [
    {
      key: 'bind',
      actionCode: roleBindingActionCode(record),
      title: '绑定',
      after: 'edit',
      pinned: true,
      visible: canBindRoleRecord(record),
    },
    {
      key: 'authorize',
      actionCode: 'rolePermissions',
      title: '授权',
      after: 'bind',
      pinned: true,
      visible: canAuthorizeRoleRecord(record),
    },
    {
      key: 'toggle',
      actionCode: roleToggleActionCode(record),
      title: roleToggleTitle(record),
      iconName: 'power',
      before: 'delete',
    },
  ];
}

function roleRowActionStateOf(record: QueryListRecord, action: RecordActionItem) {
  if (record.systemManaged !== true) {
    return undefined;
  }
  const actionKey = action.key ?? action.actionCode;
  return actionKey === 'edit' ||
    actionKey === 'update' ||
    actionKey === 'bind' ||
    actionKey === 'authorize' ||
    actionKey === 'toggle' ||
    actionKey === 'delete'
    ? { disabled: true }
    : undefined;
}

function handleRoleRowAction(action: ResolvedRecordActionItem, record: QueryListRecord) {
  if (!canLeaveRoleDetailContext()) {
    return;
  }
  if (action.key === 'view') {
    void openRoleDetail(record, 'view');
    return;
  }
  if (action.key === 'edit') {
    void openRoleDetail(record, 'edit');
    return;
  }
  if (action.key === 'bind') {
    void openRoleBinding(record, record.assignmentType === 'employment');
    return;
  }
  if (action.key === 'authorize') {
    openRoleAuthorization(record);
    return;
  }
  if (action.key === 'toggle') {
    void toggleRoleEnabled(record);
    return;
  }
  if (action.key === 'delete') {
    void removeRole(record);
  }
}

function openRoleAuthorization(record: QueryListRecord) {
  const id = String(record.id ?? '');
  if (!id || record.roleKind === 'group') {
    return;
  }
  authorizationRole.value = copyRole(record as Role) as Role;
  authorizationDrawerOpen.value = true;
}

function closeRoleAuthorization() {
  authorizationDrawerOpen.value = false;
  authorizationRole.value = undefined;
}

function handleRoleRowDblclick(record: QueryListRecord) {
  if (!canLeaveRoleDetailContext()) {
    return;
  }
  void openRoleDetail(record, 'view');
}

async function openRoleBinding(record: QueryListRecord, employment = false) {
  if (!canLeaveRoleDetailContext()) {
    return;
  }
  const id = String(record.id ?? '');
  if (!id) {
    return;
  }
  bindingDrawerOpen.value = !employment;
  employmentBindingDrawerOpen.value = employment;
  bindingRole.value = copyRole(record as Role);
  try {
    bindingRole.value = await roleContext.crud.view(id);
  } catch (cause) {
    bindingDrawerOpen.value = false;
    employmentBindingDrawerOpen.value = false;
    bindingRole.value = undefined;
    presentPlatformError(cause, { source: 'role-management', phase: 'load' });
  }
}

function closeRoleBinding() {
  if (savingRole.value) {
    return;
  }
  bindingDrawerOpen.value = false;
  employmentBindingDrawerOpen.value = false;
  bindingRole.value = undefined;
}

function startCreateRole() {
  if (!canLeaveRoleDetailContext()) {
    return;
  }
  if (!selectedScope.value) {
    presentPlatformMessage('请先选择角色归属范围', { phase: 'validation' });
    return;
  }
  selectedRole.value = undefined;
  selectedRoleKey.value = undefined;
  roleDraft.value = createRoleDraft(selectedScope.value);
  roleDetailMode.value = 'create';
  loadingRoleDetail.value = false;
  roleDetailLoadFailed.value = false;
  roleDetailRequestSeq.value += 1;
  roleDetailOpen.value = true;
}

async function openRoleDetail(record: QueryListRecord, mode: RoleDetailMode) {
  if (!canLeaveRoleDetailContext()) {
    return;
  }
  const id = String(record.id ?? '');
  if (!id) {
    return;
  }
  selectedRoleKey.value = id;
  roleDetailOpen.value = true;
  roleDetailMode.value = mode;
  selectedRole.value = undefined;
  roleDraft.value = copyRole(record as Role);
  loadingRoleDetail.value = true;
  roleDetailLoadFailed.value = false;
  const requestSeq = roleDetailRequestSeq.value + 1;
  roleDetailRequestSeq.value = requestSeq;
  try {
    const fullRecord = await roleContext.crud.view(id);
    if (!canCommitRoleDetailRequest(id, requestSeq)) {
      return;
    }
    if (!roleMatchesSelectedScope(fullRecord)) {
      roleDetailLoadFailed.value = true;
      presentPlatformMessage('角色不属于当前归属范围', { source: 'role-management', phase: 'load' });
      return;
    }
    commitRoleDetailRecord(fullRecord, mode);
  } catch (cause) {
    if (canCommitRoleDetailRequest(id, requestSeq)) {
      roleDetailLoadFailed.value = true;
      presentPlatformError(cause, { source: 'role-management', phase: 'load' });
    }
  } finally {
    if (canCommitRoleDetailRequest(id, requestSeq)) {
      loadingRoleDetail.value = false;
    }
  }
}

function closeRoleDetail() {
  if (savingRole.value) {
    return;
  }
  roleDetailRequestSeq.value += 1;
  loadingRoleDetail.value = false;
  roleDetailLoadFailed.value = false;
  roleDetailOpen.value = false;
  roleDetailMode.value = 'view';
  roleDraft.value = selectedRole.value ? copyRole(selectedRole.value) : createRoleDraft(selectedScope.value);
  if (isDrawerWorkspaceView.value) {
    workspaceViewHost?.dismiss();
  }
}

function cancelRoleDetail() {
  if (savingRole.value) {
    return;
  }
  if (!selectedRole.value?.id || roleDetailMode.value === 'create') {
    closeRoleDetail();
    return;
  }
  roleDraft.value = copyRole(selectedRole.value);
  roleDetailMode.value = 'view';
  loadingRoleDetail.value = false;
  roleDetailLoadFailed.value = false;
}

function handleRoleDetailAction(action: RecordActionItem) {
  if (action.key === 'cancel') {
    cancelRoleDetail();
    return;
  }
  if (action.key === 'save') {
    void saveRole();
    return;
  }
  if (!canLeaveRoleDetailContext()) {
    return;
  }
  if (action.key === 'edit' && selectedRole.value && !selectedRole.value.systemManaged) {
    roleDraft.value = copyRole(selectedRole.value);
    roleDetailMode.value = 'edit';
    return;
  }
  if (action.key === 'bind' && selectedRole.value) {
    void openRoleBinding(
      selectedRole.value as QueryListRecord,
      selectedRole.value.assignmentType === 'employment',
    );
    return;
  }
  if (action.key === 'delete') {
    void removeRole(selectedRole.value);
  }
}

function retryRoleDetail() {
  const id = String(roleDraft.value.id ?? selectedRoleKey.value ?? '');
  if (!id) {
    return;
  }
  const mode = roleDetailMode.value === 'create' ? 'view' : roleDetailMode.value;
  void openRoleDetail({ ...roleDraft.value, id } as QueryListRecord, mode);
}

async function saveRole() {
  await executeStaticFormSave<Role>({
    loading: savingRole,
    mode: roleDetailMode.value === 'edit' ? 'edit' : 'create',
    source: 'role-management',
    validateContext: () => (selectedScope.value ? undefined : '请先选择角色归属范围'),
    canSave: () => canSaveRole.value,
    deniedMessage: '当前用户无权保存角色',
    createRecord: () => normalizedRoleDraft(roleDraft.value, selectedScope.value!),
    validateRecord: validateRoleDraft,
    save: (draft, mode) =>
      mode === 'edit' && selectedRole.value?.id
        ? roleContext.crud.update(selectedRole.value.id, draft)
        : roleContext.crud.insert(draft),
    onSaved: ({ record }) => {
      commitRoleDetailRecord(record);
      roleReloadKey.value += 1;
      void loadMemberRoleCandidates();
    },
  });
}

async function removeRole(record: Partial<Role> | QueryListRecord | undefined) {
  await executeStaticRecordAction({
    loading: savingRole,
    source: 'role-management',
    record: () => (record?.id ? record : undefined),
    canExecute: (target) => roleContext.can('delete') === true && (target as Role).systemManaged !== true,
    deniedMessage: '当前用户无权删除角色',
    confirm: (target) =>
      confirmAction({
        title: '删除角色',
        content: `确认删除角色「${roleTitle(target)}」？`,
        okText: '删除',
        danger: true,
      }),
    execute: (target) =>
      roleContext.crud.delete(String(target.id), { version: (target as { version: number }).version }),
    onExecuted: (_, target) => {
      if (selectedRoleKey.value === String(target.id)) {
        selectedRoleKey.value = undefined;
        selectedRole.value = undefined;
        roleDraft.value = createRoleDraft(selectedScope.value);
        roleDetailOpen.value = false;
        roleDetailMode.value = 'view';
        loadingRoleDetail.value = false;
        roleDetailLoadFailed.value = false;
        roleDetailRequestSeq.value += 1;
      }
      roleReloadKey.value += 1;
      void loadMemberRoleCandidates();
    },
  });
}

async function toggleRoleEnabled(record: Partial<Role> | QueryListRecord | undefined) {
  await executeStaticRecordAction({
    loading: savingRole,
    source: 'role-management',
    record: () => (record?.id ? record : undefined),
    canExecute: (target) =>
      roleContext.can(roleToggleActionCode(target)) === true && (target as Role).systemManaged !== true,
    deniedMessage: '当前用户无权变更角色启停状态',
    execute: (target) =>
      target.enabled === false
        ? roleContext.crud.enable(String(target.id), { version: (target as { version: number }).version })
        : roleContext.crud.disable(String(target.id), { version: (target as { version: number }).version }),
    onExecuted: async (_, target) => {
      if (selectedRoleKey.value === String(target.id)) {
        const refreshed = await roleContext.crud.view(String(target.id));
        commitRoleDetailRecord(refreshed);
      }
      roleReloadKey.value += 1;
      void loadMemberRoleCandidates();
    },
  });
}

function canLeaveRoleDetailContext() {
  return !savingRole.value;
}

function canCommitRoleDetailRequest(recordId: string, requestSeq: number) {
  return roleDetailRequestSeq.value === requestSeq && selectedRoleKey.value === recordId;
}

function commitRoleDetailRecord(record: Role, nextMode: RoleDetailMode = 'view') {
  selectedRole.value = record;
  selectedRoleKey.value = record.id;
  roleDraft.value = copyRole(record);
  roleDetailMode.value = nextMode === 'edit' && record.systemManaged !== true ? 'edit' : 'view';
  roleDetailOpen.value = true;
  loadingRoleDetail.value = false;
  roleDetailLoadFailed.value = false;
  roleDetailRequestSeq.value += 1;
}

async function restoreRoleWorkspaceView() {
  const input = roleWorkspaceInput();
  if (!input) return;
  selectedScope.value = scopeFromWorkspaceInput(input);
  const session = takeRoleDetailWorkspaceSession(input);
  if (session) {
    restoreRoleDetailWorkspaceSession(session);
    return;
  }
  await openRoleDetail({ id: input.recordId }, 'view');
}

function roleWorkspaceInput() {
  const recordId = props.recordId;
  const scopeKind = props.scopeKind;
  if (!recordId || !scopeKind || (scopeKind !== 'platform' && !props.scopeId)) return undefined;
  return {
    recordId,
    scopeKind,
    ...(scopeKind === 'platform' ? {} : { scopeId: props.scopeId! }),
  };
}

function scopeFromWorkspaceInput(input: { scopeKind: RoleOwnerScopeType; scopeId?: string }): RoleScope {
  if (input.scopeKind === 'platform') {
    return { kind: 'platform', key: 'platform', title: '平台角色' };
  }
  const id = input.scopeId!;
  const scopeTitle = input.scopeKind === 'organization' ? `机构：${id}` : `租户：${id}`;
  return {
    kind: input.scopeKind,
    id,
    key: `${input.scopeKind}:${id}`,
    title: scopeTitle,
    ...(input.scopeKind === 'tenant' ? { tenant: { id, title: id, alias: id } as Tenant } : {}),
  };
}

function roleMatchesSelectedScope(record: Role) {
  const scope = selectedScope.value;
  if (!scope || record.ownerScopeType !== scope.kind) return false;
  return scope.kind === 'platform' || record.ownerScopeId === scope.id;
}

function restoreRoleDetailWorkspaceSession(session: RoleDetailWorkspaceSession) {
  selectedScope.value = session.scope;
  selectedRole.value = session.selectedRole;
  selectedRoleKey.value = session.selectedRole.id;
  roleDraft.value = session.draft;
  roleDetailMode.value = session.mode;
  roleDetailOpen.value = true;
  loadingRoleDetail.value = false;
  roleDetailLoadFailed.value = false;
  roleDetailRequestSeq.value += 1;
}

function createRoleDraft(scope: RoleScope | undefined): Partial<Role> {
  return {
    tenantId: scopeTenantId(scope),
    ownerScopeType: scope?.kind,
    ownerScopeId: scope?.kind === 'platform' ? undefined : scope?.id,
    assignmentType: 'employment',
    roleKind: 'standard',
    sharePolicy: defaultSharePolicy(),
    enabled: true,
    sortOrder: 100,
  };
}

function copyRole(record: Partial<Role>): Partial<Role> {
  return { ...record };
}

function normalizedRoleDraft(draft: Partial<Role>, scope: RoleScope): Role {
  const roleKind = normalizedRoleKind(draft.roleKind);
  const sharePolicy = normalizedSharePolicy(draft.sharePolicy, scope.kind);
  const normalized = normalizeRecordDraft<Role>(draft, {
    title: draft.title?.trim(),
    assignmentType: normalizedAssignmentType(draft.assignmentType, roleKind),
    roleKind,
    memberRoleIds: draft.memberRoleIds?.trim() || undefined,
    tenantId: scopeTenantId(scope),
    ownerScopeType: scope.kind,
    ownerScopeId: scope.kind === 'platform' ? undefined : scope.id,
    ownerScopeKey: undefined,
    sharePolicy,
    description: draft.description?.trim() || undefined,
    enabled: draft.enabled !== false,
    sortOrder: normalizeSortOrder(draft.sortOrder),
  });
  if (roleKind !== 'group') {
    normalized.memberRoleIds = undefined;
  }
  return normalized;
}

function scopeTenantId(scope: RoleScope | undefined) {
  return scope?.kind === 'platform' ? undefined : (scope?.tenant?.id ?? scope?.id);
}

function validateRoleDraft(draft: Role) {
  const requiredFields: RoleFormFieldName[] = [
    'title',
    'assignmentType',
    'roleKind',
    'ownerScopeType',
    'sharePolicy',
  ];
  for (const fieldName of requiredFields) {
    const field = resolveRecordFormFieldState(fieldName, {
      fields: roleFormFieldDefinitions.value,
      fallback: roleFormFieldFallback.value,
    });
    if (field.visible && field.required && !draft[fieldName]) {
      return `请填写${field.label}`;
    }
  }
  if (draft.ownerScopeType !== 'platform' && !draft.ownerScopeId) {
    return '请选择归属对象';
  }
  if (!sharePolicyOptions(draft.ownerScopeType).some((option) => option.value === draft.sharePolicy)) {
    return '请选择当前归属范围可用的公开策略';
  }
  return undefined;
}

function normalizedAssignmentType(
  value: RoleAssignmentType | undefined,
  roleKind: RoleKind,
): RoleAssignmentType {
  if (roleKind === 'group' || roleKind === 'dataGrant') {
    return 'employment';
  }
  return value === 'account' ? 'account' : 'employment';
}

function normalizedRoleKind(value: RoleKind | undefined): RoleKind {
  return value === 'group' || value === 'dataGrant' || value === 'system' ? value : 'standard';
}

function roleKindRequiresEmployment(value: RoleKind | undefined) {
  return value === 'group' || value === 'dataGrant';
}

function assignmentTypeOptions(roleKind: RoleKind | undefined) {
  if (roleKindRequiresEmployment(roleKind)) {
    return [{ label: '任职角色', value: 'employment' }];
  }
  return [
    { label: '账号角色', value: 'account' },
    { label: '任职角色', value: 'employment' },
  ];
}

function roleKindOptions(assignmentType: RoleAssignmentType | undefined) {
  const standardOptions = [
    { label: '标准角色', value: 'standard' },
    { label: '系统角色', value: 'system' },
  ];
  if (assignmentType === 'account') {
    return standardOptions;
  }
  return [
    standardOptions[0],
    { label: '角色组', value: 'group' },
    { label: '数据授权角色', value: 'dataGrant' },
    standardOptions[1],
  ];
}

function normalizedSharePolicy(
  value: RoleSharePolicy | undefined,
  scopeType: RoleOwnerScopeType | undefined,
): RoleSharePolicy {
  const allowed = sharePolicyOptions(scopeType).map((option) => option.value);
  return allowed.includes(value ?? '') ? (value as RoleSharePolicy) : defaultSharePolicy();
}

function defaultSharePolicy(): RoleSharePolicy {
  return 'private';
}

function normalizeSortOrder(value: unknown) {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : 100;
  }
  const parsed = Number(String(value ?? '').trim());
  return Number.isFinite(parsed) ? parsed : 100;
}

function sharePolicyOptions(scopeType: RoleOwnerScopeType | undefined) {
  if (scopeType === 'platform') {
    return [
      { label: '私有', value: 'private' },
      { label: '全局公开', value: 'platform' },
    ];
  }
  if (scopeType === 'organization') {
    return [
      { label: '私有', value: 'private' },
      { label: '本级及下级', value: 'ownerAndChildren' },
    ];
  }
  return [
    { label: '私有', value: 'private' },
    { label: '租户公开', value: 'tenant' },
  ];
}

function assignmentTypeTitle(value: RoleAssignmentType | undefined) {
  return value === 'account' ? '账号角色' : '任职角色';
}

function roleKindTitle(value: RoleKind | undefined) {
  const titles: Record<RoleKind, string> = {
    standard: '标准角色',
    group: '角色组',
    dataGrant: '数据授权角色',
    system: '系统角色',
  };
  return titles[value ?? 'standard'];
}

function sharePolicyTitle(value: RoleSharePolicy | undefined) {
  const titles: Record<RoleSharePolicy, string> = {
    private: '私有',
    ownerAndChildren: '本级及下级',
    tenant: '租户公开',
    platform: '全局公开',
  };
  return titles[value ?? 'private'];
}

function booleanTitle(value: unknown) {
  return value === true ? '是' : '否';
}

function optionTitle(record: QueryListRecord, fieldName: string, fallback: string) {
  const title = record[`${fieldName}Title`];
  return typeof title === 'string' && title.trim() ? title : fallback;
}

function canBindRoleRecord(record: Partial<Role> | QueryListRecord | undefined) {
  return (
    Boolean(record?.id) && (record?.assignmentType === 'account' || record?.assignmentType === 'employment')
  );
}

function canAuthorizeRoleRecord(record: Partial<Role> | QueryListRecord | undefined) {
  return Boolean(record?.id) && record?.roleKind !== 'group';
}

function roleBindingActionCode(record: Partial<Role> | QueryListRecord | undefined) {
  return record?.assignmentType === 'employment' ? 'employmentRoleGrants' : 'accountRoleGrants';
}

function roleBindingTitle(record: Partial<Role> | QueryListRecord | undefined) {
  return record?.assignmentType === 'employment' ? '绑定任职' : '绑定用户';
}

function roleToggleActionCode(record: Partial<Role> | QueryListRecord | undefined) {
  return record?.enabled === false ? 'enable' : 'disable';
}

function roleToggleTitle(record: Partial<Role> | QueryListRecord | undefined) {
  return record?.enabled === false ? '启用' : '停用';
}

function roleTitle(record: Partial<Role> | QueryListRecord | undefined) {
  return String(record?.title ?? record?.id ?? '角色');
}

function tenantTitle(record: Tenant | undefined) {
  return String(record?.title ?? record?.alias ?? record?.id ?? '未命名租户');
}

function organizationTitle(record: Organization | undefined) {
  return String(record?.title ?? record?.code ?? record?.id ?? '未命名机构');
}

function scopeDisplayValue(fieldName: string, value: unknown) {
  if (fieldName === 'ownerScopeType') {
    return ownerScopeTypeTitle(roleDraft.value.ownerScopeType);
  }
  if (fieldName === 'ownerScopeId') {
    return selectedScope.value?.title ?? String(value ?? '');
  }
  if (fieldName === 'memberRoleIds') {
    return memberRoleIdsTitle(value);
  }
  return undefined;
}

function memberRoleIdsTitle(value: unknown) {
  const roleIds = parseRoleIds(value);
  if (roleIds.length === 0) {
    return '-';
  }
  return roleIds
    .map((roleId) => {
      const role = memberRoleCandidates.value.find((candidate) => candidate.id === roleId);
      return role ? roleTitle(role) : roleId;
    })
    .join('，');
}

function ownerScopeTypeTitle(value: RoleOwnerScopeType | undefined) {
  if (value === 'platform') {
    return '平台';
  }
  if (value === 'organization') {
    return '机构';
  }
  return '租户';
}

function filterScopeTreeNodes(nodes: UiTreeNode[], keyword: string): UiTreeNode[] {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) return nodes;
  return nodes.flatMap((node) => {
    const children = node.children ? filterScopeTreeNodes(node.children, keyword) : undefined;
    const matches = [node.title, node.secondary, node.key].some((value) =>
      value?.toLowerCase().includes(normalized),
    );
    return matches || (children?.length ?? 0) > 0 ? [{ ...node, ...(children ? { children } : {}) }] : [];
  });
}

function parseRoleIds(value: unknown) {
  if (Array.isArray(value)) {
    return value.map((item) => String(item).trim()).filter(Boolean);
  }
  if (typeof value !== 'string') {
    return [];
  }
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}
</script>

<template>
  <section
    ref="pageRoot"
    class="role-management-page"
    :class="{
      'role-management-page--task': isWorkspaceView && !isDrawerWorkspaceView,
    }"
  >
    <RecordExplorerPanel
      v-if="!isWorkspaceView || isDrawerWorkspaceView"
      class="role-scope-panel"
      title="租户"
      refresh-title="刷新租户列表"
      :search-keyword="tenantSearchKeyword"
      search-placeholder="搜索租户名称、alias 或 ID"
      :searchable="canBrowseTenants"
      @refresh="loadScopeTree"
      @update:search-keyword="tenantSearchKeyword = $event"
    >
      <UiSpin v-if="scopeTreeLoading" tip="加载租户" />
      <UiTree
        v-else
        v-model:expanded-keys="scopeTreeExpandedKeys"
        :nodes="visibleScopeTreeNodes"
        :selected-key="
          selectedScope?.kind === 'tenant'
            ? tenantRootNodeKey(selectedScope.id)
            : selectedScope?.kind === 'organization'
              ? organizationNodeKey(selectedScope.id)
              : undefined
        "
        load-strategy="controlled"
        :branch-states="scopeBranchStates"
        @load-request="
          scopeBranchData.request(
            $event.node.key,
            $event.reason,
            scopeBranchData.stateOf($event.node.key).status === 'error',
          )
        "
        @select="handleScopeTreeSelect"
        @deselect="clearScopeSelection"
      />
    </RecordExplorerPanel>

    <RecordQueryListPanel
      v-if="!isWorkspaceView || isDrawerWorkspaceView"
      class="role-list-panel"
      :context="roleListContext"
      :title="selectedScope ? `角色列表 - ${selectedScope.title}` : '角色列表'"
      :columns="roleListColumns"
      standard-crud-actions
      standard-crud-row-actions
      :extra-row-actions-of="roleExtraRowActionsOf"
      :row-action-state-of="roleRowActionStateOf"
      :selected-key="selectedRoleKey"
      :reload-key="roleReloadKey"
      :ready="roleListReady"
      quick-search-placeholder="搜索角色名称或说明"
      empty-description="当前范围暂无角色"
      waiting-description="请选择角色归属范围"
      @action="handleRoleListAction"
      @row-action="handleRoleRowAction"
      @row-dblclick="handleRoleRowDblclick"
      @select="selectedRoleKey = String($event.id ?? '')"
    />

    <RecordDetailDrawer
      v-if="shouldRenderRoleDetailDrawer"
      :open="roleDetailOpen"
      :title="roleDetailTitle"
      render-mode="inline"
      :subtitle="roleDetailSubtitle"
      :close-on-outside="roleDetailMode === 'view'"
      :promotion="roleDetailPromotion"
      @close="closeRoleDetail"
    >
      <template #status>
        <RecordStatusSwitch
          v-if="roleDetailMode === 'view' && selectedRole"
          :enabled="selectedRole.enabled !== false"
          :disabled="savingRole || !canToggleRole"
          :loading="savingRole"
          :show-label="false"
          @change="toggleRoleEnabled(selectedRole)"
        />
      </template>
      <template #operation>
        <RecordActionBar
          :context="roleListContext"
          :actions="roleDetailOperationActions"
          @action="handleRoleDetailAction"
        />
      </template>

      <UiSpin v-if="loadingRoleDetail" class="role-detail-state" tip="加载角色详情" />
      <div v-else-if="roleDetailLoadFailed" class="role-detail-state">
        <UiError title="详情加载失败" message="无法加载角色详情，请重试" />
        <UiButton type="primary" icon-name="reload" @click="retryRoleDetail">重试</UiButton>
      </div>

      <template v-else-if="roleDetailMode === 'view' || roleDetailMode === 'create' || selectedRole">
        <RecordDetailFields
          v-if="roleDetailMode === 'view'"
          :record="roleDraft as RecordFormRecord"
          :field-names="roleDetailFieldNames"
          :fields="roleFormFieldDefinitions"
          :fallback="roleFormFieldFallback"
          :display-of="scopeDisplayValue"
        />

        <form v-else class="role-form" @submit.prevent="saveRole">
          <label>
            <span class="role-form-label">当前范围</span>
            <UiInput :value="selectedScope?.title ?? '-'" disabled />
          </label>
          <RecordFormFields
            :record="roleDraft as RecordFormRecord"
            :field-names="rolePrimaryFormFieldNames"
            :fields="roleFormFieldDefinitions"
            :fallback="roleFormFieldFallback"
            :disabled="roleFormDisabled"
            :disabled-of="roleFormFieldDisabled"
            @update:field="updateRoleDraftField"
          />
          <RoleGroupMemberSelector
            v-if="roleDraft.roleKind === 'group'"
            :value="roleDraft.memberRoleIds"
            :candidates="memberRoleCandidates"
            :current-role-id="selectedRole?.id"
            :disabled="roleFormDisabled || selectedRole?.systemManaged === true"
            @update:value="updateRoleDraftField('memberRoleIds', $event)"
          />
          <RecordFormFields
            :record="roleDraft as RecordFormRecord"
            :field-names="roleSecondaryFormFieldNames"
            :fields="roleFormFieldDefinitions"
            :fallback="roleFormFieldFallback"
            :disabled="roleFormDisabled"
            :disabled-of="roleFormFieldDisabled"
            @update:field="updateRoleDraftField"
          />
        </form>
        <RecordMetaSection v-if="roleDetailMode !== 'create'" :record="roleDraft" show-sort-order />
      </template>
    </RecordDetailDrawer>

    <RecordDetailPanel v-else :title="roleDetailTitle" :subtitle="roleDetailSubtitle">
      <template #status>
        <RecordStatusSwitch
          v-if="roleDetailMode === 'view' && selectedRole"
          :enabled="selectedRole.enabled !== false"
          :disabled="savingRole || !canToggleRole"
          :loading="savingRole"
          :show-label="false"
          @change="toggleRoleEnabled(selectedRole)"
        />
      </template>
      <template #operation>
        <RecordActionBar
          :context="roleContext"
          :actions="roleWorkspaceOperationActions"
          :record-id="selectedRole?.id"
          @action="handleRoleDetailAction"
        />
      </template>

      <UiSpin v-if="loadingRoleDetail" class="role-detail-state" tip="加载角色详情" />
      <div v-else-if="roleDetailLoadFailed" class="role-detail-state">
        <UiError title="详情加载失败" message="无法加载角色详情，请重试" />
        <UiButton type="primary" icon-name="reload" @click="retryRoleDetail">重试</UiButton>
      </div>

      <template v-else-if="roleDetailMode === 'view' || selectedRole">
        <RecordDetailFields
          v-if="roleDetailMode === 'view'"
          :record="roleDraft as RecordFormRecord"
          :field-names="roleDetailFieldNames"
          :fields="roleFormFieldDefinitions"
          :fallback="roleFormFieldFallback"
          :display-of="scopeDisplayValue"
        />

        <form v-else class="role-form" @submit.prevent="saveRole">
          <label>
            <span class="role-form-label">当前范围</span>
            <UiInput :value="selectedScope?.title ?? '-'" disabled />
          </label>
          <RecordFormFields
            :record="roleDraft as RecordFormRecord"
            :field-names="rolePrimaryFormFieldNames"
            :fields="roleFormFieldDefinitions"
            :fallback="roleFormFieldFallback"
            :disabled="roleFormDisabled"
            :disabled-of="roleFormFieldDisabled"
            @update:field="updateRoleDraftField"
          />
          <RoleGroupMemberSelector
            v-if="roleDraft.roleKind === 'group'"
            :value="roleDraft.memberRoleIds"
            :candidates="memberRoleCandidates"
            :current-role-id="selectedRole?.id"
            :disabled="roleFormDisabled || selectedRole?.systemManaged === true"
            @update:value="updateRoleDraftField('memberRoleIds', $event)"
          />
          <RecordFormFields
            :record="roleDraft as RecordFormRecord"
            :field-names="roleSecondaryFormFieldNames"
            :fields="roleFormFieldDefinitions"
            :fallback="roleFormFieldFallback"
            :disabled="roleFormDisabled"
            :disabled-of="roleFormFieldDisabled"
            @update:field="updateRoleDraftField"
          />
        </form>
        <RecordMetaSection :record="roleDraft" show-sort-order />
      </template>
    </RecordDetailPanel>

    <RoleAccountGrantDrawer
      :open="bindingDrawerOpen"
      :context="roleContext"
      :role="bindingRole"
      :container="pageRoot"
      @close="closeRoleBinding"
      @saved="roleReloadKey += 1"
    />
    <RoleEmploymentGrantDrawer
      :open="employmentBindingDrawerOpen"
      :context="roleContext"
      :role="bindingRole"
      :container="pageRoot"
      @close="closeRoleBinding"
      @saved="roleReloadKey += 1"
    />
    <RoleAuthorizationView
      v-if="authorizationDrawerOpen && authorizationRole?.id"
      :role-id="authorizationRole.id"
      :container="pageRoot"
      drawer
      @close="closeRoleAuthorization"
    />
  </section>
</template>

<style scoped>
.role-management-page {
  position: relative;
  display: grid;
  grid-template-columns: minmax(260px, 320px) minmax(0, 1fr);
  gap: 12px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.role-management-page--task {
  display: block;
  height: 100%;
}

.role-management-page--task :deep(.record-detail-layout) {
  height: 100%;
}

.role-scope-panel,
.role-list-panel {
  min-width: 0;
  min-height: 0;
}

.role-tenant-root-scope {
  display: block;
  flex: 0 0 auto;
  width: 100%;
  margin: 0 0 8px;
  padding: 0 0 8px;
  border: 0;
  border-bottom: 1px solid var(--muyun-border);
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.role-form {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

.role-form > label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.role-form-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.role-detail-state {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 32px 0;
}

@media (max-width: 1180px) {
  .role-management-page {
    grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
  }
}

@media (max-width: 980px) {
  .role-management-page {
    height: auto;
    overflow: visible;
  }
}

@media (max-width: 760px) {
  .role-management-page {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(220px, 0.8fr) minmax(360px, 1fr);
  }
}
</style>
