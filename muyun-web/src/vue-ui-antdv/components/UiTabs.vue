<script setup lang="ts">
import { Tabs as ATabs, TabPane as ATabPane } from 'ant-design-vue';
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import type { UiTabItem } from '../types';
import UiIcon from './UiIcon.vue';

defineOptions({ name: 'UiTabs', inheritAttrs: false });

const props = defineProps<{
  tabs: UiTabItem[];
  activeKey: string;
}>();

const emit = defineEmits<{
  'update:activeKey': [key: string];
  close: [key: string];
  /** Closes a transient set of tabs without persisting their order or visibility. */
  closeTabs: [keys: string[]];
  togglePin: [key: string];
  /** Reports the transient session order after a completed pointer drag. */
  reorder: [keys: string[]];
}>();

const tabsRoot = ref<HTMLElement>();
const displayTabs = ref<UiTabItem[]>([...props.tabs]);
const draggingKey = ref<string>();
const dragPreview = ref<{ title: string; left: number; top: number; width: number; height: number }>();
const tabActionMenu = ref<{ tabKey: string; left: number; top: number }>();
let pointerId: number | undefined;
let dragStartX = 0;
let dragStarted = false;
let suppressClick = false;
let suppressClickTimer: number | undefined;
let reorderFrame: number | undefined;
let pendingReorder: { targetKey: string; clientX: number } | undefined;
let touchGesture: { pointerId: number; tabKey: string; startX: number; startY: number } | undefined;
const DRAG_THRESHOLD = 5;
const TOUCH_MENU_VERTICAL_SWIPE = 28;
const AUTO_SCROLL_EDGE = 36;
const AUTO_SCROLL_STEP = 14;

watch(
  () => props.tabs,
  async (tabs, previousTabs) => {
    if (!draggingKey.value) {
      const previousRects = tabRectsByKey();
      const changedPinnedKey = tabs.find(
        (tab) => tab.pinned !== previousTabs?.find((item) => item.key === tab.key)?.pinned,
      )?.key;
      displayTabs.value = [...tabs];
      if (changedPinnedKey) {
        await nextTick();
        animateTabShift(previousRects);
      }
    }
  },
  { deep: true },
);

function handleChange(key: string | number) {
  emit('update:activeKey', String(key));
}

function handleEditEvent(targetKey: string | number | MouseEvent | KeyboardEvent, action: 'add' | 'remove') {
  if (action === 'remove' && (typeof targetKey === 'string' || typeof targetKey === 'number')) {
    emit('close', String(targetKey));
  }
}

function startDrag(event: PointerEvent, key: string) {
  if (
    event.pointerType === 'touch' ||
    event.button !== 0 ||
    (event.target as Element).closest('.ant-tabs-tab-remove')
  )
    return;
  pointerId = event.pointerId;
  dragStartX = event.clientX;
  dragStarted = false;
  draggingKey.value = key;
  document.addEventListener('pointermove', moveDrag);
  document.addEventListener('pointerup', finishDrag);
  document.addEventListener('pointercancel', cancelDrag);
}

function handleTabPointerDown(event: PointerEvent) {
  const key = tabKeyFromEvent(event);
  if (!key) return;
  if (event.pointerType !== 'touch') {
    startDrag(event, key);
    return;
  }
  touchGesture = { pointerId: event.pointerId, tabKey: key, startX: event.clientX, startY: event.clientY };
  document.addEventListener('pointerup', finishTouchGesture);
  document.addEventListener('pointercancel', cancelTouchGesture);
}

function handleTabContextMenu(event: MouseEvent) {
  const key = tabKeyFromEvent(event);
  if (!key) return;
  openTabActionMenuFromPointer(event, key);
}

function tabKeyFromEvent(event: Event) {
  const target = event.target;
  if (!(target instanceof Element) || target.closest('.ant-tabs-tab-remove')) return undefined;
  return target.closest('.ant-tabs-tab')?.querySelector<HTMLElement>('.ui-tabs-label')?.dataset.tabKey;
}

