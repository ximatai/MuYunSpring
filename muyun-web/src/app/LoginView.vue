<script setup lang="ts">
import { ref } from 'vue';
import type { AuthClient } from '@muyun/web-core';
import type { LoginResult } from '@muyun/web-contracts';
import { UiButton, UiInput } from '@muyun/vue-ui-antdv';
import { normalizeInitialValue, resolveLoginTenantDefaults } from './loginTenant';

defineOptions({ name: 'LoginView' });

const props = defineProps<{
  authClient: AuthClient;
  loading?: boolean;
  error?: string;
}>();

const emit = defineEmits<{
  authenticated: [result: LoginResult];
}>();

const loginTenantDefaults = resolveLoginTenantDefaults(import.meta.env.VITE_MUYUN_LOGIN_TENANT_ID);
const tenantId = ref(loginTenantDefaults.tenantId);
const tenantLocked = loginTenantDefaults.tenantLocked;
const username = ref(normalizeInitialValue(import.meta.env.VITE_MUYUN_LOGIN_USERNAME));
const password = ref(normalizeInitialValue(import.meta.env.VITE_MUYUN_LOGIN_PASSWORD));
const submitting = ref(false);
const formError = ref<string>();
const passwordChangeRequired = ref(false);
const pendingToken = ref<string>();
const newPassword = ref('');
const confirmPassword = ref('');

async function submit() {
  formError.value = undefined;
  submitting.value = true;
  try {
    const result = await props.authClient.login({
      tenantId: tenantId.value,
      username: username.value,
      password: password.value,
    });
    if (result.passwordChangeRequired) {
      pendingToken.value = result.token;
      passwordChangeRequired.value = true;
      formError.value = undefined;
      return;
    }
    emit('authenticated', result);
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : 'Login failed';
  } finally {
    submitting.value = false;
  }
}

async function submitPasswordChange() {
  formError.value = undefined;
  if (!pendingToken.value) {
    formError.value = '登录状态已失效，请重新登录';
    passwordChangeRequired.value = false;
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    formError.value = '两次输入的新密码不一致';
    return;
  }
  submitting.value = true;
  try {
    await props.authClient.changeOwnPassword(
      {
        currentPassword: password.value,
        newPassword: newPassword.value,
      },
      pendingToken.value,
    );
    password.value = newPassword.value;
    const result = await props.authClient.login({
      tenantId: tenantId.value,
      username: username.value,
      password: newPassword.value,
    });
    emit('authenticated', result);
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : 'Password change failed';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-panel">
      <header>
        <p>MuYun Platform</p>
        <h1>平台登录</h1>
      </header>

      <p v-if="formError || error" class="login-error">
        {{ formError || error }}
      </p>

      <form
        v-if="!passwordChangeRequired"
        class="login-form"
        data-testid="login-form"
        @submit.prevent="submit"
      >
        <p v-if="tenantLocked" class="login-context">租户：{{ tenantId }}</p>
        <label v-else>
          <span>租户 ID</span>
          <UiInput v-model:value="tenantId" autocomplete="organization" placeholder="留空进入系统工作区" />
        </label>
        <label data-testid="login-username">
          <span>用户名</span>
          <UiInput v-model:value="username" autocomplete="username" required />
        </label>
        <label data-testid="login-password">
          <span>密码</span>
          <UiInput v-model:value="password" type="password" autocomplete="current-password" required />
        </label>
        <UiButton
          class="login-submit"
          data-testid="login-submit"
          html-type="submit"
          type="primary"
          :loading="submitting || loading"
        >
          {{ submitting || loading ? '登录中' : '登录' }}
        </UiButton>
      </form>
      <form v-else class="login-form" @submit.prevent="submitPasswordChange">
        <p class="login-context">当前密码需要修改后才能进入系统</p>
        <label>
          <span>新密码</span>
          <UiInput v-model:value="newPassword" type="password" autocomplete="new-password" required />
        </label>
        <label>
          <span>确认新密码</span>
          <UiInput v-model:value="confirmPassword" type="password" autocomplete="new-password" required />
        </label>
        <UiButton class="login-submit" html-type="submit" type="primary" :loading="submitting || loading">
          {{ submitting || loading ? '保存中' : '修改密码' }}
        </UiButton>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 24px;
  background: var(--muyun-support-canvas);
}

.login-panel {
  display: grid;
  width: min(100%, 380px);
  gap: 18px;
  padding: 28px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
  box-shadow: 0 16px 48px rgb(25 39 52 / 0.12);
}

header p {
  margin: 0 0 8px;
  color: var(--muyun-support-text-muted);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

header h1 {
  margin: 0;
  color: var(--muyun-support-text);
  font-size: 22px;
  line-height: 1.25;
}

.login-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--muyun-danger-border);
  border-radius: 6px;
  color: var(--muyun-danger-soft-text);
  background: var(--muyun-danger-soft);
  font-size: 13px;
}

.login-form {
  display: grid;
  gap: 14px;
}

.login-context {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 6px;
  color: var(--muyun-support-text-body);
  background: var(--muyun-support-elevated);
  font-size: 13px;
  font-weight: 600;
}

label {
  display: grid;
  gap: 6px;
  color: var(--muyun-support-text-body);
  font-size: 13px;
  font-weight: 600;
}

.login-submit {
  width: 100%;
  min-height: 40px;
}

button:disabled {
  background: var(--muyun-support-disabled);
  color: var(--muyun-support-disabled-text);
  cursor: not-allowed;
}
</style>
