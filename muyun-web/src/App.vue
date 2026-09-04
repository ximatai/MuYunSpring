<script setup lang="ts">
import {
  computed,
  defineComponent,
  h,
  onMounted,
  onUnmounted,
  ref,
  shallowRef,
  watch,
  type Component as VueComponent,
} from 'vue';
import { type RouteLocationNormalizedLoaded } from 'vue-router';
import { Workbench, pageDescriptorToUrl, type WorkbenchRealtimeStatus } from '@muyun/platform-workbench';
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
  invokeBusinessNotificationRecordAction,
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
  platformAdminModuleRoutes,
  platformAdminRouteLayouts,
  platformAdminRoutePrefixes,
} from './platform-admin-runtime/platformAdminRoutes';
import { connectAppRealtime } from './platform-admin-runtime/realtime';
import './platform-admin-runtime/workspaceViews';
import ChangeOwnPasswordDialog from './app/ChangeOwnPasswordDialog.vue';
import CurrentUserProfileDialog from './app/CurrentUserProfileDialog.vue';
import LoginView from './app/LoginView.vue';
import ThemeSkinPreferencesDialog from './app/ThemeSkinPreferencesDialog.vue';
import {
  closeMenuTab,
  closeMenuTabs,
  activeTabUrlOf,
  arrangeLockedMenuTabs,
  menuTargetUrl,
  openDirectTab,
  openMenuTab,
  reorderMenuTabs,
  removeLockedMenuTabs,
  restoreLockedWorkbenchTabs,
  restoreSessionWorkbenchTabs,
  restoreWorkbenchStartupStateFromUrl,
  updateLockedMenuTabs,
} from './app/workbenchStartup';
import { withPageInstanceKey } from './platform-workbench/menuNavigation';
import { restoreLockedTabPreference, saveLockedTabPreference } from './app/lockedTabPreference';
import {
  clearWorkbenchSessionTabs,
  restoreWorkbenchSessionTabs,
  saveWorkbenchSessionTabs,
} from './app/workbenchSessionTabs';
import {
  provideWorkbenchNavigation,
  routeUrlWithOpenOptions,
  type OpenRouteOptions,
} from './platform-workbench/workbenchNavigation';
import { syncModulePageWorkspaceViewContributions } from './platform-workbench/modulePageWorkspaceViews';
import {
  clearWorkspaceViewUnsavedState,
  workspaceViewUnsavedStateSources,
} from './platform-workbench/workspaceViewUnsavedState';
import StaticRoutePageHost from './app/StaticRoutePageHost.vue';
import WorkspaceRouteView from './views/WorkspaceRouteView.vue';
import { ensureMenuRoutes, resetMenuRoutes, router } from './app/router';
import {
  workbenchRouteCommitFor,
  workbenchRouteWriteFor,
  type WorkbenchNavigationIntent,
} from './app/workbenchRouteSync';
import {
  restoreThemeSkinPreference,
  saveThemeSkinPreference,
  themeSkinPreferenceKey,
} from './app/themeSkinPreference';

/**
 * KeepAlive must see one stable host component. The tab key is the cache key;
 * creating a component type for each tab causes Vue to treat a return to an
 * existing tab as a fresh component boundary during route transitions.
 */
const CachePageHost = defineComponent({
  name: 'CachePageHost',
  props: {
    component: { type: Object, required: true },
    route: { type: Object, required: true },
    pageDescriptor: { type: Object, required: false },
    refreshRevision: { type: Number, required: false },
  },
  setup: (props) => () =>
    h(StaticRoutePageHost as VueComponent, {
      component: props.component as VueComponent,
      route: props.route as RouteLocationNormalizedLoaded,
      pageDescriptor: props.pageDescriptor,
      refreshRevision: props.refreshRevision,
    }),
});

/**
 * A KeepAlive entry needs an immutable route value for its own lifetime.
 * The workbench commits this snapshot together with the resolved component.
 */