function finishTouchGesture(event: PointerEvent) {
  if (!touchGesture || event.pointerId !== touchGesture.pointerId) return;
  const gesture = touchGesture;
  cancelTouchGesture();
  const deltaX = event.clientX - gesture.startX;
  const deltaY = event.clientY - gesture.startY;
  if (Math.abs(deltaY) < TOUCH_MENU_VERTICAL_SWIPE || Math.abs(deltaY) <= Math.abs(deltaX)) return;
  suppressNextClick();
  openTabActionMenu(gesture.tabKey, event.clientX, event.clientY);
}

function cancelTouchGesture() {
  touchGesture = undefined;
  document.removeEventListener('pointerup', finishTouchGesture);
  document.removeEventListener('pointercancel', cancelTouchGesture);
}

function moveDrag(event: PointerEvent) {
  if (pointerId !== event.pointerId || !draggingKey.value) return;
  if (!dragStarted && Math.abs(event.clientX - dragStartX) < DRAG_THRESHOLD) return;
  if (!dragStarted) beginDrag(event);
  else updateDragPreview(event);
  autoScrollTabs(event.clientX);
  const targetKey = dragTargetKeyAt(event.clientX);
  if (targetKey && targetKey !== draggingKey.value) scheduleReorder(targetKey, event.clientX);
}

function finishDrag(event: PointerEvent) {
  if (pointerId !== event.pointerId || !draggingKey.value) return;
  flushPendingReorder();
  const keys = displayTabs.value.map((tab) => tab.key);
  const moved = dragStarted && keys.join('|') !== props.tabs.map((tab) => tab.key).join('|');
  if (dragStarted) suppressNextClick();
  stopDragListeners();
  draggingKey.value = undefined;
  dragPreview.value = undefined;
  pointerId = undefined;
  dragStarted = false;
  if (moved) emit('reorder', keys);
  else displayTabs.value = [...props.tabs];
}

function cancelDrag() {
  discardPendingReorder();
  stopDragListeners();
  draggingKey.value = undefined;
  dragPreview.value = undefined;
  pointerId = undefined;
  dragStarted = false;
  displayTabs.value = [...props.tabs];
}

function stopDragListeners() {
  document.removeEventListener('pointermove', moveDrag);
  document.removeEventListener('pointerup', finishDrag);
  document.removeEventListener('pointercancel', cancelDrag);
}

function flushPendingReorder() {
  if (reorderFrame !== undefined) window.cancelAnimationFrame(reorderFrame);
  reorderFrame = undefined;
  const next = pendingReorder;
  pendingReorder = undefined;
  if (next) moveDraggedTabBefore(next.targetKey, next.clientX);
}

function discardPendingReorder() {
  if (reorderFrame !== undefined) window.cancelAnimationFrame(reorderFrame);
  reorderFrame = undefined;
  pendingReorder = undefined;
}

function beginDrag(event: PointerEvent) {
  const tab = displayTabs.value.find((item) => item.key === draggingKey.value);
  const tabElement = tabElementOf(draggingKey.value);
  if (!tab || !tabElement) return;
  const rect = tabElement.getBoundingClientRect();
  dragStarted = true;
  dragPreview.value = {
    title: tab.title,
    left: event.clientX - (dragStartX - rect.left),
    top: rect.top,
    width: rect.width,
    height: rect.height,
  };
}

function updateDragPreview(event: PointerEvent) {
  if (!dragPreview.value) return;
  dragPreview.value = {
    ...dragPreview.value,
    left: event.clientX - (dragStartX - dragPreview.value.left),
  };
  dragStartX = event.clientX;
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return;
  if (tabActionMenu.value) {
    event.preventDefault();
    tabActionMenu.value = undefined;
    return;
  }
  if (!draggingKey.value) return;
  event.preventDefault();
  cancelDrag();
}

function openTabActionMenu(tabKey: string, clientX: number, clientY: number) {
  tabActionMenu.value = {
    tabKey,
    left: Math.max(8, Math.min(clientX, window.innerWidth - 184)),
    top: Math.max(8, Math.min(clientY, window.innerHeight - 242)),
  };
}

function openTabActionMenuFromPointer(event: MouseEvent, tabKey: string) {
  event.preventDefault();
  openTabActionMenu(tabKey, event.clientX, event.clientY);
}

function closeTabActionMenuOnOutsidePointer(event: Event) {
  if (!tabActionMenu.value) return;
  if (event.composedPath().some((target) => target instanceof Element && target.closest('.ui-tabs-tab-menu')))
    return;
  tabActionMenu.value = undefined;
}

