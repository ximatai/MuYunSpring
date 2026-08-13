<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  DateTimeText,
  RecordActionBar,
  RecordDetailFields,
  RecordDetailPanel,
  RecordFormFields,
  RecordMetaSection,
  RecordModeDrawer,
  RecordQueryListPanel,
  RecordStatusSwitch,
  UserSessionExpandedSubtable,
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
import { UiButton, UiError, UiInput, UiSpin, confirmAction } from '@muyun/vue-ui-antdv';
import type {
  ResetPasswordResponse,
  UserAccount,
  UserSessionView,
  WebQueryRequest,
} from '@muyun/web-contracts';
import { platformErrorCodes, useModuleContext, type ModuleContext } from '@muyun/web-core';
import {
  usePageBusinessEventHandler,
  usePageRecordExternalChange,
} from '../platform-admin-runtime/pageRealtime';
import { useWorkspaceViewHost } from '../platform-admin-runtime/workspaceViewHost';
import { useWorkspaceViewPromotion } from '../platform-admin-runtime/useWorkspaceViewPromotion';
import { systemUserDetailWorkspaceView } from './systemUserDetailWorkspaceView';
import {
  handOffSystemUserDetailWorkspaceSession,
  registerSystemUserDetailWorkspaceHandoffRecipient,
  takeSystemUserDetailWorkspaceSession,
  type SystemUserDetailWorkspaceSession,
} from './systemUserDetailWorkspaceSession';
import { useUserSessionRows } from './useUserSessionRows';

defineOptions({ name: 'SystemUserManagementView' });

const props = defineProps<{
  recordId?: string;
  mode?: 'view' | 'edit';
}>();

type SystemUserDetailMode = 'view' | 'edit' | 'resetPassword';
type SystemUserFormFieldName = 'username' | 'enabled';

const userContext = useModuleContext<UserAccount>({ moduleAlias: 'iam.user' });
const workspaceViewHost = useWorkspaceViewHost();
const selectedUserKey = ref<string>();
const selectedUser = ref<UserAccount>();
const detailOpen = ref(false);
const detailMode = ref<SystemUserDetailMode>('view');
const loadingDetail = ref(false);
const detailLoadFailed = ref(false);
const savingUser = ref(false);
const detailRequestSeq = ref(0);
const reloadKey = ref(0);
const userDraft = ref<Partial<UserAccount>>(createSystemUserDraft());
const passwordDraft = ref('');
const resetPasswordResult = ref<ResetPasswordResponse>();
const formFieldDefinitions = ref(resolveRecordFormFields(undefined));
const isWorkspaceTask = computed(() => Boolean(props.recordId));
const isDrawerWorkspaceTask = computed(
  () => isWorkspaceTask.value && workspaceViewHost?.presentation === 'drawer',
);
const shouldRenderDetailDrawer = computed(() => !isWorkspaceTask.value || isDrawerWorkspaceTask.value);
const {
  expandedUserKeys,
  handleUserListLoaded,
  handleUserRowExpand,
  handleUserSessionBusinessEvent,
  loadUserSessions,
  userOnlineStatusTitle,
  userSessionState,
} = useUserSessionRows({ context: userContext, source: 'system-user-management' });

const userExternalChange = usePageRecordExternalChange({
  moduleAlias: 'iam.user',
  recordId: () => selectedUser.value?.id,
  editing: () => detailMode.value === 'edit',
  saving: () => savingUser.value,
});

