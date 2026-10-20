<script setup lang="ts">
import WorkbenchGlobalTools from './WorkbenchGlobalTools.vue';
import WorkbenchPageTitle from './WorkbenchPageTitle.vue';
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { UiButton, UiEmpty, UiError, UiSpin, UiTabs, UiSidePanelHost } from '@muyun/vue-ui-antdv';
import type {
  MenuNavigationTarget,
  MenuRecord,
  MenuTab,
  PageDescriptor,
  WorkbenchStartupState,
} from '@muyun/web-contracts';
import type { UiTabItem } from '@muyun/vue-ui-antdv';
import {
  createAssistantSurfaceRegistry,
  provideAssistantSurfaceHost,
  type AssistantTurnRequester,
  type AssistantWorkspaceContribution,
  type ConstructionPlanClient,
  type AssistantConversationClient,
  userPreferences,
  useLanguageContext,
  presentationLabel,
} from '@muyun/web-core';
import { createConfigurationCollaboration } from './configurationCollaboration';
import { createConstructionPlanSession } from './constructionPlanSession';
import WorkbenchBrandControl from './WorkbenchBrandControl.vue';
import WorkbenchAssistantPanel from './WorkbenchAssistantPanel.vue';
import WorkbenchMenu from './WorkbenchMenu.vue';
import { getMenuNavigationTarget, resolvePageDescriptor } from './menuNavigation';
import {
  createWorkbenchAssistantCapabilities,
  workbenchAssistantMenuCatalog,
} from './workbenchAssistantCapabilities';
import type { WorkbenchRealtimeStatus } from './realtimeStatus';
import { createWorkbenchMenuNodes, findWorkbenchMenuNodeById } from './menuTreeModel';
import {
  compactMenuTopOf,
  effectiveWorkbenchMenuPresentation,
  type WorkbenchMenuPresentation,
} from './workbenchLayout';

defineOptions({ name: 'Workbench' });
const language = useLanguageContext();

const props = withDefaults(
  defineProps<{
    startup?: WorkbenchStartupState;
    /** Employee name from the host-owned current-user profile. */
    currentUserEmployeeName?: string;
    loading?: boolean;
    error?: string;
    activeTabKey?: string;
    lockedTabKeys?: string[];
    realtimeStatus?: WorkbenchRealtimeStatus;
    themeAppearance?: 'light' | 'dark';
    constructionPlanClient?: ConstructionPlanClient;
    assistantConversationClient?: AssistantConversationClient;
    /** Trusted backend availability for the current identity; unknown defaults to unavailable. */
    assistantAvailable?: boolean;
    assistantRequestTurn?: AssistantTurnRequester;
    assistantWorkspaceContribution?: AssistantWorkspaceContribution;
    assistantWaitForPageReady?: () => Promise<string>;
  }>(),
  {
    loading: false,
    error: undefined,
    startup: undefined,
    activeTabKey: undefined,
    lockedTabKeys: () => [],
    realtimeStatus: 'unavailable',
    themeAppearance: 'light',
    assistantAvailable: false,
    assistantRequestTurn: undefined,
    assistantWaitForPageReady: undefined,
  },
);

const emit = defineEmits<{
  selectMenu: [menu: MenuRecord, target: MenuNavigationTarget];
  invalidMenu: [menu: MenuRecord];
  changeTab: [key: string];
  closeTab: [key: string];
  closeTabs: [keys: string[]];
  toggleTabLock: [key: string];
  reorderTabs: [keys: string[]];
  refreshPage: [key: string];
  retryLoad: [];
  'update:activeTabKey': [key: string];
  userCommand: [key: string];
}>();

