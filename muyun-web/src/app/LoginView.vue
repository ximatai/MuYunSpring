<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { LanguageSelector } from '@muyun/platform-workbench';
import { computed, onMounted, ref, watch } from 'vue';
import { useLoginFailureFeedback } from './useLoginFailureFeedback';
import {
  AppError,
  platformErrorCodes,
  normalizeError,
  userFacingErrorMessage,
  type AuthClient,
  type ExternalLoginClient,
  type LoginContextClient,
} from '@muyun/web-core';
import type { ExternalLoginMethod, LoginResult, TenantBranding } from '@muyun/web-contracts';
import { UiButton, UiInput } from '@muyun/vue-ui-antdv';
import { normalizeInitialValue, resolveLoginTenantDefaults } from './loginTenant';
import WeLinkQrCode from './WeLinkQrCode.vue';
import type { ExternalLoginStart } from '@muyun/web-contracts';
import {
  forgetExternalLogin,
  navigateToExternalAuthorization,
  rememberExternalLogin,
} from './externalLoginReturn';

const language = useLanguageContext();
const qrStart = ref<ExternalLoginStart>();
function closeQrCode() {
  if (qrStart.value) forgetExternalLogin(qrStart.value.state);
  qrStart.value = undefined;
}

defineOptions({ name: 'LoginView' });

const props = defineProps<{
  authClient: AuthClient;
  loginContextClient?: LoginContextClient;
  /** Enables external sign-in on tenant-locked entries that offer it. */
  externalLoginClient?: ExternalLoginClient;
  loading?: boolean;
  error?: string;
  /** Used only when the form is first created; URL-locked tenants always take precedence. */
  initialUsername?: string;
}>();

const emit = defineEmits<{
  authenticated: [result: LoginResult];
}>();

const loginTenantDefaults = resolveLoginTenantDefaults(import.meta.env.VITE_MUYUN_LOGIN_TENANT_ID);
const tenantId = ref(loginTenantDefaults.tenantId);
const tenantLocked = loginTenantDefaults.tenantLocked;
const username = ref(
  normalizeInitialValue(props.initialUsername) ||
    normalizeInitialValue(import.meta.env.VITE_MUYUN_LOGIN_USERNAME),
);
const password = ref(normalizeInitialValue(import.meta.env.VITE_MUYUN_LOGIN_PASSWORD));
const loginFeedback = useLoginFailureFeedback();
const submitting = ref(false);
const formError = ref<() => string>();
watch([tenantId, username], () => {
  loginFeedback.clear();
  formError.value = undefined;
});
const passwordChangeRequired = ref(false);
const pendingToken = ref<string>();
const newPassword = ref('');
const confirmPassword = ref('');
const tenantBranding = ref<TenantBranding>();
const loginContextLoading = ref(false);
const loginContextError = ref<() => string>();
const externalMethods = ref<ExternalLoginMethod[]>([]);
const externalBusy = ref(false);
const canSubmit = computed(() => !tenantLocked || (!loginContextLoading.value && !loginContextError.value));
const showLoginTitleArea = computed(() => tenantBranding.value?.mode !== 'logoOnly');
const loginTitle = computed(
  () => tenantBranding.value?.title || language.t('platform.screen.LoginView.platform-sign-in', '平台登录'),
);
const loginSubtitle = computed(() => tenantBranding.value?.subtitle);

onMounted(async () => {
  if (!tenantLocked || !tenantId.value) {
    return;
  }
  loginContextLoading.value = true;
  try {
    if (!props.loginContextClient) {
      throw new Error('login context client is unavailable');
    }
    const context = await props.loginContextClient.loginContext(tenantId.value);
    tenantBranding.value = context.branding;
    externalMethods.value = props.externalLoginClient ? (context.externalLoginMethods ?? []) : [];
  } catch {
    loginContextError.value = () =>
      language.t(
        'platform.screen.LoginView.unable-to-open-sign-in-for-this-tenant',
        '无法打开该租户的登录入口',
      );
  } finally {
    loginContextLoading.value = false;
  }
});

