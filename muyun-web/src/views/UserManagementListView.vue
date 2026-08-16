<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue';
import {
  CrudRecordListExplorer,
  RecordExplorerPanel,
  RecordQueryListPanel,
  UserSessionExpandedSubtable,
  executeStaticRecordAction,
  presentPlatformMessage,
  type CrudRecordListBase,
  type QueryListRecord,
  type RecordActionItem,
  type RecordExplorerItemDescriptor,
  type RecordQueryListColumn,
  type ResolvedRecordActionItem,
} from '@muyun/platform-components';
import { UiRecordExplorerItem, confirmAction } from '@muyun/vue-ui-antdv';
import type { Tenant, UserAccount, UserSessionView, WebQueryRequest } from '@muyun/web-contracts';
import { useModuleContext, type ModuleContext } from '@muyun/web-core';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import { usePageBusinessEventHandler } from '../platform-admin-runtime/pageRealtime';
import { useUserSessionRows } from './useUserSessionRows';
import type { UserRouteAction } from './userManagementRouteState';

defineOptions({ name: 'UserManagementListView' });

const tenantContext = useModuleContext<Tenant>({ moduleAlias: 'iam.tenant' });
const userContext = useModuleContext<UserAccount>({ moduleAlias: 'iam.user' });
const currentUser = useCurrentUserContext();
const navigation = useWorkbenchNavigation();
const tenantSearchKeyword = ref('');
const tenantReloadKey = ref(0);
const userReloadKey = ref(0);
const selectedTenant = ref<Tenant>();
const selectedUserKey = ref<string>();
const actionLoading = ref(false);
const {
  expandedUserKeys,
  handleUserListLoaded,
  handleUserRowExpand,
  handleUserSessionBusinessEvent,
  loadUserSessions,
  resetUserSessionRows,
  userOnlineStatusTitle,
  userSessionState,
} = useUserSessionRows({ context: userContext, source: 'user-management-list' });

const tenantListContext = computed(() => tenantContext as unknown as ModuleContext<CrudRecordListBase>);
const canBrowseTenants = computed(() => currentUser?.value?.system === true);
const currentUserTenant = computed<Tenant | undefined>(() => {
  const tenantId = currentUser?.value?.tenantId;
  if (currentUser?.value?.system === true || !tenantId) return undefined;
  return { id: tenantId, title: tenantId, alias: tenantId, enabled: true } as Tenant;
});
const userListContext = computed(
  () => createScopedUserModuleContext(userContext, selectedTenant.value) as ModuleContext<QueryListRecord>,
);
const userListColumns = computed<RecordQueryListColumn[]>(() => [
  { key: 'username', title: '账号', width: '180px' },
  { key: 'onlineStatus', title: '在线状态', width: '100px', align: 'center' },
  { key: 'enabled', title: '状态', type: 'enabledStatus', width: '90px', align: 'center' },
  { key: 'passwordStatus', title: '密码状态', width: '120px' },
  { key: 'employeeNo', title: '职员工号', width: '150px' },
  { key: 'employeeTitle', title: '职员姓名', width: '150px' },
  { key: 'lastLoginAt', title: '最后登录时间', type: 'datetime', width: '180px' },
]);
const userListReady = computed(() => Boolean(selectedTenant.value?.id));
const userListTitle = computed(() =>
  selectedTenant.value ? `用户列表 - ${tenantTitle(selectedTenant.value)}` : '用户列表',
);

usePageBusinessEventHandler(handleUserSessionBusinessEvent);
watch(currentUserTenant, initializeTenantUserScope, { immediate: true });
watch(selectedTenant, () => {
  selectedUserKey.value = undefined;
  resetUserSessionRows();
  userReloadKey.value += 1;
});
onActivated(() => {
  userReloadKey.value += 1;
});

/** 让用户列表只查询当前选中租户下的数据。 */
function createScopedUserModuleContext(
  context: ModuleContext<UserAccount>,
  tenant: Tenant | undefined,
): ModuleContext<UserAccount> {
  return {
    ...context,
    crud: {
      ...context.crud,
      query: (request) => context.crud.query(scopedUserQuery(request, tenant)),
    },
  };
}

/** 在原有查询条件上补上当前租户编号。 */
function scopedUserQuery(request: WebQueryRequest | undefined, tenant: Tenant | undefined): WebQueryRequest {
  const conditions = [...(request?.conditions ?? [])];
  if (tenant?.id) conditions.push({ fieldName: 'tenantId', operator: 'EQ', values: [tenant.id] });
  return { ...request, conditions };
}