function snapshotPageRoute(route: RouteLocationNormalizedLoaded): RouteLocationNormalizedLoaded {
  return {
    ...route,
    params: { ...route.params },
    query: { ...route.query },
    meta: { ...route.meta },
    matched: [...route.matched],
  } as RouteLocationNormalizedLoaded;
}

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
const pageRefreshRevisions = ref<Record<string, number>>({});
const pageCacheGenerations = ref<Record<string, number>>({});
const pendingTabPageStateDiscards = new Set<string>();
const pageCacheMax = computed(() => Math.max(startup.value?.tabs?.length ?? 0, 1));
const currentUser = computed(() => startup.value?.session.currentUser);
const currentTimeZone = computed(() => currentUser.value?.timeZone);
const loading = ref(true);
const error = ref<string>();
const activeTabKey = ref<string>();
// A tab click updates activeTabKey before Vue Router commits the new route.
// Keep the rendered host bound to the committed route's tab during that gap.
const renderedTabKey = ref<string>();
const renderedPageRoute = shallowRef<RouteLocationNormalizedLoaded>();
const renderedPageComponent = shallowRef<VueComponent>();
/**
 * A tab click and router transition are separate updates. Rendering between
 * them would feed a destination page into the previous tab's cache entry.
 */
const renderedTabMatchesRoute = computed(() => {
  const current = startup.value;
  const key = renderedTabKey.value;
  // Do not render until the active tab, current route, and committed snapshot
  // describe the same page.
  if (!current || !key || key !== activeTabKey.value) return false;
  const tab = current.tabs?.find((candidate) => candidate.key === key);
  const expected = tab?.fullPath ?? (tab?.pageDescriptor && pageDescriptorToUrl(tab.pageDescriptor));
  return expected === router.currentRoute.value.fullPath && expected === renderedPageRoute.value?.fullPath;
});
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
const realtimeStatus = ref<WorkbenchRealtimeStatus>('unavailable');
const platformAdminRouteResolveOptions = {
  businessRoutePrefixes: platformAdminRoutePrefixes,
  businessModuleRoutes: platformAdminModuleRoutes,
  businessRouteLayouts: platformAdminRouteLayouts,
};
let realtimeConnection: ReturnType<typeof connectAppRealtime> | undefined;
let securityLogoutTimer: number | undefined;
let workbenchNavigationRevision = 0;
let latestWorkbenchNavigation: WorkbenchNavigationIntent | undefined;
let pendingWorkbenchNavigation: WorkbenchNavigationIntent | undefined;
let themeSkinPreferenceRevision = 0;
let lockedTabPreferenceRevision = 0;
let lockedTabPreferenceWrite = Promise.resolve();

