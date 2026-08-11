<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { UiEmpty, UiIcon, UiInput } from '@muyun/vue-ui-antdv';
import type { MenuNavigationTarget, MenuRecord, MenuTreeNode } from '@muyun/web-contracts';
import WorkbenchBrandControl from './WorkbenchBrandControl.vue';
import WorkbenchMenuTree from './WorkbenchMenuTree.vue';
import { isPointerHeadingToMenuPanel } from './menuPointerAim';
import {
  buildWorkbenchMegaMenuModel,
  createWorkbenchMenuNodes,
  filterWorkbenchMenuNodes,
  findWorkbenchMenuNodeById,
  findWorkbenchMenuPath,
  firstDeepRootIdOf,
  type WorkbenchMenuNode,
} from './menuTreeModel';
import { presentWorkbenchRealtimeStatus, type WorkbenchRealtimeStatus } from './realtimeStatus';

defineOptions({ name: 'WorkbenchMenu' });

const props = withDefaults(
  defineProps<{
    menus: MenuTreeNode[];
    selectedMenuId?: string;
    tenantLabel?: string;
    searchPlaceholder?: string;
    realtimeStatus?: WorkbenchRealtimeStatus;
    presentation?: 'compact' | 'expanded';
    expandedMenuDepth?: 1 | 2 | 3;
    compactOpen?: boolean;
    compactTop?: number;
  }>(),
  {
    selectedMenuId: undefined,
    tenantLabel: '系统工作区',
    searchPlaceholder: '搜索菜单、模块或路由',
    realtimeStatus: 'unavailable',
    presentation: 'expanded',
    expandedMenuDepth: 1,
    compactOpen: false,
    compactTop: 54,
  },
);

const emit = defineEmits<{
  selectMenu: [menu: MenuRecord, target: MenuNavigationTarget];
  invalidMenu: [menu: MenuRecord];
  compactMenuEnter: [];
  compactMenuLeave: [];
  compactMenuClose: [];
  changePresentation: [presentation: 'compact' | 'expanded'];
  changeExpandedMenuDepth: [depth: 1 | 2 | 3];
}>();

const MEGA_GROUP_COLUMN_MIN_WIDTH = 168;
const MEGA_GROUP_COLUMN_GAP = 18;
const MEGA_GROUP_HORIZONTAL_PADDING = 28;
const MEGA_DEEP_PANEL_WIDTH = 280;
const MEGA_PANEL_MAX_WIDTH = 1040;
const MEGA_PANEL_SIDE_MARGIN = 24;
const MEGA_PANEL_MAX_HEIGHT = 620;
const MEGA_POINTER_AIM_GRACE_PERIOD = 360;

const menuShell = ref<HTMLElement>();
const megaPanel = ref<HTMLElement>();
const sidebarSubmenuPanel = ref<HTMLElement>();
const menuFilter = ref('');
const activeRootMenuId = ref<string>();
const activeDeepRootId = ref<string>();
const activeSidebarSubmenuId = ref<string>();
const megaPanelTop = ref(8);
const megaPanelLeft = ref(0);
const activeRootLeft = ref(0);
const activeRootTop = ref(0);
const activeRootHeight = ref(34);
const megaPanelWidth = ref(0);
const megaPanelHeight = ref(0);
const megaPanelPreferredWidth = ref(820);
const megaGroupColumnCount = ref(3);
const sidebarSubmenuTop = ref(0);
const sidebarSubmenuLeft = ref(0);
const sidebarSubmenuWidth = ref(0);
const sidebarSubmenuHeight = ref(0);
const sidebarSubmenuAnchorLeft = ref(0);
const sidebarSubmenuAnchorTop = ref(0);
const sidebarSubmenuAnchorHeight = ref(29);

