<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { UiEmpty, UiIcon, UiInput } from '@muyun/vue-ui-antdv';
import type { MenuNavigationTarget, MenuRecord, MenuTreeNode } from '@muyun/web-contracts';
import WorkbenchBrandControl from './WorkbenchBrandControl.vue';
import WorkbenchMenuTree from './WorkbenchMenuTree.vue';
import WorkbenchSidebarMenuEntry from './WorkbenchSidebarMenuEntry.vue';
import { createMenuHoverClickIntent } from './menuHoverClickIntent';
import { isPointerHeadingToMenuPanel, type MenuPointerPosition } from './menuPointerAim';
import { floatingMenuPanelOutlinePath, floatingPanelTopOf } from './workbenchLayout';
import {
  buildWorkbenchMegaMenuModel,
  createWorkbenchMenuNodes,
  filterWorkbenchMenuNodes,
  findWorkbenchMenuNodeById,
  findWorkbenchMenuPath,
  type WorkbenchMenuNode,
} from './menuTreeModel';
import { presentWorkbenchRealtimeStatus, type WorkbenchRealtimeStatus } from './realtimeStatus';

defineOptions({ name: 'WorkbenchMenu' });

const props = withDefaults(
  defineProps<{
    menus: MenuTreeNode[];
    selectedMenuId?: string;
    tenantLabel?: string;
    logoSrc?: string;
    showTitleArea?: boolean;
    brandTitle?: string;
    brandSubtitle?: string;
    searchPlaceholder?: string;
    realtimeStatus?: WorkbenchRealtimeStatus;
    presentation?: 'compact' | 'expanded';
    expandedMenuDepth?: 1 | 2 | 3;
    compactOpen?: boolean;
    compactTop?: number;
    compactAnchor?: { left: number; top: number; right: number; bottom: number };
  }>(),
  {
    selectedMenuId: undefined,
    tenantLabel: '系统工作区',
    logoSrc: undefined,
    showTitleArea: true,
    brandTitle: 'MuYun',
    brandSubtitle: undefined,
    searchPlaceholder: '',
    realtimeStatus: 'unavailable',
    presentation: 'expanded',
    expandedMenuDepth: 1,
    compactOpen: false,
    compactTop: 54,
    compactAnchor: undefined,
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
const MEGA_PANEL_MAX_WIDTH = 1040;
const MEGA_PANEL_SIDE_MARGIN = 24;
const MEGA_PANEL_MAX_HEIGHT = 620;
const MEGA_POINTER_AIM_GRACE_PERIOD = 360;
const MENU_SWITCH_GRACE_PERIOD = 320;
const MENU_POINTER_TRAIL_WINDOW = 140;
const MENU_HOVER_CLICK_GRACE_PERIOD = 240;

const menuShell = ref<HTMLElement>();
const menuSidebar = ref<HTMLElement>();
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
const compactPanelBounds = ref<{ left: number; top: number; right: number; bottom: number }>();
const sidebarSubmenuTop = ref(0);
const sidebarSubmenuLeft = ref(0);
const sidebarSubmenuWidth = ref(0);
const sidebarSubmenuHeight = ref(0);
const sidebarSubmenuAnchorLeft = ref(0);
const sidebarSubmenuAnchorTop = ref(0);
const sidebarSubmenuAnchorHeight = ref(29);
const hoverClickIntent = createMenuHoverClickIntent(MENU_HOVER_CLICK_GRACE_PERIOD);

const menuNodes = computed(() => createWorkbenchMenuNodes(props.menus));
const filteredMenus = computed(() => filterWorkbenchMenuNodes(menuNodes.value, menuFilter.value));
const selectedMenuPath = computed(() =>
  props.selectedMenuId ? findWorkbenchMenuPath(menuNodes.value, props.selectedMenuId) : [],
);
const selectedRootMenuId = computed(() => selectedMenuPath.value[0]?.record.id);
const selectedMenuPathIds = computed(() => selectedMenuPath.value.map((node) => node.record.id));
const realtimeStatusPresentation = computed(() => presentWorkbenchRealtimeStatus(props.realtimeStatus));
const activeRootNode = computed(() => {
  const node = activeRootMenuId.value
    ? findWorkbenchMenuNodeById(filteredMenus.value, activeRootMenuId.value)
    : undefined;
  return node?.hasChildren ? node : undefined;
});
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
const megaOutlinePath = computed(() =>
  floatingMenuPanelOutlinePath(
    {
      left: megaPanelLeft.value,
      top: megaPanelTop.value,
      width: megaPanelWidth.value,
      height: megaPanelHeight.value,
    },
    {
      left: activeRootLeft.value,
      top: activeRootTop.value,
      height: activeRootHeight.value,
    },
    6,
  ),
);
const sidebarSubmenuOutlinePath = computed(() =>
  floatingMenuPanelOutlinePath(
    {
      left: sidebarSubmenuLeft.value,
      top: sidebarSubmenuTop.value,
      width: sidebarSubmenuWidth.value,
      height: sidebarSubmenuHeight.value,
    },
    {
      left: sidebarSubmenuAnchorLeft.value,
      top: sidebarSubmenuAnchorTop.value,
      height: sidebarSubmenuAnchorHeight.value,
    },
  ),
);
const menuVisible = computed(() => props.presentation === 'expanded' || props.compactOpen);
const isCompact = computed(() => props.presentation === 'compact');
const rootChildrenUseFlyout = computed(() => isCompact.value || props.expandedMenuDepth === 1);
const compactOutlinePath = computed(() => {
  const anchor = props.compactAnchor;
  const panel = compactPanelBounds.value;
  const shellRect = menuShell.value?.getBoundingClientRect();
  if (!isCompact.value || !props.compactOpen || !anchor || !panel || !shellRect) {
    return undefined;
  }

  const anchorTop = Math.round(anchor.top - shellRect.top);
  const anchorLeft = Math.round(anchor.left - shellRect.left);
  const anchorRight = Math.round(anchor.right - shellRect.left);
  const panelRadius = 4;
  const sharedLeft = Math.min(anchorLeft, panel.left);

  return [
    `M ${sharedLeft} ${anchorTop}`,
    `H ${anchorRight - panelRadius}`,
    `Q ${anchorRight} ${anchorTop} ${anchorRight} ${anchorTop + panelRadius}`,
    `V ${panel.top}`,
    `H ${panel.right - panelRadius}`,
    `Q ${panel.right} ${panel.top} ${panel.right} ${panel.top + panelRadius}`,
    `V ${panel.bottom - panelRadius}`,
    `Q ${panel.right} ${panel.bottom} ${panel.right - panelRadius} ${panel.bottom}`,
    `H ${panel.left + panelRadius}`,
    `Q ${panel.left} ${panel.bottom} ${panel.left} ${panel.bottom - panelRadius}`,
    `V ${anchorTop}`,
  ].join(' ');
});
let megaPointerAimTimer: number | undefined;
let megaPointerAimOrigin: { x: number; y: number } | undefined;
let megaPointerAimPanel: { left: number; top: number; bottom: number } | undefined;
let pendingMenuSwitchTimer: number | undefined;
let recentMenuPointerPositions: Array<MenuPointerPosition & { at: number }> = [];

watch(
  () => props.compactOpen,
  (open) => {
    if (!open) {
      closeMenuLayers();
      return;
    }
  },
);

watch(
  () => props.expandedMenuDepth,
  () => closeMenuLayers(),
);

watch(
  () => props.presentation,
  () => closeMenuLayers(),
);

watch(
  () => [props.compactOpen, props.compactAnchor, props.presentation],
  () => {
    if (props.presentation === 'compact' && props.compactOpen) {
      void nextTick(updateCompactOutline);
      return;
    }
    compactPanelBounds.value = undefined;
  },
);

onUnmounted(() => {
  clearMegaPointerAim();
  clearPendingMenuSwitch();
});

function selectMenuNode(node: WorkbenchMenuNode) {
  closeMenuLayers();
  if (node.target) {
    emit('selectMenu', node.record, node.target);
  } else {
    emit('invalidMenu', node.record);
  }
}

function toggleSidebarSubmenu(node: WorkbenchMenuNode, target: EventTarget | null) {
  if (activeSidebarSubmenuId.value === node.record.id) {
    if (hoverClickIntent.consumeImmediateClick(node.record.id)) {
      return;
    }
    closeSidebarSubmenu();
    return;
  }
  hoverClickIntent.clear();
  activateSidebarSubmenu(node, target);
}

function handleDeepMenuSelect(menu: MenuRecord, target: MenuNavigationTarget) {
  closeMenuLayers();
  emit('selectMenu', menu, target);
}

function openRootMenu(node: WorkbenchMenuNode, event: MouseEvent) {
  if (!hoverInputAvailable()) {
    return;
  }
  const target = event.currentTarget;
  if (
    shouldDelayPanelSwitch(activeSidebarSubmenuId.value, node.record.id, event, sidebarSubmenuPanel.value)
  ) {
    scheduleMenuSwitch(() => activateRootMenuFromHover(node, target));
    return;
  }
  if (activeRootMenuId.value === node.record.id) {
    clearPendingMenuSwitch();
    return;
  }
  if (shouldDelayPanelSwitch(activeRootMenuId.value, node.record.id, event, megaPanel.value)) {
    scheduleMenuSwitch(() => activateRootMenuFromHover(node, target));
    return;
  }
  activateRootMenuFromHover(node, target);
}

function hoverInputAvailable() {
  return typeof window.matchMedia !== 'function' || window.matchMedia('(hover: hover)').matches;
}

function activateRootMenuFromHover(node: WorkbenchMenuNode, target?: EventTarget | null) {
  activateRootMenu(node, target);
  if (activeRootMenuId.value === node.record.id) {
    hoverClickIntent.markHoverActivation(node.record.id);
  }
}

function activateRootMenu(node: WorkbenchMenuNode, target?: EventTarget | null) {
  clearPendingMenuSwitch();
  if (!node.hasChildren) {
    closeMenuLayers();
    return;
  }
  if (!isCompact.value && props.expandedMenuDepth > 1) {
    closeSidebarSubmenu();
    return;
  }
  clearMegaPointerAim();
  activeSidebarSubmenuId.value = undefined;
  activeRootMenuId.value = node.record.id;
  activeDeepRootId.value = undefined;
  updateMegaPanelTop(target);
  void nextTick(updateMegaPanelSize);
}

function toggleRootMenu(node: WorkbenchMenuNode, event: MouseEvent) {
  if (activeRootMenuId.value === node.record.id) {
    if (hoverClickIntent.consumeImmediateClick(node.record.id)) {
      return;
    }
    closeMenuLayers();
    return;
  }
  hoverClickIntent.clear();
  const target =
    event.currentTarget instanceof HTMLElement
      ? event.currentTarget.closest<HTMLElement>('.root-menu-item')
      : null;
  activateRootMenu(node, target);
}

function shouldDelayPanelSwitch(
  activeId: string | undefined,
  nextId: string,
  event: MouseEvent,
  panel: HTMLElement | undefined,
): boolean {
  const panelRect = panel?.getBoundingClientRect();
  if (!activeId || activeId === nextId || !panelRect) {
    return false;
  }
  const pointer = { x: event.clientX, y: event.clientY };
  const minimumTime = event.timeStamp - MENU_POINTER_TRAIL_WINDOW;
  const latestPosition = recentMenuPointerPositions.at(-1);
  if (latestPosition && latestPosition.x > pointer.x) {
    return false;
  }
  const origin = findRecentPointerOrigin(pointer, minimumTime);
  return Boolean(
    origin &&
    isPointerHeadingToMenuPanel(pointer, origin, {
      left: panelRect.left,
      top: panelRect.top,
      bottom: panelRect.bottom,
    }),
  );
}

function findRecentPointerOrigin(pointer: MenuPointerPosition, minimumTime: number) {
  for (let index = recentMenuPointerPositions.length - 1; index >= 0; index -= 1) {
    const position = recentMenuPointerPositions[index];
    if (position.at < minimumTime) {
      break;
    }
    if (position.x <= pointer.x - 4) {
      return position;
    }
  }
  return undefined;
}

function scheduleMenuSwitch(action: () => void) {
  clearPendingMenuSwitch();
  pendingMenuSwitchTimer = window.setTimeout(() => {
    pendingMenuSwitchTimer = undefined;
    action();
  }, MENU_SWITCH_GRACE_PERIOD);
}

function clearPendingMenuSwitch() {
  if (pendingMenuSwitchTimer !== undefined) {
    window.clearTimeout(pendingMenuSwitchTimer);
    pendingMenuSwitchTimer = undefined;
  }
}

function trackMenuPointer(event: PointerEvent) {
  const minimumTime = event.timeStamp - MENU_POINTER_TRAIL_WINDOW;
  recentMenuPointerPositions = recentMenuPointerPositions.filter((position) => position.at >= minimumTime);
  const latest = recentMenuPointerPositions.at(-1);
  if (!latest || Math.hypot(event.clientX - latest.x, event.clientY - latest.y) >= 2) {
    recentMenuPointerPositions.push({ x: event.clientX, y: event.clientY, at: event.timeStamp });
  }
}

function closeMenuLayers() {
  clearMegaPointerAim();
  clearPendingMenuSwitch();
  recentMenuPointerPositions = [];
  hoverClickIntent.clear();
  activeRootMenuId.value = undefined;
  activeDeepRootId.value = undefined;
  closeSidebarSubmenu();
}

function closeSidebarSubmenu() {
  clearPendingMenuSwitch();
  activeSidebarSubmenuId.value = undefined;
}

function openSidebarSubmenu(
  node: WorkbenchMenuNode,
  event: MouseEvent,
  target: EventTarget | null = event.currentTarget,
) {
  if (!hoverInputAvailable()) {
    return;
  }
  if (activeSidebarSubmenuId.value === node.record.id) {
    clearPendingMenuSwitch();
    return;
  }
  if (
    shouldDelayPanelSwitch(activeSidebarSubmenuId.value, node.record.id, event, sidebarSubmenuPanel.value)
  ) {
    scheduleMenuSwitch(() => activateSidebarSubmenuFromHover(node, target));
    return;
  }
  activateSidebarSubmenuFromHover(node, target);
}

function activateSidebarSubmenuFromHover(node: WorkbenchMenuNode, target: EventTarget | null) {
  activateSidebarSubmenu(node, target);
  if (activeSidebarSubmenuId.value === node.record.id) {
    hoverClickIntent.markHoverActivation(node.record.id);
  }
}

function activateSidebarSubmenu(node: WorkbenchMenuNode, target: EventTarget | null) {
  clearPendingMenuSwitch();
  if (!node.hasChildren) {
    closeSidebarSubmenu();
    return;
  }
  clearMegaPointerAim();
  activeRootMenuId.value = undefined;
  activeDeepRootId.value = undefined;
  activeSidebarSubmenuId.value = node.record.id;
  updateSidebarSubmenuPosition(target);
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
  sidebarSubmenuTop.value = floatingPanelTopOf(targetRect.top, panelHeight, window.innerHeight, shellTop);
  sidebarSubmenuLeft.value = Math.round(targetRect.right - shellLeft);
  sidebarSubmenuAnchorLeft.value = Math.round(targetRect.left - shellLeft);
  sidebarSubmenuAnchorTop.value = Math.round(targetRect.top - shellTop);
  sidebarSubmenuAnchorHeight.value = Math.round(targetRect.height);
}

function updateSidebarSubmenuSize() {
  const panel = sidebarSubmenuPanel.value;
  if (!panel) {
    return;
  }
  const shellTop = menuShell.value?.getBoundingClientRect().top ?? 0;
  const panelHeight = panel.offsetHeight;
  sidebarSubmenuWidth.value = panel.offsetWidth;
  sidebarSubmenuHeight.value = panelHeight;
  sidebarSubmenuTop.value = floatingPanelTopOf(
    shellTop + sidebarSubmenuAnchorTop.value,
    panelHeight,
    window.innerHeight,
    shellTop,
  );
}

function updateCompactOutline() {
  const panel = menuSidebar.value;
  const shellRect = menuShell.value?.getBoundingClientRect();
  if (!panel || !shellRect || !props.compactAnchor) {
    compactPanelBounds.value = undefined;
    return;
  }
  const panelRect = panel.getBoundingClientRect();
  compactPanelBounds.value = {
    left: Math.round(panelRect.left - shellRect.left),
    top: Math.round(panelRect.top - shellRect.top),
    right: Math.round(panelRect.right - shellRect.left),
    bottom: Math.round(panelRect.bottom - shellRect.top),
  };
}

function handleMenuEnter() {
  clearMegaPointerAim();
  clearPendingMenuSwitch();
  if (isCompact.value) {
    emit('compactMenuEnter');
  }
}

function handleMenuLeave(event: MouseEvent) {
  if (isCompact.value) {
    emit('compactMenuLeave');
    return;
  }
  if (startMegaPointerAim(event)) {
    return;
  }
  finishMenuLeave();
}

function finishMenuLeave() {
  closeMenuLayers();
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
    if (activeDeepRootId.value) {
      closeDeepRoot();
      event.stopPropagation();
      return;
    }
    closeMenuLayers();
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
  const shellLeft = shellRect?.left ?? 0;
  megaPanelTop.value = floatingPanelTopOf(rect.top, panelHeight, window.innerHeight, shellTop);
  megaPanelLeft.value = Math.round(rect.right - shellLeft);
  activeRootLeft.value = Math.round(rect.left - shellLeft);
  activeRootTop.value = Math.round(rect.top - shellTop);
  activeRootHeight.value = Math.round(rect.height);
  megaPanelHeight.value = panelHeight;
  updateMegaPanelLayout();
}

function updateMegaPanelSize() {
  const panel = megaPanel.value;
  if (!panel) {
    return;
  }

  megaPanelWidth.value = panel.offsetWidth;
  megaPanelHeight.value = panel.offsetHeight;
  const shellTop = menuShell.value?.getBoundingClientRect().top ?? 0;
  megaPanelTop.value = floatingPanelTopOf(
    shellTop + activeRootTop.value,
    panel.offsetHeight,
    window.innerHeight,
    shellTop,
  );
}

function updateMegaPanelLayout() {
  const availableWidth = availableMegaPanelWidth();
  const maxGroupWidth = Math.max(0, Math.min(availableWidth, MEGA_PANEL_MAX_WIDTH));
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
  const preferredWidth = Math.min(availableWidth, MEGA_PANEL_MAX_WIDTH, groupWidth);

  megaGroupColumnCount.value = columnCount;
  megaPanelPreferredWidth.value = Math.max(280, preferredWidth);
  megaPanelWidth.value = megaPanelPreferredWidth.value;
}

function availableMegaPanelWidth() {
  const shellLeft = menuShell.value?.getBoundingClientRect().left ?? 0;
  const panelViewportLeft = shellLeft + megaPanelLeft.value;
  return Math.max(280, window.innerWidth - panelViewportLeft - MEGA_PANEL_SIDE_MARGIN);
}

function toggleDeepRoot(node: WorkbenchMenuNode) {
  if (!node.hasChildren) {
    return;
  }
  activeDeepRootId.value = activeDeepRootId.value === node.record.id ? undefined : node.record.id;
  void nextTick(updateMegaPanelSize);
}

function handleMegaEntryMainClick(node: WorkbenchMenuNode) {
  if (node.navigable) {
    selectMenuNode(node);
  } else {
    toggleDeepRoot(node);
  }
}

function closeDeepRoot() {
  activeDeepRootId.value = undefined;
  void nextTick(updateMegaPanelSize);
}

function handleMegaEntryKeydown(node: WorkbenchMenuNode, event: KeyboardEvent) {
  if (!node.hasChildren) {
    return;
  }
  if (event.key === 'ArrowRight' && activeDeepRootId.value !== node.record.id) {
    event.preventDefault();
    activeDeepRootId.value = node.record.id;
    void nextTick(updateMegaPanelSize);
  } else if (event.key === 'ArrowLeft' && activeDeepRootId.value === node.record.id) {
    event.preventDefault();
    activeDeepRootId.value = undefined;
    void nextTick(updateMegaPanelSize);
  }
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
      'compact-mega-open': isCompact && compactOutlinePath,
    }"
    :style="isCompact ? { '--compact-menu-top': `${compactTop}px` } : undefined"
    @mouseenter="handleMenuEnter"
    @mouseleave="handleMenuLeave"
    @pointermove="trackMenuPointer"
    @focusin="handleMenuFocusIn"
    @focusout="handleMenuFocusOut"
    @keydown="handleMenuKeydown"
  >
    <Transition name="workbench-menu-panel">
      <aside
        v-if="menuVisible"
        :id="isCompact ? 'workbench-compact-menu' : undefined"
        ref="menuSidebar"
        class="menu-sidebar"
      >
        <Transition name="workbench-sidebar-brand">
          <WorkbenchBrandControl
            v-if="!isCompact"
            presentation="expanded"
            :tenant-label="tenantLabel"
            :logo-src="logoSrc"
            :show-title-area="showTitleArea"
            :brand-title="brandTitle"
            :brand-subtitle="brandSubtitle"
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
              <div
                v-if="node.navigable && node.hasChildren && rootChildrenUseFlyout"
                class="root-menu-item root-menu-item--split navigable branch"
                :class="{
                  active: activeRootNode?.record.id === node.record.id,
                  selected: isSelectedMenu(node),
                  'selected-path': isSelectedRoot(node) && !isSelectedMenu(node),
                }"
                @mouseenter="openRootMenu(node, $event)"
              >
                <button
                  class="root-menu-item-main navigable"
                  type="button"
                  :aria-current="isSelectedRoot(node) ? 'page' : undefined"
                  @click="selectMenuNode(node)"
                >
                  <span>{{ node.record.title }}</span>
                </button>
                <button
                  class="root-menu-item-trigger"
                  type="button"
                  :aria-label="`${activeRootNode?.record.id === node.record.id ? '收起' : '展开'}${node.record.title}下级菜单`"
                  :aria-expanded="activeRootNode?.record.id === node.record.id"
                  aria-controls="workbench-mega-panel"
                  @click.stop="toggleRootMenu(node, $event)"
                >
                  <i class="root-menu-branch-indicator" aria-hidden="true" />
                </button>
              </div>
              <component
                :is="node.navigable || rootChildrenUseFlyout ? 'button' : 'div'"
                v-else
                class="root-menu-item"
                :class="{
                  active: activeRootNode?.record.id === node.record.id,
                  selected: isSelectedMenu(node),
                  'selected-path': isSelectedRoot(node) && !isSelectedMenu(node),
                  navigable: node.navigable,
                  branch: node.hasChildren,
                }"
                :type="node.navigable || rootChildrenUseFlyout ? 'button' : undefined"
                :aria-expanded="
                  node.hasChildren && rootChildrenUseFlyout
                    ? activeRootNode?.record.id === node.record.id
                    : undefined
                "
                :aria-controls="
                  node.hasChildren && rootChildrenUseFlyout && activeRootNode?.record.id === node.record.id
                    ? 'workbench-mega-panel'
                    : undefined
                "
                @mouseenter="rootChildrenUseFlyout && openRootMenu(node, $event)"
                @click="
                  node.navigable
                    ? selectMenuNode(node)
                    : rootChildrenUseFlyout && toggleRootMenu(node, $event)
                "
              >
                <span>{{ node.record.title }}</span>
                <i
                  v-if="node.hasChildren && rootChildrenUseFlyout"
                  class="root-menu-branch-indicator"
                  aria-hidden="true"
                />
              </component>
              <div
                v-if="!isCompact && expandedMenuDepth >= 2"
                class="sidebar-menu-level sidebar-menu-level--2"
              >
                <template v-for="group in node.children" :key="group.record.id">
                  <WorkbenchSidebarMenuEntry
                    :node="group"
                    :mode="expandedMenuDepth === 2 ? 'flyout' : 'inline'"
                    :selected="isSelectedMenu(group)"
                    :selected-path="isSelectedMenuAncestor(group)"
                    :active="activeSidebarSubmenuNode?.record.id === group.record.id"
                    @select="selectMenuNode"
                    @open-children="openSidebarSubmenu"
                    @toggle-children="toggleSidebarSubmenu"
                  />
                  <div v-if="expandedMenuDepth >= 3" class="sidebar-menu-level sidebar-menu-level--3">
                    <WorkbenchSidebarMenuEntry
                      v-for="entry in group.children"
                      :key="entry.record.id"
                      :node="entry"
                      mode="flyout"
                      :selected="isSelectedMenu(entry)"
                      :selected-path="isSelectedMenuAncestor(entry)"
                      :active="activeSidebarSubmenuNode?.record.id === entry.record.id"
                      @select="selectMenuNode"
                      @open-children="openSidebarSubmenu"
                      @toggle-children="toggleSidebarSubmenu"
                    />
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
              :placeholder="searchPlaceholder"
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

    <template v-if="compactOutlinePath">
      <svg class="compact-mega-outline compact-mega-outline--shadow" aria-hidden="true">
        <path :d="compactOutlinePath" />
      </svg>
    </template>

    <svg v-if="activeRootNode" class="mega-outline mega-outline--shadow" aria-hidden="true">
      <path :d="megaOutlinePath" />
    </svg>

    <svg v-if="activeRootNode" class="mega-outline mega-outline--stroke" aria-hidden="true">
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
        <div class="mega-body">
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
                  <div
                    v-for="entry in group.children"
                    :key="entry.record.id"
                    class="mega-entry"
                    :class="{
                      navigable: entry.navigable,
                      active: activeDeepRootNode?.record.id === entry.record.id,
                      selected: isSelectedMenu(entry),
                      'selected-path': isSelectedMenuAncestor(entry),
                    }"
                    @keydown="handleMegaEntryKeydown(entry, $event)"
                  >
                    <button
                      class="mega-entry-main"
                      type="button"
                      :disabled="!entry.navigable && !entry.hasChildren"
                      :aria-current="isSelectedMenu(entry) ? 'page' : undefined"
                      :aria-label="
                        !entry.navigable && entry.hasChildren
                          ? `${activeDeepRootNode?.record.id === entry.record.id ? '收起' : '展开'}${entry.record.title}下级菜单`
                          : undefined
                      "
                      :aria-expanded="
                        !entry.navigable && entry.hasChildren
                          ? activeDeepRootNode?.record.id === entry.record.id
                          : undefined
                      "
                      :aria-controls="
                        !entry.navigable && entry.hasChildren ? 'workbench-mega-deep-panel' : undefined
                      "
                      @click="handleMegaEntryMainClick(entry)"
                    >
                      <span>{{ entry.record.title }}</span>
                      <i
                        v-if="entry.hasChildren && !entry.navigable"
                        class="mega-entry-indicator"
                        aria-hidden="true"
                      />
                    </button>
                    <button
                      v-if="entry.hasChildren && entry.navigable"
                      class="mega-entry-trigger"
                      type="button"
                      :aria-label="`${activeDeepRootNode?.record.id === entry.record.id ? '收起' : '展开'}${entry.record.title}下级菜单`"
                      :aria-expanded="activeDeepRootNode?.record.id === entry.record.id"
                      aria-controls="workbench-mega-deep-panel"
                      @click.stop="toggleDeepRoot(entry)"
                      @keydown.esc.stop="closeDeepRoot"
                    >
                      <i class="mega-entry-indicator" aria-hidden="true" />
                    </button>
                  </div>
                </div>
              </section>
            </div>
          </div>

          <Transition
            name="mega-deep-dock"
            :duration="{ enter: 160, leave: 0 }"
            @after-enter="updateMegaPanelSize"
            @after-leave="updateMegaPanelSize"
          >
            <aside
              v-if="activeDeepRootNode"
              id="workbench-mega-deep-panel"
              class="mega-deep-panel"
              @mouseenter="handleMenuEnter"
              @keydown.esc.stop="closeDeepRoot"
            >
              <ul class="mega-deep-tree" :aria-label="`${activeDeepRootNode.record.title}下级菜单`">
                <WorkbenchMenuTree
                  v-for="child in activeDeepRootNode.children"
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
      </section>
    </Transition>

    <svg
      v-if="activeSidebarSubmenuNode"
      class="sidebar-submenu-outline sidebar-submenu-outline--shadow"
      aria-hidden="true"
    >
      <path :d="sidebarSubmenuOutlinePath" />
    </svg>

    <svg
      v-if="activeSidebarSubmenuNode"
      class="sidebar-submenu-outline sidebar-submenu-outline--stroke"
      aria-hidden="true"
    >
      <path :d="sidebarSubmenuOutlinePath" />
    </svg>

    <Transition name="workbench-mega-panel">
      <aside
        v-if="activeSidebarSubmenuNode"
        id="workbench-sidebar-submenu-panel"
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
  --workbench-menu-surface: var(--muyun-support-surface);
  --workbench-menu-border: var(--muyun-support-border);
  --workbench-menu-border-width: 1px;
  --workbench-menu-flyout-shadow: 0 18px 42px rgb(15 23 42 / 16%);
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
  z-index: 2;
  top: 0;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 10px;
  height: 100vh;
  min-width: 0;
  padding: 12px 10px;
  border-right: var(--workbench-menu-border-width) solid var(--workbench-menu-border);
  background: var(--muyun-support-elevated);
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