const openedTabs = computed(() => props.startup?.tabs ?? []);
const tabs = computed<UiTabItem[]>(() => openedTabs.value.map(toTabItem));
const activeTabKey = computed(
  () => props.activeTabKey ?? props.startup?.activeTabKey ?? tabs.value[0]?.key ?? '',
);
const activeTab = computed(() => openedTabs.value.find((tab) => tab.key === activeTabKey.value));
const activePageDescriptor = computed(() => pageDescriptorOf(activeTab.value));
const activePageInstanceKey = computed(() => activeTab.value?.instanceKey ?? activeTab.value?.key);
const assistantIdentity = () => {
  const user = props.startup?.session.currentUser;
  return JSON.stringify([user?.userId, user?.tenantId, user?.organizationId, user?.system]);
};
const constructionPlan = props.constructionPlanClient
  ? createConstructionPlanSession(
      props.constructionPlanClient,
      assistantIdentity,
      () => props.startup?.session.currentUser?.system === true,
      language,
    )
  : undefined;
watch(assistantIdentity, () => constructionPlan?.current(), { flush: 'sync' });
const configurationCollaboration = createConfigurationCollaboration(language);
const configurationEditor = computed(() => props.assistantWorkspaceContribution?.editor?.());
const assistantSurfaceRegistry = createAssistantSurfaceRegistry(
  assistantIdentity,
  () => {
    const plan = constructionPlan?.current();
    const planFacts = constructionPlan?.facts();
    const contribution = props.assistantWorkspaceContribution?.current();
    const menuCatalog = workbenchAssistantMenuCatalog(props.startup?.menus ?? [], language);
    return {
      revision: JSON.stringify([
        plan?.generation,
        contribution?.revision,
        configurationCollaboration.revision.value,
        menuCatalog,
      ]),
      facts: {
        ...(contribution?.facts ?? {}),
        menuCatalog,
        configurationTask: configurationCollaboration.task.value,
        configurationBoundary: configurationCollaboration.boundary(configurationEditor.value),
        configurationEditor: configurationEditor.value
          ? {
              kind: configurationEditor.value.kind,
              openingCapability: configurationEditor.value.openingCapability,
              moduleAlias: configurationEditor.value.moduleAlias,
              title: configurationEditor.value.title,
              hasUnsavedChanges: configurationEditor.value.hasUnsavedChanges,
              visible: configurationEditor.value.visible,
            }
          : undefined,
        ...(plan
          ? {
              constructionPlan: {
                generation: plan.generation,
                planId: plan.planId,
                revision: plan.saved?.revision ?? 0,
                goal: plan.candidate?.goal,
                title: plan.candidate?.title,
                constructionStatus: plan.saved?.constructionStatus ?? 'NOT_STARTED',
                configurationSource: 'CURRENT_GOVERNANCE',
                historicalDesign: plan.saved?.constructionStatus === 'DELIVERED',
                persistence: planFacts?.persistence,
                dirty: planFacts?.dirty,
                manualEditing: planFacts?.manualEditing,
                reviewRequired: planFacts?.reviewRequired,
                confirmationResultUnknown: planFacts?.confirmationResultUnknown,
                detailsCapability: 'construction.describe',
              },
            }
          : {}),
      },
    };
  },
  language,
);
const ASSISTANT_PAGE_READY_TIMEOUT_MS = 15_000;
const assistantOpen = ref(false);
const assistantUsable = computed(
  () =>
    props.assistantAvailable === true &&
    Boolean(props.startup?.session.currentUser) &&
    Boolean(props.assistantRequestTurn),
);
const allowedAssistantRequester = computed(() =>
  assistantUsable.value ? props.assistantRequestTurn : undefined,
);
watch(
  [assistantIdentity, assistantUsable],
  () => {
    assistantOpen.value = false;
  },
  { flush: 'sync' },
);
async function settleAssistantNavigation(signal?: AbortSignal, requireFormal = false) {
  const expectedPageInstanceKey = props.assistantWaitForPageReady
    ? await props.assistantWaitForPageReady()
    : await nextTick(() => activePageInstanceKey.value);
  if (!expectedPageInstanceKey || activePageInstanceKey.value !== expectedPageInstanceKey) {
    throw new Error('Assistant target page changed before it became ready');
  }
  if (!requireFormal && activePageDescriptor.value?.hostType !== 'module-page-host') {
    return assistantSurfaceRegistry.snapshot()?.token;
  }
  const pageInstanceKey = expectedPageInstanceKey;
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  const stop = watch(
    activePageInstanceKey,
    (current) => {
      if (current !== pageInstanceKey) abort();
    },
    { flush: 'sync' },
  );
  try {
    const destination = await assistantSurfaceRegistry.waitForActiveSurface({
      pageInstanceKey,
      requireFormal: true,
      signal: controller.signal,
      timeoutMs: ASSISTANT_PAGE_READY_TIMEOUT_MS,
    });
    if (activePageInstanceKey.value !== pageInstanceKey || controller.signal.aborted) {
      throw new Error('Assistant target page changed before it became ready');
    }
    return destination.token;
  } finally {
    stop();
    signal?.removeEventListener('abort', abort);
  }
}
function workbenchAssistantCapabilities() {
  const configuration = props.assistantWorkspaceContribution?.capabilities(settleAssistantNavigation) ?? [];
  const editor = configurationEditor.value;
  const shared = configurationCollaboration.filter(configuration, editor?.visible);
  const openingCode =
    configurationCollaboration.boundary(editor).state === 'OPEN_SELECTED_EDITOR_REQUIRED'
      ? editor?.openingCapability
      : undefined;
  const opening = shared.find(
    (capability) => capability.effect === 'page' && capability.descriptor.code === openingCode,
  );
  return [
    // The selected editor's required opening precedes generic navigation within the same core budget.
    ...(opening ? [{ ...opening, schemaDiscovery: 'eager' as const }] : []),
    ...createWorkbenchAssistantCapabilities(
      () => props.startup?.menus ?? [],
      (menu) => {
        const target = getMenuNavigationTarget(menu);
        if (!target) {
          emit('invalidMenu', menu);
          return false;
        }
        handleSelectMenu(menu, target);
        return true;
      },
      settleAssistantNavigation,
      language,
    ),
    ...(constructionPlan?.capabilities() ?? []),
    ...shared.filter((capability) => capability !== opening),
    ...(configuration.length ? configurationCollaboration.capabilities() : []),
  ];
}
provideAssistantSurfaceHost({
  registry: assistantSurfaceRegistry,
  activePageInstanceKey: () => activePageInstanceKey.value,
  capabilities: workbenchAssistantCapabilities,
});
watch(activePageInstanceKey, (key) => assistantSurfaceRegistry.activate(key), { immediate: true });
let unregisterWorkbenchAssistantSurface: (() => void) | undefined;
watch(
  [activePageInstanceKey, allowedAssistantRequester, () => props.startup?.menus],
  ([pageInstanceKey, requestTurn]) => {
    unregisterWorkbenchAssistantSurface?.();
    unregisterWorkbenchAssistantSurface = undefined;
    if (!pageInstanceKey || !requestTurn) return;
    unregisterWorkbenchAssistantSurface = assistantSurfaceRegistry.register({
      pageInstanceKey,
      fallback: true,
      executionScopePending: activePageDescriptor.value?.hostType === 'module-page-host',
      contextRevision: () => 'workbench',
      surface: {
        describe: () => ({
          surface: 'workbench',
          title: activeTab.value?.title,
          facts: { activeTabKey: activeTabKey.value },
        }),
        capabilities: workbenchAssistantCapabilities,
        requestTurn,
      },
    });
  },
  { immediate: true, deep: true },
);
onUnmounted(() => unregisterWorkbenchAssistantSurface?.());
const currentUser = computed(() => props.startup?.session.currentUser);
const tenantLabel = computed(
  () => currentUser.value?.tenantId ?? language.t('platform.screen.Workbench.system-workspace', '系统工作区'),
);
const tenantLogo = computed(() => {
  const branding = props.startup?.session.tenantBranding;
  return props.themeAppearance === 'dark' ? branding?.darkLogo || branding?.lightLogo : branding?.lightLogo;
});
const showTenantTitleArea = computed(() => props.startup?.session.tenantBranding?.mode !== 'logoOnly');
const tenantBrandTitle = computed(() => props.startup?.session.tenantBranding?.title?.trim() || 'MuYun');
const tenantBrandSubtitle = computed(
  () => props.startup?.session.tenantBranding?.subtitle?.trim() || tenantLabel.value,
);
const menuPresentation = ref(
  normalizeWorkbenchMenuPresentation(userPreferences.get('workbench.menu-presentation', 'compact')),
);
const narrowViewport = ref(false);
const effectiveMenuPresentation = computed(() =>
  effectiveWorkbenchMenuPresentation(menuPresentation.value, narrowViewport.value),
);
const expandedMenuDepth = ref(
  normalizeExpandedMenuDepth(userPreferences.get('workbench.expanded-menu-depth', 1)),
);
const compactMenuOpen = ref(false);
const compactMenuPinned = ref(false);
const compactMenuAnchor = ref<{ left: number; top: number; right: number; bottom: number }>();
const suppressCompactMenuPointerEnter = ref(false);
const workbenchRoot = ref<HTMLElement>();
const appTopbar = ref<HTMLElement>();
const compactMenuTop = ref(54);
let compactMenuCloseTimer: number | undefined;
let compactMenuPointerReleaseFrame: number | undefined;
let topbarResizeObserver: ResizeObserver | undefined;
let narrowViewportQuery: MediaQueryList | undefined;
const COMPACT_MENU_CLOSE_DELAY = 220;
const NARROW_VIEWPORT_QUERY = '(max-width: 980px)';