const menuNodes = computed(() => createWorkbenchMenuNodes(props.menus));
const filteredMenus = computed(() => filterWorkbenchMenuNodes(menuNodes.value, menuFilter.value));
const selectedMenuPath = computed(() =>
  props.selectedMenuId ? findWorkbenchMenuPath(menuNodes.value, props.selectedMenuId) : [],
);
const selectedRootMenuId = computed(() => selectedMenuPath.value[0]?.record.id);
const selectedMenuPathIds = computed(() => selectedMenuPath.value.map((node) => node.record.id));
const realtimeStatusPresentation = computed(() => presentWorkbenchRealtimeStatus(props.realtimeStatus));
const activeRootNode = computed(() =>
  activeRootMenuId.value ? findWorkbenchMenuNodeById(filteredMenus.value, activeRootMenuId.value) : undefined,
);
const megaMenuModel = computed(() =>
  activeRootNode.value
    ? buildWorkbenchMegaMenuModel(activeRootNode.value, activeDeepRootId.value, megaGroupColumnCount.value)
    : undefined,
);
const activeDeepRootNode = computed(() => megaMenuModel.value?.activeDeepRoot);
const activeSidebarSubmenuNode = computed(() =>
  activeSidebarSubmenuId.value
    ? findWorkbenchMenuNodeById(filteredMenus.value, activeSidebarSubmenuId.value)
    : undefined,
);
const megaColumnCount = computed(() => megaMenuModel.value?.columns.length ?? 1);
const megaOutlinePath = computed(() => {
  const activeLeft = activeRootLeft.value;
  const activeTop = activeRootTop.value;
  const activeBottom = activeTop + activeRootHeight.value;
  const panelLeft = megaPanelLeft.value;
  const panelTop = megaPanelTop.value;
  const panelRight = panelLeft + megaPanelWidth.value;
  const panelBottom = panelTop + megaPanelHeight.value;
  const activeRadius = 6;
  const panelRadius = 8;

  if (isCompact.value) {
    return [
      `M ${panelLeft} ${panelTop}`,
      `H ${panelRight - panelRadius}`,
      `Q ${panelRight} ${panelTop} ${panelRight} ${panelTop + panelRadius}`,
      `V ${panelBottom - panelRadius}`,
      `Q ${panelRight} ${panelBottom} ${panelRight - panelRadius} ${panelBottom}`,
      `H ${panelLeft}`,
    ].join(' ');
  }

  return [
    `M ${panelLeft} ${panelTop}`,
    `H ${panelRight - panelRadius}`,
    `Q ${panelRight} ${panelTop} ${panelRight} ${panelTop + panelRadius}`,
    `V ${panelBottom - panelRadius}`,
    `Q ${panelRight} ${panelBottom} ${panelRight - panelRadius} ${panelBottom}`,
    `H ${panelLeft}`,
    `V ${activeBottom}`,
    `H ${activeLeft + activeRadius}`,
    `Q ${activeLeft} ${activeBottom} ${activeLeft} ${activeBottom - activeRadius}`,
    `V ${activeTop + activeRadius}`,
    `Q ${activeLeft} ${activeTop} ${activeLeft + activeRadius} ${activeTop}`,
    `H ${panelLeft}`,
    `V ${panelTop}`,
  ].join(' ');
});
const sidebarSubmenuOutlinePath = computed(() => {
  const anchorLeft = sidebarSubmenuAnchorLeft.value;
  const anchorTop = sidebarSubmenuAnchorTop.value;
  const anchorBottom = anchorTop + sidebarSubmenuAnchorHeight.value;
  const panelLeft = sidebarSubmenuLeft.value;
  const panelTop = sidebarSubmenuTop.value;
  const panelRight = panelLeft + sidebarSubmenuWidth.value;
  const panelBottom = panelTop + sidebarSubmenuHeight.value;
  const anchorRadius = 5;
  const panelRadius = 8;

  return [
    `M ${panelLeft} ${panelTop}`,
    `H ${panelRight - panelRadius}`,
    `Q ${panelRight} ${panelTop} ${panelRight} ${panelTop + panelRadius}`,
    `V ${panelBottom - panelRadius}`,
    `Q ${panelRight} ${panelBottom} ${panelRight - panelRadius} ${panelBottom}`,
    `H ${panelLeft}`,
    `V ${anchorBottom}`,
    `H ${anchorLeft + anchorRadius}`,
    `Q ${anchorLeft} ${anchorBottom} ${anchorLeft} ${anchorBottom - anchorRadius}`,
    `V ${anchorTop + anchorRadius}`,
    `Q ${anchorLeft} ${anchorTop} ${anchorLeft + anchorRadius} ${anchorTop}`,
    `H ${panelLeft}`,
    `V ${panelTop}`,
  ].join(' ');
});
const menuVisible = computed(() => props.presentation === 'expanded' || props.compactOpen);
const isCompact = computed(() => props.presentation === 'compact');
let megaPointerAimTimer: number | undefined;
let megaPointerAimOrigin: { x: number; y: number } | undefined;
let megaPointerAimPanel: { left: number; top: number; bottom: number } | undefined;

watch(
  () => props.compactOpen,
  (open) => {
    if (!open) {
      closeMegaMenu();
      return;
    }
  },
);

watch(
  () => props.expandedMenuDepth,
  () => closeMegaMenu(),
);

onUnmounted(() => {
  clearMegaPointerAim();
});

function selectMenuNode(node: WorkbenchMenuNode) {
  closeMegaMenu();
  if (node.target) {
    emit('selectMenu', node.record, node.target);
  } else {
    emit('invalidMenu', node.record);
  }
}

function handleSidebarEntryClick(node: WorkbenchMenuNode, event: MouseEvent) {
  if (node.navigable) {
    selectMenuNode(node);
    return;
  }
  openSidebarSubmenu(node, event);
}

function handleDeepMenuSelect(menu: MenuRecord, target: MenuNavigationTarget) {
  closeMegaMenu();
  emit('selectMenu', menu, target);
}

function openRootMenu(node: WorkbenchMenuNode, event?: MouseEvent | FocusEvent) {
  if (!isCompact.value && props.expandedMenuDepth > 1) {
    closeSidebarSubmenu();
    return;
  }
  clearMegaPointerAim();
  activeSidebarSubmenuId.value = undefined;
  activeRootMenuId.value = node.record.id;
  activeDeepRootId.value = firstDeepRootIdOf(node);
  updateMegaPanelTop(event?.currentTarget);
  void nextTick(updateMegaPanelSize);
}

function closeMegaMenu() {
  clearMegaPointerAim();
  activeRootMenuId.value = undefined;
  activeDeepRootId.value = undefined;
  closeSidebarSubmenu();
}

