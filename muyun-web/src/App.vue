<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import {
  Workbench,
  WorkbenchOutlet,
  pageDescriptorToUrl,
  type WorkbenchRealtimeStatus,
} from '@muyun/platform-workbench';
import {
  UiThemeProvider,
  confirmAction,
  defaultUiThemeSkinId,
  uiThemeSkinById,
  uiThemeSkins,
  type UiThemeSkinId,
} from '@muyun/vue-ui-antdv';
import {
  presentPlatformError,
  presentPlatformSuccess,
  providePlatformTimeZoneContext,
  BusinessNotificationPanel,
} from '@muyun/platform-components';
import {
  configureModuleContext,
  createModuleContext,
  createAuthClient,
  createLoginContextClient,
  invokeBusinessNotificationCommand,
  provideModuleContextConfig,
  userPreferences,
  type AppError,
  type RealtimeConnectionState,
} from '@muyun/web-core';
import { configureUserPreferenceBackend } from './web-core/userPreferences';
import type {
  LoginResult,
  CurrentUserProfile,
  MenuTab,
  MenuNavigationTarget,
  MenuRecord,
  WebUserNotification,
  WebBusinessNotification,
  WebBusinessNotificationAction,
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
  platformAdminDynamicModuleRoutes,
  platformAdminModuleRoutes,
  platformAdminRouteLayouts,
  platformAdminRoutePrefixes,
  isPlatformAdminRoutePage,
} from './platform-admin-runtime/platformAdminRoutes';
import { connectAppRealtime } from './platform-admin-runtime/realtime';
import ChangeOwnPasswordDialog from './app/ChangeOwnPasswordDialog.vue';
import CurrentUserProfileDialog from './app/CurrentUserProfileDialog.vue';
import LoginView from './app/LoginView.vue';
import ThemeSkinPreferencesDialog from './app/ThemeSkinPreferencesDialog.vue';
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
  closeMenuTabs,
  arrangeLockedMenuTabs,
  menuTargetUrl,
  openDirectTab,
  openMenuTab,
  reorderMenuTabs,
  removeLockedMenuTabs,
  restoreLockedWorkbenchTabs,
  restoreWorkbenchStartupStateFromUrl,
  updateLockedMenuTabs,
} from './app/workbenchStartup';
import { restoreLockedTabPreference, saveLockedTabPreference } from './app/lockedTabPreference';
import { provideWorkbenchNavigation } from './platform-workbench/workbenchNavigation';
import { syncModulePageWorkspaceViewContributions } from './platform-workbench/modulePageWorkspaceViews';
import { router } from './app/router';
import { shouldRestoreWorkbenchFromRoute, workbenchRouteWriteFor } from './app/workbenchRouteSync';
import {
  restoreThemeSkinPreference,
  saveThemeSkinPreference,
  themeSkinPreferenceKey,
} from './app/themeSkinPreference';

configureUserPreferenceBackend({
  load: async (key) => {
    const response = await createBackendHttpClient().request<{ valueJson?: string } | undefined>({
      path: `/platform.user-preference/${encodeURIComponent(key)}`,
      query: { clientType: 'WEB' },
    });
    return response?.valueJson;
  },
  save: (key, valueJson) =>
    createBackendHttpClient().request({
      method: 'POST',
      path: `/platform.user-preference/${encodeURIComponent(key)}`,
      body: { clientType: 'WEB', valueJson },
    }),
  remove: (key) =>
    createBackendHttpClient().request({
      method: 'DELETE',
      path: `/platform.user-preference/${encodeURIComponent(key)}`,
      query: { clientType: 'WEB' },
    }),
});

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
const profileOpen = ref(false);
const profileLoading = ref(false);
const profileSaving = ref(false);
const profileError = ref<string>();
const profile = ref<CurrentUserProfile>();
const themeSkinPreferencesOpen = ref(false);
const themeSkinSaving = ref(false);
const themeSkinError = ref<string>();
const themeSkinId = ref<UiThemeSkinId>(
  uiThemeSkinById(userPreferences.get(themeSkinPreferenceKey, defaultUiThemeSkinId)).id,
);
const activeThemeSkin = computed(() => uiThemeSkinById(themeSkinId.value));
const lockedTabs = ref<MenuTab[]>([]);
const currentPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const securityNotification = ref<WebUserNotification>();
const businessNotifications = ref<WebBusinessNotification[]>([]);
const securityLogoutCountdown = ref(0);
const openApiCatalogOpen = ref(isOpenApiCatalogPath(window.location.pathname));
const realtimeStatus = ref<WorkbenchRealtimeStatus>('unavailable');
const platformAdminRouteResolveOptions = {
  businessRoutePrefixes: platformAdminRoutePrefixes,
  businessModuleRoutes: platformAdminModuleRoutes,
  dynamicModuleRoutes: platformAdminDynamicModuleRoutes,
  businessRouteLayouts: platformAdminRouteLayouts,
};
let realtimeConnection: ReturnType<typeof connectAppRealtime> | undefined;
let securityLogoutTimer: number | undefined;
let pendingWorkbenchNavigation: string | undefined;
let themeSkinPreferenceRevision = 0;
let lockedTabPreferenceRevision = 0;
let lockedTabPreferenceWrite = Promise.resolve();

