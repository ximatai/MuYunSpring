<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  RecordDetailDrawer,
  RecordPicker,
  handlePlatformActionSuccess,
  presentPlatformError,
  type RecordPickerRecord,
} from '@muyun/platform-components';
import { UiButton, UiError, UiInput, UiSpin, UiTable } from '@muyun/vue-ui-antdv';
import type { UiDataTablePagination } from '@muyun/vue-ui-antdv';
import type {
  AccountRoleGrant,
  RecordData,
  Role,
  TableContract,
  Tenant,
  UserSelectorItem,
} from '@muyun/web-contracts';
import { useModuleContext, type ModuleContext } from '@muyun/web-core';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import { createRoleGrantClient } from './roleGrantClient';

defineOptions({ name: 'RoleAccountGrantDrawer' });

const props = defineProps<{
  open: boolean;
  container?: HTMLElement | null;
  /** Uses the owning platform drawer and renders only this operation's content. */
  embedded?: boolean;
  drawerContext?: ModulePageDrawerContext;
  context: ModuleContext<Role>;
  role?: Role;
}>();

const emit = defineEmits<{
  close: [];
  saved: [];
}>();

interface UserRow extends RecordData {
  id: string;
  username: string;
  employeeNo: string;
  employeeTitle: string;
  organizationTitle: string;
  departmentTitle: string;
  boundTitle: string;
}

const grants = ref<AccountRoleGrant[]>([]);
const usersById = ref<Record<string, UserSelectorItem>>({});
const pageUsers = ref<UserSelectorItem[]>([]);
const checkedUserIds = ref<Set<string>>(new Set());
const originalUserIds = ref<Set<string>>(new Set());
const keyword = ref('');
const appliedKeyword = ref('');
const pageNum = ref(1);
const pageSize = 20;
const total = ref(0);
const loading = ref(false);
const loadingUsers = ref(false);
const saving = ref(false);
const loadFailed = ref(false);
const selectedTargetTenant = ref<Tenant>();

const client = computed(() => createRoleGrantClient(props.context.http));
const tenantContext = useModuleContext<Tenant>({ moduleAlias: 'iam.tenant' });
const roleId = computed(() => props.role?.id);
const title = computed(() => (props.role ? `绑定用户 - ${roleTitle(props.role)}` : '绑定用户'));
const contentContainer = computed(() => (props.embedded ? 'section' : RecordDetailDrawer));
const contentContainerProps = computed(() =>
  props.embedded
    ? {}
    : {
        open: props.open,
        title: title.value,
        renderMode: props.container ? 'inline' : 'portal',
        closeTitle: '关闭',
        onClose: handleClose,
      },
);
const needsTargetTenant = computed(() => props.role?.ownerScopeType === 'platform');
const targetTenantId = computed(() => selectedTargetTenant.value?.id);
const bindingReady = computed(() => !needsTargetTenant.value || !!targetTenantId.value);
const tenantPickerContext = computed(() => tenantContext as unknown as ModuleContext<RecordPickerRecord>);
const grantByUserId = computed(() => {
  const next = new Map<string, AccountRoleGrant>();
  for (const grant of grants.value) {
    if (grant.userId) {
      next.set(grant.userId, grant);
    }
  }
  return next;
});
const rows = computed<UserRow[]>(() =>
  pageUsers.value
    .filter((user) => user.id)
    .map((user) => ({
      id: user.id,
      username: userTitle(user),
      employeeNo: user.employeeNo ?? '',
      employeeTitle: user.employeeTitle ?? '',
      organizationTitle: user.organizationTitle ?? user.organizationId ?? '',
      departmentTitle: user.departmentTitle ?? user.departmentId ?? '',
      boundTitle: originalUserIds.value.has(user.id) ? '已绑定' : '未绑定',
    })),
);
const selectedUsers = computed(() =>
  [...checkedUserIds.value].map((userId) => usersById.value[userId] ?? userFallback(userId)),
);
const addedUserIds = computed(() =>
  [...checkedUserIds.value].filter((userId) => !originalUserIds.value.has(userId)),
);
const removedUserIds = computed(() =>
  [...originalUserIds.value].filter((userId) => !checkedUserIds.value.has(userId)),
);
const changed = computed(() => addedUserIds.value.length > 0 || removedUserIds.value.length > 0);
const userTableContract: TableContract = {
  rowKey: 'id',
  columns: [
    { title: '用户账号', key: 'username', width: 140 },
    { title: '职员姓名', key: 'employeeTitle', width: 120 },
    { title: '职员工号', key: 'employeeNo', width: 120 },
    { title: '所属机构', key: 'organizationTitle', width: 140 },
    { title: '所属部门', key: 'departmentTitle', width: 140 },
    { title: '状态', key: 'boundTitle', width: 90 },
  ],
};
const rowSelection = computed(() => ({
  selectedRowKeys: [...checkedUserIds.value],
  preserveSelectedRowKeys: true,
  disabledOf: () => saving.value,
  onChange: (keys: (string | number)[]) => {
    checkedUserIds.value = new Set(keys.map((key) => String(key)));
  },
}));
const userTablePagination = computed<UiDataTablePagination>(() => ({
  current: pageNum.value,
  total: total.value,
  pageSize,
  showSizeChanger: false,
  showQuickJumper: false,
  onChange: (page) => void loadUsersPage(page),
}));