function closeSidebarSubmenu() {
  activeSidebarSubmenuId.value = undefined;
}

function openSidebarSubmenu(node: WorkbenchMenuNode, event: MouseEvent | FocusEvent) {
  if (!node.hasChildren) {
    closeSidebarSubmenu();
    return;
  }
  clearMegaPointerAim();
  activeRootMenuId.value = undefined;
  activeDeepRootId.value = undefined;
  activeSidebarSubmenuId.value = node.record.id;
  updateSidebarSubmenuPosition(event.currentTarget);
  void nextTick(updateSidebarSubmenuSize);
}

function updateSidebarSubmenuPosition(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) {
    return;
  }
  const targetRect = target.getBoundingClientRect();
  const shellRect = menuShell.value?.getBoundingClientRect();
  const shellTop = shellRect?.top ?? 0;
  const shellLeft = shellRect?.left ?? 0;
  const panelHeight = Math.min(window.innerHeight - 16, MEGA_PANEL_MAX_HEIGHT);
  const top = Math.min(Math.max(targetRect.top, 8), Math.max(8, window.innerHeight - panelHeight - 8));
  sidebarSubmenuTop.value = Math.round(top - shellTop);
  sidebarSubmenuLeft.value = Math.round(targetRect.right - shellLeft);
  sidebarSubmenuAnchorLeft.value = Math.round(targetRect.left - shellLeft);
  sidebarSubmenuAnchorTop.value = Math.round(targetRect.top - shellTop);
  sidebarSubmenuAnchorHeight.value = Math.round(targetRect.height);
}

function updateSidebarSubmenuSize() {
  if (!sidebarSubmenuPanel.value) {
    return;
  }
  const rect = sidebarSubmenuPanel.value.getBoundingClientRect();
  const shellTop = menuShell.value?.getBoundingClientRect().top ?? 0;
  const viewportTop = 8 - shellTop;
  const viewportBottom = window.innerHeight - rect.height - 8 - shellTop;
  sidebarSubmenuWidth.value = Math.round(rect.width);
  sidebarSubmenuHeight.value = Math.round(rect.height);
  sidebarSubmenuTop.value = Math.round(
    Math.min(Math.max(sidebarSubmenuAnchorTop.value, viewportTop), Math.max(viewportTop, viewportBottom)),
  );
}

function handleMenuEnter() {
  clearMegaPointerAim();
  if (isCompact.value) {
    emit('compactMenuEnter');
  }
}

function handleMenuLeave(event: MouseEvent) {
  if (startMegaPointerAim(event)) {
    return;
  }
  finishMenuLeave();
}

function finishMenuLeave() {
  closeMegaMenu();
  if (isCompact.value) {
    emit('compactMenuLeave');
  }
}

function startMegaPointerAim(event: MouseEvent): boolean {
  const panelRect = megaPanel.value?.getBoundingClientRect();
  if (!activeRootNode.value || !panelRect || event.clientX > panelRect.left + 8) {
    return false;
  }

  megaPointerAimOrigin = { x: event.clientX, y: event.clientY };
  megaPointerAimPanel = { left: panelRect.left, top: panelRect.top, bottom: panelRect.bottom };
  window.addEventListener('pointermove', handleMegaPointerAimMove);
  megaPointerAimTimer = window.setTimeout(finishMenuLeave, MEGA_POINTER_AIM_GRACE_PERIOD);
  return true;
}

function handleMegaPointerAimMove(event: PointerEvent) {
  if (!megaPointerAimOrigin || !megaPointerAimPanel) {
    return;
  }
  if (
    isPointerHeadingToMenuPanel(
      { x: event.clientX, y: event.clientY },
      megaPointerAimOrigin,
      megaPointerAimPanel,
    )
  ) {
    return;
  }
  finishMenuLeave();
}

function clearMegaPointerAim() {
  if (megaPointerAimTimer !== undefined) {
    window.clearTimeout(megaPointerAimTimer);
    megaPointerAimTimer = undefined;
  }
  window.removeEventListener('pointermove', handleMegaPointerAimMove);
  megaPointerAimOrigin = undefined;
  megaPointerAimPanel = undefined;
}

function handleMenuFocusIn() {
  if (isCompact.value) {
    emit('compactMenuEnter');
  }
}

function handleMenuFocusOut(event: FocusEvent) {
  const menu = event.currentTarget;
  if (menu instanceof HTMLElement && menu.contains(event.relatedTarget as Node | null)) {
    return;
  }
  if (isCompact.value) {
    emit('compactMenuLeave');
  }
}

function changePresentation(presentation: 'compact' | 'expanded') {
  emit('changePresentation', presentation);
}

function changeExpandedMenuDepth(depth: 1 | 2 | 3) {
  emit('changeExpandedMenuDepth', depth);
}

function handleMenuKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    closeMegaMenu();
    if (isCompact.value) {
      emit('compactMenuClose');
    }
  }
}

