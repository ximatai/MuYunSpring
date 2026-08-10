<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import {
  Workbench,
  WorkbenchOutlet,
  pageDescriptorToUrl,
  type WorkbenchRealtimeStatus,
} from '@muyun/platform-workbench';
import { presentPlatformError, providePlatformTimeZoneContext } from '@muyun/platform-components';
import {
  configureModuleContext,
  createAuthClient,
  provideModuleContextConfig,
  type AppError,
  type RealtimeConnectionState,
} from '@muyun/web-core';
import type {
  LoginResult,
  MenuNavigationTarget,
  MenuRecord,
  WebUserNotification,
  WorkbenchStartupState,
} from '@muyun/web-contracts';
import {
  clearAuthToken,
  effectiveAuthToken,
  isAuthenticationRequiredError,
  isPasswordChangeRequiredError,
  saveAuthSessionId,
  saveAuthToken,
  storedAuthSessionId,
} from './platform-admin-runtime/authSession';
import { configureAuthenticationRecovery } from './platform-admin-runtime/sessionRecovery';
import { platformMessage } from './app/platformMessage';
import { provideCurrentUserContext } from './platform-admin-runtime/currentUserContext';
import { loadAppWorkbenchStartupState, usesMockStartup } from './app/appWorkbenchStartup';
import { createBackendHttpClient } from './platform-admin-runtime/backendHttp';
import {
  platformAdminModuleRoutes,
  platformAdminRouteLayouts,
  platformAdminRoutePrefixes,
  isPlatformAdminRoutePage,
} from './platform-admin-runtime/platformAdminRoutes';
import { connectAppRealtime } from './platform-admin-runtime/realtime';
import ChangeOwnPasswordDialog from './app/ChangeOwnPasswordDialog.vue';
import LoginView from './app/LoginView.vue';
import PlatformAdminRouteOutlet from './platform-admin-runtime/PlatformAdminOutlet.vue';
import {
  createModuleOpenApiPageDescriptor,
  isModuleOpenApiPage,
  isOpenApiCatalogPath,
} from './platform-admin-runtime/moduleOpenApi';
import ModuleOpenApiView from './views/ModuleOpenApiView.vue';
import OpenApiCatalogView from './views/OpenApiCatalogView.vue';
import {
  closeMenuTab,
  menuTargetUrl,
  openDirectTab,
  openMenuTab,
  restoreWorkbenchStartupStateFromUrl,
} from './app/workbenchStartup';
import { provideWorkbenchNavigation } from './platform-workbench/workbenchNavigation';
import { router } from './app/router';
import { shouldRestoreWorkbenchFromRoute, workbenchRouteWriteFor } from './app/workbenchRouteSync';

const startup = ref<WorkbenchStartupState>();
const currentUser = computed(() => startup.value?.session.currentUser);
const currentTimeZone = computed(() => currentUser.value?.timeZone);
const loading = ref(true);
const error = ref<string>();
const activeTabKey = ref<string>();
const loginRequired = ref(false);
const loginLoading = ref(false);
const logoutLoading = ref(false);
const changePasswordOpen = ref(false);
const changePasswordSaving = ref(false);
const changePasswordError = ref<string>();
const currentPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const securityNotification = ref<WebUserNotification>();
const securityLogoutCountdown = ref(0);
const openApiCatalogOpen = ref(isOpenApiCatalogPath(window.location.pathname));
const realtimeStatus = ref<WorkbenchRealtimeStatus>('unavailable');
const platformAdminRouteResolveOptions = {
  businessRoutePrefixes: platformAdminRoutePrefixes,
  businessModuleRoutes: platformAdminModuleRoutes,
  businessRouteLayouts: platformAdminRouteLayouts,
};
let realtimeConnection: ReturnType<typeof connectAppRealtime> | undefined;
let securityLogoutTimer: number | undefined;
let pendingWorkbenchNavigation: string | undefined;