.workbench-menu--compact.compact-mega-open .menu-sidebar {
  z-index: 2;
  border-color: transparent;
  background: var(--workbench-menu-surface);
  box-shadow: none;
}

.workbench-menu--compact.mega-open .menu-sidebar {
  z-index: 2;
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
  gap: 4px;
  min-height: 50px;
  padding: 0 2px;
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
  border-top: 1px solid var(--muyun-support-border-subtle);
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
  gap: 0;
  height: 34px;
  padding: 0 9px;
  border: var(--workbench-menu-border-width) solid var(--workbench-menu-border);
  border-radius: 7px;
  background: var(--muyun-support-surface);
  color: var(--muyun-support-text-muted);
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
  color: var(--muyun-support-text);
  font-size: 12px;
}

.menu-search :deep(.ant-input-affix-wrapper-focused) {
  border-color: transparent;
  box-shadow: none;
}

.menu-search :deep(.ant-input::placeholder) {
  color: var(--muyun-support-icon);
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
.root-menu-item-main,
.root-menu-item-trigger {
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
  color: var(--muyun-support-text-body);
  font-size: 13px;
  cursor: default;
}

.root-menu-item--split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 32px;
  align-items: stretch;
  padding: 0;
  overflow: hidden;
}

.root-menu-item-main,
.root-menu-item-trigger {
  min-width: 0;
  min-height: 34px;
  color: inherit;
}

