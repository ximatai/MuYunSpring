<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  RecordActionBar,
  RecordDetailPanel,
  RecordPicker,
  RecordStatusSwitch,
  UserSessionExpandedSubtable,
  executeStaticFormSave,
  executeStaticRecordAction,
  normalizeRecordDraft,
  presentPlatformError,
  presentPlatformMessage,
  resolveRecordFormFieldState,
  resolveRecordFormFields,
  type RecordActionItem,
  type RecordFormFieldFallback,
  type RecordFormFieldValue,
  type RecordPickerRecord,
} from '@muyun/platform-components';
import { confirmAction } from '@muyun/vue-ui-antdv';
import type {
  ResetPasswordResponse,
  Tenant,
  UserAccount,
  UserEmployeeBindingView,
  UserSessionView,
} from '@muyun/web-contracts';
import { useModuleContext, type ModuleContext } from '@muyun/web-core';
import { useRoute } from 'vue-router';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';
import { createBackendHttpClient } from '../platform-admin-runtime/backendHttp';
import { usePageBusinessEventHandler } from '../platform-admin-runtime/pageRealtime';
import UserDetailContent from './UserDetailContent.vue';
import { shouldCommitUserDetailRequest, type UserDetailMode } from './userDetailStateModel';
import { userActionTitle, type UserRouteAction } from './userManagementRouteState';
import { useUserSessionRows } from './useUserSessionRows';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';

defineOptions({ name: 'UserDetailRouteView' });

const props = defineProps<{
  action: UserRouteAction;
  userId?: string;
}>();

type UserFormFieldName = 'username' | 'enabled';

const route = useRoute();
const navigation = useWorkbenchNavigation();
const currentUser = useCurrentUserContext();
const tenantContext = useModuleContext<Tenant>({ moduleAlias: 'iam.tenant' });
const userContext = useModuleContext<UserAccount>({ moduleAlias: 'iam.user' });
const selectedUser = ref<UserAccount>();
const selectedTenant = ref<Tenant>();
const selectedUserKey = ref<string>();
const loadingUserDetail = ref(false);
const userDetailLoadFailed = ref(false);
const savingUser = ref(false);
const userDetailRequestSeq = ref(0);
const userDraft = ref<Partial<UserAccount>>(createUserDraft(undefined));
const passwordDraft = ref('');
const resetPasswordResult = ref<ResetPasswordResponse>();
const localMode = ref<UserDetailMode>('view');
const userFormFieldDefinitions = ref(resolveRecordFormFields(undefined));
const { loadUserSessions, handleUserSessionBusinessEvent, userSessionState } = useUserSessionRows({
  context: userContext,
  source: 'user-management-detail',
});

const detailMode = computed<UserDetailMode>(() =>
  localMode.value === 'resetPassword' ? 'resetPassword' : routeActionToDetailMode(props.action),
);
const canSelectTenant = computed(() => detailMode.value === 'create' && currentUser?.value?.system === true);
const tenantPickerContext = computed(() => tenantContext as unknown as ModuleContext<RecordPickerRecord>);
const userDetailTitle = computed(() => {
  if (props.action === 'add') return '新建用户';
  const username = userTitle(selectedUser.value ?? userDraft.value);
  return props.action === 'view' ? username : `${userActionTitle(props.action)}：${username}`;
});
const userDetailSubtitle = computed(() =>
  detailMode.value === 'create' ? undefined : userEmployeeSubtitle(selectedUser.value ?? userDraft.value),
);
const canSaveUser = computed(() => {
  if (loadingUserDetail.value) return false;
  if (detailMode.value === 'create')
    return Boolean(selectedTenant.value?.id) && userContext.can('create') === true;
  if (detailMode.value === 'edit')
    return Boolean(selectedUser.value?.id) && userContext.can('update') === true;
  if (detailMode.value === 'resetPassword') {
    const userId = selectedUser.value?.id;
    return Boolean(userId) && userContext.can('changePassword', userId) === true;
  }
  return false;
});
const canToggleUser = computed(() => {
  if (!selectedUser.value?.id || loadingUserDetail.value) return false;
  return userContext.can(userToggleActionCode(selectedUser.value)) === true;
});
const detailActions = computed<RecordActionItem[]>(() => {
  if (detailMode.value === 'view') {
    if (!selectedUser.value?.id) return [];
    return [
      { key: 'edit', actionCode: 'update', title: '编辑', iconName: 'edit', disabled: savingUser.value },
      {
        key: 'resetPassword',
        actionCode: 'changePassword',
        title: '修改密码',
        iconName: 'lock',
        disabled: savingUser.value,
      },
      {
        key: 'resetGeneratedPassword',
        actionCode: 'resetPassword',
        title: '重置密码',
        iconName: 'reload',
        disabled: savingUser.value,
      },
      {
        key: 'delete',
        actionCode: 'delete',
        title: '删除',
        iconName: 'delete',
        danger: true,
        disabled: savingUser.value,
      },
    ];
  }
  return [
    { key: 'cancel', title: '取消', iconName: 'close', disabled: savingUser.value },
    {
      key: 'save',
      actionCode:
        detailMode.value === 'create' ? 'create' : detailMode.value === 'edit' ? 'update' : 'changePassword',
      title: '保存',
      iconName: 'save',
      primary: true,
      loading: savingUser.value,
      disabled: !canSaveUser.value,
    },
  ];
});
const userFormFieldFallback = computed<Record<UserFormFieldName, RecordFormFieldFallback>>(() => ({
  username: { label: '账号', required: true, visible: true, placeholder: '请输入登录账号' },
  enabled: { label: '允许登录', visible: true, controlType: 'enabledStatus' },
}));
const userFormFieldNames = computed<UserFormFieldName[]>(() => ['username', 'enabled']);