configureModuleContext({ httpFactory: createBackendHttpClient });
provideModuleContextConfig({ httpFactory: createBackendHttpClient });
provideCurrentUserContext(currentUser);
providePlatformTimeZoneContext(currentTimeZone);
provideWorkbenchNavigation({ openPage: handleOpenPage, replacePage: handleReplacePage });

const authClient = createAuthClient(createBackendHttpClient({ withAuth: false }));

configureAuthenticationRecovery((error, token) => {
  if (!isCurrentAuthToken(token)) {
    return false;
  }
  scheduleLocalLogout({
    code: error.code,
    message: platformMessage(error.code, error.message),
    logoutRequired: true,
  });
  return true;
});

onMounted(async () => {
  if (!usesMockStartup() && !effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN)) {
    loginRequired.value = true;
    loading.value = false;
    return;
  }
  await loadWorkbench();
});

onUnmounted(() => {
  clearSecurityLogoutTimer();
});

watch(
  () => router.currentRoute.value.fullPath,
  (url) => restoreWorkbenchFromRoute(url),
);

async function loadWorkbench() {
  loading.value = true;
  error.value = undefined;
  try {
    const startupState = await loadAppWorkbenchStartupState();
    const state = restoreWorkbenchStartupStateFromUrl(
      startupState,
      currentBrowserPath(),
      platformAdminRouteResolveOptions,
    );
    startup.value = state;
    activeTabKey.value = state.activeTabKey;
    loginRequired.value = false;
    reconnectRealtime();
    if (!openApiCatalogOpen.value) {
      syncBrowserUrl(state, 'replace');
    }
  } catch (cause) {
    if (isPasswordChangeRequiredError(cause)) {
      openChangeOwnPasswordDialog();
      return;
    }
    if (requiresLogin(cause)) {
      const error = cause as AppError;
      scheduleLocalLogout({
        code: error.code,
        message: platformMessage(error.code, error.message),
        logoutRequired: true,
      });
      return;
    }
    error.value = cause instanceof Error ? cause.message : 'Workbench startup failed';
  } finally {
    loading.value = false;
  }
}

async function handleAuthenticated(result: LoginResult) {
  saveAuthToken(result.token);
  saveAuthSessionId(result.sessionId);
  loginRequired.value = false;
  if (result.passwordChangeRequired) {
    loading.value = false;
    openChangeOwnPasswordDialog();
    return;
  }
  loginLoading.value = true;
  try {
    await loadWorkbench();
  } finally {
    loginLoading.value = false;
  }
}

async function handleUserCommand(command: string) {
  if (command === 'changePassword') {
    openChangeOwnPasswordDialog();
    return;
  }
  if (command === 'logout') {
    await handleLogout();
  }
}

function openChangeOwnPasswordDialog() {
  currentPassword.value = '';
  newPassword.value = '';
  confirmPassword.value = '';
  changePasswordError.value = undefined;
  changePasswordOpen.value = true;
}

function closeChangeOwnPasswordDialog() {
  if (changePasswordSaving.value) {
    return;
  }
  changePasswordOpen.value = false;
  changePasswordError.value = undefined;
}

async function submitChangeOwnPassword() {
  if (changePasswordSaving.value) {
    return;
  }
  const validationError = validateChangeOwnPassword();
  if (validationError) {
    changePasswordError.value = validationError;
    return;
  }
  const token = effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN);
  if (!token) {
    changePasswordError.value = '登录已失效，请重新登录';
    return;
  }
  changePasswordSaving.value = true;
  changePasswordError.value = undefined;
  try {
    await authClient.changeOwnPassword(
      {
        currentPassword: currentPassword.value,
        newPassword: newPassword.value,
      },
      token,
    );
    changePasswordOpen.value = false;
    currentPassword.value = '';
    newPassword.value = '';
    confirmPassword.value = '';
    handleSecurityNotification({
      code: 'platform.security.password-changed',
      message: '你的密码已修改，请重新登录',
      logoutRequired: true,
    });
  } catch (cause) {
    const error = presentPlatformError(cause, { source: 'change-own-password-dialog', phase: 'action' });
    changePasswordError.value = error.message;
  } finally {
    changePasswordSaving.value = false;
  }
}