function tabKeysForAction(action: 'close' | 'close-left' | 'close-right' | 'close-others' | 'close-all') {
  const activeMenu = tabActionMenu.value;
  if (!activeMenu) return [];
  const index = displayTabs.value.findIndex((tab) => tab.key === activeMenu.tabKey);
  if (index < 0) return [];
  const closable = (tab: UiTabItem) => tab.closable !== false;
  const ordinaryClosable = (tab: UiTabItem) => closable(tab) && !tab.pinned;
  if (action === 'close') return closable(displayTabs.value[index]) ? [activeMenu.tabKey] : [];
  if (action === 'close-left')
    return displayTabs.value
      .slice(0, index)
      .filter(ordinaryClosable)
      .map((tab) => tab.key);
  if (action === 'close-right')
    return displayTabs.value
      .slice(index + 1)
      .filter(ordinaryClosable)
      .map((tab) => tab.key);
  if (action === 'close-others')
    return displayTabs.value
      .filter((tab) => tab.key !== activeMenu.tabKey && ordinaryClosable(tab))
      .map((tab) => tab.key);
  return displayTabs.value.filter(ordinaryClosable).map((tab) => tab.key);
}

function performTabAction(action: 'close' | 'close-left' | 'close-right' | 'close-others' | 'close-all') {
  const keys = tabKeysForAction(action);
  tabActionMenu.value = undefined;
  if (keys.length) emit('closeTabs', keys);
}

function togglePinFromMenu() {
  const key = tabActionMenu.value?.tabKey;
  tabActionMenu.value = undefined;
  if (key) emit('togglePin', key);
}

function suppressNextClick() {
  suppressClick = true;
  if (suppressClickTimer !== undefined) window.clearTimeout(suppressClickTimer);
  suppressClickTimer = window.setTimeout(() => {
    suppressClick = false;
    suppressClickTimer = undefined;
  }, 0);
}

function preventDragClick(event: MouseEvent) {
  if (!suppressClick) return;
  event.preventDefault();
  event.stopPropagation();
  suppressClick = false;
  if (suppressClickTimer !== undefined) window.clearTimeout(suppressClickTimer);
  suppressClickTimer = undefined;
}

function dragTargetKeyAt(clientX: number) {
  return [...(tabsRoot.value?.querySelectorAll<HTMLElement>('.ui-tabs-label') ?? [])].find((label) => {
    const rect = label.getBoundingClientRect();
    return clientX >= rect.left && clientX <= rect.right;
  })?.dataset.tabKey;
}

function scheduleReorder(targetKey: string, clientX: number) {
  pendingReorder = { targetKey, clientX };
  if (reorderFrame !== undefined) return;
  reorderFrame = window.requestAnimationFrame(() => {
    reorderFrame = undefined;
    const next = pendingReorder;
    pendingReorder = undefined;
    if (next) moveDraggedTabBefore(next.targetKey, next.clientX);
  });
}

function moveDraggedTabBefore(targetKey: string, clientX: number) {
  const sourceIndex = displayTabs.value.findIndex((tab) => tab.key === draggingKey.value);
  const targetIndex = displayTabs.value.findIndex((tab) => tab.key === targetKey);
  if (sourceIndex < 0 || targetIndex < 0) return;
  if (displayTabs.value[sourceIndex].pinned !== displayTabs.value[targetIndex].pinned) return;
  const targetElement = [...(tabsRoot.value?.querySelectorAll<HTMLElement>('.ui-tabs-label') ?? [])].find(
    (label) => label.dataset.tabKey === targetKey,
  );
  const targetRect = targetElement?.getBoundingClientRect();
  const insertionIndex =
    targetRect && clientX > targetRect.left + targetRect.width / 2 ? targetIndex + 1 : targetIndex;
  const nextTabs = [...displayTabs.value];
  const [draggedTab] = nextTabs.splice(sourceIndex, 1);
  const nextIndex = insertionIndex > sourceIndex ? insertionIndex - 1 : insertionIndex;
  if (nextIndex === sourceIndex) return;
  const previousRects = tabRectsByKey();
  nextTabs.splice(nextIndex, 0, draggedTab);
  displayTabs.value = nextTabs;
  window.requestAnimationFrame(() => animateTabShift(previousRects));
}