function updateMegaPanelTop(target: EventTarget | null | undefined) {
  if (!(target instanceof HTMLElement)) {
    return;
  }
  const rect = target.getBoundingClientRect();
  const shellRect = menuShell.value?.getBoundingClientRect();
  const shellTop = shellRect?.top ?? 0;
  const panelHeight = Math.min(window.innerHeight - 16, MEGA_PANEL_MAX_HEIGHT);
  const idealTop = rect.top;
  const maxTop = Math.max(8, window.innerHeight - panelHeight - 8);
  const panelTop = Math.min(Math.max(idealTop, 8), maxTop);
  const shellLeft = shellRect?.left ?? 0;
  megaPanelTop.value = Math.round(panelTop - shellTop);
  megaPanelLeft.value = Math.round(rect.right - shellLeft);
  activeRootLeft.value = Math.round(rect.left - shellLeft);
  activeRootTop.value = Math.round(rect.top - shellTop);
  activeRootHeight.value = Math.round(rect.height);
  megaPanelHeight.value = panelHeight;
  updateMegaPanelLayout();
}

function updateMegaPanelSize() {
  const rect = megaPanel.value?.getBoundingClientRect();
  if (!rect) {
    return;
  }

  megaPanelWidth.value = Math.round(rect.width);
  megaPanelHeight.value = Math.round(rect.height);
}

function updateMegaPanelLayout() {
  const availableWidth = availableMegaPanelWidth();
  const deepPanelWidth = activeDeepRootId.value ? MEGA_DEEP_PANEL_WIDTH : 0;
  const maxGroupWidth = Math.max(0, Math.min(availableWidth, MEGA_PANEL_MAX_WIDTH) - deepPanelWidth);
  const groupCount = activeRootNode.value?.children.length ?? 0;
  const columnCount = Math.max(
    1,
    Math.min(
      4,
      groupCount || 1,
      Math.floor(
        (maxGroupWidth - MEGA_GROUP_HORIZONTAL_PADDING + MEGA_GROUP_COLUMN_GAP) /
          (MEGA_GROUP_COLUMN_MIN_WIDTH + MEGA_GROUP_COLUMN_GAP),
      ) || 1,
    ),
  );
  const groupWidth =
    columnCount * MEGA_GROUP_COLUMN_MIN_WIDTH +
    Math.max(0, columnCount - 1) * MEGA_GROUP_COLUMN_GAP +
    MEGA_GROUP_HORIZONTAL_PADDING;
  const preferredWidth = Math.min(availableWidth, MEGA_PANEL_MAX_WIDTH, groupWidth + deepPanelWidth);

  megaGroupColumnCount.value = columnCount;
  megaPanelPreferredWidth.value = Math.max(280, preferredWidth);
  megaPanelWidth.value = megaPanelPreferredWidth.value;
}

function availableMegaPanelWidth() {
  const shellLeft = menuShell.value?.getBoundingClientRect().left ?? 0;
  const panelViewportLeft = shellLeft + megaPanelLeft.value;
  return Math.max(280, window.innerWidth - panelViewportLeft - MEGA_PANEL_SIDE_MARGIN);
}

function keepDeepRoot(node: WorkbenchMenuNode) {
  activeDeepRootId.value = node.hasChildren ? node.record.id : undefined;
  void nextTick(() => {
    updateMegaPanelLayout();
    updateMegaPanelSize();
  });
}

function isSelectedRoot(node: WorkbenchMenuNode) {
  return selectedRootMenuId.value === node.record.id;
}

function isSelectedMenu(node: WorkbenchMenuNode) {
  return props.selectedMenuId === node.record.id;
}

function isSelectedMenuAncestor(node: WorkbenchMenuNode) {
  return !isSelectedMenu(node) && selectedMenuPathIds.value.includes(node.record.id);
}
</script>

