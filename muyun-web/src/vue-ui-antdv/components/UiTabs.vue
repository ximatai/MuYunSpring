<script setup lang="ts">
import { Tabs as ATabs, TabPane as ATabPane } from 'ant-design-vue';
import { onMounted, onUnmounted, ref, watch } from 'vue';
import type { UiTabItem } from '../types';

defineOptions({ name: 'UiTabs', inheritAttrs: false });

const props = defineProps<{
  tabs: UiTabItem[];
  activeKey: string;
}>();

const emit = defineEmits<{
  'update:activeKey': [key: string];
  close: [key: string];
  /** Reports the transient session order after a completed pointer drag. */
  reorder: [keys: string[]];
}>();

const tabsRoot = ref<HTMLElement>();
const displayTabs = ref<UiTabItem[]>([...props.tabs]);
const draggingKey = ref<string>();
const dragPreview = ref<{ title: string; left: number; top: number; width: number; height: number }>();
let pointerId: number | undefined;
let dragStartX = 0;
let dragStarted = false;
let suppressClick = false;
let suppressClickTimer: number | undefined;
let reorderFrame: number | undefined;
let pendingReorder: { targetKey: string; clientX: number } | undefined;
const DRAG_THRESHOLD = 5;
const AUTO_SCROLL_EDGE = 36;
const AUTO_SCROLL_STEP = 14;

watch(
  () => props.tabs,
  (tabs) => {
    if (!draggingKey.value) displayTabs.value = [...tabs];
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
  if (event.key !== 'Escape' || !draggingKey.value) return;
  event.preventDefault();
  cancelDrag();
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

onMounted(() => document.addEventListener('keydown', handleKeydown));
onUnmounted(() => {
  document.removeEventListener('keydown', handleKeydown);
  stopDragListeners();
  if (suppressClickTimer !== undefined) window.clearTimeout(suppressClickTimer);
  discardPendingReorder();
});
</script>

<template>
  <div ref="tabsRoot" class="ui-tabs" @click.capture="preventDragClick">
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
            :class="{ 'ui-tabs-label--dragging': dragPreview && draggingKey === tab.key }"
            :data-tab-key="tab.key"
            @pointerdown="startDrag($event, tab.key)"
          >
            {{ tab.title }}
          </span>
        </template>
      </ATabPane>
    </ATabs>
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
  cursor: grab;
  touch-action: pan-x;
  user-select: none;
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