async function startExternalLogin(method: ExternalLoginMethod) {
  if (!props.externalLoginClient) {
    return;
  }
  formError.value = undefined;
  externalBusy.value = true;
  try {
    closeQrCode();
    const start = await props.externalLoginClient.startLogin(method.providerId, tenantId.value);
    rememberExternalLogin(start, {
      providerId: method.providerId,
      purpose: 'login',
      tenantId: tenantId.value,
    });
    if (start.qrCode) {
      qrStart.value = start;
      externalBusy.value = false;
    } else {
      navigateToExternalAuthorization(start.authorizationUrl);
    }
  } catch (cause) {
    formError.value = () => loginErrorMessage(cause);
    externalBusy.value = false;
  }
}

async function submit() {
  if (submitting.value || props.loading || loginFeedback.locked.value) return;
  const credentials = { tenantId: tenantId.value, username: username.value, password: password.value };
  loginFeedback.clear();
  formError.value = undefined;
  submitting.value = true;
  try {
    const result = await props.authClient.login(credentials);
    if (result.passwordChangeRequired) {
      pendingToken.value = result.token;
      passwordChangeRequired.value = true;
      formError.value = undefined;
      return;
    }
    emit('authenticated', result);
  } catch (cause) {
    if (
      credentials.tenantId === tenantId.value &&
      credentials.username === username.value &&
      !loginFeedback.accept(cause)
    ) {
      formError.value = () => loginErrorMessage(cause);
    }
  } finally {
    submitting.value = false;
  }
}

function loginErrorMessage(cause: unknown) {
  if (cause instanceof AppError) {
    if (cause.status === 403) {
      return language.t(
        'platform.screen.LoginView.this-site-is-not-authorized-by-the-server',
        '当前站点未获后端授权，请联系管理员配置访问来源',
      );
    }
    if (
      cause.code === platformErrorCodes.networkError ||
      cause.code === platformErrorCodes.httpError ||
      (cause.status ?? 0) >= 500
    ) {
      return language.t('platform.screen.LoginView.service-unavailable', '暂时无法连接服务，请稍后重试。');
    }
    if (cause.code === platformErrorCodes.loginBadCredentials) {
      return language.t('platform.screen.LoginView.sign-in-rejected', '用户名或密码错误，请检查后重试。');
    }
    return cause.message;
  }
  return cause instanceof Error
    ? cause.message
    : language.t('platform.screen.LoginView.sign-in-failed-please-try-again-later', '登录失败，请稍后重试');
}