.root-menu-item-main {
  display: flex;
  align-items: center;
  padding: 7px 8px;
}

.root-menu-item-trigger {
  display: grid;
  place-items: center;
  padding: 0;
  border-radius: 0 6px 6px 0;
  cursor: pointer;
}

.root-menu-item-trigger:hover {
  background: var(--muyun-theme-soft);
}

.root-menu-item-trigger .root-menu-branch-indicator {
  position: relative;
  left: 4px;
}

.root-menu-item span,
.mega-entry-main span,
.mega-group-title span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.root-menu-item:hover,
.root-menu-item.active,
.root-menu-item.active.selected {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
}

.root-menu-item.selected {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
  font-weight: 700;
}

.root-menu-item.selected,
.root-menu-item.selected-path,
.mega-group-title.selected,
.mega-group-title.selected-path,
.mega-entry.selected,
.mega-entry.selected-path {
  position: relative;
}

.root-menu-item.selected::before,
.root-menu-item.selected-path::before,
.mega-group-title.selected::before,
.mega-group-title.selected-path::before,
.mega-entry.selected::before,
.mega-entry.selected-path::before {
  position: absolute;
  top: 5px;
  bottom: 5px;
  left: 0;
  z-index: 1;
  width: 3px;
  border-radius: 0 999px 999px 0;
  background: var(--muyun-theme-base);
  content: '';
}