<template>
  <div
    ref="menuShell"
    class="workbench-menu"
    :class="{
      'mega-open': activeRootNode,
      'workbench-menu--compact': isCompact,
      'workbench-menu--expanded': !isCompact,
      'workbench-menu--compact-open': isCompact && compactOpen,
    }"
    :style="isCompact ? { '--compact-menu-top': `${compactTop}px` } : undefined"
    @mouseenter="handleMenuEnter"
    @mouseleave="handleMenuLeave"
    @focusin="handleMenuFocusIn"
    @focusout="handleMenuFocusOut"
    @keydown="handleMenuKeydown"
  >
    <Transition name="workbench-menu-panel">
      <aside v-if="menuVisible" :id="isCompact ? 'workbench-compact-menu' : undefined" class="menu-sidebar">
        <Transition name="workbench-sidebar-brand">
          <WorkbenchBrandControl
            v-if="!isCompact"
            presentation="expanded"
            :tenant-label="tenantLabel"
            :expanded-menu-depth="expandedMenuDepth"
            @change-presentation="changePresentation"
            @change-expanded-menu-depth="changeExpandedMenuDepth"
          />
        </Transition>

        <div v-if="!isCompact" class="menu-search">
          <UiIcon name="search" />
          <UiInput
            v-model:value="menuFilter"
            type="search"
            allow-clear
            :placeholder="searchPlaceholder"
            aria-label="搜索菜单"
          />
        </div>

        <nav class="root-menu" aria-label="主导航">
          <div v-if="filteredMenus.length > 0" class="root-menu-list">
            <template v-for="node in filteredMenus" :key="node.record.id">
              <button
                class="root-menu-item"
                :class="{
                  active: activeRootNode?.record.id === node.record.id,
                  selected: isSelectedRoot(node),
                  navigable: node.navigable,
                }"
                type="button"
                :aria-expanded="activeRootNode?.record.id === node.record.id"
                :aria-controls="
                  activeRootNode?.record.id === node.record.id ? 'workbench-mega-panel' : undefined
                "
                @mouseenter="openRootMenu(node, $event)"
                @focus="openRootMenu(node, $event)"
                @click="node.navigable && selectMenuNode(node)"
              >
                <span>{{ node.record.title }}</span>
              </button>
              <div
                v-if="!isCompact && expandedMenuDepth >= 2"
                class="sidebar-menu-level sidebar-menu-level--2"
              >
                <template v-for="group in node.children" :key="group.record.id">
                  <component
                    :is="expandedMenuDepth === 3 && !group.navigable ? 'div' : 'button'"
                    class="sidebar-menu-entry"
                    :class="{
                      navigable: group.navigable,
                      selected: isSelectedMenu(group),
                      'selected-path': isSelectedMenuAncestor(group),
                      branch: group.hasChildren,
                      active: activeSidebarSubmenuNode?.record.id === group.record.id,
                      'sidebar-menu-entry--group': expandedMenuDepth === 3 && !group.navigable,
                    }"
                    :type="expandedMenuDepth === 3 && !group.navigable ? undefined : 'button'"
                    :disabled="expandedMenuDepth === 2 && !group.navigable && !group.hasChildren"
                    :aria-current="isSelectedMenu(group) ? 'page' : undefined"
                    @mouseenter="expandedMenuDepth === 2 && openSidebarSubmenu(group, $event)"
                    @focus="expandedMenuDepth === 2 && openSidebarSubmenu(group, $event)"
                    @click="
                      expandedMenuDepth === 2
                        ? handleSidebarEntryClick(group, $event)
                        : group.navigable && selectMenuNode(group)
                    "
                  >
                    <span>{{ group.record.title }}</span>
                  </component>
                  <div v-if="expandedMenuDepth >= 3" class="sidebar-menu-level sidebar-menu-level--3">
                    <button
                      v-for="entry in group.children"
                      :key="entry.record.id"
                      class="sidebar-menu-entry"
                      :class="{
                        navigable: entry.navigable,
                        selected: isSelectedMenu(entry),
                        'selected-path': isSelectedMenuAncestor(entry),
                        branch: entry.hasChildren,
                        active: activeSidebarSubmenuNode?.record.id === entry.record.id,
                      }"
                      type="button"
                      :disabled="!entry.navigable && !entry.hasChildren"
                      :aria-current="isSelectedMenu(entry) ? 'page' : undefined"
                      @mouseenter="expandedMenuDepth === 3 && openSidebarSubmenu(entry, $event)"
                      @focus="expandedMenuDepth === 3 && openSidebarSubmenu(entry, $event)"
                      @click="handleSidebarEntryClick(entry, $event)"
                    >
                      <span>{{ entry.record.title }}</span>
                    </button>
                  </div>
                </template>
              </div>
            </template>
          </div>
          <UiEmpty v-else description="暂无菜单" />
        </nav>

        <div v-if="isCompact" class="compact-menu-tools">
          <div class="menu-search">
            <UiIcon name="search" />
            <UiInput
              v-model:value="menuFilter"
              type="search"
              allow-clear
              :placeholder="isCompact ? '' : searchPlaceholder"
              aria-label="搜索菜单"
            />
          </div>
        </div>

        <div
          v-if="!isCompact && realtimeStatusPresentation"
          class="sidebar-footer"
          :class="`realtime-${realtimeStatusPresentation.tone}`"
          :title="realtimeStatusPresentation.title"
          role="status"
        >
          <div class="status-dot" />
          <span>{{ realtimeStatusPresentation.label }}</span>
        </div>
      </aside>
    </Transition>

    <svg v-if="activeRootNode" class="mega-outline" aria-hidden="true">
      <path :d="megaOutlinePath" />
    </svg>

    <Transition name="workbench-mega-panel">
      <section
        v-if="activeRootNode"
        id="workbench-mega-panel"
        ref="megaPanel"
        class="mega-panel"
        :style="{
          '--mega-panel-top': `${megaPanelTop}px`,
          '--mega-panel-left': `${megaPanelLeft}px`,
          '--mega-panel-width': `${megaPanelPreferredWidth}px`,
          '--mega-column-count': megaColumnCount,
        }"
        @mouseenter="handleMenuEnter"
      >
        <div class="mega-body" :class="{ 'has-deep': activeDeepRootNode }">
          <div class="mega-groups">
            <div
              v-for="(column, columnIndex) in megaMenuModel?.columns ?? []"
              :key="`mega-column-${columnIndex}`"
              class="mega-column"
            >
              <section v-for="group in column" :key="group.record.id" class="mega-group">
                <button
                  class="mega-group-title"
                  :class="{
                    navigable: group.navigable,
                    selected: isSelectedMenu(group),
                    'selected-path': isSelectedMenuAncestor(group),
                  }"
                  type="button"
                  :disabled="!group.navigable"
                  :aria-current="isSelectedMenu(group) ? 'page' : undefined"
                  @click="selectMenuNode(group)"
                >
                  <span>{{ group.record.title }}</span>
                </button>

                <div class="mega-entry-list">
                  <button
                    v-for="entry in group.children"
                    :key="entry.record.id"
                    class="mega-entry"
                    :class="{
                      navigable: entry.navigable,
                      active: activeDeepRootNode?.record.id === entry.record.id,
                      branch: entry.hasChildren,
                      selected: isSelectedMenu(entry),
                      'selected-path': isSelectedMenuAncestor(entry),
                    }"
                    type="button"
                    :disabled="!entry.navigable && !entry.hasChildren"
                    :aria-current="isSelectedMenu(entry) ? 'page' : undefined"
                    @mouseenter="keepDeepRoot(entry)"
                    @focus="keepDeepRoot(entry)"
                    @click="entry.navigable && selectMenuNode(entry)"
                  >
                    <span>{{ entry.record.title }}</span>
                  </button>
                </div>
              </section>
            </div>
          </div>

          <aside v-if="activeDeepRootNode" class="mega-deep-panel">
            <header>
              <span>深层导航</span>
              <strong>{{ activeDeepRootNode.record.title }}</strong>
            </header>
            <ul class="mega-deep-tree">
              <WorkbenchMenuTree
                :node="activeDeepRootNode"
                :selected-menu-id="selectedMenuId"
                :selected-path-ids="selectedMenuPathIds"
                @select-menu="handleDeepMenuSelect"
              />
            </ul>
          </aside>
        </div>
      </section>
    </Transition>

    <svg v-if="activeSidebarSubmenuNode" class="sidebar-submenu-outline" aria-hidden="true">
      <path :d="sidebarSubmenuOutlinePath" />
    </svg>

    <Transition name="workbench-mega-panel">
      <aside
        v-if="activeSidebarSubmenuNode"
        ref="sidebarSubmenuPanel"
        class="sidebar-submenu-panel"
        aria-label="下级菜单"
        :style="{
          '--sidebar-submenu-top': `${sidebarSubmenuTop}px`,
          '--sidebar-submenu-left': `${sidebarSubmenuLeft}px`,
        }"
        @mouseenter="handleMenuEnter"
      >
        <ul class="sidebar-submenu-tree">
          <WorkbenchMenuTree
            v-for="child in activeSidebarSubmenuNode.children"
            :key="child.record.id"
            :node="child"
            :selected-menu-id="selectedMenuId"
            :selected-path-ids="selectedMenuPathIds"
            @select-menu="handleDeepMenuSelect"
          />
        </ul>
      </aside>
    </Transition>
  </div>