const systemUserContext = computed(
  () => createSystemUserModuleContext(userContext) as ModuleContext<QueryListRecord>,
);
const columns = computed<RecordQueryListColumn[]>(() => [
  { key: 'username', title: '账号', width: '24%' },
  { key: 'onlineStatus', title: '在线状态', width: '14%', align: 'center' },
  { key: 'passwordStatusTitle', title: '密码状态', width: '18%' },
  { key: 'lastLoginAt', title: '最后登录', type: 'datetime', width: '24%' },
  { key: 'enabled', title: '登录状态', type: 'enabledStatus', width: '14%' },
]);
const detailTitle = computed(() => {
  return systemUserTitle(selectedUser.value ?? userDraft.value);
});
const detailSubtitle = computed(() => (detailMode.value === 'resetPassword' ? '修改密码' : '系统账号'));
const formDisabled = computed(() => savingUser.value || loadingDetail.value);
const canSaveUser = computed(() => {
  if (loadingDetail.value) {
    return false;
  }
  if (detailMode.value === 'edit') {
    return Boolean(selectedUser.value?.id) && userContext.can('update') === true;
  }
  if (detailMode.value === 'resetPassword') {
    const userId = selectedUser.value?.id;
    return Boolean(userId) && userContext.can('changePassword', userId) === true;
  }
  return false;
});
const canToggleUser = computed(() => {
  if (!selectedUser.value?.id || loadingDetail.value) {
    return false;
  }
  return userContext.can(systemUserToggleActionCode(selectedUser.value)) === true;
});
const detailActions = computed<RecordActionItem[]>(() => {
  if (detailMode.value === 'view') {
    if (!selectedUser.value?.id) {
      return [];
    }
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
    ];
  }
  return [
    { key: 'cancel', title: '取消', iconName: 'close', disabled: savingUser.value },
    {
      key: 'save',
      actionCode: detailMode.value === 'resetPassword' ? 'changePassword' : 'update',
      title: '保存',
      iconName: 'save',
      primary: true,
      loading: savingUser.value,
      disabled: !canSaveUser.value,
    },
  ];
});
const detailOperationActions = computed(() => detailActions.value);
const systemUserDetailPromotion = useWorkspaceViewPromotion({
  view: systemUserDetailWorkspaceView,
  input: computed(() => {
    const recordId = selectedUser.value?.id;
    return recordId && (detailMode.value === 'view' || detailMode.value === 'edit')
      ? { recordId }
      : undefined;
  }),
  title: computed(() => systemUserTitle(selectedUser.value)),
  eligibility: computed(() => ({
    hasStableIdentity: Boolean(selectedUser.value?.id) && !loadingDetail.value,
    busy: savingUser.value,
  })),
  beforePromote: async (input) => {
    const selected = selectedUser.value;
    if (!selected) return;
    return (
      (await handOffSystemUserDetailWorkspaceSession(input, {
        selectedUser: selected,
        draft: userDraft.value,
        mode: detailMode.value === 'edit' ? 'edit' : 'view',
        password: passwordDraft.value,
        resetPasswordResult: resetPasswordResult.value,
      })) === 'accepted'
    );
  },
  onPromoted: closeDetail,
});

watch(
  selectedUser,
  (user) => {
    if (!isWorkspaceTask.value || !user) return;
    workspaceViewHost?.setTitle(systemUserTitle(user));
  },
  { immediate: true },
);
const formFieldFallback = computed<Record<SystemUserFormFieldName, RecordFormFieldFallback>>(() => ({
  username: { label: '账号', required: true, visible: true, placeholder: '请输入登录账号' },
  enabled: { label: '允许登录', visible: true, controlType: 'enabledStatus' },
}));
const formFieldNames = computed<SystemUserFormFieldName[]>(() => ['username', 'enabled']);

let disposeSystemUserWorkspaceHandoffRecipient: (() => void) | undefined;

onMounted(() => {
  void loadFormDefinition();
  if (props.recordId) {
    const input = { recordId: props.recordId } as const;
    if (!isDrawerWorkspaceTask.value) {
      disposeSystemUserWorkspaceHandoffRecipient = registerSystemUserDetailWorkspaceHandoffRecipient(
        input,
        receiveSystemUserDetailWorkspaceSession,
      );
    }
    const session = takeSystemUserDetailWorkspaceSession(input);
    if (session) {
      restoreSystemUserDetailWorkspaceSession(session);
      return;
    }
    void openDetail({ id: props.recordId }, props.mode ?? 'view');
  }
});

onBeforeUnmount(() => disposeSystemUserWorkspaceHandoffRecipient?.());

function receiveSystemUserDetailWorkspaceSession(session: SystemUserDetailWorkspaceSession) {
  if (detailMode.value !== 'view') return false;
  restoreSystemUserDetailWorkspaceSession(session);
  return true;
}

usePageBusinessEventHandler(handleUserSessionBusinessEvent);

async function loadFormDefinition() {
  try {
    const runtimeContext = await userContext.runtime.ready;
    formFieldDefinitions.value = resolveRecordFormFields(runtimeContext.uiDescriptor);
  } catch (cause) {
    presentPlatformError(cause, { source: 'system-user-management', phase: 'load' });
  }
}