.root-menu-item.selected-path::before,
.mega-group-title.selected-path::before,
.mega-entry.selected-path::before {
  background: var(--muyun-theme-hover);
  opacity: 0.58;
}

.root-menu-item.selected-path {
  background: var(--muyun-theme-soft);
  color: var(--muyun-support-text-muted);
}

.root-menu-item.active,
.root-menu-item.active.selected {
  z-index: 2;
  border-color: transparent;
  border-radius: 6px 0 0 6px;
  background: var(--workbench-menu-surface);
}

.root-menu-item.active.selected-path {
  background: var(--workbench-menu-surface);
  color: var(--muyun-theme-soft-text);
  box-shadow: inset 3px 0 0 var(--muyun-theme-border);
}

.root-menu-item.navigable {
  cursor: pointer;
}

.root-menu-item--split.navigable {
  cursor: default;
}

.root-menu-item.navigable > span,
.root-menu-item-main.navigable > span,
.mega-group-title.navigable > span,
.mega-entry.navigable .mega-entry-main > span {
  position: relative;
  display: inline-block;
  max-width: 100%;
}

.root-menu-item.navigable > span::after,
.root-menu-item-main.navigable > span::after,
.mega-group-title.navigable > span::after,
.mega-entry.navigable .mega-entry-main > span::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 1px;
  background: var(--muyun-theme-base);
  content: '';
  opacity: 0;
  transform: scaleX(0.55);
  transform-origin: center;
  transition:
    opacity 140ms ease,
    transform 160ms ease;
}

