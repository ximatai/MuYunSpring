<script setup lang="ts">
import { executeBusinessNotificationRecordAction } from './app/executeBusinessNotificationRecordAction';
import {
  computed,
  defineAsyncComponent,
  defineComponent,
  h,
  nextTick,
  onMounted,
  onUnmounted,
  ref,
  shallowRef,
  watch,
  type Component as VueComponent,
} from 'vue';
import { createWorkspaceViewDescriptor } from './platform-workbench/workspaceViews';
import { moduleGovernanceWorkspaceView } from './views/moduleGovernanceWorkspaceView';
import {
  createPageCompositionWorkspace,
  providePageCompositionWorkspace,
} from './views/pageCompositionWorkspace';
import { createMetadataWorkspace, provideMetadataWorkspace } from './views/metadataWorkspace';
import { createBusinessRuleWorkspace, provideBusinessRuleWorkspace } from './views/businessRuleWorkspace';
import {
  createModuleMenuWorkspace,
  provideModuleMenuWorkspace,
} from './platform-admin-runtime/module-menu/moduleMenuWorkspace';
import { type RouteLocationNormalizedLoaded } from 'vue-router';
import { Workbench, pageDescriptorToUrl, type WorkbenchRealtimeStatus } from '@muyun/platform-workbench';
import {
  UiThemeProvider,
  UiLanguageProvider,
  confirmAction,
  defaultUiThemeSkinId,
  uiThemeSkinById,
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
  createAuthClient,
  createAssistantTurnRequester,
  readAssistantAvailability,
  createConstructionPlanClient,
  createAssistantConversationClient,
  createMenuClient,
  createLoginContextClient,
  createExternalLoginClient,
  invokeBusinessNotificationRecordAction,
  businessNotificationExpired,
  provideModuleContextConfig,
  StaleAssistantInvocationError,
  userPreferences,
  platformLanguageContext,
  loadLanguageCatalog,
  normalizeError,
  userFacingErrorMessage,
  type AppError,
  type RealtimeConnectionState,
} from '@muyun/web-core';
import { configureUserPreferenceBackend } from './web-core/userPreferences';
import type {
  LoginResult,
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
  useEffectiveAuthToken,
  isAuthenticationRequiredError,
  isPasswordChangeRequiredError,
  saveAuthSessionId,
  saveAuthToken,
  storedAuthSessionId,
} from './platform-admin-runtime/authSession';
import { configureAuthenticationRecovery } from './platform-admin-runtime/sessionRecovery';
import { platformMessage } from './app/platformMessage';
import { businessNotificationPageDescriptor } from './app/businessNotificationNavigation';
import { useWorkbenchMenuRefresh } from './app/useWorkbenchMenuRefresh';
import { createWorkbenchMenuRefresh } from './app/workbenchMenuRefresh';
import { provideCurrentUserContext } from './platform-admin-runtime/currentUserContext';
import { loadAppWorkbenchStartupState, usesMockStartup } from './app/appWorkbenchStartup';
import { createBackendHttpClient } from './platform-admin-runtime/backendHttp';
import {
  platformAdminModuleRoutes,
  platformAdminRouteLayouts,
  platformAdminRoutePrefixes,
} from './platform-admin-runtime/platformAdminRoutes';
import { connectAppRealtime, disconnectAppRealtime } from './platform-admin-runtime/realtime';
import './platform-admin-runtime/workspaceViews';
import { useAssistantAvailability } from './app/useAssistantAvailability';
import LoginView from './app/LoginView.vue';
import { useExternalLoginHost } from './app/useExternalLoginHost';
import ExternalProvisioningConfirmation from './app/ExternalProvisioningConfirmation.vue';
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
  withWorkbenchTabTitle,
} from './app/workbenchStartup';
import { getMenuNavigationTarget, withPageInstanceKey } from './platform-workbench/menuNavigation';
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
import { clearWorkspaceViewUnsavedState } from './platform-workbench/workspaceViewUnsavedState';
import {
  confirmDiscardWorkspaceViewState,
  hasWorkspaceViewDiscardProtection,
} from './platform-workbench/workspaceViewDiscardGuard';
import WorkbenchAccountCenter from './app/WorkbenchAccountCenter.vue';
import StaticRoutePageHost from './app/StaticRoutePageHost.vue';
import WorkspaceRouteView from './views/WorkspaceRouteView.vue';
import { ensureMenuRoutes, resetMenuRoutes, router } from './app/router';
import {
  sameWorkbenchRoute,
  workbenchRouteCommitFor,
  workbenchRouteWriteFor,
  type WorkbenchNavigationIntent,
} from './app/workbenchRouteSync';
import { themeSkinPreferenceKey } from './app/themeSkinPreference';