function pageDescriptorOf(tab: MenuTab | undefined): PageDescriptor | undefined {
  if (!tab) {
    return undefined;
  }
  const descriptor =
    tab?.pageDescriptor ??
    (tab?.target ? resolvePageDescriptor(tab.target, { title: tab.title }) : undefined);
  const menu = menuRecordOf(tab);
  return descriptor?.titleText && menu?.titleText ? { ...descriptor, titleText: menu.titleText } : descriptor;
}

function menuRecordOf(tab: MenuTab) {
  const id = tab.pageDescriptor?.menuId ?? tab.target?.menuId;
  const menu = id
    ? findWorkbenchMenuNodeById(createWorkbenchMenuNodes(props.startup?.menus ?? []), id)?.record
    : undefined;
  return menu && (!tab.target || JSON.stringify(getMenuNavigationTarget(menu)) === JSON.stringify(tab.target))
    ? menu
    : undefined;
}

function toTabItem(tab: MenuTab): UiTabItem {
  return {
    key: tab.key,
    title: presentationLabel(
      tab.title,
      menuRecordOf(tab)?.titleText ?? tab.pageDescriptor?.titleText,
      language,
    ),
    closable: tab.closable,
    pinned: props.lockedTabKeys.includes(tab.key),
  };
}

function handleTabChange(key: string) {
  emit('update:activeTabKey', key);
  emit('changeTab', key);
}

