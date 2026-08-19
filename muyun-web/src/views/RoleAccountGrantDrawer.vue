<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  RecordDetailDrawer,
  handlePlatformActionSuccess,
  presentPlatformError,
} from '@muyun/platform-components';
import { UiButton, UiError, UiInput, UiSpin, UiTable } from '@muyun/vue-ui-antdv';
import type {
  AccountRoleGrant,
  ManagementScopeType,
  RecordData,
  Role,
  TableContract,
  UserSelectorItem,
} from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import { createRoleGrantClient } from './roleGrantClient';

defineOptions({ name: 'RoleAccountGrantDrawer' });

const props = defineProps<{
  open: boolean;
  container: HTMLElement | null;
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
const pageNum = ref(0);
const pageSize = 20;
const total = ref(0);
const loading = ref(false);
const loadingUsers = ref(false);
const saving = ref(false);
const loadFailed = ref(false);

const client = computed(() => createRoleGrantClient(props.context.http));
const roleId = computed(() => props.role?.id);
const title = computed(() => (props.role ? `绑定用户 - ${roleTitle(props.role)}` : '绑定用户'));
const defaultManagementScopeType = computed<ManagementScopeType>(() => {
  if (props.role?.ownerScopeType === 'platform') {
    return 'platform';
  }
  if (props.role?.ownerScopeType === 'organization') {
    return 'organization';
  }
  return 'tenant';
});
const defaultManagementScopeId = computed(() =>
  defaultManagementScopeType.value === 'platform' ? undefined : props.role?.ownerScopeId,
);
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
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const canGoPrevious = computed(() => pageNum.value > 0);
const canGoNext = computed(() => pageNum.value + 1 < pages.value);
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

async function load() {
  const id = roleId.value;
  if (!id) {
    resetState();
    return;
  }
  loading.value = true;
  loadFailed.value = false;
  try {
    const [nextGrants, boundUsers] = await Promise.all([
      client.value.accountRoleGrants(id),
      client.value.userSelector({
        roleId: id,
        enabledOnly: false,
        page: { pageNum: 0, pageSize: 500 },
      }),
    ]);
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
      ...boundUsers.records,
    ]);
    await loadUsersPage(0);
  } catch (cause) {
    loadFailed.value = true;
    presentPlatformError(cause, { source: 'role-account-grants', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function loadUsersPage(nextPage: number) {
  loadingUsers.value = true;
  try {
    const response = await client.value.userSelector({
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
  if (!id) {
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
        managementScopeType: defaultManagementScopeType.value,
        managementScopeId: defaultManagementScopeId.value,
      });
    }
    for (const userId of removedUserIds.value) {
      const grantId = grantByUserId.value.get(userId)?.id;
      if (grantId) {
        await client.value.deleteAccountRoleGrant(id, grantId);
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
  grants.value = [];
  usersById.value = {};
  pageUsers.value = [];
  checkedUserIds.value = new Set();
  originalUserIds.value = new Set();
  keyword.value = '';
  appliedKeyword.value = '';
  pageNum.value = 0;
  total.value = 0;
  loading.value = false;
  loadingUsers.value = false;
  saving.value = false;
  loadFailed.value = false;
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
</script>

<template>
  <RecordDetailDrawer
    :open="open"
    :title="title"
    :container="container"
    close-title="关闭"
    @close="handleClose"
  >
    <template #operation>
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
    <section v-else class="role-account-grant-drawer-body">
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

      <div class="role-account-grant-summary">
        <span>已选 {{ checkedUserIds.size }} 个</span>
        <span v-if="changed">新增 {{ addedUserIds.length }} 个，移除 {{ removedUserIds.length }} 个</span>
      </div>

      <section class="role-account-grant-selected">
        <div class="role-account-grant-selected-title">
          <strong>已选用户</strong>
          <span>{{ selectedUsers.length }} 个</span>
        </div>
        <div v-if="selectedUsers.length > 0" class="role-account-grant-selected-list">
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
        <span v-else class="role-account-grant-selected-empty">暂无已选用户</span>
      </section>

      <UiTable
        class="role-account-grant-table"
        size="middle"
        :contract="userTableContract"
        :rows="rows"
        :loading="loadingUsers"
        :pagination="false"
        :selection="rowSelection"
      />

      <div class="role-account-grant-pagination">
        <span>共 {{ total }} 个用户，第 {{ pageNum + 1 }} / {{ pages }} 页</span>
        <div>
          <UiButton :disabled="saving || loadingUsers || !canGoPrevious" @click="loadUsersPage(pageNum - 1)">
            上一页
          </UiButton>
          <UiButton :disabled="saving || loadingUsers || !canGoNext" @click="loadUsersPage(pageNum + 1)">
            下一页
          </UiButton>
        </div>
      </div>
    </section>
  </RecordDetailDrawer>
</template>

<style scoped>
.role-account-grant-drawer-body {
  display: grid;
  gap: 12px;
}

.role-account-grant-search {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}

.role-account-grant-summary {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.role-account-grant-selected {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-hover-subtle);
}

.role-account-grant-selected-title,
.role-account-grant-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.role-account-grant-selected-title strong {
  color: var(--muyun-text);
  font-size: 13px;
}

.role-account-grant-selected-title span,
.role-account-grant-selected-empty,
.role-account-grant-pagination {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.role-account-grant-selected-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
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

.role-account-grant-pagination > div {
  display: flex;
  gap: 8px;
}

.role-account-grant-drawer-state {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 32px 0;
}
</style>