function createSystemUserModuleContext(context: ModuleContext<UserAccount>): ModuleContext<UserAccount> {
  return {
    ...context,
    crud: {
      ...context.crud,
      query: (request) => context.crud.query(systemUserQuery(request)),
    },
  };
}

function systemUserQuery(request: WebQueryRequest | undefined): WebQueryRequest {
  return {
    ...request,
    conditions: [...(request?.conditions ?? []), { fieldName: 'tenantId', operator: 'NULL', values: [] }],
  };
}

function rowActionsOf(): RecordActionItem[] {
  return [
    { key: 'view', actionCode: 'view', title: '查看' },
    { key: 'edit', actionCode: 'update', title: '编辑', iconName: 'edit' },
  ];
}

function handleRowAction(action: ResolvedRecordActionItem, record: QueryListRecord) {
  if (!canLeaveDetailContext()) {
    return;
  }
  if (action.key === 'view') {
    void openDetail(record, 'view');
    return;
  }
  if (action.key === 'edit') {
    void openDetail(record, 'edit');
  }
}

function handleRowDblclick(record: QueryListRecord) {
  if (!canLeaveDetailContext()) {
    return;
  }
  void openDetail(record, 'view');
}

async function openDetail(record: QueryListRecord, mode: SystemUserDetailMode) {
  if (!canLeaveDetailContext()) {
    return;
  }
  const id = String(record.id ?? '');
  if (!id) {
    return;
  }
  selectedUserKey.value = id;
  userExternalChange.clearExternalChanged();
  detailOpen.value = true;
  detailMode.value = mode;
  selectedUser.value = undefined;
  userDraft.value = copySystemUser(record as UserAccount);
  passwordDraft.value = '';
  resetPasswordResult.value = undefined;
  loadingDetail.value = true;
  detailLoadFailed.value = false;
  const requestSeq = detailRequestSeq.value + 1;
  detailRequestSeq.value = requestSeq;
  try {
    const fullRecord = await userContext.crud.view(id);
    if (!canCommitDetailRequest(id, requestSeq)) {
      return;
    }
    if (!fullRecord?.id) {
      detailLoadFailed.value = true;
      presentPlatformMessage('未找到指定系统账号', { source: 'system-user-management', phase: 'load' });
      return;
    }
    if (fullRecord.tenantId) {
      rejectTenantUserWorkspace();
      return;
    }
    commitDetailRecord(fullRecord, mode);
  } catch (cause) {
    if (canCommitDetailRequest(id, requestSeq)) {
      detailLoadFailed.value = true;
      presentPlatformError(cause, { source: 'system-user-management', phase: 'load' });
    }
  } finally {
    if (canCommitDetailRequest(id, requestSeq)) {
      loadingDetail.value = false;
    }
  }
}

function closeDetail() {
  if (savingUser.value) {
    return;
  }
  detailRequestSeq.value += 1;
  loadingDetail.value = false;
  detailLoadFailed.value = false;
  detailOpen.value = false;
  detailMode.value = 'view';
  passwordDraft.value = '';
  resetPasswordResult.value = undefined;
  userExternalChange.clearExternalChanged();
  userDraft.value = selectedUser.value ? copySystemUser(selectedUser.value) : createSystemUserDraft();
  if (isDrawerWorkspaceTask.value) {
    workspaceViewHost?.dismiss();
  }
}

/** A system-account URL must never become an alternate entry for a tenant user. */
function rejectTenantUserWorkspace() {
  detailRequestSeq.value += 1;
  loadingDetail.value = false;
  detailLoadFailed.value = false;
  detailOpen.value = false;
  selectedUser.value = undefined;
  selectedUserKey.value = undefined;
  userDraft.value = createSystemUserDraft();
  presentPlatformMessage('指定账号不属于系统账号管理范围', {
    source: 'system-user-management',
    phase: 'validation',
  });
  if (isWorkspaceTask.value) {
    workspaceViewHost?.dismiss();
  }
}

function cancelDetail() {
  if (savingUser.value) {
    return;
  }
  if (!selectedUser.value?.id) {
    closeDetail();
    return;
  }
  userDraft.value = copySystemUser(selectedUser.value);
  passwordDraft.value = '';
  resetPasswordResult.value = undefined;
  userExternalChange.clearExternalChanged();
  detailMode.value = 'view';
  loadingDetail.value = false;
  detailLoadFailed.value = false;
}