.root-menu-item.navigable:hover > span::after,
.root-menu-item.navigable:focus-visible > span::after,
.root-menu-item--split.navigable:hover .root-menu-item-main > span::after,
.root-menu-item-main.navigable:focus-visible > span::after,
.mega-group-title.navigable:hover > span::after,
.mega-group-title.navigable:focus-visible > span::after,
.mega-entry.navigable:hover .mega-entry-main > span::after,
.mega-entry.navigable .mega-entry-main:focus-visible > span::after {
  opacity: 0.62;
  transform: scaleX(1);
}

.root-menu-branch-indicator {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  margin-right: 2px;
  border-right: 1.5px solid currentcolor;
  border-bottom: 1.5px solid currentcolor;
  opacity: 0.48;
  transform: rotate(-45deg);
  transition:
    opacity 140ms ease,
    transform 160ms ease;
}

.root-menu-item.branch:hover .root-menu-branch-indicator,
.root-menu-item.branch.active .root-menu-branch-indicator {
  opacity: 0.88;
}

.root-menu-item.branch.active .root-menu-branch-indicator {
  transform: rotate(45deg) translate(-1px, -1px);
}

.root-menu-item:focus-visible,
.root-menu-item-main:focus-visible,
.root-menu-item-trigger:focus-visible,
.mega-group-title:focus-visible,
.mega-entry-main:focus-visible,
.mega-entry-trigger:focus-visible {
  outline: 0;
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
  box-shadow: inset 0 0 0 2px var(--muyun-theme-focus);
}