watch(
  () => [props.open, props.role?.id] as const,
  ([open]) => {
    if (open) {
      void load();
    } else {
      resetState();
    }
  },
  { immediate: true },
);

watch(
  [
    () => props.embedded,
    () => props.drawerContext,
    checkedUserIds,
    changed,
    bindingReady,
    addedUserIds,
    removedUserIds,
    saving,
    loading,
  ],
  configureDrawerPresentation,
  { immediate: true },
);

onBeforeUnmount(() => props.drawerContext?.setOperation(undefined));

function configureDrawerPresentation() {
  const drawer = props.drawerContext;
  if (!props.embedded || !drawer) return;
  drawer.setSubtitle(`角色：${roleTitle(props.role ?? {})} · ${scopeTitle(props.role)}`);
  drawer.setOperation({
    summary: selectionSummary(),
    actions: [
      {
        key: 'cancel-account-role-grants',
        label: '取消',
        disabled: saving.value || loading.value,
        run: handleClose,
      },
      {
        key: 'save-account-role-grants',
        label: '确定',
        emphasis: 'primary',
        disabled: loading.value || !bindingReady.value || !changed.value,
        loading: saving.value,
        run: () => void save(),
      },
    ],
  });
}

function selectionSummary() {
  if (!bindingReady.value) return '请选择目标租户';
  if (!changed.value) return `已选 ${checkedUserIds.value.size} 个用户`;
  return `已选 ${checkedUserIds.value.size} 个用户 · 新增 ${addedUserIds.value.length} · 移除 ${removedUserIds.value.length}`;
}