configureModuleContext({ httpFactory: createBackendHttpClient });
provideModuleContextConfig({ httpFactory: createBackendHttpClient });
const employeeProfileContext = createModuleContext({ moduleAlias: 'iam.employee' });
provideCurrentUserContext(currentUser);
providePlatformTimeZoneContext(currentTimeZone);
provideWorkbenchNavigation({
  openPage: handleOpenPage,
  replacePage: handleReplacePage,
  closePage: handleCloseTab,
});

const anonymousHttpClient = createBackendHttpClient({ withAuth: false });
const authClient = createAuthClient(anonymousHttpClient);
const loginContextClient = createLoginContextClient(anonymousHttpClient);

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
  disconnectRealtime();
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
    syncModulePageWorkspaceViewContributions();
    const state = restoreWorkbenchStartupStateFromUrl(
      startupState,
      currentBrowserPath(),
      platformAdminRouteResolveOptions,
    );
    const restoredLockedTabs = restoreLockedWorkbenchTabs(
      await restoreLockedTabs(),
      state.menus,
      platformAdminRouteResolveOptions,
    );
    lockedTabs.value = restoredLockedTabs;
    const arrangedState = { ...state, tabs: arrangeLockedMenuTabs(state.tabs ?? [], restoredLockedTabs) };
    startup.value = arrangedState;
    activeTabKey.value = arrangedState.activeTabKey;
    loginRequired.value = false;
    void restoreThemeSkinFromBackend();
    reconnectRealtime();
    if (!openApiCatalogOpen.value) {
      syncBrowserUrl(arrangedState, 'replace');
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
  if (command === 'themeSkin') {
    openThemeSkinPreferences();
    return;
  }
  if (command === 'profile') {
    await openCurrentUserProfile();
    return;
  }
  if (command === 'logout') {
    await handleLogout();
  }
}

function openThemeSkinPreferences() {
  themeSkinError.value = undefined;
  themeSkinPreferencesOpen.value = true;
}

async function restoreThemeSkinFromBackend() {
  if (usesMockStartup()) {
    return;
  }
  const revision = ++themeSkinPreferenceRevision;
  try {
    const restored = await restoreThemeSkinPreference(userPreferences, themeSkinId.value);
    if (revision !== themeSkinPreferenceRevision) {
      return;
    }
    themeSkinId.value = restored;
  } catch {
    // Keep the locally restored skin when the preference service is temporarily unavailable.
  }
}

async function selectThemeSkin(skinId: UiThemeSkinId) {
  if (themeSkinSaving.value || skinId === themeSkinId.value) {
    return;
  }
  const revision = ++themeSkinPreferenceRevision;
  const previousSkinId = themeSkinId.value;
  themeSkinId.value = skinId;
  themeSkinSaving.value = true;
  themeSkinError.value = undefined;
  if (usesMockStartup()) {
    await userPreferences.set(themeSkinPreferenceKey, skinId, { persistence: 'local' });
    themeSkinSaving.value = false;
    return;
  }
  try {
    const result = await saveThemeSkinPreference(userPreferences, skinId, previousSkinId);
    if (result.error) {
      if (revision !== themeSkinPreferenceRevision) {
        return;
      }
      themeSkinId.value = result.skinId;
      themeSkinError.value = result.error;
    }
  } finally {
    themeSkinSaving.value = false;
  }
}

function closeThemeSkinPreferences() {
  if (!themeSkinSaving.value) {
    themeSkinPreferencesOpen.value = false;
  }
}

function openChangeOwnPasswordDialog() {
  currentPassword.value = '';
  newPassword.value = '';
  confirmPassword.value = '';
  changePasswordError.value = undefined;
  changePasswordOpen.value = true;
}

async function openCurrentUserProfile() {
  const token = effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN);
  if (!token) {
    return;
  }
  profileOpen.value = true;
  profileLoading.value = true;
  profileError.value = undefined;
  try {
    profile.value = await authClient.currentProfile(token);
  } catch (cause) {
    profileError.value = presentPlatformError(cause, {
      source: 'current-user-profile',
      phase: 'load',
    }).message;
  } finally {
    profileLoading.value = false;
  }
}

