<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { UiDropdown, UiError, UiIcon, UiSidePanelHost, UiSpin, UiTabs } from '@muyun/vue-ui-antdv';
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
import { compactMenuTopOf } from './workbenchLayout';

defineOptions({ name: 'Workbench' });

const props = withDefaults(
  defineProps<{
    startup?: WorkbenchStartupState;
    loading?: boolean;
    error?: string;
    activeTabKey?: string;
    realtimeStatus?: WorkbenchRealtimeStatus;
  }>(),
  {
    loading: false,
    error: undefined,
    startup: undefined,
    activeTabKey: undefined,
    realtimeStatus: 'unavailable',
  },
);

const emit = defineEmits<{
  selectMenu: [menu: MenuRecord, target: MenuNavigationTarget];
  invalidMenu: [menu: MenuRecord];
  changeTab: [key: string];
  closeTab: [key: string];
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
const activePageTypeLabel = computed(() => pageTypeLabelOf(activePageDescriptor.value?.pageType));
const activeTargetLabel = computed(() => targetLabelOf(activePageDescriptor.value));
const userMenuItems: UiDropdownItem[] = [
  { key: 'changePassword', title: '修改密码' },
  { key: 'profile', title: '个人信息' },
  { key: 'settings', title: '偏好设置' },
  { key: 'logout', title: '退出登录', danger: true },
];
const menuPresentation = ref<'compact' | 'expanded'>('compact');
const expandedMenuDepth = ref(
  normalizeExpandedMenuDepth(userPreferences.get('workbench.expanded-menu-depth', 1)),
);
const compactMenuOpen = ref(false);
const suppressCompactMenuPointerEnter = ref(false);
const workbenchRoot = ref<HTMLElement>();
const appTopbar = ref<HTMLElement>();
const compactMenuTop = ref(54);
let compactMenuCloseTimer: number | undefined;
let compactMenuPointerReleaseFrame: number | undefined;
let topbarResizeObserver: ResizeObserver | undefined;
const COMPACT_MENU_CLOSE_DELAY = 220;

function pageDescriptorOf(tab: MenuTab | undefined): PageDescriptor | undefined {
  if (!tab) {
    return undefined;
  }
  return (
    tab?.pageDescriptor ?? (tab?.target ? resolvePageDescriptor(tab.target, { title: tab.title }) : undefined)
  );
}

function shouldKeepTabMounted(tab: MenuTab) {
  return tab.key === activeTabKey.value || pageDescriptorOf(tab)?.tabPolicy.cacheable !== false;
}

function toTabItem(tab: MenuTab): UiTabItem {
  return {
    key: tab.key,
    title: tab.title,
    closable: tab.closable,
  };
}

function handleTabChange(key: string) {
  emit('update:activeTabKey', key);
  emit('changeTab', key);
}

function handleUserCommand(key: string) {
  emit('userCommand', key);
}

function handleSelectMenu(menu: MenuRecord, target: MenuNavigationTarget) {
  emit('selectMenu', menu, target);
  closeCompactMenu();
}

function openCompactMenu(source: 'pointer' | 'focus' | 'click' = 'pointer') {
  if (source === 'pointer' && suppressCompactMenuPointerEnter.value) {
    return;
  }
  clearCompactMenuCloseTimer();
  compactMenuOpen.value = true;
}

function scheduleCompactMenuClose() {
  clearCompactMenuCloseTimer();
  compactMenuCloseTimer = window.setTimeout(() => {
    compactMenuOpen.value = false;
    compactMenuCloseTimer = undefined;
  }, COMPACT_MENU_CLOSE_DELAY);
}

function closeCompactMenu() {
  clearCompactMenuCloseTimer();
  compactMenuOpen.value = false;
}

function clearCompactMenuCloseTimer() {
  if (compactMenuCloseTimer === undefined) {
    return;
  }
  window.clearTimeout(compactMenuCloseTimer);
  compactMenuCloseTimer = undefined;
}

function setMenuPresentation(presentation: 'compact' | 'expanded') {
  if (compactMenuPointerReleaseFrame !== undefined) {
    window.cancelAnimationFrame(compactMenuPointerReleaseFrame);
    compactMenuPointerReleaseFrame = undefined;
  }
  suppressCompactMenuPointerEnter.value = presentation === 'compact';
  menuPresentation.value = presentation;
  closeCompactMenu();
  if (presentation === 'compact') {
    compactMenuPointerReleaseFrame = window.requestAnimationFrame(() => {
      suppressCompactMenuPointerEnter.value = false;
      compactMenuPointerReleaseFrame = undefined;
    });
  }
}

function setExpandedMenuDepth(depth: 1 | 2 | 3) {
  expandedMenuDepth.value = depth;
  void userPreferences.set('workbench.expanded-menu-depth', depth);
}

function normalizeExpandedMenuDepth(value: unknown): 1 | 2 | 3 {
  return value === 2 || value === 3 ? value : 1;
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
  clearCompactMenuCloseTimer();
  if (compactMenuPointerReleaseFrame !== undefined) {
    window.cancelAnimationFrame(compactMenuPointerReleaseFrame);
  }
  topbarResizeObserver?.disconnect();
});