/**
 * Requests a refresh of the active page instance.
 *
 * The router/cache owner performs the actual remount. This keeps tab switches
 * from recreating the shared KeepAlive subtree and discarding other drafts.
 */
function refreshActivePage() {
  if (!activeTabKey.value) return;
  emit('refreshPage', activeTabKey.value);
}

function handleUserCommand(key: string) {
  emit('userCommand', key);
}

function handleSelectMenu(menu: MenuRecord, target: MenuNavigationTarget) {
  emit('selectMenu', menu, target);
  closeCompactMenu();
}

function openCompactMenu(
  source: 'pointer' | 'focus' | 'click' = 'pointer',
  anchor?: { left: number; top: number; right: number; bottom: number },
) {
  if (source === 'click') {
    if (compactMenuOpen.value && compactMenuPinned.value) {
      closeCompactMenu();
      return;
    }
    compactMenuPinned.value = true;
  }
  if (source === 'pointer' && suppressCompactMenuPointerEnter.value) {
    return;
  }
  if (anchor) {
    compactMenuAnchor.value = anchor;
  }
  clearCompactMenuCloseTimer();
  compactMenuOpen.value = true;
}

function scheduleCompactMenuClose() {
  if (compactMenuPinned.value) {
    return;
  }
  clearCompactMenuCloseTimer();
  compactMenuCloseTimer = window.setTimeout(() => {
    compactMenuOpen.value = false;
    compactMenuCloseTimer = undefined;
  }, COMPACT_MENU_CLOSE_DELAY);
}