usePageBusinessEventHandler(handleUserSessionBusinessEvent);
onMounted(() => {
  void loadUserFormDefinition();
});
watch(
  () => [props.action, props.userId, route.query.InstanceKey] as const,
  () => void initializeFromAddress(),
  { immediate: true },
);

/** 根据地址重新准备当前页签的数据，地址变化时不会沿用上一个用户的草稿。 */
async function initializeFromAddress() {
  localMode.value = 'view';
  passwordDraft.value = '';
  resetPasswordResult.value = undefined;
  if (props.action === 'add') {
    await prepareCreateUser();
    return;
  }
  if (!props.userId) return;
  await loadUserDetail(props.userId);
}

/** 读取后台给用户表单提供的字段说明。 */
async function loadUserFormDefinition() {
  try {
    const runtimeContext = await userContext.runtime.ready;
    userFormFieldDefinitions.value = resolveRecordFormFields(runtimeContext.uiDescriptor);
  } catch (cause) {
    presentPlatformError(cause, { source: 'user-management-detail', phase: 'load' });
  }
}

/** 普通租户用户自动使用当前租户；系统管理员在表单中选择租户。 */
async function prepareCreateUser() {
  const tenantId = currentUser?.value?.system ? undefined : currentUser?.value?.tenantId;
  if (!tenantId) {
    selectedUser.value = undefined;
    selectedTenant.value = undefined;
    userDraft.value = createUserDraft(undefined);
    loadingUserDetail.value = false;
    userDetailLoadFailed.value = false;
    return;
  }
  const requestSeq = beginUserRequest(tenantId);
  selectedUser.value = undefined;
  selectedTenant.value = undefined;
  userDraft.value = createUserDraft({ id: tenantId } as Tenant);
  loadingUserDetail.value = true;
  userDetailLoadFailed.value = false;
  try {
    const tenant = await tenantContext.crud.view(tenantId);
    if (!canCommitUserRequest(tenantId, requestSeq)) return;
    selectedTenant.value = tenant ?? ({ id: tenantId, title: tenantId } as Tenant);
    userDraft.value = createUserDraft(selectedTenant.value);
  } catch (cause) {
    if (canCommitUserRequest(tenantId, requestSeq)) {
      selectedTenant.value = { id: tenantId, title: tenantId } as Tenant;
      presentPlatformError(cause, { source: 'user-management-detail', phase: 'load' });
    }
  } finally {
    if (canCommitUserRequest(tenantId, requestSeq)) loadingUserDetail.value = false;
  }
}