function closeCurrentUserProfile() {
  if (!profileSaving.value) {
    profileOpen.value = false;
    profileError.value = undefined;
  }
}

async function submitCurrentUserProfile(value: { mobile: string; email: string; avatarAssetId?: string }) {
  const token = effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN);
  if (!token || profileSaving.value) return;
  profileSaving.value = true;
  profileError.value = undefined;
  try {
    profile.value = await authClient.updateCurrentProfile(value, token);
    profileOpen.value = false;
    presentPlatformSuccess('个人资料已保存', { source: 'current-user-profile', phase: 'action' });
  } catch (cause) {
    profileError.value = presentPlatformError(cause, {
      source: 'current-user-profile',
      phase: 'action',
    }).message;
  } finally {
    profileSaving.value = false;
  }
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
    businessNotifications.value = [];
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
      baseUrl: import.meta.env.VITE_MUYUN_API_BASE_URL,
      token,
      onUnauthorized: () => handleRealtimeUnauthorized(token),
      onUserNotification: handleSecurityNotification,
      onBusinessNotification: receiveBusinessNotification,
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

function receiveBusinessNotification(notification: WebBusinessNotification) {
  const current = businessNotifications.value.filter((item) => item.id !== notification.id);
  businessNotifications.value = [...current, notification];
}

function dismissBusinessNotification(notificationId: string) {
  businessNotifications.value = businessNotifications.value.filter((item) => item.id !== notificationId);
}

async function executeBusinessNotificationAction(
  notification: WebBusinessNotification,
  action: WebBusinessNotificationAction,
) {
  try {
    if (action.kind === 'navigate') {
      handleOpenPage({
        pageType: 'dynamic-module',
        openMode: 'dynamic-runner',
        hostType: 'dynamic-module-host',
        target: {
          moduleAlias: action.moduleAlias,
          pageMode: action.pageMode ?? 'LIST',
        },
        params: { ...(action.query ?? {}), ...(action.recordId ? { recordId: action.recordId } : {}) },
        tabPolicy: { identity: action.recordId ? 'by-params' : 'by-target', closable: true, cacheable: true },
      });
    } else {
      if (action.confirmation) {
        const confirmed = await confirmAction({
          title: action.label,
          content: action.confirmation,
          danger: action.danger,
        });
        if (!confirmed) return;
      }
      await invokeBusinessNotificationCommand(createBackendHttpClient(), notification.id, action);
      presentPlatformSuccess('操作完成', { source: 'business-notification', phase: 'action' });
    }
    if (action.dismissOnSuccess) dismissBusinessNotification(notification.id);
  } catch (cause) {
    presentPlatformError(cause, { source: 'business-notification', phase: 'action' });
  }
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
  businessNotifications.value = [];
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
  const replacement = tabs.find((tab) => tab.key === pageKey);
  if (replacement && lockedTabs.value.some((tab) => tab.key === pageKey)) {
    updateLockedTabs(updateLockedMenuTabs(lockedTabs.value, replacement));
  }
  syncBrowserUrl(startup.value, 'replace');
}

function openWindow(url: string) {
  window.open(url, '_blank', 'noopener,noreferrer');
}

async function restoreLockedTabs(): Promise<MenuTab[]> {
  if (usesMockStartup()) return [];
  try {
    return await restoreLockedTabPreference(userPreferences);
  } catch {
    return [];
  }
}

function lockedTabKeys() {
  return lockedTabs.value.map((tab) => tab.key);
}

function updateLockedTabs(nextLockedTabs: MenuTab[]) {
  const previousLockedTabs = lockedTabs.value;
  const revision = ++lockedTabPreferenceRevision;
  lockedTabs.value = nextLockedTabs;
  void persistLockedTabs(nextLockedTabs, previousLockedTabs, revision);
}

async function persistLockedTabs(nextLockedTabs: MenuTab[], previousLockedTabs: MenuTab[], revision: number) {
  try {
    lockedTabPreferenceWrite = lockedTabPreferenceWrite
      .catch(() => undefined)
      .then(async () => {
        if (usesMockStartup()) return;
        await saveLockedTabPreference(userPreferences, nextLockedTabs);
      });
    await lockedTabPreferenceWrite;
  } catch {
    if (revision !== lockedTabPreferenceRevision) return;
    lockedTabs.value = previousLockedTabs;
    const current = startup.value;
    if (current)
      startup.value = {
        ...current,
        tabs: arrangeLockedMenuTabs(current.tabs ?? [], previousLockedTabs, false),
      };
  }
}