function closeCompactMenu() {
  clearCompactMenuCloseTimer();
  compactMenuOpen.value = false;
  compactMenuPinned.value = false;
  compactMenuAnchor.value = undefined;
}

function clearCompactMenuCloseTimer() {
  if (compactMenuCloseTimer === undefined) {
    return;
  }
  window.clearTimeout(compactMenuCloseTimer);
  compactMenuCloseTimer = undefined;
}

function handleCompactMenuOutsideInteraction(event: Event) {
  if (!compactMenuOpen.value || !compactMenuPinned.value) {
    return;
  }
  const insideMenu = event
    .composedPath()
    .some(
      (target) =>
        target instanceof Element &&
        (target.matches('.workbench-brand-identity') || target.matches('.workbench-menu')),
    );
  if (!insideMenu) {
    closeCompactMenu();
  }
}

function setMenuPresentation(presentation: WorkbenchMenuPresentation) {
  if (compactMenuPointerReleaseFrame !== undefined) {
    window.cancelAnimationFrame(compactMenuPointerReleaseFrame);
    compactMenuPointerReleaseFrame = undefined;
  }
  suppressCompactMenuPointerEnter.value = presentation === 'compact';
  menuPresentation.value = presentation;
  void userPreferences.set('workbench.menu-presentation', presentation);
  closeCompactMenu();
  if (presentation === 'compact') {
    compactMenuPointerReleaseFrame = window.requestAnimationFrame(() => {
      suppressCompactMenuPointerEnter.value = false;
      compactMenuPointerReleaseFrame = undefined;
    });
  }
}

function handleNarrowViewportChange(event: MediaQueryListEvent) {
  narrowViewport.value = event.matches;
  closeCompactMenu();
}

function setExpandedMenuDepth(depth: 1 | 2 | 3) {
  expandedMenuDepth.value = depth;
  void userPreferences.set('workbench.expanded-menu-depth', depth);
}

function normalizeExpandedMenuDepth(value: unknown): 1 | 2 | 3 {
  return value === 2 || value === 3 ? value : 1;
}

function normalizeWorkbenchMenuPresentation(value: unknown): WorkbenchMenuPresentation {
  return value === 'expanded' ? value : 'compact';
}

function updateCompactMenuTop() {
  if (!appTopbar.value || !workbenchRoot.value) {
    return;
  }
  const topbarRect = appTopbar.value.getBoundingClientRect();
  const workbenchRect = workbenchRoot.value.getBoundingClientRect();
  compactMenuTop.value = compactMenuTopOf(topbarRect.bottom, workbenchRect.top);
}

onMounted(() => {
  document.addEventListener('pointerdown', handleCompactMenuOutsideInteraction);
  document.addEventListener('focusin', handleCompactMenuOutsideInteraction);
  if (typeof window.matchMedia === 'function') {
    narrowViewportQuery = window.matchMedia(NARROW_VIEWPORT_QUERY);
    narrowViewport.value = narrowViewportQuery.matches;
    narrowViewportQuery.addEventListener('change', handleNarrowViewportChange);
  }
  void nextTick(() => {
    updateCompactMenuTop();
    if (typeof ResizeObserver === 'undefined' || !appTopbar.value) {
      return;
    }
    topbarResizeObserver = new ResizeObserver(updateCompactMenuTop);
    topbarResizeObserver.observe(appTopbar.value);
  });
});