// Local host demonstration; excluded from production builds.
const DevelopmentTopbarActions = import.meta.env.DEV
  ? defineAsyncComponent(() => import('./app/DevelopmentTopbarActions.vue'))
  : undefined;

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

void loadLanguageCatalog(createBackendHttpClient({ withAuth: false }), platformLanguageContext).catch(
  () => undefined,
);

const startup = ref<WorkbenchStartupState>();
const assistantConversationClient = createAssistantConversationClient(createBackendHttpClient());
const constructionPlanClient = createConstructionPlanClient(createBackendHttpClient());
const assistantRequestTurn = createAssistantTurnRequester(createBackendHttpClient());
const pageRefreshRevisions = ref<Record<string, number>>({});
const pageCacheGenerations = ref<Record<string, number>>({});
const pendingTabPageStateDiscards = new Set<string>();
const pageCacheMax = computed(() => Math.max(startup.value?.tabs?.length ?? 0, 1));
const currentUser = computed(() => startup.value?.session.currentUser);
const configurationIdentity = () =>
  JSON.stringify([
    currentUser.value?.userId,
    currentUser.value?.tenantId,
    currentUser.value?.organizationId,
    currentUser.value?.system,
  ]);
function openConfigurationEditor(
  moduleAlias: string,
  governanceTab: 'rules' | 'metadata' | 'ui' | 'menu',
  moduleTitle?: string,
) {
  handleOpenRoute(
    pageDescriptorToUrl(
      createWorkspaceViewDescriptor(moduleGovernanceWorkspaceView, {
        moduleAlias,
        governanceTab,
        moduleTitle,
      }),
      platformAdminRouteResolveOptions,
    ),
  );
}
const businessRuleWorkspace = createBusinessRuleWorkspace(
  createBackendHttpClient(),
  configurationIdentity,
  () => currentUser.value?.system === true,
  (alias) => openConfigurationEditor(alias, 'rules'),
  () => {
    metadataWorkspace.clearFocus();
    pageCompositionWorkspace.clearFocus();
    moduleMenuWorkspace.clearFocus();
  },
  platformLanguageContext,
);
const metadataWorkspace = createMetadataWorkspace(
  createBackendHttpClient(),
  configurationIdentity,
  () => currentUser.value?.system === true,
  (alias, title) => openConfigurationEditor(alias, 'metadata', title),
  () => {
    businessRuleWorkspace.clearFocus();
    pageCompositionWorkspace.clearFocus();
    moduleMenuWorkspace.clearFocus();
  },
  (alias, title) => openConfigurationEditor(alias, 'ui', title),
  platformLanguageContext,
);
const pageCompositionWorkspace = createPageCompositionWorkspace(
  createBackendHttpClient(),
  configurationIdentity,
  () => currentUser.value?.system === true,
  (alias) => openConfigurationEditor(alias, 'ui'),
  () => {
    metadataWorkspace.clearFocus();
    businessRuleWorkspace.clearFocus();
    moduleMenuWorkspace.clearFocus();
  },
  platformLanguageContext,
);
const moduleMenuWorkspace = createModuleMenuWorkspace(
  createBackendHttpClient(),
  configurationIdentity,
  () => Boolean(currentUser.value),
  (alias) => openConfigurationEditor(alias, 'menu'),
  () => {
    metadataWorkspace.clearFocus();
    businessRuleWorkspace.clearFocus();
    pageCompositionWorkspace.clearFocus();
  },
  () => refreshWorkbenchMenus(),
  undefined,
  undefined,
  platformLanguageContext,
);
provideModuleMenuWorkspace(moduleMenuWorkspace);
providePageCompositionWorkspace(pageCompositionWorkspace);
provideBusinessRuleWorkspace(businessRuleWorkspace);
provideMetadataWorkspace(metadataWorkspace);
const configurationWorkspace = {
  editor: () =>
    metadataWorkspace.editor() ??
    businessRuleWorkspace.editor() ??
    pageCompositionWorkspace.editor() ??
    moduleMenuWorkspace.editor(),
  current() {
    const rules = businessRuleWorkspace.current();
    const metadata = metadataWorkspace.current();
    const pages = pageCompositionWorkspace.current();
    const menus = moduleMenuWorkspace.current();
    return {
      revision: JSON.stringify([rules.revision, metadata.revision, pages.revision, menus.revision]),
      facts: { ...rules.facts, ...metadata.facts, ...pages.facts, ...menus.facts },
    };
  },
  capabilities: (settle?: Parameters<typeof metadataWorkspace.capabilities>[0]) => [
    ...businessRuleWorkspace.capabilities(settle),
    ...metadataWorkspace.capabilities(settle),
    ...pageCompositionWorkspace.capabilities(),
    ...moduleMenuWorkspace.capabilities(settle),
  ],
};
watch(currentUser, () => configurationWorkspace.current(), { flush: 'sync' });
onUnmounted(() => {
  metadataWorkspace.dispose();
  pageCompositionWorkspace.dispose();
  businessRuleWorkspace.dispose();
  moduleMenuWorkspace.dispose();
});