</template>

<style scoped>
.workbench-menu {
  --workbench-menu-surface: #fff;
  --workbench-menu-border: #d8e1ea;
  --workbench-menu-border-width: 1px;
  position: relative;
  z-index: 20;
  min-width: 0;
}

.workbench-menu--compact {
  position: absolute;
  z-index: 30;
  top: var(--compact-menu-top, 54px);
  left: 8px;
  width: fit-content;
  max-width: calc(100vw - 16px);
}

.menu-sidebar {
  position: sticky;
  top: 0;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 10px;
  height: 100vh;
  min-width: 0;
  padding: 12px 10px;
  border-right: var(--workbench-menu-border-width) solid var(--workbench-menu-border);
  background: #fbfcfe;
}

.workbench-menu--compact .menu-sidebar {
  position: relative;
  z-index: 1;
  width: fit-content;
  max-width: 100%;
  max-height: min(620px, calc(100dvh - 78px));
  height: auto;
  min-height: 0;
  padding: 0;
  grid-template-rows: minmax(0, 1fr) auto;
  border: var(--workbench-menu-border-width) solid var(--workbench-menu-border);
  gap: 0;
  border-radius: 0 0 4px 4px;
  box-shadow: 0 14px 28px rgb(15 23 42 / 13%);
}

.workbench-menu-panel-enter-active,
.workbench-menu-panel-leave-active {
  transition:
    opacity 160ms ease,
    transform 200ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.workbench-menu-panel-enter-from,
.workbench-menu-panel-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.workbench-sidebar-brand-enter-active,
.workbench-sidebar-brand-leave-active {
  transition:
    opacity 150ms ease,
    transform 200ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.workbench-sidebar-brand-enter-from,
.workbench-sidebar-brand-leave-to {
  opacity: 0;
  transform: translateX(-12px);
}

.workbench-menu--expanded :deep(.workbench-brand-control) {
  gap: 6px;
  height: 40px;
  padding: 0 5px;
}

.compact-menu-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  min-width: 0;
  width: fit-content;
  max-width: 100%;
  padding: 3px 7px;
  border-top: 1px solid #e2e8f0;
}

.compact-menu-tools .menu-search {
  flex: 0 1 160px;
  width: 160px;
  min-width: 0;
  height: 32px;
  padding: 0 2px;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.compact-menu-tools .menu-search:focus-within {
  background: #f1f7f6;
}

.workbench-menu--compact .root-menu {
  width: 100%;
  padding: 0;
}

.workbench-menu--compact .root-menu-list {
  width: 100%;
}

.workbench-menu--compact .root-menu-item {
  width: 100%;
  max-width: 100%;
  border-radius: 0;
}

.menu-search {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 9px;
  border: var(--workbench-menu-border-width) solid var(--workbench-menu-border);
  border-radius: 7px;
  background: #fff;
  color: #64748b;
}

.menu-search :deep(.ant-input-affix-wrapper) {
  flex: 1 1 auto;
  width: 100%;
  min-width: 0;
  border: 0;
  border-radius: 0;
  box-shadow: none;
  background: transparent;
}

.menu-search :deep(.ant-input) {
  outline: 0;
  background: transparent;
  color: #172033;
  font-size: 12px;
}

.menu-search :deep(.ant-input-affix-wrapper-focused) {
  border-color: transparent;
  box-shadow: none;
}

.menu-search :deep(.ant-input::placeholder) {
  color: #94a3b8;
}

.root-menu {
  position: relative;
  z-index: 2;
  min-height: 0;
  overflow: auto;
  padding: 2px 0;
}

.root-menu-list {
  display: grid;
  gap: 2px;
}

.root-menu-item,
.mega-group-title,
.mega-entry {
  width: 100%;
  border: 0;
  background: transparent;
  font: inherit;
  text-align: left;
}

.root-menu-item {
  position: relative;
  display: flex;
  box-sizing: border-box;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 34px;
  padding: 7px 8px;
  border: var(--workbench-menu-border-width) solid transparent;
  border-radius: 6px;
  color: #334155;
  font-size: 13px;
  cursor: default;
}

.root-menu-item span,
.mega-entry span,
.mega-group-title span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.root-menu-item:hover,
.root-menu-item.active,
.root-menu-item.active.selected {
  background: #edf4f7;
  color: #0f766e;
}

.root-menu-item.selected {
  background: #e4f2ef;
  color: #0f766e;
  font-weight: 700;
}

.root-menu-item.active,
.root-menu-item.active.selected {
  z-index: 2;
  border-color: transparent;
  border-radius: 6px 0 0 6px;
  background: var(--workbench-menu-surface);
  font-weight: 700;
}

.root-menu-item.navigable {
  cursor: pointer;
}

.sidebar-menu-level {
  display: grid;
  gap: 1px;
  margin: 1px 0 4px;
}

.sidebar-menu-level--2,
.sidebar-menu-level--3 {
  padding-left: 12px;
}

.sidebar-menu-entry {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 29px;
  padding: 5px 8px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #64748b;
  font: inherit;
  font-size: 12px;
  text-align: left;
}

.sidebar-menu-entry span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-menu-entry.navigable {
  color: #334155;
  cursor: pointer;
}

.sidebar-menu-entry.navigable:hover {
  background: #edf4f7;
  color: #0f766e;
}

.sidebar-menu-entry.active {
  z-index: 2;
  border-radius: 5px 0 0 5px;
  background: var(--workbench-menu-surface);
  color: #0f766e;
  font-weight: 700;
}

.sidebar-menu-entry.branch {
  position: relative;
  padding-right: 24px;
  font-weight: 600;
}

.sidebar-menu-entry.branch::after {
  position: absolute;
  right: 9px;
  color: #94a3b8;
  content: '›';
  font-size: 18px;
  font-weight: 400;
  line-height: 1;
}

.sidebar-menu-entry--group {
  color: #475569;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.sidebar-menu-entry--group.branch {
  padding-right: 8px;
}

.sidebar-menu-entry--group.branch::after {
  display: none;
}

.sidebar-menu-entry.selected {
  background: #e4f2ef;
  color: #0f766e;
  font-weight: 700;
}

.sidebar-menu-entry.selected-path {
  color: #334155;
  font-weight: 600;
}

.sidebar-menu-entry:disabled {
  cursor: default;
}

.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 9px;
  border: var(--workbench-menu-border-width) solid var(--workbench-menu-border);
  border-radius: 7px;
  background: #fff;
  color: #475569;
  font-size: 11px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #10b981;
  box-shadow: 0 0 0 4px rgb(16 185 129 / 12%);
}

.sidebar-footer.realtime-connecting .status-dot {
  background: #f59e0b;
  box-shadow: 0 0 0 4px rgb(245 158 11 / 12%);
}

.sidebar-footer.realtime-disconnected .status-dot {
  background: #94a3b8;
  box-shadow: none;
}

.mega-panel {
  position: absolute;
  z-index: 1;
  top: var(--mega-panel-top);
  left: var(--mega-panel-left);
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  width: min(var(--mega-panel-width), calc(100vw - var(--mega-panel-left) - 24px));
  max-height: calc(100vh - 16px);
  border: 0;
  border-radius: 0 8px 8px 0;
  background: var(--workbench-menu-surface);
  box-shadow: 0 24px 60px rgb(15 23 42 / 14%);
  clip-path: inset(0 -80px -80px 0);
  overflow: hidden;
}

.sidebar-submenu-panel {
  position: absolute;
  z-index: 1;
  top: var(--sidebar-submenu-top);
  left: var(--sidebar-submenu-left);
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  width: min(360px, calc(100vw - var(--sidebar-submenu-left) - 24px));
  max-height: min(620px, calc(100vh - 16px));
  border: 0;
  border-radius: 0 8px 8px 0;
  background: var(--workbench-menu-surface);
  box-shadow: 0 24px 60px rgb(15 23 42 / 14%);
  overflow: hidden;
}

.sidebar-submenu-tree {
  min-height: 0;
  margin: 0;
  padding: 8px;
  overflow: auto;
}

.sidebar-submenu-outline {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 4;
  width: 100vw;
  height: 100vh;
  overflow: visible;
  pointer-events: none;
}

.sidebar-submenu-outline path {
  fill: none;
  stroke: var(--workbench-menu-border);
  stroke-linejoin: round;
  stroke-width: var(--workbench-menu-border-width);
  vector-effect: non-scaling-stroke;
}

.workbench-mega-panel-enter-active,
.workbench-mega-panel-leave-active {
  transition:
    opacity 140ms ease,
    transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1);
  transform-origin: left top;
}