onUnmounted(() => {
  document.removeEventListener('pointerdown', handleCompactMenuOutsideInteraction);
  document.removeEventListener('focusin', handleCompactMenuOutsideInteraction);
  clearCompactMenuCloseTimer();
  if (compactMenuPointerReleaseFrame !== undefined) {
    window.cancelAnimationFrame(compactMenuPointerReleaseFrame);
  }
  topbarResizeObserver?.disconnect();
  narrowViewportQuery?.removeEventListener('change', handleNarrowViewportChange);
});
</script>

<template>
  <div class="workbench-layout" :class="{ 'workbench-layout--assistant': assistantOpen }">
    <main
      ref="workbenchRoot"
      class="workbench"
      :class="{
        'workbench--menu-expanded': effectiveMenuPresentation === 'expanded',
        'workbench--compact-menu-open': effectiveMenuPresentation === 'compact' && compactMenuOpen,
      }"
    >
      <WorkbenchMenu
        :menus="startup?.menus ?? []"
        :selected-menu-id="activeTab?.target?.menuId"
        :tenant-label="tenantLabel"
        :logo-src="tenantLogo"
        :show-title-area="showTenantTitleArea"
        :brand-title="tenantBrandTitle"
        :brand-subtitle="tenantBrandSubtitle"
        :realtime-status="realtimeStatus"
        :presentation="effectiveMenuPresentation"
        :expanded-menu-depth="expandedMenuDepth"
        :compact-open="compactMenuOpen"
        :compact-top="compactMenuTop"
        :compact-anchor="compactMenuAnchor"
        @select-menu="handleSelectMenu"
        @invalid-menu="emit('invalidMenu', $event)"
        @compact-menu-enter="openCompactMenu"
        @compact-menu-leave="scheduleCompactMenuClose"
        @compact-menu-close="closeCompactMenu"
        @change-presentation="setMenuPresentation"
        @change-expanded-menu-depth="setExpandedMenuDepth"
      />

      <section class="app-main">
        <header ref="appTopbar" class="app-topbar">
          <div class="topbar-identity">
            <Transition name="workbench-brand">
              <WorkbenchBrandControl
                v-if="effectiveMenuPresentation === 'compact'"
                presentation="compact"
                :compact-open="compactMenuOpen"
                :tenant-label="tenantLabel"
                :logo-src="tenantLogo"
                :show-title-area="showTenantTitleArea"
                :brand-title="tenantBrandTitle"
                :brand-subtitle="tenantBrandSubtitle"
                :presentation-toggle-visible="!narrowViewport"
                @open-compact-menu="openCompactMenu"
                @schedule-compact-menu-close="scheduleCompactMenuClose"
                @close-compact-menu="closeCompactMenu"
                @change-presentation="setMenuPresentation"
              />
            </Transition>
            <Transition name="workbench-divider">
              <span
                v-if="effectiveMenuPresentation === 'compact' && activeTab"
                class="header-title-divider"
                aria-hidden="true"
              />
            </Transition>
            <WorkbenchPageTitle
              v-if="activeTab"
              :title="toTabItem(activeTab).title"
              @refresh="refreshActivePage"
            />
          </div>

          <WorkbenchGlobalTools
            :current-user="currentUser"
            :employee-name="currentUserEmployeeName"
            :assistant-available="assistantUsable"
            :assistant-open="assistantOpen"
            @toggle-assistant="assistantUsable && (assistantOpen = !assistantOpen)"
            @user-command="handleUserCommand"
          >
            <template v-if="$slots['topbar-actions']" #topbar-actions="context">
              <slot name="topbar-actions" v-bind="context" />
            </template>
          </WorkbenchGlobalTools>
        </header>

        <section class="workbench-mega-surface">
          <div class="tab-strip">
            <UiTabs
              v-if="tabs.length > 0"
              :tabs="tabs"
              :active-key="activeTabKey"
              @toggle-pin="emit('toggleTabLock', $event)"
              @update:active-key="handleTabChange"
              @close="emit('closeTab', $event)"
              @close-tabs="emit('closeTabs', $event)"
              @reorder="emit('reorderTabs', $event)"
            />
            <div v-else class="empty-tabs">
              {{ language.t('platform.screen.Workbench.no-pages-open', '暂无打开页面') }}
            </div>
          </div>

          <section class="app-content">
            <UiSpin v-if="loading" />
            <div v-else-if="error" class="workbench-load-error">
              <UiError :message="error" />
              <UiButton icon="reload" @click="emit('retryLoad')">{{
                language.t('platform.screen.Workbench.retry-loading', '重试加载')
              }}</UiButton>
            </div>
            <UiSidePanelHost v-else-if="activeTab" class="tab-panel-host">
              <div
                class="tab-page"
                :class="{ 'tab-page--workspace': activePageDescriptor?.layout === 'workspace' }"
              >
                <slot
                  :active-tab="activeTab"
                  :target="activeTab.target"
                  :page-descriptor="activePageDescriptor"
                />
              </div>
            </UiSidePanelHost>
            <UiEmpty
              v-else
              :description="language.t('platform.screen.Workbench.no-page-available', '暂无页面')"
            />
          </section>
        </section>
      </section>
    </main>
    <WorkbenchAssistantPanel
      v-if="assistantUsable"
      :open="assistantOpen"
      :registry="assistantSurfaceRegistry"
      :construction-plan="constructionPlan"
      :conversation-client="assistantConversationClient"
      :configuration-collaboration="configurationCollaboration"
      :configuration-editor="configurationEditor"
      @close="assistantOpen = false"
    />
  </div>