function tabElementOf(key: string | undefined) {
  return [...(tabsRoot.value?.querySelectorAll<HTMLElement>('.ui-tabs-label') ?? [])]
    .find((label) => label.dataset.tabKey === key)
    ?.closest<HTMLElement>('.ant-tabs-tab');
}

function tabRectsByKey() {
  return new Map(
    [...(tabsRoot.value?.querySelectorAll<HTMLElement>('.ui-tabs-label') ?? [])].flatMap((label) => {
      const tab = label.closest<HTMLElement>('.ant-tabs-tab');
      return tab && label.dataset.tabKey
        ? [[label.dataset.tabKey, tab.getBoundingClientRect()] as const]
        : [];
    }),
  );
}

function animateTabShift(previousRects: Map<string, DOMRect>) {
  for (const label of tabsRoot.value?.querySelectorAll<HTMLElement>('.ui-tabs-label') ?? []) {
    const tab = label.closest<HTMLElement>('.ant-tabs-tab');
    const previousRect = label.dataset.tabKey ? previousRects.get(label.dataset.tabKey) : undefined;
    if (!tab || !previousRect) continue;
    const nextRect = tab.getBoundingClientRect();
    const deltaX = previousRect.left - nextRect.left;
    if (!deltaX) continue;
    tab.style.transition = 'none';
    tab.style.transform = `translateX(${deltaX}px)`;
    window.requestAnimationFrame(() => {
      tab.style.transition = 'transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1)';
      tab.style.transform = '';
    });
  }
}

function autoScrollTabs(clientX: number) {
  const nav = tabsRoot.value?.querySelector<HTMLElement>('.ant-tabs-nav-wrap');
  if (!nav) return;
  const rect = nav.getBoundingClientRect();
  if (clientX < rect.left + AUTO_SCROLL_EDGE) nav.scrollLeft -= AUTO_SCROLL_STEP;
  if (clientX > rect.right - AUTO_SCROLL_EDGE) nav.scrollLeft += AUTO_SCROLL_STEP;
}

onMounted(() => {
  document.addEventListener('keydown', handleKeydown);
  document.addEventListener('pointerdown', closeTabActionMenuOnOutsidePointer);
});
onUnmounted(() => {
  document.removeEventListener('keydown', handleKeydown);
  document.removeEventListener('pointerdown', closeTabActionMenuOnOutsidePointer);
  stopDragListeners();
  cancelTouchGesture();
  if (suppressClickTimer !== undefined) window.clearTimeout(suppressClickTimer);
  discardPendingReorder();
});
</script>

<template>
  <div
    ref="tabsRoot"
    class="ui-tabs"
    @click.capture="preventDragClick"
    @pointerdown="handleTabPointerDown"
    @contextmenu="handleTabContextMenu"
  >
    <ATabs
      type="editable-card"
      hide-add
      :active-key="activeKey"
      :class="$attrs.class"
      :style="$attrs.style"
      @change="handleChange"
      @edit="handleEditEvent"
    >
      <ATabPane v-for="tab in displayTabs" :key="tab.key" :closable="tab.closable ?? true">
        <template #tab>
          <span
            class="ui-tabs-label"
            :class="{
              'ui-tabs-label--dragging': dragPreview && draggingKey === tab.key,
              'ui-tabs-label--pinned': tab.pinned,
            }"
            :data-tab-key="tab.key"
          >
            <button
              v-if="tab.pinned"
              class="ui-tabs-pin-button"
              type="button"
              aria-label="取消锁定标签"
              @pointerdown.stop
              @click.stop="emit('togglePin', tab.key)"
            >
              <UiIcon name="pin" />
            </button>
            {{ tab.title }}
          </span>
        </template>
      </ATabPane>
    </ATabs>
    <div
      v-if="tabActionMenu"
      class="ui-tabs-tab-menu"
      :style="{ left: `${tabActionMenu.left}px`, top: `${tabActionMenu.top}px` }"
      role="menu"
      aria-label="页签操作"
    >
      <button type="button" role="menuitem" @click="togglePinFromMenu">
        {{
          displayTabs.find((tab) => tab.key === tabActionMenu?.tabKey)?.pinned ? '取消锁定标签' : '锁定标签'
        }}
      </button>
      <span class="ui-tabs-tab-menu-divider" role="separator" />
      <button
        type="button"
        role="menuitem"
        :disabled="!tabKeysForAction('close').length"
        @click="performTabAction('close')"
      >
        关闭标签
      </button>
      <button
        type="button"
        role="menuitem"
        :disabled="!tabKeysForAction('close-left').length"
        @click="performTabAction('close-left')"
      >
        关闭左侧
      </button>
      <button
        type="button"
        role="menuitem"
        :disabled="!tabKeysForAction('close-right').length"
        @click="performTabAction('close-right')"
      >
        关闭右侧
      </button>
      <button
        type="button"
        role="menuitem"
        :disabled="!tabKeysForAction('close-others').length"
        @click="performTabAction('close-others')"
      >
        关闭其他
      </button>
      <span class="ui-tabs-tab-menu-divider" role="separator" />
      <button
        type="button"
        role="menuitem"
        :disabled="!tabKeysForAction('close-all').length"
        @click="performTabAction('close-all')"
      >
        关闭所有
      </button>
    </div>
    <div
      v-if="dragPreview"
      class="ui-tabs-drag-preview"
      :style="{
        left: `${dragPreview.left}px`,
        top: `${dragPreview.top}px`,
        width: `${dragPreview.width}px`,
        height: `${dragPreview.height}px`,
      }"
      aria-hidden="true"
    >
      {{ dragPreview.title }}
    </div>
  </div>