async function load() {
  const id = roleId.value;
  if (!id) {
    resetState();
    return;
  }
  if (!bindingReady.value) {
    clearBindingData();
    return;
  }
  loading.value = true;
  loadFailed.value = false;
  try {
    const nextGrants = await client.value.accountRoleGrants(id, targetTenantId.value);
    grants.value = nextGrants;
    const boundIds = new Set(
      nextGrants.map((grant) => grant.userId).filter((userId): userId is string => !!userId),
    );
    originalUserIds.value = boundIds;
    checkedUserIds.value = new Set(boundIds);
    usersById.value = mergeUsers([
      ...nextGrants
        .map((grant) => userFallback(grant.userId))
        .filter((user): user is UserSelectorItem => !!user),
    ]);
    await loadUsersPage(1);
  } catch (cause) {
    loadFailed.value = true;
    presentPlatformError(cause, { source: 'role-account-grants', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function loadUsersPage(nextPage: number) {
  const id = roleId.value;
  if (!id || !bindingReady.value) return;
  loadingUsers.value = true;
  try {
    const response = await client.value.accountRoleCandidates(id, {
      targetTenantId: targetTenantId.value,
      keyword: appliedKeyword.value,
      enabledOnly: true,
      page: { pageNum: Math.max(0, nextPage), pageSize },
    });
    pageUsers.value = response.records;
    usersById.value = { ...usersById.value, ...mergeUsers(response.records) };
    pageNum.value = response.pageNum;
    total.value = response.total;
  } catch (cause) {
    presentPlatformError(cause, { source: 'role-account-grants', phase: 'load' });
  } finally {
    loadingUsers.value = false;
  }
}

function submitSearch() {
  appliedKeyword.value = keyword.value.trim();
  void loadUsersPage(0);
}

function toggleUser(userId: string, checked: boolean) {
  const next = new Set(checkedUserIds.value);
  if (checked) {
    next.add(userId);
  } else {
    next.delete(userId);
  }
  checkedUserIds.value = next;
}

async function save() {
  const id = roleId.value;
  if (!id || !bindingReady.value) {
    return;
  }
  if (!changed.value) {
    emit('close');
    return;
  }
  saving.value = true;
  try {
    for (const userId of addedUserIds.value) {
      await client.value.grantAccountRole(id, {
        userId,
        targetTenantId: targetTenantId.value,
      });
    }
    for (const userId of removedUserIds.value) {
      const grantId = grantByUserId.value.get(userId)?.id;
      if (grantId) {
        await client.value.deleteAccountRoleGrant(id, grantId, targetTenantId.value);
      }
    }
    await handlePlatformActionSuccess(
      { success: true, message: '用户绑定已保存' },
      { source: 'role-account-grants', phase: 'action', fallbackMessage: '用户绑定已保存' },
    );
    emit('saved');
    emit('close');
  } catch (cause) {
    presentPlatformError(cause, { source: 'role-account-grants', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

function handleClose() {
  if (!saving.value) {
    emit('close');
  }
}

function resetState() {
  selectedTargetTenant.value = undefined;
  clearBindingData();
}

function clearBindingData() {
  grants.value = [];
  usersById.value = {};
  pageUsers.value = [];
  checkedUserIds.value = new Set();
  originalUserIds.value = new Set();
  keyword.value = '';
  appliedKeyword.value = '';
  pageNum.value = 1;
  total.value = 0;
  loading.value = false;
  loadingUsers.value = false;
  saving.value = false;
  loadFailed.value = false;
}

async function selectTargetTenant(tenantId: string | undefined) {
  if (!tenantId) {
    selectedTargetTenant.value = undefined;
    clearBindingData();
    return;
  }
  const tenant = await tenantContext.crud.view(tenantId);
  selectedTargetTenant.value = tenant ?? ({ id: tenantId, title: tenantId } as Tenant);
  await load();
}

function removeSelectedUser(userId: string) {
  toggleUser(userId, false);
}

function mergeUsers(users: UserSelectorItem[]) {
  const next: Record<string, UserSelectorItem> = {};
  for (const user of users) {
    if (user.id) {
      next[user.id] = user;
    }
  }
  return next;
}

function userFallback(userId: string | undefined): UserSelectorItem | undefined {
  return userId ? { id: userId, username: userId } : undefined;
}

function userTitle(user: UserSelectorItem | undefined) {
  return String(user?.username ?? user?.id ?? '未知用户');
}

function selectedUserDescription(user: UserSelectorItem) {
  if (user.employeeTitle && user.employeeNo) {
    return `${user.employeeTitle} / ${user.employeeNo}`;
  }
  return user.employeeTitle ?? user.employeeNo ?? user.employeeId ?? user.id;
}

function roleTitle(record: Partial<Role>) {
  return String(record.title ?? record.id ?? '角色');
}

function scopeTitle(role: Role | undefined) {
  if (role?.ownerScopeType === 'platform') return '平台范围';
  if (role?.ownerScopeType === 'organization') return '机构范围';
  return '租户范围';
}
</script>

<template>
  <component
    :is="contentContainer"
    v-bind="contentContainerProps"
    :class="{ 'role-account-grant-drawer-surface': embedded }"
  >
    <template v-if="!embedded" #operation>
      <UiButton :disabled="saving || loading" @click="handleClose">取消</UiButton>
      <UiButton type="primary" :loading="saving" :disabled="loading || !changed" @click="save">
        确定
      </UiButton>
    </template>

    <UiSpin v-if="loading" class="role-account-grant-drawer-state" tip="加载用户" />
    <div v-else-if="loadFailed" class="role-account-grant-drawer-state">
      <UiError title="用户加载失败" message="无法加载当前角色的用户绑定" />
      <UiButton type="primary" icon-name="reload" @click="load">重试</UiButton>
    </div>
    <section
      v-else
      class="role-account-grant-drawer-body"
      :class="{ 'role-account-grant-drawer-body--target-tenant': needsTargetTenant }"
    >
      <label v-if="needsTargetTenant" class="role-account-grant-target-tenant">
        <span>目标租户</span>
        <RecordPicker
          :context="tenantPickerContext"
          :value="targetTenantId"
          mode="list"
          placeholder="请选择角色下发的目标租户"
          :disabled="saving || loading"
          @update:value="selectTargetTenant"
        />
      </label>

      <div v-if="!bindingReady" class="role-account-grant-target-hint">
        平台共享角色需要先选择目标租户，再加载可绑定账号。
      </div>

      <template v-else>
        <div class="role-account-grant-search">
          <UiInput
            :value="keyword"
            allow-clear
            :disabled="saving"
            placeholder="搜索用户账号"
            @update:value="keyword = $event"
            @keydown.enter="submitSearch"
          />
          <UiButton icon-name="search" :disabled="saving" @click="submitSearch">查询</UiButton>
        </div>

        <UiTable
          class="role-account-grant-table"
          size="middle"
          :contract="userTableContract"
          :rows="rows"
          :loading="loadingUsers"
          :pagination="userTablePagination"
          :selection="rowSelection"
          fill-height
        />

        <section v-if="selectedUsers.length > 0" class="role-account-grant-selected">
          <div class="role-account-grant-selected-title">
            <strong>已选用户</strong>
            <span>{{ selectedUsers.length }} 个</span>
          </div>
          <div class="role-account-grant-selected-list">
            <button
              v-for="user in selectedUsers"
              :key="user.id"
              type="button"
              :disabled="saving"
              @click="removeSelectedUser(user.id)"
            >
              <span>{{ userTitle(user) }}</span>
              <small>{{ selectedUserDescription(user) }}</small>
            </button>
          </div>
        </section>
      </template>
    </section>
  </component>
</template>

<style scoped>
.role-account-grant-drawer-surface {
  height: 100%;
  min-height: 0;
}

.role-account-grant-drawer-body {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 12px;
  height: 100%;
  min-height: 0;
}

.role-account-grant-drawer-body--target-tenant {
  grid-template-rows: auto auto minmax(0, 1fr) auto;
}

.role-account-grant-target-tenant {
  display: grid;
  grid-template-columns: 72px minmax(0, 280px);
  align-items: center;
  gap: 8px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.role-account-grant-target-hint {
  display: grid;
  place-items: center;
  min-height: 180px;
  color: var(--muyun-text-muted);
  border: 1px dashed var(--muyun-border-subtle);
  border-radius: 8px;
}

.role-account-grant-search {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}

.role-account-grant-selected {
  display: grid;
  gap: 8px;
  max-block-size: 168px;
  padding: 10px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-hover-subtle);
}

.role-account-grant-selected-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.role-account-grant-selected-title strong {
  color: var(--muyun-text);
  font-size: 13px;
}

.role-account-grant-selected-title span {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.role-account-grant-selected-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  overflow: auto;
}

.role-account-grant-selected-list button {
  display: inline-grid;
  max-width: 180px;
  min-width: 0;
  padding: 5px 8px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
  background: var(--muyun-surface);
  color: var(--muyun-text-muted);
  text-align: left;
  cursor: pointer;
}

.role-account-grant-selected-list button:disabled {
  cursor: not-allowed;
}

.role-account-grant-selected-list span,
.role-account-grant-selected-list small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-account-grant-selected-list span {
  color: var(--muyun-text);
  font-size: 12px;
}

.role-account-grant-selected-list small {
  font-size: 11px;
}

.role-account-grant-table :deep(.ant-table-cell) {
  overflow-wrap: anywhere;
}

.role-account-grant-drawer-state {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 32px 0;
}
</style>
