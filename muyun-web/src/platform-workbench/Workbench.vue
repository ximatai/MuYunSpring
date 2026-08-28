<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { UiButton, UiDropdown, UiEmpty, UiError, UiIcon, UiSpin, UiTabs } from '@muyun/vue-ui-antdv';
import type {
  MenuNavigationTarget,
  MenuRecord,
  MenuTab,
  PageDescriptor,
  WorkbenchStartupState,
} from '@muyun/web-contracts';
import type { UiDropdownItem, UiTabItem } from '@muyun/vue-ui-antdv';
import { userPreferences } from '@muyun/web-core';
import WorkbenchBrandControl from './WorkbenchBrandControl.vue';
import WorkbenchMenu from './WorkbenchMenu.vue';
import { resolvePageDescriptor } from './menuNavigation';
import type { WorkbenchRealtimeStatus } from './realtimeStatus';
import {
  compactMenuTopOf,
  effectiveWorkbenchMenuPresentation,
  type WorkbenchMenuPresentation,
} from './workbenchLayout';

defineOptions({ name: 'Workbench' });

const props = withDefaults(
  defineProps<{
    startup?: WorkbenchStartupState;
    loading?: boolean;
    error?: string;
    activeTabKey?: string;
    lockedTabKeys?: string[];
    realtimeStatus?: WorkbenchRealtimeStatus;
    themeAppearance?: 'light' | 'dark';
  }>(),
  {
    loading: false,
    error: undefined,
    startup: undefined,
    activeTabKey: undefined,
    lockedTabKeys: () => [],
    realtimeStatus: 'unavailable',
    themeAppearance: 'light',
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
const currentUser = computed(() => props.startup?.session.currentUser);
const userDisplayName = computed(() => currentUser.value?.username ?? currentUser.value?.userId ?? '未登录');
const userInitial = computed(() => userDisplayName.value.trim().slice(0, 1).toUpperCase() || 'M');
const tenantLabel = computed(() => currentUser.value?.tenantId ?? '系统工作区');
const tenantLogo = computed(() => {
  const branding = props.startup?.session.tenantBranding;
  return props.themeAppearance === 'dark' ? branding?.darkLogo || branding?.lightLogo : branding?.lightLogo;
});
const showTenantTitleArea = computed(() => props.startup?.session.tenantBranding?.mode !== 'logoOnly');
const tenantBrandTitle = computed(() => props.startup?.session.tenantBranding?.title?.trim() || 'MuYun');
const tenantBrandSubtitle = computed(
  () => props.startup?.session.tenantBranding?.subtitle?.trim() || tenantLabel.value,
);
const activePageTypeLabel = computed(() => pageTypeLabelOf(activePageDescriptor.value?.pageType));
const activeTargetLabel = computed(() => targetLabelOf(activePageDescriptor.value));
const userMenuItems: UiDropdownItem[] = [
  { key: 'changePassword', title: '修改密码' },
  { key: 'profile', title: '个人信息' },
  { key: 'logout', title: '退出登录', danger: true },
];
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
  return (
    tab?.pageDescriptor ?? (tab?.target ? resolvePageDescriptor(tab.target, { title: tab.title }) : undefined)
  );
}

function toTabItem(tab: MenuTab): UiTabItem {
  return {
    key: tab.key,
    title: tab.title,
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

function pageTypeLabelOf(pageType: string | undefined) {
  if (pageType === 'dynamic-module') {
    return '标准模块';
  }
  if (pageType === 'business-route') {
    return '业务页面';
  }
  if (pageType === 'platform-route') {
    return '平台页面';
  }
  if (pageType === 'remote-url') {
    return '在线页面';
  }
  if (pageType === 'external-link') {
    return '外部链接';
  }
  return '工作区';
}

function targetLabelOf(descriptor: PageDescriptor | undefined) {
  if (!descriptor) {
    return '未选择入口';
  }
  if (descriptor.pageType === 'dynamic-module') {
    return descriptor.target.moduleAlias;
  }
  if (descriptor.pageType === 'platform-route' || descriptor.pageType === 'business-route') {
    return descriptor.target.route ?? descriptor.target.routeName ?? descriptor.target.pageKey ?? 'workspace';
  }
  return descriptor.target.url;
}
</script>

<template>
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
              v-if="effectiveMenuPresentation === 'compact'"
              class="header-title-divider"
              aria-hidden="true"
            />
          </Transition>
          <div class="topbar-title">
            <div class="topbar-title-heading">
              <h1>{{ activeTab?.title ?? '控制台' }}</h1>
              <UiButton
                class="title-refresh-action"
                aria-label="刷新当前页"
                icon-name="reload"
                type="text"
                title="刷新当前页"
                :disabled="!activeTab"
                @click="refreshActivePage"
              />
            </div>
            <span>{{ activePageTypeLabel }} / {{ activeTargetLabel }}</span>
          </div>
        </div>

        <div class="topbar-actions" aria-label="全局工具">
          <button
            class="icon-button skin-button"
            type="button"
            aria-label="皮肤切换"
            title="切换皮肤"
            @click="emit('userCommand', 'themeSkin')"
          >
            <UiIcon name="skin" />
          </button>
          <UiDropdown v-slot="{ toggle }" :items="userMenuItems" @select="handleUserCommand">
            <button class="user-button" type="button" @click.stop="toggle">
              <span class="avatar">{{ userInitial }}</span>
              <span class="user-meta">
                <strong>{{ userDisplayName }}</strong>
                <small>{{ currentUser?.system ? '系统管理员' : '业务用户' }}</small>
              </span>
              <UiIcon class="user-caret" name="down" />
            </button>
          </UiDropdown>
        </div>
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
          <div v-else class="empty-tabs">暂无打开页面</div>
        </div>

        <section class="app-content">
          <UiSpin v-if="loading" />
          <UiError v-else-if="error" :message="error" />
          <div v-else-if="activeTab" class="tab-panel-host">
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
          </div>
          <UiEmpty v-else description="暂无页面" />
        </section>
      </section>
    </section>
  </main>
</template>

<style scoped>
.workbench {
  position: relative;
  display: grid;
  grid-template-columns: 0 minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  min-height: 0;
  height: 100vh;
  height: 100dvh;
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

.app-topbar h1,
.workbench-eyebrow {
  margin: 0;
}

.topbar-title {
  display: grid;
  min-width: 0;
}

.topbar-title-heading {
  display: inline-flex;
  max-width: 100%;
  min-width: 0;
  align-items: center;
}

.title-refresh-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 0;
  min-width: 0;
  height: 18px;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  margin-inline-start: 0;
  color: var(--muyun-support-text-muted);
  opacity: 0;
  overflow: hidden;
  pointer-events: none;
  transition:
    width 120ms ease,
    margin-inline-start 120ms ease,
    opacity 120ms ease;
}

.topbar-title-heading:hover .title-refresh-action,
.title-refresh-action:focus-visible {
  width: 12px;
  margin-inline-start: 6px;
  opacity: 1;
  pointer-events: auto;
}

:deep(.title-refresh-action.ant-btn-text:not(:disabled):hover),
.title-refresh-action:focus-visible {
  border-color: transparent;
  background: transparent;
  box-shadow: none;
}

:deep(.title-refresh-action .anticon) {
  display: block;
  font-size: 12px;
}

:deep(.title-refresh-action .ant-btn-icon) {
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
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
  height: 30px;
  margin: 0 14px;
  background: var(--muyun-support-border);
}

.app-topbar h1 {
  overflow: hidden;
  color: var(--muyun-support-text);
  font-size: 16px;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar-title span {
  overflow: hidden;
  max-width: 560px;
  margin-top: 3px;
  color: var(--muyun-support-text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  margin-left: auto;
}

.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 32px;
  height: 32px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 7px;
  background: var(--muyun-support-surface);
  color: var(--muyun-support-text-body);
  cursor: pointer;
  transition:
    border-color 160ms ease,
    color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;
}

.icon-button.wide {
  width: auto;
  min-width: 112px;
  padding: 0 10px;
  justify-content: flex-start;
  color: var(--muyun-support-text-muted);
  font-size: 12px;
}

.icon-button:hover {
  border-color: var(--muyun-theme-border);
  color: var(--muyun-theme-base);
  box-shadow: 0 8px 18px rgb(15 23 42 / 8%);
  transform: translateY(-1px);
}

.skin-button {
  position: relative;
  width: 29px;
  height: 29px;
  isolation: isolate;
  overflow: hidden;
  border-color: transparent;
  color: var(--muyun-theme-base);
  background:
    linear-gradient(var(--muyun-support-surface), var(--muyun-support-surface)) padding-box,
    var(--muyun-skin-picker-border-gradient) border-box;
  background-size:
    100% 100%,
    220% 100%;
  background-position:
    0 0,
    0 0;
}

.skin-button :deep(.anticon) {
  position: relative;
  z-index: 1;
}

.skin-button::before {
  position: absolute;
  inset: -14px;
  z-index: -1;
  content: '';
  background: var(--muyun-skin-picker-glow);
  opacity: 0;
  transform: rotate(-20deg) scale(0.88);
  transition:
    opacity 180ms ease,
    transform 320ms ease;
}

.skin-button:hover,
.skin-button:focus-visible {
  border-color: transparent;
  background-position:
    0 0,
    100% 0;
  box-shadow: inset 0 0 0 1px var(--muyun-skin-picker-focus-ring);
  transform: none;
}

.skin-button:hover::before,
.skin-button:focus-visible::before {
  opacity: 1;
  transform: rotate(0deg) scale(1);
}

@media (prefers-reduced-motion: no-preference) {
  .skin-button:hover,
  .skin-button:focus-visible {
    animation: skin-spectrum-shift 2.8s linear infinite;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skin-button,
  .skin-button::before {
    transition: none;
  }
}

@keyframes skin-spectrum-shift {
  to {
    background-position:
      0 0,
      220% 0;
  }
}

.user-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 154px;
  height: 32px;
  padding: 3px 7px 3px 3px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 7px;
  background: var(--muyun-support-surface);
  color: var(--muyun-support-text);
  cursor: pointer;
}

.avatar {
  display: inline-grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 6px;
  background: var(--muyun-theme-base);
  color: var(--muyun-support-surface);
  font-size: 11px;
  font-weight: 800;
}

.user-meta {
  display: grid;
  min-width: 0;
  text-align: left;
}

.user-meta strong,
.user-meta small {
  overflow: hidden;
  max-width: 104px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-meta strong {
  color: var(--muyun-support-text);
  font-size: 12px;
}

.user-meta small {
  color: var(--muyun-support-text-muted);
  font-size: 11px;
}

.user-caret {
  margin-left: auto;
  color: var(--muyun-support-text-muted);
  font-size: 11px;
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
  font-size: 12px;
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
  font-size: 12px;
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

/* The menu compacts below 980px, but tablet and narrow desktop workspaces remain viewport-bound.
 * Only handset layouts fall back to document scrolling. */
@media (max-width: 720px) {
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

  .topbar-actions {
    flex: 0 0 auto;
    justify-content: flex-start;
    margin-left: 0;
  }
}

@media (max-width: 480px) {
  .app-topbar {
    padding-inline: 10px;
  }

  .header-title-divider {
    margin-inline: 8px;
  }

  .topbar-title span,
  .user-meta {
    display: none;
  }

  .user-button {
    min-width: 0;
    gap: 4px;
    padding-right: 5px;
  }

  .user-caret {
    margin-left: 0;
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