async function submitPasswordChange() {
  formError.value = undefined;
  if (!pendingToken.value) {
    formError.value = () =>
      language.t(
        'platform.screen.LoginView.your-session-is-no-longer-valid-please-sign',
        '登录状态已失效，请重新登录',
      );
    passwordChangeRequired.value = false;
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    formError.value = () =>
      language.t('platform.screen.LoginView.the-new-passwords-do-not-match', '两次输入的新密码不一致');
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
    formError.value = () =>
      cause instanceof Error
        ? userFacingErrorMessage(normalizeError(cause), language)
        : language.t('platform.screen.LoginView.password-change-failed', '密码修改失败，请重试');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="login-language"><LanguageSelector /></div>
  <main class="login-page">
    <section class="login-panel">
      <header>
        <div class="login-brand">
          <img v-if="tenantBranding?.lightLogo" class="login-logo" :src="tenantBranding.lightLogo" alt="" />
          <div v-if="showLoginTitleArea">
            <p v-if="!tenantBranding?.lightLogo">MuYun Platform</p>
            <h1>{{ loginTitle }}</h1>
            <p v-if="loginSubtitle" class="login-subtitle">{{ loginSubtitle }}</p>
          </div>
        </div>
      </header>

      <p v-if="loginFeedback.message.value || formError || error || loginContextError" class="login-error">
        {{ loginFeedback.message.value || formError?.() || error || loginContextError?.() }}
        <span v-if="loginFeedback.unlockTime.value" class="login-unlock-time">{{
          loginFeedback.unlockTime.value
        }}</span>
      </p>

      <WeLinkQrCode v-if="qrStart" :key="qrStart.state" :start="qrStart" @close="closeQrCode" />
      <form v-if="!passwordChangeRequired" class="login-form" @submit.prevent="submit">
        <label v-if="!tenantLocked">
          <span>{{ language.t('platform.screen.LoginView.tenant-id', '租户 ID') }}</span>
          <UiInput
            v-model:value="tenantId"
            autocomplete="organization"
            :placeholder="
              language.t(
                'platform.screen.LoginView.leave-blank-to-enter-the-system-workspace',
                '留空进入系统工作区',
              )
            "
          />
        </label>
        <label>
          <span>{{ language.t('platform.screen.LoginView.username', '用户名') }}</span>
          <UiInput v-model:value="username" autocomplete="username" required />
        </label>
        <label>
          <span>{{ language.t('platform.screen.LoginView.password', '密码') }}</span>
          <UiInput
            v-model:value="password"
            type="password"
            visibility-toggle
            autocomplete="current-password"
            required
          />
        </label>
        <UiButton
          class="login-submit"
          html-type="submit"
          type="primary"
          :disabled="!canSubmit || loginFeedback.locked.value"
          :loading="submitting || loading || loginContextLoading"
        >
          {{
            submitting || loading
              ? language.t('platform.screen.LoginView.signing-in', '登录中')
              : language.t('platform.screen.LoginView.sign-in', '登录')
          }}
        </UiButton>
      </form>
      <div v-if="!passwordChangeRequired && externalMethods.length > 0" class="login-external">
        <p class="login-external-divider">
          {{ language.t('platform.screen.LoginView.or-sign-in-with', '或使用以下方式登录') }}
        </p>
        <UiButton
          v-for="method in externalMethods"
          :key="method.providerId"
          class="login-submit"
          :loading="externalBusy"
          :disabled="externalBusy"
          @click="startExternalLogin(method)"
        >
          {{
            language.t('platform.screen.LoginView.sign-in-with', '使用 {provider} 登录', {
              provider: method.title,
            })
          }}
        </UiButton>
      </div>
      <form v-else-if="passwordChangeRequired" class="login-form" @submit.prevent="submitPasswordChange">
        <p class="login-context">
          {{
            language.t(
              'platform.screen.LoginView.change-your-current-password-before-entering-the-system',
              '当前密码需要修改后才能进入系统',
            )
          }}
        </p>
        <label>
          <span>{{ language.t('platform.screen.LoginView.new-password', '新密码') }}</span>
          <UiInput
            v-model:value="newPassword"
            type="password"
            visibility-toggle
            autocomplete="new-password"
            required
          />
        </label>
        <label>
          <span>{{ language.t('platform.screen.LoginView.confirm-new-password', '确认新密码') }}</span>
          <UiInput
            v-model:value="confirmPassword"
            type="password"
            visibility-toggle
            autocomplete="new-password"
            required
          />
        </label>
        <UiButton class="login-submit" html-type="submit" type="primary" :loading="submitting || loading">
          {{
            submitting || loading
              ? language.t('platform.screen.LoginView.saving', '保存中')
              : language.t('platform.screen.LoginView.change-password', '修改密码')
          }}
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
  width: min(100%, 440px);
  gap: 18px;
  padding: 32px;
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
  font-size: 26px;
  line-height: 1.25;
}

.login-logo {
  display: block;
  width: 56px;
  height: 56px;
  object-fit: contain;
}

.login-brand {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.login-subtitle {
  margin: 5px 0 0;
  color: var(--muyun-support-text-muted);
  font-size: 14px;
  line-height: 1.5;
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

.login-unlock-time {
  display: block;
  margin-top: 4px;
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

.login-external {
  display: grid;
  gap: 10px;
}

.login-external-divider {
  margin: 0;
  color: var(--muyun-support-text-muted);
  font-size: 12px;
  text-align: center;
}

button:disabled {
  background: var(--muyun-support-disabled);
  color: var(--muyun-support-disabled-text);
  cursor: not-allowed;
}
</style>

<style scoped>
.login-language {
  position: absolute;
  top: 16px;
  right: 16px;
  min-width: 120px;
}
</style>