/** 通过用户编号独立读取完整用户和职员绑定，支持刷新或直接粘贴地址进入。 */
async function loadUserDetail(recordId: string) {
  const requestSeq = beginUserRequest(recordId);
  selectedUser.value = undefined;
  selectedTenant.value = undefined;
  userDraft.value = {};
  loadingUserDetail.value = true;
  userDetailLoadFailed.value = false;
  try {
    const fullRecord = await userContext.crud.view(recordId);
    if (!canCommitUserRequest(recordId, requestSeq)) return;
    if (!fullRecord?.id) {
      userDetailLoadFailed.value = true;
      presentPlatformMessage('未找到指定用户', { source: 'user-management-detail', phase: 'load' });
      return;
    }
    const [binding, tenant] = await Promise.all([
      loadUserEmployeeBinding(recordId),
      loadTenant(fullRecord.tenantId),
    ]);
    if (!canCommitUserRequest(recordId, requestSeq)) return;
    const user = { ...fullRecord, ...binding };
    selectedUser.value = user;
    userDraft.value = copyUser(user);
    selectedTenant.value = tenant ?? ({ id: fullRecord.tenantId, title: fullRecord.tenantId } as Tenant);
    void loadUserSessions(recordId);
  } catch (cause) {
    if (canCommitUserRequest(recordId, requestSeq)) {
      userDetailLoadFailed.value = true;
      presentPlatformError(cause, { source: 'user-management-detail', phase: 'load' });
    }
  } finally {
    if (canCommitUserRequest(recordId, requestSeq)) loadingUserDetail.value = false;
  }
}

/** 读取用户关联的职员信息。 */
function loadUserEmployeeBinding(userId: string) {
  return createBackendHttpClient().request<UserEmployeeBindingView>({
    path: `/iam.user/${encodeURIComponent(userId)}/employee-binding`,
  });
}

/** 读取租户名称；读取失败时仍可用租户编号展示页面。 */
async function loadTenant(tenantId: string | undefined) {
  if (!tenantId) return undefined;
  try {
    return await tenantContext.crud.view(tenantId);
  } catch {
    return undefined;
  }
}

/** 建立一次新请求编号，旧请求返回后不会覆盖当前页签的数据。 */
function beginUserRequest(recordId: string) {
  const requestSeq = userDetailRequestSeq.value + 1;
  userDetailRequestSeq.value = requestSeq;
  selectedUserKey.value = recordId;
  return requestSeq;
}

/** 判断这次请求仍对应当前地址和当前用户。 */
function canCommitUserRequest(recordId: string, requestSeq: number) {
  return shouldCommitUserDetailRequest({
    activeRequestSeq: userDetailRequestSeq.value,
    requestSeq,
    selectedUserKey: selectedUserKey.value,
    recordId,
  });
}

/** 处理详情页按钮，把跳转、保存和删除确认放到各自清晰的分支里。 */
function handleDetailAction(action: RecordActionItem) {
  if (action.key === 'save') {
    void saveUser();
    return;
  }
  if (action.key === 'cancel') {
    cancelEditOrPassword();
    return;
  }
  if (!selectedUser.value?.id) return;
  if (action.key === 'edit') {
    openUserAction('edit');
    return;
  }
  if (action.key === 'delete') {
    void removeUser();
    return;
  }
  if (action.key === 'resetPassword') {
    passwordDraft.value = '';
    resetPasswordResult.value = undefined;
    localMode.value = 'resetPassword';
    return;
  }
  if (action.key === 'resetGeneratedPassword') void resetUserLoginPassword();
}

/** 打开一张独立编辑页签，不复用已打开的同用户页签。 */
function openUserAction(action: Extract<UserRouteAction, 'edit'>) {
  const user = selectedUser.value;
  if (!user?.id) return;
  navigation?.openRoute(`/iam/users/${encodeURIComponent(user.id)}`, {
    tabTitle: `编辑用户：${userTitle(user)}`,
    query: { userAction: action },
  });
}

/** 保存新建、编辑或密码修改内容。 */
async function saveUser() {
  if (detailMode.value === 'resetPassword') {
    await resetUserPassword();
    return;
  }
  await executeStaticFormSave<UserAccount>({
    loading: savingUser,
    mode: detailMode.value === 'edit' ? 'edit' : 'create',
    source: 'user-management-detail',
    validateContext: () =>
      detailMode.value === 'create' && !selectedTenant.value?.id ? '请先选择租户' : undefined,
    canSave: () => canSaveUser.value,
    deniedMessage: '当前用户无权保存用户',
    createRecord: () =>
      normalizedUserDraft(userDraft.value, selectedTenant.value, detailMode.value, passwordDraft.value),
    validateRecord: validateUserDraft,
    save: (draft, mode) =>
      mode === 'edit' && selectedUser.value?.id
        ? userContext.crud.update(selectedUser.value.id, draft)
        : userContext.crud.insert(draft),
    onSaved: ({ record }) => {
      selectedUser.value = record;
      userDraft.value = copyUser(record);
      replaceWithView(record);
    },
  });
}