.root-menu-item.active,
.root-menu-item.active.selected {
  box-shadow: inset 3px 0 0 var(--muyun-theme-base);
}

.root-menu-item.active:focus-visible,
.root-menu-item.active.selected:focus-visible {
  background: var(--muyun-theme-soft);
  box-shadow: inset 3px 0 0 var(--muyun-theme-base);
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

.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 9px;
  border: var(--workbench-menu-border-width) solid var(--workbench-menu-border);
  border-radius: 7px;
  background: var(--muyun-support-surface);
  color: var(--muyun-support-text-muted);
  font-size: 11px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--muyun-positive-base);
  box-shadow: 0 0 0 4px var(--muyun-positive-focus);
}

.sidebar-footer.realtime-connecting .status-dot {
  background: var(--muyun-warning-base);
  box-shadow: 0 0 0 4px var(--muyun-warning-focus);
}

.sidebar-footer.realtime-disconnected .status-dot {
  background: var(--muyun-support-icon);
  box-shadow: none;
}

.mega-panel {
  position: absolute;
  z-index: 2;
  top: var(--mega-panel-top);
  left: var(--mega-panel-left);
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  width: min(var(--mega-panel-width), calc(100vw - var(--mega-panel-left) - 24px));
  max-height: calc(100vh - 16px);
  border: 0;
  border-radius: 0 8px 8px 0;
  background: var(--workbench-menu-surface);
  box-shadow: none;
  overflow: visible;
}