function validateChangeOwnPassword() {
  if (!currentPassword.value.trim()) {
    return '请输入当前密码';
  }
  if (!newPassword.value.trim()) {
    return '请输入新密码';
  }
  if (newPassword.value !== confirmPassword.value) {
    return '两次输入的新密码不一致';
  }
  if (currentPassword.value === newPassword.value) {
    return '新密码不能与当前密码相同';
  }
  return undefined;
}

async function handleLogout() {
  if (logoutLoading.value) {
    return;
  }
  logoutLoading.value = true;
  const token = effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN);
  try {
    await authClient.logout(token);
  } catch {
    // Local logout should still be possible if the token is already expired or the backend is unavailable.
  } finally {
    clearAuthToken();
    startup.value = undefined;
    activeTabKey.value = undefined;
    error.value = undefined;
    loginRequired.value = true;
    loading.value = false;
    disconnectRealtime();
    logoutLoading.value = false;
    if (currentBrowserPath() !== '/') {
      void router.replace('/');
    }
  }
}

function reconnectRealtime() {
  disconnectRealtime();
  if (!usesMockStartup()) {
    const token = effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN);
    realtimeConnection = connectAppRealtime({
      onUnauthorized: () => handleRealtimeUnauthorized(token),
      onUserNotification: handleSecurityNotification,
      onStateChange: (state) => {
        realtimeStatus.value = workbenchRealtimeStatusOf(state);
      },
    });
  }
}

function disconnectRealtime() {
  const current = realtimeConnection;
  realtimeConnection = undefined;
  realtimeStatus.value = 'unavailable';
  void current?.disconnect();
}

function workbenchRealtimeStatusOf(state: RealtimeConnectionState): WorkbenchRealtimeStatus {
  if (state === 'connected') {
    return 'connected';
  }
  if (state === 'connecting' || state === 'reconnecting') {
    return 'connecting';
  }
  if (state === 'idle') {
    return 'unavailable';
  }
  return 'disconnected';
}

function handleRealtimeUnauthorized(token?: string) {
  if (!isCurrentAuthToken(token)) {
    return;
  }
  scheduleLocalLogout({
    code: 'AUTH_REQUIRED',
    message: platformMessage('AUTH_REQUIRED', '登录状态已失效，请重新登录'),
    logoutRequired: true,
  });
}

function handleSecurityNotification(notification: WebUserNotification) {
  if (notification.targetSessionId && notification.targetSessionId !== storedAuthSessionId()) {
    return;
  }
  if (!notification.logoutRequired) {
    return;
  }
  scheduleLocalLogout(notification);
}

function scheduleLocalLogout(notification: WebUserNotification) {
  if (securityNotification.value || (loginRequired.value && !startup.value)) {
    return;
  }
  securityNotification.value = notification;
  disconnectRealtime();
  startSecurityLogoutCountdown(5);
}

function isCurrentAuthToken(token?: string) {
  return !!token && token === effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN);
}

function startSecurityLogoutCountdown(seconds: number) {
  clearSecurityLogoutTimer();
  securityLogoutCountdown.value = seconds;
  securityLogoutTimer = window.setInterval(() => {
    securityLogoutCountdown.value -= 1;
    if (securityLogoutCountdown.value <= 0) {
      clearSecurityLogoutTimer();
      forceLocalLogout();
    }
  }, 1000);
}

function clearSecurityLogoutTimer() {
  if (securityLogoutTimer === undefined) {
    return;
  }
  window.clearInterval(securityLogoutTimer);
  securityLogoutTimer = undefined;
}