/** 系统管理员首次进入时，默认选择第一个租户。 */
function handleTenantsLoaded(records: CrudRecordListBase[]) {
  if (canBrowseTenants.value && !selectedTenant.value && records.length > 0)
    selectedTenant.value = records[0] as Tenant;
}

/** 普通用户只能看到自己的租户，因此自动建立该范围。 */
function initializeTenantUserScope(record = currentUserTenant.value) {
  if (record && !canBrowseTenants.value && !selectedTenant.value) selectedTenant.value = record;
}

/** 切换租户并让用户列表重新加载。 */
function selectTenant(record: Tenant) {
  selectedTenant.value = record;
}

/** 列表顶端的新建按钮通过地址打开一张新的用户页签。 */
function handleUserListAction(action: RecordActionItem) {
  if (action.key !== 'create') return;
  navigation?.openRoute('/iam/users', {
    newInstance: true,
    query: { userAction: 'add' },
  });
}

/** 行内查看、编辑打开独立页签；删除在列表直接确认并刷新。 */
function handleUserRowAction(action: ResolvedRecordActionItem, record: QueryListRecord) {
  if (action.key === 'view' || action.key === 'edit') {
    openUserPage(action.key, record);
    return;
  }
  if (action.key === 'delete') void removeUser(record);
}

/** 双击用户时打开或激活该用户的查看页签。 */
function handleUserRowDblclick(record: QueryListRecord) {
  openUserPage('view', record);
}

/** 每次打开用户都创建一个新页签，地址只保留资源编号和可编辑状态。 */
function openUserPage(action: Extract<UserRouteAction, 'view' | 'edit'>, record: QueryListRecord) {
  const userId = String(record.id ?? '');
  if (!userId) return;
  navigation?.openRoute(`/iam/users/${encodeURIComponent(userId)}`, {
    newInstance: true,
    query: action === 'edit' ? { userAction: 'edit' } : undefined,
  });
}

/** 删除不通过地址表达，确认成功后直接刷新当前列表。 */
async function removeUser(record: QueryListRecord) {
  await executeStaticRecordAction<UserAccount, unknown>({
    loading: actionLoading,
    source: 'user-management-list',
    record: () => (record.id ? (record as UserAccount) : undefined),
    canExecute: (user) => userContext.can('delete', user.id) === true,
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
      userReloadKey.value += 1;
    },
  });
}