configureModuleContext({ httpFactory: createBackendHttpClient });
provideModuleContextConfig({ httpFactory: createBackendHttpClient });
const employeeProfileContext = createModuleContext({ moduleAlias: 'iam.employee' });
provideCurrentUserContext(currentUser);
providePlatformTimeZoneContext(currentTimeZone);
provideWorkbenchNavigation({
  openRoute: handleOpenRoute,
  replaceRoute: handleReplaceRoute,
  closeCurrentTab: handleCloseCurrentTab,
  openPage: handleOpenPage,
  replacePage: handleReplacePage,
  closePage: handleCloseTab,
  setTabName: handleSetTabName,
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
  (url) => {
    if (restoreWorkbenchFromRoute(url)) {
      commitRenderedTab(activeTabKey.value);
    }
  },
);

watch(
  () => {
    const state = startup.value;
    if (!state) return undefined;
    return {
      userId: state.session.currentUser.userId,
      tabs: state.tabs ?? [],
      activeTabKey: state.activeTabKey,
    };
  },
  (snapshot) => {
    if (snapshot) saveWorkbenchSessionTabs(snapshot);
  },
  { deep: true },
);

async function loadWorkbench() {
  loading.value = true;
  error.value = undefined;
  try {
    const startupState = await loadAppWorkbenchStartupState();
    syncModulePageWorkspaceViewContributions();
    const sessionTabs = restoreWorkbenchSessionTabs(startupState.session.currentUser.userId);
    const restoredSessionTabs = sessionTabs
      ? restoreSessionWorkbenchTabs(sessionTabs.tabs, startupState.menus, platformAdminRouteResolveOptions)
      : [];
    const sessionState = {
      ...startupState,
      tabs: restoredSessionTabs.length > 0 ? restoredSessionTabs : startupState.tabs,
      activeTabKey: restoredSessionTabs.some((tab) => tab.key === sessionTabs?.activeTabKey)
        ? sessionTabs?.activeTabKey
        : startupState.activeTabKey,
    };
    const state = restoreWorkbenchStartupStateFromUrl(
      sessionState,
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
    await ensureMenuRoutes(arrangedState.menus);
    activeTabKey.value = arrangedState.activeTabKey;
    commitRenderedTab(arrangedState.activeTabKey);
    loginRequired.value = false;
    void restoreThemeSkinFromBackend();
    reconnectRealtime();
    syncBrowserUrl(arrangedState, 'replace');
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
    clearWorkbenchSessionTabs();
    resetMenuRoutes();
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
        hostType: 'module-page-host',
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
      await invokeBusinessNotificationRecordAction(createBackendHttpClient(), action);
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
  clearWorkbenchSessionTabs();
  resetMenuRoutes();
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
  const result = openDirectTab(current.tabs ?? [], descriptor, platformAdminRouteResolveOptions);
  startup.value = { ...current, tabs: result.tabs, activeTabKey: result.activeTabKey };
  activeTabKey.value = result.activeTabKey;
  syncBrowserUrl(startup.value, 'push');
  return { created: result.created };
}

function handleOpenRoute(path: string, options: OpenRouteOptions = {}) {
  return navigateWorkbenchRoute(
    routeUrlWithOpenOptions(path, options),
    'push',
    startup.value,
    options.tabTitle,
    false,
    options.newInstance,
  );
}

function handleReplaceRoute(path: string, options: OpenRouteOptions = {}) {
  const current = startup.value;
  if (!current) return { created: false };
  return navigateWorkbenchRoute(
    routeUrlWithOpenOptions(path, options),
    'replace',
    current,
    options.tabTitle,
    true,
    false,
  );
}

function handleCloseCurrentTab(fallbackPath: string) {
  const current = startup.value;
  const currentTabKey = activeTabKey.value ?? current?.activeTabKey;
  if (!current || !currentTabKey) return { created: false };

  // WorkbenchNavigation keeps a synchronous result contract for consumers,
  // while confirmation is asynchronous.  Start the guarded close here rather
  // than directly mutating the tab array so this public entry cannot bypass
  // workspace draft protection.
  void closeCurrentTabAfterConfirm(fallbackPath, currentTabKey);
  return { created: false };
}

async function closeCurrentTabAfterConfirm(fallbackPath: string, currentTabKey: string) {
  if (!(await confirmDiscardWorkspaceViewState([currentTabKey]))) return;

  const current = startup.value;
  if (!current || !(current.tabs ?? []).some((tab) => tab.key === currentTabKey)) return;
  const result = closeMenuTab(current.tabs ?? [], activeTabKey.value, currentTabKey);
  scheduleTabPageStateDiscard([currentTabKey]);
  clearWorkspaceViewUnsavedState(currentTabKey);
  if (lockedTabs.value.some((tab) => tab.key === currentTabKey)) {
    updateLockedTabs(removeLockedMenuTabs(lockedTabs.value, [currentTabKey]));
  }
  const fallback = result.tabs.find((tab) => {
    const url = tab.fullPath ?? (tab.pageDescriptor ? pageDescriptorToUrl(tab.pageDescriptor) : undefined);
    return url && new URL(url, window.location.origin).pathname === fallbackPath;
  });
  if (fallback) {
    startup.value = { ...current, tabs: result.tabs, activeTabKey: fallback.key };
    activeTabKey.value = fallback.key;
    syncBrowserUrl(startup.value, 'replace');
    return { created: false };
  }
  return navigateWorkbenchRoute(routeUrlWithOpenOptions(fallbackPath), 'replace', {
    ...current,
    tabs: result.tabs,
    activeTabKey: result.activeTabKey,
  });
}

function navigateWorkbenchRoute(
  url: string,
  mode: 'push' | 'replace',
  state: WorkbenchStartupState | undefined,
  tabTitle?: string,
  replaceCurrent = false,
  newInstance = false,
) {
  if (!state) return { created: false };
  const previousKeys = new Set((state.tabs ?? []).map((tab) => tab.key));
  let next = restoreWorkbenchStartupStateFromUrl(state, url, platformAdminRouteResolveOptions, newInstance);
  const replacement = next.tabs?.find((tab) => tab.key === next.activeTabKey);
  if (replaceCurrent && state.activeTabKey && replacement) {
    const currentTab = state.tabs?.find((tab) => tab.key === state.activeTabKey);
    if (currentTab) {
      const title = tabTitle ?? replacement.title;
      const pageDescriptor =
        replacement.pageDescriptor && currentTab.instanceKey
          ? withPageInstanceKey(replacement.pageDescriptor, currentTab.instanceKey)
          : replacement.pageDescriptor;
      const fullPath = pageDescriptor
        ? pageDescriptorToUrl(pageDescriptor, platformAdminRouteResolveOptions)
        : replacement.fullPath;
      const tabs = (next.tabs ?? [])
        .filter((tab) => tab.key !== replacement.key)
        .map((tab) =>
          tab.key === currentTab.key
            ? {
                ...currentTab,
                ...replacement,
                key: currentTab.key,
                instanceKey: currentTab.instanceKey,
                pageDescriptor,
                fullPath,
                restoreState: fullPath ? { url: fullPath } : replacement.restoreState,
                title,
              }
            : tab,
        );
      next = { ...next, tabs, activeTabKey: currentTab.key };
    }
  } else if (tabTitle && next.activeTabKey) {
    next = {
      ...next,
      tabs: (next.tabs ?? []).map((tab) =>
        tab.key === next.activeTabKey ? { ...tab, title: tabTitle } : tab,
      ),
    };
  }
  startup.value = next;
  activeTabKey.value = next.activeTabKey;
  syncBrowserUrl(next, mode);
  return { created: Boolean(next.activeTabKey && !previousKeys.has(next.activeTabKey)) };
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
          fullPath: pageDescriptorToUrl(descriptor, platformAdminRouteResolveOptions),
          pageDescriptor: descriptor,
          restoreState: { url: pageDescriptorToUrl(descriptor, platformAdminRouteResolveOptions) },
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

function handleSetTabName(instanceKey: string, name: string) {
  const current = startup.value;
  const title = name.trim();
  if (!current || !instanceKey || !title) return;
  startup.value = {
    ...current,
    tabs: (current.tabs ?? []).map((tab) => (tab.instanceKey === instanceKey ? { ...tab, title } : tab)),
  };
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

async function handleCloseTab(key: string) {
  const current = startup.value;
  if (!current) {
    return;
  }
  if (!(await confirmDiscardWorkspaceViewState([key]))) return;

  const result = closeMenuTab(current.tabs ?? [], activeTabKey.value, key);
  startup.value = {
    ...current,
    tabs: result.tabs,
    activeTabKey: result.activeTabKey,
  };
  activeTabKey.value = result.activeTabKey;
  scheduleTabPageStateDiscard([key]);
  clearWorkspaceViewUnsavedState(key);
  if (lockedTabs.value.some((tab) => tab.key === key))
    updateLockedTabs(removeLockedMenuTabs(lockedTabs.value, [key]));
  syncBrowserUrl(startup.value, 'replace');
}

async function handleCloseTabs(keys: string[]) {
  const current = startup.value;
  if (!current || keys.length === 0) {
    return;
  }
  if (!(await confirmDiscardWorkspaceViewState(keys))) return;
  const result = closeMenuTabs(current.tabs ?? [], activeTabKey.value, keys);
  startup.value = {
    ...current,
    tabs: result.tabs,
    activeTabKey: result.activeTabKey,
  };
  activeTabKey.value = result.activeTabKey;
  scheduleTabPageStateDiscard(keys);
  keys.forEach(clearWorkspaceViewUnsavedState);
  const nextLockedTabs = removeLockedMenuTabs(lockedTabs.value, keys);
  if (nextLockedTabs.length !== lockedTabs.value.length) updateLockedTabs(nextLockedTabs);
  syncBrowserUrl(startup.value, 'replace');
}

async function confirmDiscardWorkspaceViewState(keys: readonly string[]): Promise<boolean> {
  const dirtySources = [...new Set(keys.flatMap(workspaceViewUnsavedStateSources))];
  if (dirtySources.length === 0) return true;
  const summary = dirtySources.join('、');
  return confirmAction({
    title: '关闭标签',
    content: `“${summary}”存在未保存的更改，关闭后将丢失。是否继续？`,
    okText: '关闭',
  });
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

function pageDescriptorForTab(tabKey: string | undefined) {
  const current = startup.value;
  if (!current || !tabKey) return undefined;
  return (current.tabs ?? []).find((tab) => tab.key === tabKey)?.pageDescriptor;
}

function syncBrowserUrl(state: WorkbenchStartupState, mode: 'push' | 'replace') {
  const intent: WorkbenchNavigationIntent = {
    url: activeTabUrlOf(state) ?? '/',
    revision: ++workbenchNavigationRevision,
  };
  latestWorkbenchNavigation = intent;
  const navigation = workbenchRouteWriteFor(state, currentBrowserPath(), mode);
  if (!navigation) {
    // Different workbench tabs may intentionally share one public URL. There
    // is no router update in that case, so commit the tab runtime explicitly.
    commitRenderedTab(state.activeTabKey);
    return;
  }

  pendingWorkbenchNavigation = intent;
  void router[navigation.mode](navigation.url).finally(() => {
    if (pendingWorkbenchNavigation === intent) {
      pendingWorkbenchNavigation = undefined;
    }
  });
}

/**
 * The rendered tab is the single owner of a page runtime's cache key,
 * descriptor, and refresh revision. It moves only when its route is committed,
 * except when two tabs intentionally share the same public URL.
 */
function commitRenderedTab(tabKey: string | undefined) {
  renderedTabKey.value = tabKey;
  const route = router.currentRoute.value;
  const component = componentForCommittedRoute(route);
  if (component) {
    renderedPageRoute.value = snapshotPageRoute(route);
    renderedPageComponent.value = component;
  }
  flushPendingTabPageStateDiscards();
}

/** Returns false while a stale route is being reconciled to the latest tab intent. */
function restoreWorkbenchFromRoute(url: string): boolean {
  const pending = pendingWorkbenchNavigation;
  if (
    workbenchRouteCommitFor(url, pending, latestWorkbenchNavigation) === 'reconcile' &&
    latestWorkbenchNavigation
  ) {
    // A previous router transition committed after a newer tab intent (which
    // may intentionally have the current URL). Never restore that stale tab
    // into the current runtime; return the router to the latest intent first.
    pendingWorkbenchNavigation = latestWorkbenchNavigation;
    void router.replace(latestWorkbenchNavigation.url).finally(() => {
      if (pendingWorkbenchNavigation === latestWorkbenchNavigation) {
        pendingWorkbenchNavigation = undefined;
      }
    });
    return false;
  }
  if (workbenchRouteCommitFor(url, pending, latestWorkbenchNavigation) === 'commit') {
    pendingWorkbenchNavigation = undefined;
    return true;
  }

  const current = startup.value;
  if (!current) {
    return true;
  }

  const restored = restoreWorkbenchStartupStateFromUrl(current, url, platformAdminRouteResolveOptions);
  startup.value = restored;
  activeTabKey.value = restored.activeTabKey;
  return true;
}

function requiresLogin(cause: unknown) {
  if (usesMockStartup()) {
    return false;
  }
  return isAuthenticationRequiredError(cause);
}

function pageRefreshRevisionFor(tabKey: string | undefined) {
  return tabKey ? (pageRefreshRevisions.value[tabKey] ?? 0) : 0;
}

/** Rebuilds exactly the requested page instance without touching other cached tabs. */
function refreshPage(tabKey: string) {
  pageRefreshRevisions.value = {
    ...pageRefreshRevisions.value,
    [tabKey]: pageRefreshRevisionFor(tabKey) + 1,
  };
}

/**
 * Closing the active tab must not mutate its key while its route is still on
 * screen: doing so would mount a new instance of a page that is being closed.
 */
function scheduleTabPageStateDiscard(keys: readonly string[]) {
  const deferred = keys.filter((key) => key === renderedTabKey.value);
  const immediate = keys.filter((key) => key !== renderedTabKey.value);
  if (immediate.length > 0) discardTabPageState(immediate);
  deferred.forEach((key) => pendingTabPageStateDiscards.add(key));
}

function flushPendingTabPageStateDiscards() {
  const ready = [...pendingTabPageStateDiscards].filter((key) => key !== renderedTabKey.value);
  ready.forEach((key) => pendingTabPageStateDiscards.delete(key));
  if (ready.length > 0) discardTabPageState(ready);
}

/** Refresh revisions are page-instance state and must leave with their closed tabs. */
function discardTabPageState(keys: readonly string[]) {
  if (keys.length === 0) return;
  const nextCacheGenerations = { ...pageCacheGenerations.value };
  keys.forEach((key) => {
    nextCacheGenerations[key] = (nextCacheGenerations[key] ?? 0) + 1;
  });
  pageCacheGenerations.value = nextCacheGenerations;
  const discarded = new Set(keys);
  const retained = Object.fromEntries(
    Object.entries(pageRefreshRevisions.value).filter(([key]) => !discarded.has(key)),
  );
  if (Object.keys(retained).length !== Object.keys(pageRefreshRevisions.value).length) {
    pageRefreshRevisions.value = retained;
  }
}

/** Closing a tab advances only its generation; a later reopen gets a fresh page. */
function pageRuntimeCacheKey(tabKey: string | undefined) {
  const generation = tabKey ? (pageCacheGenerations.value[tabKey] ?? 0) : 0;
  return `${tabKey ?? 'unbound'}:${generation}`;
}

/** The generic workspace route is a stable shell, even while its async outlet resolves. */
function componentForWorkbenchRoute(
  route: import('vue-router').RouteLocationNormalizedLoaded,
  component: VueComponent,
) {
  return route.name === 'workspace-view-route' ? WorkspaceRouteView : component;
}

/**
 * Resolve the already-committed route record, then hand its component and a
 * route snapshot to KeepAlive as one unit. This prevents a cached tab from
 * observing another tab's route.
 */
function componentForCommittedRoute(route: RouteLocationNormalizedLoaded): VueComponent | undefined {
  const component = route.matched.at(-1)?.components?.default as VueComponent | undefined;
  return component ? componentForWorkbenchRoute(route, component) : undefined;
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
      @refresh-page="refreshPage"
      @user-command="handleUserCommand"
    >
      <template #default>
        <KeepAlive :max="pageCacheMax">
          <component
            :is="CachePageHost"
            v-if="renderedPageRoute && renderedPageComponent && renderedPageRoute.meta.cacheable !== false && renderedTabMatchesRoute"
            :key="pageRuntimeCacheKey(renderedTabKey)"
            :component="renderedPageComponent"
            :route="renderedPageRoute"
            :page-descriptor="pageDescriptorForTab(renderedTabKey)"
            :refresh-revision="pageRefreshRevisionFor(renderedTabKey)"
          />
        </KeepAlive>
        <StaticRoutePageHost
          v-if="renderedPageRoute && renderedPageComponent && renderedPageRoute.meta.cacheable === false && renderedTabMatchesRoute"
          :key="pageRuntimeCacheKey(renderedTabKey)"
          :component="renderedPageComponent"
          :route="renderedPageRoute"
          :page-descriptor="pageDescriptorForTab(renderedTabKey)"
          :refresh-revision="pageRefreshRevisionFor(renderedTabKey)"
        />
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