// A script HMR update can replace this module's local connection handle before Vue unmounts it.
// Release the application-owned connection before the updated setup runs.
if (import.meta.hot) {
  import.meta.hot.dispose(() => void disconnectAppRealtime());
}

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
  return (
    sameWorkbenchRoute(expected, router.currentRoute.value.fullPath) &&
    sameWorkbenchRoute(expected, renderedPageRoute.value?.fullPath)
  );
});

/** Wait until router, active tab and the mounted page Surface describe one page. */
function waitForAssistantPageReady() {
  const expectedTabKey = activeTabKey.value;
  if (!expectedTabKey) return Promise.reject(new StaleAssistantInvocationError());
  const expectedTab = startup.value?.tabs?.find((tab) => tab.key === expectedTabKey);
  const expectedPageInstanceKey = expectedTab?.instanceKey ?? expectedTabKey;
  const targetReady = () =>
    activeTabKey.value === expectedTabKey &&
    renderedTabKey.value === expectedTabKey &&
    renderedTabMatchesRoute.value;
  if (targetReady()) return nextTick(() => expectedPageInstanceKey);
  return new Promise<string>((resolve, reject) => {
    let settled = false;
    const finish = (error?: Error) => {
      if (settled) return;
      settled = true;
      stop();
      window.clearTimeout(timeout);
      if (error) reject(error);
      else void nextTick(() => resolve(expectedPageInstanceKey));
    };
    const stop = watch([activeTabKey, renderedTabKey, renderedTabMatchesRoute], () => {
      if (activeTabKey.value !== expectedTabKey) {
        finish(new StaleAssistantInvocationError());
      } else if (targetReady()) {
        finish();
      }
    });
    const timeout = window.setTimeout(
      () => finish(new Error('Assistant target page did not become ready in time')),
      5_000,
    );
  });
}
const loginRequired = ref(false);
const loginLoading = ref(false);
const logoutLoading = ref(false);
const accountCenter = ref<InstanceType<typeof WorkbenchAccountCenter>>();
const accountSessionToken = useEffectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN);
const themeSkinId = ref<UiThemeSkinId>(
  uiThemeSkinById(userPreferences.get(themeSkinPreferenceKey, defaultUiThemeSkinId)).id,
);
const activeThemeSkin = computed(() => uiThemeSkinById(themeSkinId.value));
const lockedTabs = ref<MenuTab[]>([]);
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
let lockedTabPreferenceRevision = 0;
let lockedTabPreferenceWrite = Promise.resolve();