/** 在列表展开区下线一条非当前登录会话。 */
async function revokeUserSession(record: QueryListRecord, session: UserSessionView) {
  await executeStaticRecordAction<UserAccount, number>({
    loading: actionLoading,
    source: 'user-management-list',
    record: () => (record.id ? (record as UserAccount) : undefined),
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

/** 在列表展开区下线该用户全部可下线会话。 */
async function revokeAllUserSessions(record: QueryListRecord) {
  const userId = String(record.id ?? '');
  const sessionIds = revokableUserSessions(userId).map((session) => session.id);
  if (sessionIds.length === 0) {
    presentPlatformMessage('当前没有可下线的登录会话', {
      source: 'user-management-list',
      phase: 'validation',
    });
    return;
  }
  await executeStaticRecordAction<UserAccount, number>({
    loading: actionLoading,
    source: 'user-management-list',
    record: () => (record.id ? (record as UserAccount) : undefined),
    canExecute: (user) => userContext.can('revokeSessions', user.id) === true,
    deniedMessage: '当前用户无权批量下线登录会话',
    confirm: (user) =>
      confirmAction({
        title: '批量下线登录会话',
        content: `确认下线用户「${userTitle(user)}」的 ${sessionIds.length} 个登录会话？`,
        okText: '全部下线',
        danger: true,
      }),
    execute: (user) =>
      userContext.http.request<number>({
        method: 'POST',
        path: `/iam.user/${encodeURIComponent(user.id!)}/sessions/revoke`,
        body: { sessionIds },
      }),
    onExecuted: (_, user) => void loadUserSessions(user.id),
  });
}

/** 返回当前用户可以下线的会话。 */
function revokableUserSessions(userId: string | undefined) {
  if (!userId || userContext.can('revokeSession', userId) !== true) return [];
  return userSessionState(userId).records.filter((session) => !session.current);
}

/** 判断某条会话是否能显示下线按钮。 */
function canRevokeUserSession(userId: string | undefined, session: UserSessionView) {
  return Boolean(userId) && !session.current && userContext.can('revokeSession', userId) === true;
}

/** 生成租户的显示名称。 */
function tenantTitle(record: Tenant | CrudRecordListBase | undefined) {
  return String(record?.title ?? record?.alias ?? record?.id ?? '未命名租户');
}

/** 生成用户的显示名称。 */
function userTitle(record: Partial<UserAccount>) {
  return String(record.username ?? record.id ?? '用户');
}

/** 把租户数据转成左侧列表需要的标题和说明。 */
function tenantItemOf(record: CrudRecordListBase): RecordExplorerItemDescriptor {
  return {
    title: tenantTitle(record),
    secondary: record.alias ?? record.id,
    muted: record.enabled === false,
  };
}
</script>

<template>
  <section class="user-management-list-page">
    <RecordExplorerPanel
      class="user-scope-panel"
      title="租户"
      refresh-title="刷新租户列表"
      :search-keyword="tenantSearchKeyword"
      search-placeholder="搜索租户名称、alias 或 ID"
      :searchable="canBrowseTenants"
      @refresh="canBrowseTenants ? (tenantReloadKey += 1) : initializeTenantUserScope()"
      @update:search-keyword="tenantSearchKeyword = $event"
    >
      <button
        v-if="!canBrowseTenants && currentUserTenant"
        class="user-scope-entry"
        type="button"
        @click="selectTenant(currentUserTenant)"
      >
        <UiRecordExplorerItem
          :title="tenantTitle(currentUserTenant)"
          secondary="当前租户"
          clickable
          :selected="selectedTenant?.id === currentUserTenant.id"
        />
      </button>
      <CrudRecordListExplorer
        v-if="canBrowseTenants"
        :context="tenantListContext"
        :selected-id="selectedTenant?.id"
        :reload-key="tenantReloadKey"
        :keyword="tenantSearchKeyword"
        empty-description="暂无租户"
        loading-tip="加载租户列表"
        fallback-title="未命名租户"
        :item-of="tenantItemOf"
        @loaded="handleTenantsLoaded"
        @select="selectTenant($event as Tenant)"
      />
    </RecordExplorerPanel>

    <RecordQueryListPanel
      class="user-list-panel"
      :context="userListContext"
      :title="userListTitle"
      :columns="userListColumns"
      standard-crud-actions
      standard-crud-row-actions
      :selected-key="selectedUserKey"
      :expanded-row-keys="expandedUserKeys"
      :cell-renderers="{ onlineStatus: userOnlineStatusTitle }"
      :reload-key="userReloadKey"
      :ready="userListReady"
      quick-search-placeholder="搜索账号"
      empty-description="当前租户暂无账号"
      waiting-description="请选择租户"
      @action="handleUserListAction"
      @row-action="handleUserRowAction"
      @row-dblclick="handleUserRowDblclick"
      @row-expand="handleUserRowExpand"
      @loaded="handleUserListLoaded"
      @select="selectedUserKey = String($event.id ?? '')"
    >
      <template #expandedRow="{ record }">
        <UserSessionExpandedSubtable
          :sessions="userSessionState(String(record.id ?? '')).records"
          :loading="userSessionState(String(record.id ?? '')).loading"
          :error="userSessionState(String(record.id ?? '')).error"
          :actions-disabled="actionLoading"
          :can-revoke="(session) => canRevokeUserSession(String(record.id ?? ''), session)"
          :can-revoke-all="revokableUserSessions(String(record.id ?? '')).length > 1"
          @refresh="loadUserSessions(String(record.id ?? ''))"
          @revoke="revokeUserSession(record, $event)"
          @revoke-all="revokeAllUserSessions(record)"
        />
      </template>
    </RecordQueryListPanel>
  </section>
</template>

<style scoped>
.user-management-list-page {
  display: grid;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  gap: 12px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.user-scope-panel,
.user-list-panel {
  min-width: 0;
  min-height: 0;
}

.user-scope-entry {
  display: block;
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

@media (max-width: 1180px) {
  .user-management-list-page {
    grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
    grid-template-rows: minmax(0, 0.95fr) minmax(0, 1.3fr);
  }

  .user-list-panel {
    grid-column: 1 / -1;
  }
}

@media (max-width: 980px) {
  .user-management-list-page {
    height: auto;
    overflow: visible;
  }
}

@media (max-width: 760px) {
  .user-management-list-page {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(180px, 0.65fr) minmax(360px, 1fr);
  }

  .user-list-panel {
    grid-column: auto;
  }
}
</style>