.workbench-mega-panel-enter-from,
.workbench-mega-panel-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.985);
}

.workbench-menu--compact .mega-panel {
  box-shadow: none;
}

.workbench-menu--compact.mega-open .root-menu-item.active::after {
  position: absolute;
  z-index: 3;
  top: -1px;
  right: -2px;
  bottom: -1px;
  width: 3px;
  background: var(--workbench-menu-surface);
  content: '';
}

.mega-outline {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 4;
  width: 100vw;
  height: 100vh;
  overflow: visible;
  pointer-events: none;
}

.mega-outline path {
  fill: none;
  stroke: var(--workbench-menu-border);
  stroke-linejoin: round;
  stroke-width: var(--workbench-menu-border-width);
  vector-effect: non-scaling-stroke;
}

.mega-deep-panel header span {
  color: #64748b;
  font-size: 11px;
}

.mega-deep-panel header strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mega-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-height: 0;
}

.mega-body.has-deep {
  grid-template-columns: minmax(0, 1fr) 280px;
}

.mega-groups {
  display: grid;
  grid-template-columns: repeat(var(--mega-column-count), minmax(168px, 1fr));
  align-content: start;
  gap: 18px;
  min-width: 0;
  max-height: calc(100vh - 16px);
  padding: 14px;
  overflow: auto;
}