function forceLocalLogout() {
  if (loginRequired.value && !startup.value) {
    return;
  }
  clearSecurityLogoutTimer();
  clearAuthToken();
  startup.value = undefined;
  activeTabKey.value = undefined;
  error.value = undefined;
  loginRequired.value = true;
  loading.value = false;
  disconnectRealtime();
  securityNotification.value = undefined;
  securityLogoutCountdown.value = 0;
  if (currentBrowserPath() !== '/') {
    void router.replace('/');
  }
}

function handleSelectMenu(menu: MenuRecord, target: MenuNavigationTarget) {
  if (target.openMode === 'window') {
    openWindow(menuTargetUrl(menu, target));
    return;
  }

  const current = startup.value;
  if (!current) {
    return;
  }

  const result = openMenuTab(current.tabs ?? [], menu, target, platformAdminRouteResolveOptions);
  startup.value = {
    ...current,
    tabs: result.tabs,
    activeTabKey: result.activeTabKey,
  };
  activeTabKey.value = result.activeTabKey;
  syncBrowserUrl(startup.value, 'push');
}

function handleOpenPage(descriptor: import('@muyun/web-contracts').PageDescriptor) {
  const current = startup.value;
  if (!current) {
    return { created: false };
  }
  const result = openDirectTab(current.tabs ?? [], descriptor);
  startup.value = { ...current, tabs: result.tabs, activeTabKey: result.activeTabKey };
  activeTabKey.value = result.activeTabKey;
  syncBrowserUrl(startup.value, 'push');
  return { created: result.created };
}

function handleReplacePage(pageKey: string, descriptor: import('@muyun/web-contracts').PageDescriptor) {
  const current = startup.value;
  if (!current || !(current.tabs ?? []).some((tab) => tab.key === pageKey)) {
    return;
  }
  const tabs = (current.tabs ?? []).map((tab) =>
    tab.key === pageKey
      ? {
          ...tab,
          title: descriptor.title ?? tab.title,
          pageDescriptor: descriptor,
          restoreState: { url: pageDescriptorToUrl(descriptor) },
        }
      : tab,
  );
  startup.value = { ...current, tabs };
  syncBrowserUrl(startup.value, 'replace');
}

function openWindow(url: string) {
  window.open(url, '_blank', 'noopener,noreferrer');
}

function handleCloseTab(key: string) {
  const current = startup.value;
  if (!current) {
    return;
  }

  const result = closeMenuTab(current.tabs ?? [], activeTabKey.value, key);
  startup.value = {
    ...current,
    tabs: result.tabs,
    activeTabKey: result.activeTabKey,
  };
  activeTabKey.value = result.activeTabKey;
  syncBrowserUrl(startup.value, 'replace');
}

function handleChangeTab(key: string) {
  activeTabKey.value = key;
  const current = startup.value;
  if (!current) {
    return;
  }

  startup.value = {
    ...current,
    activeTabKey: key,
  };
  syncBrowserUrl(startup.value, 'push');
}

function currentBrowserPath() {
  return router.currentRoute.value.fullPath;
}

function returnToWorkbench() {
  if (startup.value) syncBrowserUrl(startup.value, 'replace');
}

function openModuleOpenApi(moduleAlias: string, moduleTitle?: string) {
  handleOpenPage(createModuleOpenApiPageDescriptor(moduleAlias, moduleTitle));
}

function resolveModuleOpenApiTitle(tabKey: string, moduleAlias: string, moduleTitle: string) {
  handleReplacePage(tabKey, createModuleOpenApiPageDescriptor(moduleAlias, moduleTitle));
}

function syncBrowserUrl(state: WorkbenchStartupState, mode: 'push' | 'replace') {
  const navigation = workbenchRouteWriteFor(state, currentBrowserPath(), mode);
  if (!navigation) {
    return;
  }

  pendingWorkbenchNavigation = navigation.url;
  void router[navigation.mode](navigation.url).finally(() => {
    if (pendingWorkbenchNavigation === navigation.url) {
      pendingWorkbenchNavigation = undefined;
    }
  });
}