function handleDetailAction(action: RecordActionItem) {
  if (action.key === 'cancel') {
    cancelDetail();
    return;
  }
  if (action.key === 'save') {
    void saveUser();
    return;
  }
  if (!canLeaveDetailContext()) {
    return;
  }
  if (action.key === 'edit' && selectedUser.value) {
    userDraft.value = copySystemUser(selectedUser.value);
    userExternalChange.clearExternalChanged();
    detailMode.value = 'edit';
    return;
  }
  if (action.key === 'resetPassword' && selectedUser.value) {
    passwordDraft.value = '';
    resetPasswordResult.value = undefined;
    userExternalChange.clearExternalChanged();
    detailMode.value = 'resetPassword';
    return;
  }
  if (action.key === 'resetGeneratedPassword' && selectedUser.value) {
    void resetUserLoginPassword();
  }
}

function retryDetail() {
  const id = String(userDraft.value.id ?? selectedUserKey.value ?? '');
  if (!id) {
    return;
  }
  void openDetail({ ...userDraft.value, id } as QueryListRecord, detailMode.value);
}

function reloadExternalUserChange() {
  const id = String(
    userExternalChange.externalChangedRecordId.value ?? userDraft.value.id ?? selectedUserKey.value ?? '',
  );
  if (!id) {
    return;
  }
  userExternalChange.clearExternalChanged();
  void openDetail({ ...userDraft.value, id } as QueryListRecord, 'edit');
}

async function saveUser() {
  if (detailMode.value === 'resetPassword') {
    await resetUserPassword();
    return;
  }
  await executeStaticFormSave<UserAccount>({
    loading: savingUser,
    mode: 'edit',
    source: 'system-user-management',
    canSave: () => canSaveUser.value,
    deniedMessage: '当前用户无权保存系统账号',
    createRecord: () => normalizedSystemUserDraft(userDraft.value),
    validateRecord: validateSystemUserDraft,
    save: (draft) => userContext.crud.update(selectedUser.value!.id!, draft),
    actionErrorHandlers: [
      {
        code: platformErrorCodes.conflictVersion,
        handle: (_error, { mode, record }) =>
          mode === 'edit' && userExternalChange.markExternalRecordChanged(record.id),
      },
    ],
    onSaved: ({ record }) => {
      commitDetailRecord(record);
      reloadKey.value += 1;
    },
  });
}

async function resetUserPassword() {
  if (!passwordDraft.value.trim()) {
    presentPlatformMessage('请填写新密码', { source: 'system-user-management', phase: 'validation' });
    return;
  }
  await executeStaticRecordAction<UserAccount, number>({
    loading: savingUser,
    source: 'system-user-management',
    record: () => (selectedUser.value?.id ? selectedUser.value : undefined),
    canExecute: () => canSaveUser.value,
    deniedMessage: '当前用户无权重置系统账号密码',
    execute: (user) =>
      userContext.http.request<number>({
        method: 'POST',
        path: `/iam.user/changePassword/${encodeURIComponent(user.id!)}`,
        body: { password: passwordDraft.value },
      }),
    onExecuted: async (_, user) => {
      const refreshed = await userContext.crud.view(user.id!);
      commitDetailRecord(refreshed);
      reloadKey.value += 1;
    },
  });
}

async function resetUserLoginPassword() {
  await executeStaticRecordAction<UserAccount, ResetPasswordResponse>({
    loading: savingUser,
    source: 'system-user-management',
    record: () => (selectedUser.value?.id ? selectedUser.value : undefined),
    canExecute: (user) => userContext.can('resetPassword', user.id) === true,
    deniedMessage: '当前用户无权重置系统账号密码',
    execute: (user) =>
      userContext.http.request<ResetPasswordResponse>({
        method: 'POST',
        path: `/iam.user/resetPassword/${encodeURIComponent(user.id!)}`,
      }),
    onExecuted: async (result, user) => {
      const refreshed = await userContext.crud.view(user.id!);
      commitDetailRecord(refreshed);
      resetPasswordResult.value = result;
      reloadKey.value += 1;
    },
  });
}