</template>

<style scoped>
.ui-tabs-label {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  cursor: grab;
  touch-action: pan-x;
  user-select: none;
}

.ui-tabs-pin-button {
  display: inline-grid;
  place-items: center;
  width: 15px;
  height: 15px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--muyun-theme-base);
  cursor: pointer;
  font-size: 11px;
  transition:
    background-color 120ms ease,
    color 120ms ease,
    transform 120ms ease;
}

.ui-tabs-pin-button:hover,
.ui-tabs-pin-button:focus-visible {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-active);
  outline: 0;
  transform: scale(1.08);
}

.ui-tabs :deep(.ant-tabs-tab .ant-tabs-tab-remove) {
  width: 0;
  margin: 0;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.ui-tabs :deep(.ant-tabs-tab:hover .ant-tabs-tab-remove),
.ui-tabs :deep(.ant-tabs-tab:focus-within .ant-tabs-tab-remove) {
  width: auto;
  margin-left: 4px;
  opacity: 1;
  pointer-events: auto;
}

.ui-tabs-tab-menu {
  position: fixed;
  z-index: 1001;
  display: grid;
  min-width: 176px;
  padding: 5px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
  box-shadow: 0 12px 28px rgb(15 23 42 / 20%);
}

.ui-tabs-tab-menu button {
  min-height: 30px;
  padding: 5px 9px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--muyun-support-text-body);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  text-align: left;
}

.ui-tabs-tab-menu button:hover:not(:disabled),
.ui-tabs-tab-menu button:focus-visible:not(:disabled) {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
  outline: 0;
}

.ui-tabs-tab-menu button:disabled {
  color: var(--muyun-support-disabled-text);
  cursor: not-allowed;
}

.ui-tabs-tab-menu-divider {
  height: 1px;
  margin: 4px;
  background: var(--muyun-support-border-subtle);
}

.ui-tabs-label--dragging {
  cursor: grabbing;
}

.ui-tabs :deep(.ant-tabs-tab:has(.ui-tabs-label--dragging)) {
  border-style: dashed !important;
  opacity: 0.36;
}

.ui-tabs-drag-preview {
  position: fixed;
  z-index: 1000;
  display: flex;
  align-items: center;
  box-sizing: border-box;
  padding: 0 10px;
  border: 1px solid var(--muyun-theme-border);
  border-radius: 7px;
  background: var(--muyun-support-surface);
  box-shadow: 0 14px 30px rgb(15 23 42 / 18%);
  color: var(--muyun-theme-base);
  font-size: 12px;
  font-weight: 700;
  pointer-events: none;
  transform: rotate(1deg) scale(1.02);
  transition: box-shadow 160ms ease;
}
</style>