function handleToggleTabLock(key: string) {
  const current = startup.value;
  const tab = current?.tabs?.find((item) => item.key === key);
  if (!current || !tab) return;
  const nextLockedTabs = lockedTabs.value.some((item) => item.key === key)
    ? removeLockedMenuTabs(lockedTabs.value, [key])
    : updateLockedMenuTabs(lockedTabs.value, tab);
  updateLockedTabs(nextLockedTabs);
  startup.value = { ...current, tabs: arrangeLockedMenuTabs(current.tabs ?? [], nextLockedTabs) };
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
  if (lockedTabs.value.some((tab) => tab.key === key))
    updateLockedTabs(removeLockedMenuTabs(lockedTabs.value, [key]));
  syncBrowserUrl(startup.value, 'replace');
}

function handleCloseTabs(keys: string[]) {
  const current = startup.value;
  if (!current || keys.length === 0) {
    return;
  }
  const result = closeMenuTabs(current.tabs ?? [], activeTabKey.value, keys);
  startup.value = {
    ...current,
    tabs: result.tabs,
    activeTabKey: result.activeTabKey,
  };
  activeTabKey.value = result.activeTabKey;
  const nextLockedTabs = removeLockedMenuTabs(lockedTabs.value, keys);
  if (nextLockedTabs.length !== lockedTabs.value.length) updateLockedTabs(nextLockedTabs);
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

function handleReorderTabs(keys: string[]) {
  const current = startup.value;
  if (!current) return;
  const tabs = reorderMenuTabs(current.tabs ?? [], keys, lockedTabKeys());
  startup.value = { ...current, tabs };
  const nextLockedTabs = tabs.filter((tab) =>
    lockedTabs.value.some((lockedTab) => lockedTab.key === tab.key),
  );
  if (nextLockedTabs.map((tab) => tab.key).join('|') !== lockedTabKeys().join('|'))
    updateLockedTabs(nextLockedTabs);
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
  <UiThemeProvider :theme="activeThemeSkin.theme" scope="global">
    <LoginView
      v-if="loginRequired"
      :auth-client="authClient"
      :login-context-client="loginContextClient"
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
      :theme-appearance="activeThemeSkin.theme.appearance"
      :locked-tab-keys="lockedTabKeys()"
      @select-menu="handleSelectMenu"
      @change-tab="handleChangeTab"
      @close-tab="handleCloseTab"
      @close-tabs="handleCloseTabs"
      @toggle-tab-lock="handleToggleTabLock"
      @reorder-tabs="handleReorderTabs"
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
    <CurrentUserProfileDialog
      :open="profileOpen"
      :profile="profile"
      :loading="profileLoading"
      :saving="profileSaving"
      :error="profileError"
      :avatar-context="employeeProfileContext"
      @close="closeCurrentUserProfile"
      @submit="submitCurrentUserProfile"
    />
    <ThemeSkinPreferencesDialog
      :open="themeSkinPreferencesOpen"
      :skins="uiThemeSkins"
      :active-skin-id="themeSkinId"
      :saving="themeSkinSaving"
      :error="themeSkinError"
      @close="closeThemeSkinPreferences"
      @select="selectThemeSkin"
    />
    <BusinessNotificationPanel
      :notifications="businessNotifications"
      :execute-action="executeBusinessNotificationAction"
      @dismiss="dismissBusinessNotification"
    />
    <div v-if="securityNotification" class="security-notification-mask" role="presentation">
      <section class="security-notification-dialog" role="alertdialog" aria-modal="true">
        <h2>需要重新登录</h2>
        <p>{{ securityNotification.message }}</p>
        <p class="security-notification-countdown">{{ securityLogoutCountdown }} 秒后自动返回登录页</p>
        <button type="button" @click="forceLocalLogout">立即重新登录</button>
      </section>
    </div>
  </UiThemeProvider>
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
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
  box-shadow: 0 22px 54px rgba(15, 23, 42, 0.24);
}

.security-notification-dialog h2,
.security-notification-dialog p {
  margin: 0;
}

.security-notification-dialog h2 {
  color: var(--muyun-support-text);
  font-size: 18px;
}

.security-notification-dialog p {
  color: var(--muyun-support-text-body);
  font-size: 14px;
  line-height: 1.6;
}

.security-notification-countdown {
  color: var(--muyun-support-text-muted);
}

.security-notification-dialog button {
  justify-self: end;
  min-width: 108px;
  height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: 6px;
  background: var(--muyun-theme-base);
  color: var(--muyun-support-surface);
  font-size: 14px;
  cursor: pointer;
}
</style>