async function revokeUserSession(record: Partial<UserAccount> | QueryListRecord, session: UserSessionView) {
  await executeStaticRecordAction<UserAccount, number>({
    loading: savingUser,
    source: 'system-user-management',
    record: () => (record?.id ? (record as UserAccount) : undefined),
    canExecute: (user) => userContext.can('revokeSession', user.id) === true && !session.current,
    deniedMessage: '当前用户无权下线该登录会话',
    confirm: (user) =>
      confirmAction({
        title: '下线登录会话',
        content: `确认下线系统账号「${systemUserTitle(user)}」的该登录会话？`,
        okText: '下线',
        danger: true,
      }),
    execute: (user) =>
      userContext.http.request<number>({
        method: 'POST',
        path: `/iam.user/${encodeURIComponent(user.id!)}/sessions/${encodeURIComponent(session.id)}/revoke`,
      }),
    onExecuted: (_, user) => {
      void loadUserSessions(user.id);
      reloadKey.value += 1;
    },
  });
}

async function revokeAllUserSessions(record: Partial<UserAccount> | QueryListRecord) {
  const userId = String(record.id ?? '');
  const sessionIds = revokableUserSessions(userId).map((session) => session.id);
  if (sessionIds.length === 0) {
    presentPlatformMessage('当前没有可下线的登录会话', {
      source: 'system-user-management',
      phase: 'validation',
    });
    return;
  }
  await executeStaticRecordAction<UserAccount, number>({
    loading: savingUser,
    source: 'system-user-management',
    record: () => (record?.id ? (record as UserAccount) : undefined),
    canExecute: (user) => userContext.can('revokeSessions', user.id) === true,
    deniedMessage: '当前用户无权批量下线登录会话',
    confirm: (user) =>
      confirmAction({
        title: '批量下线登录会话',
        content: `确认下线系统账号「${systemUserTitle(user)}」的 ${sessionIds.length} 个登录会话？`,
        okText: '全部下线',
        danger: true,
      }),
    execute: (user) =>
      userContext.http.request<number>({
        method: 'POST',
        path: `/iam.user/${encodeURIComponent(user.id!)}/sessions/revoke`,
        body: { sessionIds },
      }),
    onExecuted: (_, user) => {
      void loadUserSessions(user.id);
      reloadKey.value += 1;
    },
  });
}

async function toggleUserEnabled() {
  await executeStaticRecordAction({
    loading: savingUser,
    source: 'system-user-management',
    record: () => (selectedUser.value?.id ? selectedUser.value : undefined),
    canExecute: () => canToggleUser.value,
    deniedMessage: '当前用户无权变更系统账号启停状态',
    execute: (user) =>
      user.enabled === false
        ? userContext.crud.enable(user.id!, { version: user.version! })
        : userContext.crud.disable(user.id!, { version: user.version! }),
    onExecuted: async (_, user) => {
      const refreshed = await userContext.crud.view(user.id!);
      commitDetailRecord(refreshed);
      reloadKey.value += 1;
    },
  });
}

function canLeaveDetailContext() {
  return !savingUser.value;
}

function canCommitDetailRequest(recordId: string, requestSeq: number) {
  return detailRequestSeq.value === requestSeq && selectedUserKey.value === recordId;
}

function commitDetailRecord(record: UserAccount, nextMode: SystemUserDetailMode = 'view') {
  selectedUser.value = record;
  selectedUserKey.value = record.id;
  userDraft.value = copySystemUser(record);
  passwordDraft.value = '';
  detailMode.value = nextMode === 'edit' ? 'edit' : 'view';
  userExternalChange.clearExternalChanged();
  detailOpen.value = true;
  loadingDetail.value = false;
  detailLoadFailed.value = false;
  detailRequestSeq.value += 1;
}

function restoreSystemUserDetailWorkspaceSession(session: SystemUserDetailWorkspaceSession) {
  selectedUserKey.value = session.selectedUser.id;
  selectedUser.value = { ...session.selectedUser };
  userDraft.value = copySystemUser(session.draft);
  detailMode.value = session.mode;
  passwordDraft.value = session.password;
  resetPasswordResult.value = session.resetPasswordResult;
  detailOpen.value = true;
  loadingDetail.value = false;
  detailLoadFailed.value = false;
}

function revokableUserSessions(userId: string | undefined) {
  if (!userId || userContext.can('revokeSession', userId) !== true) {
    return [];
  }
  return userSessionState(userId).records.filter((session) => !session.current);
}

function canRevokeUserSession(userId: string | undefined, session: UserSessionView) {
  return Boolean(userId) && !session.current && userContext.can('revokeSession', userId) === true;
}

function createSystemUserDraft(): Partial<UserAccount> {
  return {
    enabled: true,
  };
}