function restoreWorkbenchFromRoute(url: string) {
  openApiCatalogOpen.value = isOpenApiCatalogPath(router.currentRoute.value.path);
  if (!shouldRestoreWorkbenchFromRoute(url, pendingWorkbenchNavigation, openApiCatalogOpen.value)) {
    pendingWorkbenchNavigation = undefined;
    return;
  }

  const current = startup.value;
  if (!current) {
    return;
  }

  const restored = restoreWorkbenchStartupStateFromUrl(current, url, platformAdminRouteResolveOptions);
  startup.value = restored;
  activeTabKey.value = restored.activeTabKey;
}

function requiresLogin(cause: unknown) {
  if (usesMockStartup()) {
    return false;
  }
  return isAuthenticationRequiredError(cause);
}
</script>

<template>
  <LoginView
    v-if="loginRequired"
    :auth-client="authClient"
    :loading="loginLoading"
    :error="error"
    @authenticated="handleAuthenticated"
  />
  <OpenApiCatalogView v-else-if="openApiCatalogOpen" @open="openModuleOpenApi" @back="returnToWorkbench" />
  <Workbench
    v-else
    v-model:active-tab-key="activeTabKey"
    :startup="startup"
    :loading="loading"
    :error="error"
    :realtime-status="realtimeStatus"
    @select-menu="handleSelectMenu"
    @change-tab="handleChangeTab"
    @close-tab="handleCloseTab"
    @user-command="handleUserCommand"
  >
    <template #default="{ activeTab, pageDescriptor }">
      <ModuleOpenApiView
        v-if="isModuleOpenApiPage(pageDescriptor)"
        :module-alias="pageDescriptor?.target.moduleAlias ?? ''"
        @title-resolved="
          resolveModuleOpenApiTitle(activeTab.key, pageDescriptor?.target.moduleAlias ?? '', $event)
        "
      />
      <PlatformAdminRouteOutlet
        v-else-if="isPlatformAdminRoutePage(pageDescriptor)"
        :descriptor="pageDescriptor"
      />
      <WorkbenchOutlet v-else :descriptor="pageDescriptor" />
    </template>
  </Workbench>
  <ChangeOwnPasswordDialog
    v-model:current-password="currentPassword"
    v-model:new-password="newPassword"
    v-model:confirm-password="confirmPassword"
    :open="changePasswordOpen"
    :saving="changePasswordSaving"
    :error="changePasswordError"
    @close="closeChangeOwnPasswordDialog"
    @submit="submitChangeOwnPassword"
  />
  <div v-if="securityNotification" class="security-notification-mask" role="presentation">
    <section class="security-notification-dialog" role="alertdialog" aria-modal="true">
      <h2>需要重新登录</h2>
      <p>{{ securityNotification.message }}</p>
      <p class="security-notification-countdown">{{ securityLogoutCountdown }} 秒后自动返回登录页</p>
      <button type="button" @click="forceLocalLogout">立即重新登录</button>
    </section>
  </div>
</template>

<style scoped>
.security-notification-mask {
  position: fixed;
  inset: 0;
  z-index: 1300;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.42);
}

.security-notification-dialog {
  display: grid;
  gap: 12px;
  width: min(400px, 100%);
  padding: 22px;
  border: 1px solid #d7dee8;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 22px 54px rgba(15, 23, 42, 0.24);
}

.security-notification-dialog h2,
.security-notification-dialog p {
  margin: 0;
}

.security-notification-dialog h2 {
  color: #111827;
  font-size: 18px;
}

.security-notification-dialog p {
  color: #334155;
  font-size: 14px;
  line-height: 1.6;
}

.security-notification-countdown {
  color: #64748b;
}

.security-notification-dialog button {
  justify-self: end;
  min-width: 108px;
  height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: 6px;
  background: #2563eb;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}
</style>