</template>

<style scoped>
.workbench-load-error {
  display: grid;
  justify-items: start;
  gap: 12px;
}

.workbench-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

.workbench-layout--assistant {
  grid-template-columns: minmax(0, 1fr) clamp(380px, 38vw, 600px);
}

@media (max-width: 900px) {
  .workbench-layout--assistant {
    grid-template-columns: minmax(0, 1fr);
  }
  .workbench-layout--assistant > .workbench {
    display: none;
  }
}

.workbench {
  position: relative;
  display: grid;
  grid-template-columns: 0 minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  background: var(--muyun-support-canvas);
  transition: grid-template-columns 220ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.workbench--menu-expanded {
  grid-template-columns: 252px minmax(0, 1fr);
}

.app-main {
  grid-column: 2;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.app-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 54px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--muyun-support-border);
  background: var(--muyun-support-surface);
}

.topbar-identity {
  display: flex;
  align-items: center;
  min-width: 0;
}

.workbench--compact-menu-open .topbar-identity {
  position: relative;
  z-index: 31;
}

.workbench--compact-menu-open .app-topbar {
  border-bottom-color: transparent;
}

.workbench-brand-enter-active,
.workbench-brand-leave-active {
  transition:
    opacity 180ms ease,
    transform 220ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.workbench-brand-enter-from,
.workbench-brand-leave-to {
  opacity: 0;
  transform: translateX(-12px);
}

.workbench-divider-enter-active,
.workbench-divider-leave-active {
  transition:
    opacity 140ms ease,
    transform 180ms ease;
}

.workbench-divider-enter-from,
.workbench-divider-leave-to {
  opacity: 0;
  transform: scaleY(0.45);
}

.header-title-divider {
  width: 1px;
  flex: 0 0 1px;
  height: 20px;
  margin: 0 12px;
  background: var(--muyun-support-border);
}

.tab-strip {
  position: relative;
  z-index: 1;
  margin-bottom: -1px;
  min-width: 0;
  padding: 0 10px;
  background: transparent;
}

.workbench-mega-surface {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  padding: 6px 0 0;
  overflow: hidden;
}

.tab-strip :deep(.ant-tabs) {
  margin: 0;
}

.tab-strip :deep(.ant-tabs-nav) {
  margin: 0;
}

.tab-strip :deep(.ant-tabs-nav::before) {
  display: none;
}

.tab-strip :deep(.ant-tabs-ink-bar) {
  display: none;
}

.tab-strip :deep(.ant-tabs-tab) {
  margin: 0 4px 0 0 !important;
  padding: 5px 8px !important;
  border: 1px solid var(--muyun-support-border) !important;
  border-bottom-color: var(--muyun-support-border) !important;
  border-radius: 8px 8px 0 0 !important;
  background: var(--muyun-support-elevated) !important;
  color: var(--muyun-support-text-muted);
  font-size: var(--muyun-font-size-helper, 12px);
  transition:
    border-color 160ms ease,
    background-color 160ms ease,
    box-shadow 160ms ease;
}

.tab-strip :deep(.ant-tabs-tab-active) {
  position: relative;
  z-index: 2;
  border-color: var(--muyun-theme-border) !important;
  border-top-color: var(--muyun-brand-accent-base) !important;
  border-bottom-color: var(--muyun-support-surface) !important;
  background: var(--muyun-support-surface) !important;
  box-shadow: 0 -5px 14px rgb(15 23 42 / 5%);
}

.tab-strip :deep(.ant-tabs-tab-active .ant-tabs-tab-btn) {
  color: var(--muyun-theme-base) !important;
  font-weight: 700;
}

.tab-strip :deep(.ant-tabs-tab-with-remove .ant-tabs-tab-btn) {
  display: inline-flex;
  align-items: center;
  min-width: 0;
}

.tab-strip :deep(.ant-tabs-tab:not(.ant-tabs-tab-active):hover) {
  border-color: var(--muyun-theme-border) !important;
  background: var(--muyun-theme-soft) !important;
}

.tab-strip :deep(.ant-tabs-tab:not(.ant-tabs-tab-active):hover .ant-tabs-tab-btn) {
  color: var(--muyun-theme-base) !important;
}

.tab-strip :deep(.ant-tabs-nav-add) {
  display: none;
}

.empty-tabs {
  padding: 9px 4px;
  color: var(--muyun-support-text-muted);
  font-size: var(--muyun-font-size-helper, 12px);
}

.app-content {
  position: relative;
  z-index: 0;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--muyun-support-border);
  border-left: 0;
  border-radius: 0;
  background: var(--muyun-support-surface);
  overflow: hidden;
  overscroll-behavior: contain;
}