function copySystemUser(record: Partial<UserAccount>): Partial<UserAccount> {
  return { ...record, password: undefined };
}

function normalizedSystemUserDraft(draft: Partial<UserAccount>): UserAccount {
  return normalizeRecordDraft<UserAccount>(draft, {
    tenantId: undefined,
    username: draft.username?.trim(),
    enabled: draft.enabled !== false,
    password: undefined,
  });
}

function validateSystemUserDraft(draft: UserAccount) {
  const field = resolveRecordFormFieldState('username', {
    fields: formFieldDefinitions.value,
    fallback: formFieldFallback.value,
  });
  if (field.visible && field.required && !draft.username) {
    return `请填写${field.label}`;
  }
  if (detailMode.value === 'resetPassword' && !passwordDraft.value.trim()) {
    return '请填写新密码';
  }
  return undefined;
}

function systemUserFormFieldDisabled(fieldName: string) {
  return fieldName === 'username';
}

function updateUserDraftField(
  fieldName: string,
  value: import('@muyun/platform-components').RecordFormFieldValue,
) {
  userDraft.value = {
    ...userDraft.value,
    [fieldName]: value,
  };
}

function systemUserToggleActionCode(record: Partial<UserAccount>) {
  return record.enabled === false ? 'enable' : 'disable';
}

function systemUserTitle(record: Partial<UserAccount> | QueryListRecord | undefined) {
  return String(record?.username ?? record?.id ?? '系统账号');
}
</script>