configureModuleContext({ httpFactory: createBackendHttpClient });
provideModuleContextConfig({ httpFactory: createBackendHttpClient });
provideCurrentUserContext(currentUser);
providePlatformTimeZoneContext(currentTimeZone);
const menuRefresh = createWorkbenchMenuRefresh({
  ready: () => Boolean(startup.value) && !loginRequired.value && !logoutLoading.value,
  identity: () =>
    JSON.stringify([
      effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN),
      storedAuthSessionId(),
      configurationIdentity(),
    ]),
  menus: () => startup.value?.menus ?? [],
  read: async () => (await createMenuClient(createBackendHttpClient()).mine()).records,
  installRoutes: (records) => {
    resetMenuRoutes();
    return ensureMenuRoutes(records);
  },
  commit: (records) => {
    if (startup.value) startup.value = { ...startup.value, menus: records };
  },
});
watch(currentUser, menuRefresh.invalidate, { flush: 'sync' });
onUnmounted(menuRefresh.dispose);
if (import.meta.hot) import.meta.hot.dispose(menuRefresh.dispose);
useWorkbenchMenuRefresh({
  ready: () => Boolean(startup.value) && !usesMockStartup(),
  refresh: refreshWorkbenchMenus,
});
provideWorkbenchNavigation({
  refreshMenus: refreshWorkbenchMenus,
  openMenu: (menu) => {
    const target = getMenuNavigationTarget(menu);
    if (target) handleSelectMenu(menu, target);
  },
  openRoute: handleOpenRoute,
  replaceRoute: handleReplaceRoute,
  closeCurrentTab: handleCloseCurrentTab,
  openPage: handleOpenPage,
  replacePage: handleReplacePage,
  closePage: handleCloseTab,
  setTabName: handleSetTabName,
});

async function refreshWorkbenchMenus() {
  return menuRefresh.refresh();
}

const anonymousHttpClient = createBackendHttpClient({ withAuth: false });
const authClient = createAuthClient(anonymousHttpClient);
const accountContext = computed(() => ({
  http: anonymousHttpClient,
  preferencePersistence: usesMockStartup() ? ('local' as const) : ('backend' as const),
  token: accountSessionToken.value,
  currentUser: currentUser.value,
}));
const assistantAvailability = useAssistantAvailability({
  identity: () => {
    const user = currentUser.value;
    return user
      ? JSON.stringify([
          effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN),
          user.userId,
          user.tenantId,
          user.organizationId,
          user.system,
        ])
      : undefined;
  },
  load: (signal) => readAssistantAvailability(createBackendHttpClient(), signal),
});
const loginContextClient = createLoginContextClient(anonymousHttpClient);
const externalLoginClient = createExternalLoginClient(anonymousHttpClient);
const externalLoginHost = useExternalLoginHost({
  client: externalLoginClient,
  router,
  signedIn: () => Boolean(effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN)),
  onAuthenticated: handleAuthenticated,
});
async function confirmExternalProvisioning() {
  await externalLoginHost.confirmProvisioning();
  if (!externalLoginHost.confirmation.value && externalLoginHost.error.value) {
    error.value = externalLoginHost.error.value;
    loginRequired.value = true;
    loading.value = false;
  }
}
function cancelExternalProvisioning() {
  externalLoginHost.cancelProvisioning();
  loginRequired.value = true;
  loading.value = false;
}

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
  if (await externalLoginHost.completeReturn()) {
    if (externalLoginHost.confirmation.value) loading.value = false;
    if (externalLoginHost.error.value) {
      error.value = externalLoginHost.error.value;
      loginRequired.value = true;
      loading.value = false;
    }
    return;
  }
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
  menuRefresh.invalidate();
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
    error.value = userFacingErrorMessage(normalizeError(cause));
  } finally {
    loading.value = false;
  }
}