/** 在当前页签内改为查看地址，不改变这个页签的身份。 */
function replaceWithView(record: UserAccount) {
  if (!record.id) return;
  navigation?.replaceRoute(`/iam/users/${encodeURIComponent(record.id)}`);
}

/** 取消编辑或改密码，新建取消则直接关闭新建页签。 */
function cancelEditOrPassword() {
  if (props.action === 'add') {
    navigation?.closeCurrentTab('/iam/users');
    return;
  }
  const user = selectedUser.value;
  if (!user?.id) return;
  userDraft.value = copyUser(user);
  passwordDraft.value = '';
  resetPasswordResult.value = undefined;
  localMode.value = 'view';
  replaceWithView(user);
}

/** 修改当前用户登录密码，并刷新页面中的用户资料。 */
async function resetUserPassword() {
  await executeStaticRecordAction<UserAccount, number>({
    loading: savingUser,
    source: 'user-management-detail',
    record: () => selectedUser.value,
    canExecute: () => canSaveUser.value,
    deniedMessage: '当前用户无权修改用户密码',
    execute: (user) =>
      userContext.http.request<number>({
        method: 'POST',
        path: `/iam.user/changePassword/${encodeURIComponent(user.id!)}`,
        body: { password: passwordDraft.value },
      }),
    onExecuted: (_, user) => {
      localMode.value = 'view';
      void loadUserDetail(user.id!);
    },
  });
}

/** 请求后台生成临时密码，并在当前查看页签中展示结果。 */
async function resetUserLoginPassword() {
  await executeStaticRecordAction<UserAccount, ResetPasswordResponse>({
    loading: savingUser,
    source: 'user-management-detail',
    record: () => selectedUser.value,
    canExecute: (user) => userContext.can('resetPassword', user.id) === true,
    deniedMessage: '当前用户无权重置用户密码',
    execute: (user) =>
      userContext.http.request<ResetPasswordResponse>({
        method: 'POST',
        path: `/iam.user/resetPassword/${encodeURIComponent(user.id!)}`,
      }),
    onExecuted: (result, user) => {
      resetPasswordResult.value = result;
      void loadUserDetail(user.id!);
    },
  });
}

/** 切换用户是否允许登录，并重新读取最新资料。 */
async function toggleUserEnabled() {
  await executeStaticRecordAction({
    loading: savingUser,
    source: 'user-management-detail',
    record: () => selectedUser.value,
    canExecute: () => canToggleUser.value,
    deniedMessage: '当前用户无权变更用户启停状态',
    execute: (user) =>
      user.enabled === false
        ? userContext.crud.enable(user.id!, { version: user.version! })
        : userContext.crud.disable(user.id!, { version: user.version! }),
    onExecuted: (_, user) => void loadUserDetail(user.id!),
  });
}

/** 详情删除直接确认，成功后关闭当前用户页签并回到列表。 */
async function removeUser() {
  await executeStaticRecordAction({
    loading: savingUser,
    source: 'user-management-detail',
    record: () => selectedUser.value,
    canExecute: () => userContext.can('delete') === true,
    deniedMessage: '当前用户无权删除用户',
    confirm: (user) =>
      confirmAction({
        title: '删除用户',
        content: `确认删除用户「${userTitle(user)}」？`,
        okText: '删除',
        danger: true,
      }),
    execute: (user) => userContext.crud.delete(String(user.id), { version: user.version! }),
    onExecuted: () => {
      navigation?.closeCurrentTab('/iam/users');
    },
  });
}

/** 下线一条非当前登录会话。 */
async function revokeUserSession(session: UserSessionView) {
  await executeStaticRecordAction<UserAccount, number>({
    loading: savingUser,
    source: 'user-management-detail',
    record: () => selectedUser.value,
    canExecute: (user) => userContext.can('revokeSession', user.id) === true && !session.current,
    deniedMessage: '当前用户无权下线该登录会话',
    confirm: (user) =>
      confirmAction({
        title: '下线登录会话',
        content: `确认下线用户「${userTitle(user)}」的该登录会话？`,
        okText: '下线',
        danger: true,
      }),
    execute: (user) =>
      userContext.http.request<number>({
        method: 'POST',
        path: `/iam.user/${encodeURIComponent(user.id!)}/sessions/${encodeURIComponent(session.id)}/revoke`,
      }),
    onExecuted: (_, user) => void loadUserSessions(user.id),
  });
}