.sidebar-submenu-panel {
  position: absolute;
  z-index: 2;
  top: var(--sidebar-submenu-top);
  left: var(--sidebar-submenu-left);
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  width: max-content;
  min-width: min(240px, calc(100vw - var(--sidebar-submenu-left) - 24px));
  max-width: min(360px, calc(100vw - var(--sidebar-submenu-left) - 24px));
  max-height: min(620px, calc(100vh - 16px));
  border: 0;
  border-radius: 0 8px 8px 0;
  background: var(--workbench-menu-surface);
  box-shadow: none;
  overflow: hidden;
}

.sidebar-submenu-tree {
  min-height: 0;
  margin: 0;
  padding: 8px;
  overflow: auto;
}

.sidebar-submenu-outline,
.mega-outline,
.compact-mega-outline {
  position: absolute;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  overflow: visible;
  pointer-events: none;
}

.sidebar-submenu-outline--shadow,
.mega-outline--shadow,
.compact-mega-outline--shadow {
  z-index: 1;
  filter: drop-shadow(var(--workbench-menu-flyout-shadow));
}

.sidebar-submenu-outline--shadow path,
.mega-outline--shadow path,
.compact-mega-outline--shadow path {
  fill: var(--workbench-menu-surface);
  stroke: none;
}

.sidebar-submenu-outline--stroke,
.mega-outline--stroke {
  z-index: 3;
}