function pageTypeLabelOf(pageType: string | undefined) {
  if (pageType === 'dynamic-module') {
    return '动态模块';
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
      'workbench--menu-expanded': menuPresentation === 'expanded',
      'workbench--compact-menu-open': menuPresentation === 'compact' && compactMenuOpen,
    }"
  >
    <WorkbenchMenu
      :menus="startup?.menus ?? []"
      :selected-menu-id="activeTab?.target?.menuId"
      :tenant-label="tenantLabel"
      :realtime-status="realtimeStatus"
      :presentation="menuPresentation"
      :expanded-menu-depth="expandedMenuDepth"
      :compact-open="compactMenuOpen"
      :compact-top="compactMenuTop"
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
              v-if="menuPresentation === 'compact'"
              presentation="compact"
              :compact-open="compactMenuOpen"
              :tenant-label="tenantLabel"
              @open-compact-menu="openCompactMenu"
              @schedule-compact-menu-close="scheduleCompactMenuClose"
              @close-compact-menu="closeCompactMenu"
              @change-presentation="setMenuPresentation"
            />
          </Transition>
          <Transition name="workbench-divider">
            <span v-if="menuPresentation === 'compact'" class="header-title-divider" aria-hidden="true" />
          </Transition>
          <div class="topbar-title">
            <h1>{{ activeTab?.title ?? '控制台' }}</h1>
            <span>{{ activePageTypeLabel }} / {{ activeTargetLabel }}</span>
          </div>
        </div>

        <div class="topbar-actions" aria-label="全局工具">
          <button class="icon-button wide" type="button" aria-label="搜索">
            <UiIcon name="search" />
            <span>搜索</span>
          </button>
          <button class="icon-button" type="button" aria-label="刷新">
            <UiIcon name="reload" />
          </button>
          <button class="icon-button" type="button" aria-label="通知">
            <UiIcon name="notification" />
          </button>
          <button class="icon-button" type="button" aria-label="设置">
            <UiIcon name="settings" />
          </button>
        </div>

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
      </header>

      <div class="tab-strip">
        <UiTabs
          v-if="tabs.length > 0"
          :tabs="tabs"
          :active-key="activeTabKey"
          @update:active-key="handleTabChange"
          @close="emit('closeTab', $event)"
        />
        <div v-else class="empty-tabs">暂无打开页面</div>
      </div>

      <section class="app-content">
        <UiSpin v-if="loading" />
        <UiError v-else-if="error" :message="error" />
        <template v-else>
          <template v-for="tab in openedTabs" :key="tab.key">
            <UiSidePanelHost
              v-if="shouldKeepTabMounted(tab)"
              v-show="tab.key === activeTabKey"
              class="tab-panel-host"
            >
              <div
                class="tab-page"
                :class="{ 'tab-page--workspace': pageDescriptorOf(tab)?.layout === 'workspace' }"
              >
                <slot :active-tab="tab" :target="tab.target" :page-descriptor="pageDescriptorOf(tab)" />
              </div>
            </UiSidePanelHost>
          </template>
        </template>
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
  background: #f5f7fa;
  transition: grid-template-columns 220ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.workbench--menu-expanded {
  grid-template-columns: 252px minmax(0, 1fr);
}

.app-main {
  grid-column: 2;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
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
  border-bottom: 1px solid #dde5ef;
  background: #fff;
}

.app-topbar h1,
.workbench-eyebrow {
  margin: 0;
}

.topbar-title {
  display: grid;
  min-width: 0;
}

.topbar-identity {
  display: flex;
  align-items: center;
  min-width: 0;
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
  background: #d8e1ea;
}

.app-topbar h1 {
  color: #1f2933;
  font-size: 16px;
  line-height: 1.2;
}

.topbar-title span {
  overflow: hidden;
  max-width: 560px;
  margin-top: 3px;
  color: #64748b;
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
  border: 1px solid #d6e0ec;
  border-radius: 7px;
  background: #fff;
  color: #334155;
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
  color: #64748b;
  font-size: 12px;
}

.icon-button:hover {
  border-color: #9cc8c2;
  color: #0f766e;
  box-shadow: 0 8px 18px rgb(15 23 42 / 8%);
  transform: translateY(-1px);
}

.user-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 154px;
  height: 32px;
  padding: 3px 7px 3px 3px;
  border: 1px solid #d6e0ec;
  border-radius: 7px;
  background: #fff;
  color: #1f2933;
  cursor: pointer;
}

.avatar {
  display: inline-grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 6px;
  background: #172033;
  color: #fff;
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
  color: #172033;
  font-size: 12px;
}

.user-meta small {
  color: #64748b;
  font-size: 11px;
}

.user-caret {
  margin-left: auto;
  color: #64748b;
  font-size: 11px;
}

.tab-strip {
  min-width: 0;
  padding: 0 12px;
  border-bottom: 1px solid #dde5ef;
  background: #f8fafc;
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

.tab-strip :deep(.ant-tabs-tab) {
  margin: 6px 4px 6px 0 !important;
  padding: 5px 10px !important;
  border: 1px solid #d8e1ea !important;
  border-radius: 6px !important;
  background: #fff !important;
  color: #475569;
  font-size: 12px;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
}

.tab-strip :deep(.ant-tabs-tab-active) {
  border-color: #9cc8c2 !important;
  box-shadow: 0 8px 18px rgb(15 23 42 / 7%);
}

.tab-strip :deep(.ant-tabs-tab-active .ant-tabs-tab-btn) {
  color: #0f766e !important;
  font-weight: 700;
}

.tab-strip :deep(.ant-tabs-nav-add) {
  display: none;
}

.empty-tabs {
  padding: 9px 4px;
  color: #64748b;
  font-size: 12px;
}

.app-content {
  position: relative;
  min-width: 0;
  min-height: 0;
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
  padding: 14px;
  overflow: auto;
  overscroll-behavior: contain;
}

.tab-page--workspace {
  overflow-x: auto;
  overflow-y: hidden;
}

@media (max-width: 980px) {
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

  .tab-page {
    height: auto;
    overflow: visible;
    overscroll-behavior: auto;
  }

  .app-topbar {
    display: grid;
    grid-template-columns: 1fr;
  }

  .topbar-actions {
    justify-content: flex-start;
    margin-left: 0;
  }

  .user-button {
    width: 100%;
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