/** 下线当前用户所有可下线的会话。 */
async function revokeAllUserSessions() {
  const user = selectedUser.value;
  if (!user?.id) return;
  const sessionIds = revokableUserSessions(user.id).map((session) => session.id);
  if (sessionIds.length === 0) {
    presentPlatformMessage('当前没有可下线的登录会话', {
      source: 'user-management-detail',
      phase: 'validation',
    });
    return;
  }
  await executeStaticRecordAction<UserAccount, number>({
    loading: savingUser,
    source: 'user-management-detail',
    record: () => user,
    canExecute: (record) => userContext.can('revokeSessions', record.id) === true,
    deniedMessage: '当前用户无权批量下线登录会话',
    confirm: (record) =>
      confirmAction({
        title: '批量下线登录会话',
        content: `确认下线用户「${userTitle(record)}」的 ${sessionIds.length} 个登录会话？`,
        okText: '全部下线',
        danger: true,
      }),
    execute: (record) =>
      userContext.http.request<number>({
        method: 'POST',
        path: `/iam.user/${encodeURIComponent(record.id!)}/sessions/revoke`,
        body: { sessionIds },
      }),
    onExecuted: (_, record) => void loadUserSessions(record.id),
  });
}

/** 重试当前地址所要求的用户读取。 */
function retryUserDetail() {
  if (props.action === 'add') void prepareCreateUser();
  else if (props.userId) void loadUserDetail(props.userId);
}

/** 把地址操作换成详情内容组件可展示的状态。 */
function routeActionToDetailMode(action: UserRouteAction): UserDetailMode {
  if (action === 'add') return 'create';
  if (action === 'edit') return 'edit';
  return 'view';
}

/** 建立新建用户的默认字段。 */
function createUserDraft(tenant: Tenant | undefined): Partial<UserAccount> {
  return { tenantId: tenant?.id, enabled: true };
}

/** 系统管理员选择租户后，保存时会把所选租户写入新用户资料。 */
async function selectTenant(tenantId: string | undefined) {
  if (!tenantId) {
    selectedTenant.value = undefined;
    userDraft.value = createUserDraft(undefined);
    return;
  }
  const tenant = await tenantContext.crud.view(tenantId);
  selectedTenant.value = tenant ?? ({ id: tenantId, title: tenantId } as Tenant);
  userDraft.value = { ...userDraft.value, tenantId };
}

/** 复制用户资料，避免编辑时直接改写已加载的原始资料。 */
function copyUser(record: Partial<UserAccount>): Partial<UserAccount> {
  return { ...record, password: undefined };
}

/** 整理表单输入，确保新建时带上租户和初始密码。 */
function normalizedUserDraft(
  draft: Partial<UserAccount>,
  tenant: Tenant | undefined,
  mode: UserDetailMode,
  password: string,
): UserAccount {
  return normalizeRecordDraft<UserAccount>(draft, {
    tenantId: tenant?.id ?? draft.tenantId,
    username: draft.username?.trim(),
    enabled: draft.enabled !== false,
    password: mode === 'create' ? password.trim() : undefined,
  });
}

/** 在提交前检查必填账号和密码。 */
function validateUserDraft(draft: UserAccount) {
  const usernameField = resolveRecordFormFieldState('username', {
    fields: userFormFieldDefinitions.value,
    fallback: userFormFieldFallback.value,
  });
  if (usernameField.visible && usernameField.required && !draft.username)
    return `请填写${usernameField.label}`;
  if (detailMode.value === 'create' && !draft.password) return '请填写初始密码';
  if (detailMode.value === 'resetPassword' && !passwordDraft.value.trim()) return '请填写新密码';
  return undefined;
}

/** 编辑已有用户时不允许改登录账号。 */
function userFormFieldDisabled(fieldName: string) {
  return fieldName === 'username' && detailMode.value === 'edit';
}

/** 把表单字段变化写入当前页签自己的草稿。 */
function updateUserDraftField(fieldName: string, value: RecordFormFieldValue) {
  userDraft.value = { ...userDraft.value, [fieldName]: value };
}