.mega-column {
  display: grid;
  align-content: start;
  gap: 16px;
  min-width: 0;
}

.mega-group {
  display: grid;
  align-content: start;
  gap: 6px;
  min-width: 0;
}

.mega-group-title {
  display: flex;
  align-items: center;
  min-height: 24px;
  padding: 2px 0;
  color: #475569;
  font-size: 12px;
  font-weight: 800;
  cursor: default;
}

.mega-group-title.navigable {
  color: #0f766e;
  cursor: pointer;
}

.mega-group-title.navigable:hover {
  text-decoration: underline;
}

.mega-group-title.selected,
.mega-group-title.selected-path {
  color: #0f766e;
}

.mega-entry-list {
  display: grid;
  gap: 2px;
}

.mega-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 30px;
  padding: 5px 7px;
  border-radius: 6px;
  color: #64748b;
  font-size: 12px;
  cursor: default;
}

.mega-entry.navigable {
  color: #1e293b;
  cursor: pointer;
}

.mega-entry.branch {
  font-weight: 600;
}

.mega-entry:hover,
.mega-entry.active {
  background: #eef7f4;
  color: #0f766e;
}

.mega-entry.selected {
  background: #e4f2ef;
  color: #0f766e;
  font-weight: 700;
}

.mega-entry.selected-path {
  color: #334155;
  font-weight: 600;
}

.mega-deep-panel {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  border-left: 1px solid #e2e8f0;
  background: #fbfcfe;
}

.mega-deep-panel header {
  display: grid;
  gap: 2px;
  min-height: 46px;
  padding: 8px 12px;
  border-bottom: 1px solid #e2e8f0;
}

.mega-deep-tree {
  min-height: 0;
  margin: 0;
  padding: 8px;
  overflow: auto;
}

@media (max-width: 980px) {
  .mega-outline,
  .sidebar-submenu-outline {
    display: none;
  }

  .menu-sidebar {
    position: relative;
    height: auto;
    min-height: 0;
    border-right: 0;
    border-bottom: 1px solid #d8e1ea;
  }

  .root-menu {
    max-height: 240px;
  }

  .mega-panel {
    position: relative;
    top: auto;
    left: auto;
    width: 100%;
    max-height: none;
    margin-top: 8px;
    border: var(--workbench-menu-border-width) solid var(--workbench-menu-border);
    border-radius: 8px;
    box-shadow: 0 16px 34px rgb(15 23 42 / 10%);
  }

  .sidebar-submenu-panel {
    position: relative;
    top: auto;
    left: auto;
    width: 100%;
    max-height: none;
    margin-top: 8px;
    border-radius: 8px;
  }

  .mega-body,
  .mega-body.has-deep {
    grid-template-columns: minmax(0, 1fr);
  }

  .mega-groups {
    grid-template-columns: minmax(0, 1fr);
    max-height: none;
  }

  .mega-column {
    gap: 14px;
  }

  .mega-deep-panel {
    border-top: 1px solid #e2e8f0;
    border-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .workbench-menu-panel-enter-active,
  .workbench-menu-panel-leave-active,
  .workbench-sidebar-brand-enter-active,
  .workbench-sidebar-brand-leave-active,
  .workbench-mega-panel-enter-active,
  .workbench-mega-panel-leave-active {
    transition: none !important;
  }
}
</style>