async function handleAuthenticated(result: LoginResult) {
  menuRefresh.invalidate();
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

function openChangeOwnPasswordDialog() {
  void accountCenter.value?.requestPasswordChange();
}
function beforeAccountCommand(command: { key: string }) {
  return (
    command.key !== 'logout' ||
    confirmDiscardWorkspaceViewState(
      (startup.value?.tabs ?? []).map((tab) => tab.key),
      'close',
      platformLanguageContext,
    )
  );
}
function handleAccountLogout(reason: 'requested' | 'passwordChanged') {
  if (reason === 'passwordChanged') {
    handleSecurityNotification({
      code: 'platform.security.password-changed',
      message: platformLanguageContext.t(
        'platform.screen.App.your-password-has-changed-please-sign-in-again',
        '你的密码已修改，请重新登录',
      ),
      logoutRequired: true,
    });
  } else void handleLogout();
}
async function handleLogout() {
  if (logoutLoading.value) {
    return;
  }
  menuRefresh.invalidate();
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
  const current = businessNotifications.value.filter(
    (item) => item.id !== notification.id && !businessNotificationExpired(item),
  );
  businessNotifications.value = [...current, notification];
}

function removeBusinessNotification(notificationId: string) {
  businessNotifications.value = businessNotifications.value.filter((item) => item.id !== notificationId);
}

async function executeBusinessNotificationAction(
  notification: WebBusinessNotification,
  action: WebBusinessNotificationAction,
) {
  const identity = configurationIdentity();
  try {
    if (businessNotificationExpired(notification)) return false;
    if (action.kind === 'navigate') {
      handleOpenPage(
        businessNotificationPageDescriptor(action, platformAdminRouteResolveOptions, startup.value?.menus),
      );
    } else {
      const completed = await executeBusinessNotificationRecordAction(action, {
        current: () =>
          identity === configurationIdentity()
            ? businessNotifications.value.find((item) => item.id === notification.id)
            : undefined,
        confirm: () =>
          confirmAction(
            {
              title: action.label,
              content: action.confirmation ?? '',
              danger: action.danger,
              layer: 'notification',
            },
            platformLanguageContext,
          ),
        invoke: () => invokeBusinessNotificationRecordAction(createBackendHttpClient(), action),
      });
      if (!completed || identity !== configurationIdentity()) return false;
      presentPlatformSuccess(
        platformLanguageContext.t('platform.screen.App.operation-completed', '操作完成'),
        { source: 'business-notification', phase: 'action' },
      );
    }
    return true;
  } catch (cause) {
    presentPlatformError(cause, { source: 'business-notification', phase: 'action' });
    return false;
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
  menuRefresh.invalidate();
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
  if (!hasWorkspaceViewDiscardProtection([currentTabKey]))
    return closeCurrentTabNow(fallbackPath, currentTabKey);

  // WorkbenchNavigation keeps a synchronous result contract for consumers,
  // while confirmation is asynchronous.  Start the guarded close here rather
  // than directly mutating the tab array so this public entry cannot bypass
  // workspace draft protection.
  void closeCurrentTabAfterConfirm(fallbackPath, currentTabKey);
  return { created: false };
}

async function closeCurrentTabAfterConfirm(fallbackPath: string, currentTabKey: string) {
  if (!(await confirmDiscardWorkspaceViewState([currentTabKey], 'close', platformLanguageContext))) return;
  return closeCurrentTabNow(fallbackPath, currentTabKey);
}

function closeCurrentTabNow(fallbackPath: string, currentTabKey: string) {
  const current = startup.value;
  if (!current || !(current.tabs ?? []).some((tab) => tab.key === currentTabKey)) return { created: false };
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
  let next = restoreWorkbenchStartupStateFromUrl(
    state,
    url,
    { ...platformAdminRouteResolveOptions, title: tabTitle },
    newInstance,
  );
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
        tab.key === next.activeTabKey ? withWorkbenchTabTitle(tab, tabTitle) : tab,
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
    tabs: (current.tabs ?? []).map((tab) =>
      tab.instanceKey === instanceKey ? withWorkbenchTabTitle(tab, title) : tab,
    ),
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
  if (!startup.value || !(await confirmDiscardWorkspaceViewState([key], 'close', platformLanguageContext)))
    return;
  const current = startup.value;
  if (!current) return;

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
  if (
    !startup.value ||
    keys.length === 0 ||
    !(await confirmDiscardWorkspaceViewState(keys, 'close', platformLanguageContext))
  )
    return;
  const current = startup.value;
  if (!current) return;
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
async function refreshPage(tabKey: string) {
  if (!(await confirmDiscardWorkspaceViewState([tabKey], 'refresh', platformLanguageContext))) return;
  if (!(startup.value?.tabs ?? []).some((tab) => tab.key === tabKey)) return;
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
  <UiLanguageProvider :context="platformLanguageContext" scope="global">
    <UiThemeProvider :theme="activeThemeSkin.theme" scope="global">
      <WorkbenchAccountCenter
        ref="accountCenter"
        v-slot="account"
        v-model:skin-id="themeSkinId"
        :context="accountContext"
        :before-command="beforeAccountCommand"
        @logout="handleAccountLogout"
      >
        <ExternalProvisioningConfirmation
          v-if="externalLoginHost.confirmation.value"
          :confirmation="externalLoginHost.confirmation.value"
          :busy="externalLoginHost.confirming.value"
          :error="externalLoginHost.error.value"
          @confirm="confirmExternalProvisioning"
          @cancel="cancelExternalProvisioning"
        />
        <LoginView
          v-else-if="loginRequired"
          :auth-client="authClient"
          :login-context-client="loginContextClient"
          :external-login-client="externalLoginClient"
          :loading="loginLoading"
          :error="error"
          @authenticated="handleAuthenticated"
        />
        <Workbench
          v-else
          v-model:active-tab-key="activeTabKey"
          :startup="startup"
          :current-user-employee-name="account.profile?.employee?.title"
          :managed-account-menu-items="account.items"
          :loading="loading"
          :error="error"
          :realtime-status="realtimeStatus"
          :theme-appearance="activeThemeSkin.theme.appearance"
          :locked-tab-keys="lockedTabKeys()"
          :assistant-available="assistantAvailability.available.value"
          :assistant-request-turn="assistantRequestTurn"
          :construction-plan-client="constructionPlanClient"
          :assistant-workspace-contribution="configurationWorkspace"
          :assistant-conversation-client="assistantConversationClient"
          :assistant-wait-for-page-ready="waitForAssistantPageReady"
          @account-menu-open="account.refresh()"
          @select-menu="handleSelectMenu"
          @change-tab="handleChangeTab"
          @close-tab="handleCloseTab"
          @close-tabs="handleCloseTabs"
          @toggle-tab-lock="handleToggleTabLock"
          @reorder-tabs="handleReorderTabs"
          @refresh-page="refreshPage"
          @retry-load="!loading && loadWorkbench()"
          @user-command="account.select($event)"
        >
          <template v-if="DevelopmentTopbarActions" #topbar-actions="{ currentUser: slotUser }">
            <DevelopmentTopbarActions :current-user="slotUser" />
          </template>
          <template #default>
            <KeepAlive :max="pageCacheMax">
              <component
                :is="CachePageHost"
                v-if="
                  renderedPageRoute &&
                  renderedPageComponent &&
                  renderedPageRoute.meta.cacheable !== false &&
                  renderedTabMatchesRoute
                "
                :key="pageRuntimeCacheKey(renderedTabKey)"
                :component="renderedPageComponent"
                :route="renderedPageRoute"
                :page-descriptor="pageDescriptorForTab(renderedTabKey)"
                :refresh-revision="pageRefreshRevisionFor(renderedTabKey)"
              />
            </KeepAlive>
            <StaticRoutePageHost
              v-if="
                renderedPageRoute &&
                renderedPageComponent &&
                renderedPageRoute.meta.cacheable === false &&
                renderedTabMatchesRoute
              "
              :key="pageRuntimeCacheKey(renderedTabKey)"
              :component="renderedPageComponent"
              :route="renderedPageRoute"
              :page-descriptor="pageDescriptorForTab(renderedTabKey)"
              :refresh-revision="pageRefreshRevisionFor(renderedTabKey)"
            />
          </template>
        </Workbench>
        <BusinessNotificationPanel
          :key="configurationIdentity()"
          :notifications="businessNotifications"
          :execute-action="executeBusinessNotificationAction"
          @dismiss="removeBusinessNotification"
        />
        <div v-if="securityNotification" class="security-notification-mask" role="presentation">
          <section class="security-notification-dialog" role="alertdialog" aria-modal="true">
            <h2>{{ platformLanguageContext.t('platform.screen.App.sign-in-required', '需要重新登录') }}</h2>
            <p>{{ platformLanguageContext.t(securityNotification.code, securityNotification.message) }}</p>
            <p class="security-notification-countdown">
              {{
                platformLanguageContext.t(
                  'platform.security.logout-countdown',
                  '{seconds} 秒后自动返回登录页',
                  { seconds: securityLogoutCountdown },
                )
              }}
            </p>
            <button type="button" @click="forceLocalLogout">
              {{ platformLanguageContext.t('platform.screen.App.sign-in-now', '立即重新登录') }}
            </button>
          </section>
        </div>
      </WorkbenchAccountCenter>
    </UiThemeProvider>
  </UiLanguageProvider>
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