/** 根据当前启停状态选择后台权限名称。 */
function userToggleActionCode(record: Partial<UserAccount>) {
  return record.enabled === false ? 'enable' : 'disable';
}

/** 生成用户的显示名称。 */
function userTitle(record: Partial<UserAccount> | undefined) {
  return String(record?.username ?? record?.id ?? '用户');
}

/** 生成用户已关联职员的说明。 */
function userEmployeeSubtitle(record: Partial<UserAccount> | undefined) {
  const employeeTitle = String(record?.employeeTitle ?? '').trim();
  return employeeTitle ? `职员：${employeeTitle}` : '未关联职员';
}

/** 生成人能看懂的租户名称。 */
function tenantTitle(record: Tenant | undefined) {
  return String(record?.title ?? record?.alias ?? record?.id ?? '未命名租户');
}

/** 取得当前用户可下线的会话。 */
function revokableUserSessions(userId: string | undefined) {
  if (!userId || userContext.can('revokeSession', userId) !== true) return [];
  return userSessionState(userId).records.filter((session) => !session.current);
}

/** 判断某条会话能否在页面上显示下线操作。 */
function canRevokeUserSession(userId: string | undefined, session: UserSessionView) {
  return Boolean(userId) && !session.current && userContext.can('revokeSession', userId) === true;
}

/** 用户详情字段没有特殊转换，交给通用字段展示。 */
const userDetailDisplayValue = () => undefined;
</script>

<template>
  <RecordDetailPanel :title="userDetailTitle" :subtitle="userDetailSubtitle">
    <template #status>
      <RecordStatusSwitch
        v-if="detailMode === 'view' && selectedUser"
        :enabled="selectedUser.enabled !== false"
        :disabled="savingUser || !canToggleUser"
        :loading="savingUser"
        :show-label="false"
        @change="toggleUserEnabled"
      />
    </template>
    <template #operation>
      <RecordActionBar
        :context="userContext"
        :actions="detailActions"
        :record-id="selectedUser?.id"
        @action="handleDetailAction"
      />
    </template>

    <UserDetailContent
      :mode="detailMode"
      :draft="userDraft"
      :selected-user="selectedUser"
      :loading="loadingUserDetail"
      :load-failed="userDetailLoadFailed"
      :saving="savingUser || loadingUserDetail"
      :tenant-title="tenantTitle(selectedTenant)"
      :fields="userFormFieldDefinitions"
      :fallback="userFormFieldFallback"
      :field-names="userFormFieldNames"
      :password="passwordDraft"
      :reset-password-result="resetPasswordResult"
      :display-of="userDetailDisplayValue"
      :disabled-of="userFormFieldDisabled"
      @retry="retryUserDetail"
      @save="saveUser"
      @update:field="updateUserDraftField"
      @update:password="passwordDraft = $event"
    />

    <label v-if="canSelectTenant" class="tenant-selector">
      <span>所属租户</span>
      <RecordPicker
        :context="tenantPickerContext"
        :value="selectedTenant?.id"
        mode="list"
        placeholder="请选择租户"
        :disabled="savingUser"
        @update:value="selectTenant"
      />
    </label>

    <section v-if="detailMode === 'view' && selectedUser" class="user-session-section">
      <h3>登录会话</h3>
      <UserSessionExpandedSubtable
        :sessions="userSessionState(String(selectedUser.id ?? '')).records"
        :loading="userSessionState(String(selectedUser.id ?? '')).loading"
        :error="userSessionState(String(selectedUser.id ?? '')).error"
        :actions-disabled="savingUser"
        :can-revoke="(session) => canRevokeUserSession(selectedUser?.id, session)"
        :can-revoke-all="revokableUserSessions(selectedUser.id).length > 1"
        @refresh="loadUserSessions(String(selectedUser?.id ?? ''))"
        @revoke="revokeUserSession($event)"
        @revoke-all="revokeAllUserSessions"
      />
    </section>
  </RecordDetailPanel>
</template>

<style scoped>
.tenant-selector {
  display: grid;
  gap: 6px;
  margin-top: 12px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.user-session-section {
  display: grid;
  gap: 8px;
  margin-top: 20px;
}

.user-session-section h3 {
  margin: 0;
  font-size: 15px;
}
</style>