<template>
  <section class="system-user-management-page">
    <RecordQueryListPanel
      v-if="!isWorkspaceTask || isDrawerWorkspaceTask"
      class="system-user-list-panel"
      :context="systemUserContext"
      title="系统账号"
      :columns="columns"
      :cell-renderers="{ onlineStatus: userOnlineStatusTitle }"
      :row-actions-of="rowActionsOf"
      :selected-key="selectedUserKey"
      :expanded-row-keys="expandedUserKeys"
      :reload-key="reloadKey"
      :ready="true"
      quick-search-placeholder="搜索账号、姓名、手机号或邮箱"
      empty-description="暂无系统账号"
      @row-action="handleRowAction"
      @row-dblclick="handleRowDblclick"
      @row-expand="handleUserRowExpand"
      @loaded="handleUserListLoaded"
      @select="selectedUserKey = String($event.id ?? '')"
    >
      <template #expandedRow="{ record }">
        <UserSessionExpandedSubtable
          :sessions="userSessionState(String(record.id ?? '')).records"
          :loading="userSessionState(String(record.id ?? '')).loading"
          :error="userSessionState(String(record.id ?? '')).error"
          :actions-disabled="savingUser"
          :can-revoke="(session) => canRevokeUserSession(String(record.id ?? ''), session)"
          :can-revoke-all="revokableUserSessions(String(record.id ?? '')).length > 1"
          @refresh="loadUserSessions(String(record.id ?? ''))"
          @revoke="revokeUserSession(record, $event)"
          @revoke-all="revokeAllUserSessions(record)"
        />
      </template>
    </RecordQueryListPanel>

    <RecordModeDrawer
      v-if="shouldRenderDetailDrawer"
      :open="detailOpen"
      :title="detailTitle"
      :subtitle="detailSubtitle"
      :mode="detailMode"
      :form-modes="['edit', 'resetPassword']"
      :loading="loadingDetail"
      :load-failed="detailLoadFailed"
      :externally-changed="userExternalChange.externallyChanged.value"
      :promotion="systemUserDetailPromotion"
      error-title="详情加载失败"
      error-message="无法加载系统账号详情，请重试"
      @close="closeDetail"
      @retry="retryDetail"
      @reload-external-change="reloadExternalUserChange"
      @dismiss-external-change="userExternalChange.clearExternalChanged"
    >
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
          :context="systemUserContext"
          :actions="detailOperationActions"
          :record-id="selectedUser?.id"
          @action="handleDetailAction"
        />
      </template>

      <template #loading>
        <UiSpin class="system-user-detail-state" tip="加载系统账号详情" />
      </template>
      <template #error>
        <div class="system-user-detail-state">
          <UiError title="详情加载失败" message="无法加载系统账号详情，请重试" />
          <UiButton type="primary" icon-name="reload" @click="retryDetail">重试</UiButton>
        </div>
      </template>

      <template #view>
        <RecordDetailFields
          :record="userDraft as RecordFormRecord"
          :fields="formFieldDefinitions"
          :fallback="formFieldFallback"
        />
        <div
          v-if="detailMode === 'view' && resetPasswordResult?.temporaryPassword"
          class="system-user-password-reset-result"
        >
          <span>临时密码</span>
          <UiInput :value="resetPasswordResult.temporaryPassword" disabled />
          <small v-if="resetPasswordResult.expiresAt">
            有效期至 <DateTimeText :value="resetPasswordResult.expiresAt" />
          </small>
        </div>
        <RecordMetaSection :record="userDraft" />
      </template>

      <template #form>
        <form class="system-user-form" @submit.prevent="saveUser">
          <RecordFormFields
            v-if="detailMode !== 'resetPassword'"
            :record="userDraft as RecordFormRecord"
            :field-names="formFieldNames"
            :fields="formFieldDefinitions"
            :fallback="formFieldFallback"
            :disabled="formDisabled"
            :disabled-of="systemUserFormFieldDisabled"
            @update:field="updateUserDraftField"
          />
          <label v-else>
            <span class="system-user-form-label">新密码</span>
            <UiInput
              :value="passwordDraft"
              type="password"
              :disabled="formDisabled"
              placeholder="请输入密码"
              allow-clear
              @update:value="passwordDraft = $event"
            />
          </label>
        </form>
        <RecordMetaSection v-if="detailMode !== 'resetPassword'" :record="userDraft" />
      </template>
    </RecordModeDrawer>

    <RecordDetailPanel v-else :title="detailTitle" :subtitle="detailSubtitle">
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

      <UiSpin v-if="loadingDetail" class="system-user-detail-state" tip="加载系统账号详情" />
      <div v-else-if="detailLoadFailed" class="system-user-detail-state">
        <UiError title="详情加载失败" message="无法加载系统账号详情，请重试" />
        <UiButton type="primary" icon-name="reload" @click="retryDetail">重试</UiButton>
      </div>
      <template v-else-if="detailMode === 'view'">
        <RecordDetailFields
          :record="userDraft as RecordFormRecord"
          :fields="formFieldDefinitions"
          :fallback="formFieldFallback"
        />
        <div v-if="resetPasswordResult?.temporaryPassword" class="system-user-password-reset-result">
          <span>临时密码</span>
          <UiInput :value="resetPasswordResult.temporaryPassword" disabled />
          <small v-if="resetPasswordResult.expiresAt">
            有效期至 <DateTimeText :value="resetPasswordResult.expiresAt" />
          </small>
        </div>
        <RecordMetaSection :record="userDraft" />
      </template>
      <template v-else>
        <form class="system-user-form" @submit.prevent="saveUser">
          <RecordFormFields
            v-if="detailMode !== 'resetPassword'"
            :record="userDraft as RecordFormRecord"
            :field-names="formFieldNames"
            :fields="formFieldDefinitions"
            :fallback="formFieldFallback"
            :disabled="formDisabled"
            :disabled-of="systemUserFormFieldDisabled"
            @update:field="updateUserDraftField"
          />
          <label v-else>
            <span class="system-user-form-label">新密码</span>
            <UiInput
              :value="passwordDraft"
              type="password"
              :disabled="formDisabled"
              placeholder="请输入密码"
              allow-clear
              @update:value="passwordDraft = $event"
            />
          </label>
        </form>
        <RecordMetaSection v-if="detailMode !== 'resetPassword'" :record="userDraft" />
      </template>

      <template #operation>
        <RecordActionBar
          :context="systemUserContext"
          :actions="detailOperationActions"
          :record-id="selectedUser?.id"
          @action="handleDetailAction"
        />
      </template>
    </RecordDetailPanel>
  </section>
</template>

<style scoped>
.system-user-management-page {
  position: relative;
  display: grid;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.system-user-list-panel {
  min-width: 0;
  min-height: 0;
}

.system-user-form {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

.system-user-form > label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.system-user-form-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.system-user-password-reset-result {
  display: grid;
  gap: 6px;
  margin: 12px 0;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-hover-subtle);
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.system-user-password-reset-result small {
  color: var(--muyun-text-muted);
}

.system-user-detail-state {
  display: grid;
  place-items: center;
  gap: 12px;
  min-height: 180px;
}

@media (max-width: 980px) {
  .system-user-management-page {
    height: auto;
    overflow: visible;
  }
}
</style>