.tab-panel-host {
  height: 100%;
  min-height: 0;
}

.tab-page {
  box-sizing: border-box;
  height: 100%;
  min-height: 0;
  padding: 10px;
  overflow: auto;
  overscroll-behavior: contain;
}

.tab-page--workspace {
  overflow-x: auto;
  overflow-y: hidden;
}
/* Cached page hosts own their scroll container and side-panel target together. */
.tab-page:has(> .ui-side-panel-host) {
  padding: 0;
  overflow: hidden;
}

/* The menu compacts below 980px, but tablet and narrow desktop workspaces remain viewport-bound.
 * Only handset layouts fall back to document scrolling. */
@media (max-width: 720px) {
  .workbench-layout:not(.workbench-layout--assistant) {
    height: auto;
    min-height: 100dvh;
    overflow: visible;
  }

  .workbench {
    grid-template-columns: 1fr;
    grid-template-rows: auto auto;
    min-height: 100vh;
    min-height: 100dvh;
    height: auto;
    overflow: visible;
    transition: none;
  }

  .app-main {
    grid-column: auto;
    min-height: 100vh;
    min-height: 100dvh;
    height: auto;
    overflow: visible;
  }

  .app-content {
    overflow: visible;
    overscroll-behavior: auto;
  }

  .workbench-mega-surface {
    min-height: auto;
    overflow: visible;
  }

  .tab-page {
    height: auto;
    overflow: visible;
    overscroll-behavior: auto;
  }

  .app-topbar {
    gap: 8px;
    flex-wrap: nowrap;
  }

  .topbar-identity {
    flex: 1 1 auto;
  }
}

@media (max-width: 480px) {
  .app-topbar {
    padding-inline: 10px;
  }

  .header-title-divider {
    display: none;
  }
  .topbar-identity :deep(.workbench-brand-copy) {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .workbench,
  .workbench-brand-enter-active,
  .workbench-brand-leave-active,
  .workbench-divider-enter-active,
  .workbench-divider-leave-active {
    transition: none !important;
  }
}
</style>
