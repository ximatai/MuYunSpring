<script setup lang="ts">
import { useLanguageContext, actionResultData } from '@muyun/web-core';

import { onMounted, ref } from 'vue';

import {
  UserPicker,
  presentPlatformError,
  presentPlatformMessage,
  type UserPickerCandidate,
} from '@muyun/platform-components';
import { UiButton, UiError, UiInput, UiSpin, confirmAction } from '@muyun/vue-ui-antdv';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import type {
  EmployeeAccount,
  UserAccount,
  UserSelectorItem,
  WebActionResultEnvelope,
  WebPageResponse,
} from '@muyun/web-contracts';

const language = useLanguageContext();

defineOptions({ name: 'EmployeeAccountDrawer' });

const props = defineProps<{ context: ModulePageDrawerContext }>();

type AccountProvisionResponse = { user: UserAccount; binding: EmployeeAccount };

const account = ref<EmployeeAccount>();
const selectedExistingUserId = ref<string>();
const username = ref('');
const password = ref('');
const loading = ref(false);
const saving = ref(false);

onMounted(() => void load());

async function load() {
  const employeeId = employeeIdOf();
  if (!employeeId) return;
  loading.value = true;
  try {
    account.value = await props.context.module.http.request<EmployeeAccount | undefined>({
      path: `/iam.employee/${encodeURIComponent(employeeId)}/account`,
    });
  } catch (error) {
    presentPlatformError(error, { language, source: 'employee-account', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function provision() {
  const employeeId = employeeIdOf();
  if (!employeeId || saving.value) return;
  const normalizedUsername = username.value.trim();
  if (!normalizedUsername || !password.value) {
    presentPlatformMessage(
      language.t(
        'platform.screen.EmployeeAccountDrawer.enter-an-account-and-initial-password',
        '请输入账号和初始密码。',
      ),
      { language, source: 'employee-account', phase: 'validation' },
    );
    return;
  }
  saving.value = true;
  try {
    const result = await props.context.module.http.request<WebActionResultEnvelope<AccountProvisionResponse>>(
      {
        method: 'POST',
        path: `/iam.employee/${encodeURIComponent(employeeId)}/account/provision`,
        body: { username: normalizedUsername, password: password.value },
      },
    );
    account.value = actionResultData(result).binding;
    password.value = '';
    props.context.refreshList();
  } catch (error) {
    presentPlatformError(error, { language, source: 'employee-account', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function bindSelected(users: UserPickerCandidate[]) {
  const user = users[0];
  if (!user) return;
  await bindExistingAccount(user.id);
}

async function bindExistingAccount(userId: string) {
  const employeeId = employeeIdOf();
  if (!employeeId || !userId || saving.value) return;
  saving.value = true;
  try {
    account.value = await props.context.module.http.request<EmployeeAccount>({
      method: 'POST',
      path: `/iam.employee/${encodeURIComponent(employeeId)}/account`,
      body: { userId },
    });
    selectedExistingUserId.value = undefined;
    props.context.refreshList();
    presentPlatformMessage(
      language.t(
        'platform.screen.EmployeeAccountDrawer.the-account-is-bound-to-this-employee',
        '账号已绑定到当前职员。',
      ),
      { language, source: 'employee-account', phase: 'action' },
    );
  } catch (error) {
    presentPlatformError(error, { language, source: 'employee-account', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function searchExistingUsers(request: { keyword: string; pageNum: number; pageSize: number }) {
  const employeeId = employeeIdOf();
  if (!employeeId) return { records: [], total: 0 };
  const response = await props.context.module.http.request<WebPageResponse<UserSelectorItem>>({
    method: 'POST',
    path: `/iam.employee/${encodeURIComponent(employeeId)}/account-candidates/query`,
    body: {
      keyword: request.keyword,
      page: { pageNum: request.pageNum, pageSize: request.pageSize },
    },
  });
  return { records: response.records.map(userPickerCandidate), total: response.total };
}

async function resolveExistingUsers(ids: string[]) {
  const employeeId = employeeIdOf();
  if (!employeeId || ids.length === 0) return [];
  const response = await props.context.module.http.request<WebPageResponse<UserSelectorItem>>({
    method: 'POST',
    path: `/iam.employee/${encodeURIComponent(employeeId)}/account-candidates/query`,
    body: { ids, page: { pageNum: 1, pageSize: ids.length } },
  });
  return response.records.map(userPickerCandidate);
}

function userPickerCandidate(user: UserSelectorItem): UserPickerCandidate {
  return {
    id: user.id,
    title: user.username ?? user.id,
    account: user.username ?? user.id,
    employeeName: user.employeeTitle,
    organizationId: user.organizationId,
    organizationName: user.organizationTitle,
    departmentId: user.departmentId,
    departmentName: user.departmentTitle,
  };
}

async function remove() {
  const employeeId = employeeIdOf();
  if (!employeeId || !account.value || saving.value) return;
  const confirmed = await confirmAction(
    {
      title: language.t('platform.screen.EmployeeAccountDrawer.remove-account', '移除账户'),
      content: language.t(
        'platform.screen.EmployeeAccountDrawer.remove-this-employee-s-account-the-user-account-will-also',
        '确认移除该职员的账户？该用户账号会同步删除。',
      ),
      okText: language.t('platform.screen.RecordPermissionDialog.remove', '移除'),
      danger: true,
    },
    language,
  );
  if (!confirmed) return;
  saving.value = true;
  try {
    await props.context.module.http.request({
      method: 'POST',
      path: `/iam.employee/${encodeURIComponent(employeeId)}/account/delete`,
    });
    account.value = undefined;
    props.context.refreshList();
  } catch (error) {
    presentPlatformError(error, { language, source: 'employee-account', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

function employeeIdOf() {
  const id = props.context.record?.id;
  return id == null ? undefined : String(id);
}
</script>

<template>
  <UiSpin
    v-if="loading"
    :tip="language.t('platform.screen.EmployeeAccountDrawer.loading-account-binding', '加载账号绑定')"
  />
  <section v-else class="employee-account-drawer">
    <UiError
      v-if="!employeeIdOf()"
      :title="language.t('platform.screen.EmployeeAccountDrawer.employee-not-found', '职员不存在')"
      :message="
        language.t(
          'platform.screen.EmployeeAccountDrawer.reopen-the-employee-record-to-set-up-an-account',
          '请重新打开职员档案后再设置账号。',
        )
      "
    />
    <template v-else-if="account">
      <p>
        {{ language.t('platform.screen.EmployeeAccountDrawer.currently-bound-user', '当前已绑定用户：')
        }}{{ account.userId ?? '-' }}
      </p>
      <UiButton danger icon-name="delete" :loading="saving" @click="remove">{{
        language.t('platform.screen.EmployeeAccountDrawer.remove-account', '移除账户')
      }}</UiButton>
    </template>
    <template v-else>
      <section class="employee-account-existing-picker">
        <p>
          {{
            language.t(
              'platform.screen.EmployeeAccountDrawer.bind-an-existing-user-account-only-enabled-accounts-in-this',
              '绑定已有用户账号；仅显示当前租户内启用且尚未绑定其他职员的账号。',
            )
          }}
        </p>
        <UserPicker
          :value="selectedExistingUserId"
          :disabled="saving"
          :placeholder="
            language.t(
              'platform.screen.EmployeeAccountDrawer.search-and-select-an-existing-login-account',
              '搜索并选择已有登录账号',
            )
          "
          :title="
            language.t(
              'platform.screen.EmployeeAccountDrawer.select-an-existing-login-account',
              '选择已有登录账号',
            )
          "
          :search-page="searchExistingUsers"
          :resolve-users="resolveExistingUsers"
          @update:value="selectedExistingUserId = typeof $event === 'string' ? $event : undefined"
          @select="bindSelected"
        />
      </section>
      <form @submit.prevent="provision">
        <p>
          {{
            language.t(
              'platform.screen.EmployeeAccountDrawer.you-can-also-create-a-login-account-and-bind-it',
              '也可直接创建登录账号并自动绑定到当前职员。',
            )
          }}
        </p>
        <label>
          <span>{{ language.t('platform.screen.EmployeeAccountDrawer.account', '账号') }}</span>
          <UiInput
            v-model:value="username"
            :disabled="saving"
            :placeholder="
              language.t('platform.screen.EmployeeAccountDrawer.enter-a-login-account', '请输入登录账号')
            "
          />
        </label>
        <label>
          <span>{{ language.t('platform.screen.EmployeeAccountDrawer.initial-password', '初始密码') }}</span>
          <UiInput
            v-model:value="password"
            type="password"
            :disabled="saving"
            :placeholder="
              language.t('platform.screen.EmployeeAccountDrawer.enter-an-initial-password', '请输入初始密码')
            "
          />
        </label>
        <UiButton type="primary" html-type="submit" icon-name="plus" :loading="saving">
          {{ language.t('platform.screen.EmployeeAccountDrawer.create-and-bind-account', '创建账号并绑定') }}
        </UiButton>
      </form>
    </template>
  </section>
</template>

<style scoped>
.employee-account-drawer,
.employee-account-drawer form,
.employee-account-existing-picker {
  display: grid;
  gap: 14px;
}
.employee-account-drawer p {
  margin: 0;
  color: var(--muyun-text-muted);
}
.employee-account-drawer label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
</style>