.sidebar-submenu-outline--stroke path,
.mega-outline--stroke path {
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

.workbench-menu--compact.mega-open .root-menu-item.active {
  background: var(--workbench-menu-surface);
  box-shadow: inset 3px 0 0 var(--muyun-theme-base);
}

.workbench-menu--compact.mega-open .root-menu-item.active.selected-path {
  color: var(--muyun-theme-base);
  box-shadow: inset 3px 0 0 var(--muyun-theme-base);
}

.mega-body {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  grid-template-columns: minmax(0, 1fr);
  min-height: 0;
  max-height: calc(100vh - 16px);
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
  color: var(--muyun-support-text-muted);
  font-size: 12px;
  font-weight: 800;
  cursor: default;
}

.mega-group-title.navigable {
  cursor: pointer;
}

.mega-group-title.selected,
.mega-group-title.selected-path {
  color: var(--muyun-theme-base);
  padding-left: 8px;
}

.mega-entry-list {
  display: grid;
  gap: 2px;
}

.mega-entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: stretch;
  min-height: 30px;
  border-radius: 6px;
  background: transparent;
  color: var(--muyun-support-text-muted);
  font-size: 12px;
  cursor: default;
  overflow: hidden;
}

.mega-entry-main,
.mega-entry-trigger {
  min-width: 0;
  min-height: 30px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
}

.mega-entry-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 7px;
  text-align: left;
}

.mega-entry-main:not(:disabled),
.mega-entry-trigger {
  cursor: pointer;
}

.mega-entry-main:disabled {
  opacity: 1;
}

.mega-entry-main > .mega-entry-indicator {
  margin-right: 6px;
}

.mega-entry-trigger {
  display: grid;
  width: 32px;
  place-items: center;
  padding: 0;
  border-radius: 0 6px 6px 0;
}

.mega-entry-trigger:hover {
  background: var(--muyun-theme-soft);
}

.mega-entry-indicator {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  border-right: 1.5px solid currentcolor;
  border-bottom: 1.5px solid currentcolor;
  opacity: 0.58;
  transform: rotate(-45deg);
  transition:
    opacity 140ms ease,
    transform 160ms ease;
}

.mega-entry-main:hover .mega-entry-indicator,
.mega-entry-trigger:hover .mega-entry-indicator,
.mega-entry.active .mega-entry-indicator {
  opacity: 1;
}

.mega-entry.active .mega-entry-indicator {
  transform: rotate(45deg) translate(-1px, -1px);
}

.mega-entry:hover,
.mega-entry.active {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
}

.mega-entry.active {
  box-shadow: inset 3px 0 0 var(--muyun-theme-base);
}

.mega-entry.selected {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
}

.mega-entry.selected-path {
  color: var(--muyun-support-text-body);
}

.mega-deep-panel {
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  min-width: 0;
  max-height: min(320px, 45vh);
  border-top: var(--workbench-menu-border-width) solid var(--workbench-menu-border);
  border-radius: 0 0 8px 0;
  background: var(--muyun-support-elevated);
  box-shadow: inset 0 10px 18px -18px rgb(15 23 42 / 28%);
  overflow: hidden;
}

.mega-deep-tree {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  align-items: start;
  gap: 4px 16px;
  min-height: 0;
  margin: 0;
  padding: 10px 14px 12px;
  overflow: auto;
}

.mega-deep-dock-enter-active {
  transition:
    opacity 120ms ease,
    transform 160ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.mega-deep-dock-enter-from {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 980px) {
  .workbench-menu--expanded .mega-outline,
  .workbench-menu--expanded .sidebar-submenu-outline,
  .workbench-menu--expanded .compact-mega-outline {
    display: none;
  }

  .workbench-menu--expanded .menu-sidebar {
    position: relative;
    height: auto;
    min-height: 0;
    border-right: 0;
    border-bottom: 1px solid var(--muyun-support-border);
  }

  .workbench-menu--expanded .root-menu {
    max-height: 240px;
  }

  .workbench-menu--expanded .mega-panel {
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

  .workbench-menu--expanded .sidebar-submenu-panel {
    position: relative;
    top: auto;
    left: auto;
    width: 100%;
    max-height: none;
    margin-top: 8px;
    border-radius: 8px;
  }

  .workbench-menu--expanded .mega-groups {
    grid-template-columns: minmax(0, 1fr);
    max-height: none;
  }

  .workbench-menu--expanded .mega-column {
    gap: 14px;
  }

  .mega-deep-tree {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (prefers-reduced-motion: reduce) {
  .workbench-menu-panel-enter-active,
  .workbench-menu-panel-leave-active,
  .workbench-sidebar-brand-enter-active,
  .workbench-sidebar-brand-leave-active,
  .workbench-mega-panel-enter-active,
  .workbench-mega-panel-leave-active,
  .mega-deep-dock-enter-active,
  .mega-entry-indicator,
  .root-menu-branch-indicator {
    transition: none !important;
  }
}
</style>
