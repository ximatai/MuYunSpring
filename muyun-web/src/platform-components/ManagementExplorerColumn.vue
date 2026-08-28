<script setup lang="ts">
import { computed, getCurrentInstance, inject, nextTick, onUnmounted, provide, ref, watch } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';
import {
  MANAGEMENT_EXPLORER_COLUMN_CONTEXT,
  type ManagementExplorerColumnContext,
} from './managementExplorerContext';
import { MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT } from './managementWorkspaceLayout';
import { MANAGEMENT_WORKSPACE_CONTEXT } from './managementWorkspaceContext';

defineOptions({ name: 'ManagementExplorerColumn' });

const props = withDefaults(
  defineProps<{
    /** Explicit opt-in for compact navigation micro lists. */
    collapsible?: boolean;
    /** Title shown on the collapsed rail tab. */
    title?: string;
    /** Whether the navigator currently constrains the primary list. */
    hasSelection?: boolean;
  }>(),
  {
    collapsible: false,
    title: '',
    hasSelection: false,
  },
);

const workspace = inject(MANAGEMENT_WORKSPACE_CONTEXT, undefined);
const instance = getCurrentInstance();
const id = `management-explorer-${instance?.uid ?? Math.random().toString(36).slice(2)}`;
const previewOpen = ref(false);
const collapsing = ref(false);
const collapseLeaving = ref(false);
const expanding = ref(false);
const contentElement = ref<HTMLElement>();
const tabTriggerElement = ref<HTMLElement>();
const tabTransitionOffset = ref(0);
const tabTransitionWidth = ref<number>(MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.railWidth);
let previewCloseTimer: ReturnType<typeof setTimeout> | undefined;
let collapseTimer: ReturnType<typeof setTimeout> | undefined;
let collapseStartTimer: ReturnType<typeof setTimeout> | undefined;
let expandStartTimer: ReturnType<typeof setTimeout> | undefined;
let workspacePushFrame: number | undefined;
let workspacePushCleanupTimer: ReturnType<typeof setTimeout> | undefined;
let workspacePushEffects: WorkspacePushEffect[] = [];

type WorkspaceChildPosition = {
  element: HTMLElement;
  left: number;
  top: number;
};

type WorkspacePushEffect = {
  element: HTMLElement;
  transform: string;
  transition: string;
};

const isCollapsible = computed(() => props.collapsible && workspace != null);
const collapsed = computed(() => isCollapsible.value && workspace!.isExplorerCollapsed(id));
const collapsedOffset = computed(() => (workspace ? workspace.collapsedExplorerOffset(id) : 0));
const collapseContext: ManagementExplorerColumnContext = {
  collapsible: isCollapsible,
  collapsed,
  collapse,
};

provide(MANAGEMENT_EXPLORER_COLUMN_CONTEXT, collapseContext);

watch(
  () => [isCollapsible.value, props.title, props.hasSelection] as const,
  ([collapsible, title, hasSelection]) => {
    if (collapsible) {
      workspace!.registerExplorer({ id, title, hasSelection });
      return;
    }
    workspace?.unregisterExplorer(id);
  },
  { immediate: true },
);

onUnmounted(() => {
  closePreview();
  clearCollapseTimers();
  if (expandStartTimer) clearTimeout(expandStartTimer);
  clearWorkspacePushAnimations();
  workspace?.unregisterExplorer(id);
});

function restore() {
  if (!collapsed.value || collapsing.value) return;
  const workspaceLayoutBeforeRestore = captureWorkspaceLayout();
  tabTransitionOffset.value = collapsedOffset.value;
  const measuredTabWidth = tabTriggerElement.value?.getBoundingClientRect().width ?? 0;
  tabTransitionWidth.value =
    measuredTabWidth > 0 ? measuredTabWidth : MANAGEMENT_COLLAPSED_EXPLORER_LAYOUT.railWidth;
  closePreview();
  expanding.value = true;
  workspace?.toggleExplorer(id);
  void nextTick(() => {
    animateWorkspacePush(workspaceLayoutBeforeRestore);
    // Read the newly restored panel once so its drawer start state is committed
    // before the following frame releases it into the CSS transition.
    void contentElement.value?.offsetWidth;
    expandStartTimer = setTimeout(() => {
      expanding.value = false;
      expandStartTimer = undefined;
    }, 48);
  });
}

function collapse() {
  if (collapsed.value || collapsing.value) return;
  const workspaceLayoutBeforeCollapse = captureWorkspaceLayout();
  if (expandStartTimer) clearTimeout(expandStartTimer);
  expandStartTimer = undefined;
  expanding.value = false;
  clearWorkspacePushAnimations();
  closePreview();
  collapsing.value = true;
  workspace?.toggleExplorer(id);
  void nextTick(() => {
    animateWorkspacePush(workspaceLayoutBeforeCollapse);
    // Keep the departing panel at its former position until the released grid
    // has committed, then let both movements play as one inverse transition.
    void contentElement.value?.offsetWidth;
    collapseStartTimer = setTimeout(() => {
      collapseLeaving.value = true;
      collapseStartTimer = undefined;
      collapseTimer = setTimeout(() => {
        collapseLeaving.value = false;
        collapsing.value = false;
        collapseTimer = undefined;
      }, 240);
    }, 48);
  });
}

function clearCollapseTimers() {
  if (collapseStartTimer) clearTimeout(collapseStartTimer);
  if (collapseTimer) clearTimeout(collapseTimer);
  collapseStartTimer = undefined;
  collapseTimer = undefined;
}

function workspaceGridElement(): HTMLElement | undefined {
  const columnElement = contentElement.value?.parentElement;
  const gridElement = columnElement?.parentElement;
  return gridElement instanceof HTMLElement ? gridElement : undefined;
}

function captureWorkspaceLayout(): WorkspaceChildPosition[] {
  const columnElement = contentElement.value?.parentElement;
  const gridElement = workspaceGridElement();
  if (!columnElement || !gridElement) return [];

  return Array.from(gridElement.children).flatMap((child) => {
    if (
      !(child instanceof HTMLElement) ||
      child === columnElement ||
      getComputedStyle(child).position === 'absolute'
    ) {
      return [];
    }
    const { left, top } = child.getBoundingClientRect();
    return [{ element: child, left, top }];
  });
}

function animateWorkspacePush(previousLayout: WorkspaceChildPosition[]) {
  clearWorkspacePushAnimations();
  for (const { element, left, top } of previousLayout) {
    const nextPosition = element.getBoundingClientRect();
    const translateX = left - nextPosition.left;
    const translateY = top - nextPosition.top;
    if (Math.abs(translateX) < 1 && Math.abs(translateY) < 1) continue;

    workspacePushEffects.push({
      element,
      transform: element.style.transform,
      transition: element.style.transition,
    });
    element.style.transition = 'none';
    element.style.transform = `translate3d(${translateX}px, ${translateY}px, 0)`;
  }
  if (workspacePushEffects.length === 0) return;

  // Commit the inverse transform before releasing it on the next frame.
  void workspaceGridElement()?.offsetWidth;
  workspacePushFrame = requestAnimationFrame(() => {
    for (const { element, transform } of workspacePushEffects) {
      element.style.transition = 'transform 240ms cubic-bezier(0.16, 1, 0.3, 1)';
      element.style.transform = transform;
    }
    workspacePushFrame = undefined;
    workspacePushCleanupTimer = setTimeout(clearWorkspacePushAnimations, 240);
  });
}

function clearWorkspacePushAnimations() {
  if (workspacePushFrame) cancelAnimationFrame(workspacePushFrame);
  if (workspacePushCleanupTimer) clearTimeout(workspacePushCleanupTimer);
  for (const { element, transform, transition } of workspacePushEffects) {
    element.style.transform = transform;
    element.style.transition = transition;
  }
  workspacePushFrame = undefined;
  workspacePushCleanupTimer = undefined;
  workspacePushEffects = [];
}

function closePreview() {
  clearPreviewCloseTimer();
  previewOpen.value = false;
}

function openPreview() {
  if (!collapsed.value) return;
  clearPreviewCloseTimer();
  previewOpen.value = true;
}

function schedulePreviewClose() {
  clearPreviewCloseTimer();
  previewCloseTimer = setTimeout(() => {
    previewOpen.value = false;
    previewCloseTimer = undefined;
  }, 140);
}

function clearPreviewCloseTimer() {
  if (previewCloseTimer) clearTimeout(previewCloseTimer);
  previewCloseTimer = undefined;
}

watch(collapsed, (isCollapsed) => {
  if (!isCollapsed) closePreview();
});
</script>

<template>
  <section
    class="management-explorer-column"
    :class="{
      'management-explorer-column--collapsible': isCollapsible,
      'management-explorer-column--collapsed': collapsed,
      'management-explorer-column--collapsing': collapsing,
      'management-explorer-column--collapse-leaving': collapseLeaving,
      'management-explorer-column--expanding': expanding,
      'management-explorer-column--preview-open': previewOpen,
    }"
    :style="{
      '--muyun-management-collapsed-offset': `${collapsedOffset}px`,
      '--muyun-management-tab-transition-offset': `${tabTransitionOffset}px`,
      '--muyun-management-tab-transition-width': `${tabTransitionWidth}px`,
    }"
    @pointerenter="openPreview"
    @pointerleave="schedulePreviewClose"
  >
    <Transition name="management-explorer-tab">
      <div v-if="collapsed" ref="tabTriggerElement" class="management-explorer-column-tab-trigger">
        <div
          class="management-explorer-column-tab"
          :class="{ 'management-explorer-column-tab--selected': hasSelection }"
        >
          <span class="management-explorer-column-tab-title">{{ title }}</span>
          <UiButton
            class="management-explorer-column-tab-expand"
            icon-name="menu-expand"
            icon-only
            size="small"
            type="text"
            :title="`展开${title}`"
            :aria-label="`展开${title}`"
            @click="restore"
          />
        </div>
      </div>
    </Transition>
    <div ref="contentElement" class="management-explorer-column-content">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.management-explorer-column {
  position: relative;
  display: flex;
  align-self: stretch;
  min-width: 0;
  min-height: 0;
}

.management-explorer-column-content {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  box-shadow: none;
  transition:
    box-shadow 0.18s ease,
    opacity 0.24s ease,
    transform 0.24s cubic-bezier(0.16, 1, 0.3, 1);
}

.management-explorer-column-content :slotted(*) {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}

.management-explorer-column--collapsed {
  position: absolute;
  z-index: 3;
  top: var(--muyun-management-collapsed-offset, 0px);
  left: 0;
  width: calc(var(--muyun-management-collapsed-rail-width, 44px) + var(--muyun-management-column-gap, 12px));
}

.management-explorer-column-tab {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--muyun-management-collapsed-tab-content-gap);
  width: 100%;
  padding: var(--muyun-management-collapsed-tab-padding-block-start) 1px
    var(--muyun-management-collapsed-tab-padding-block-end);
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  color: var(--muyun-text);
  background: var(--muyun-surface);
  transition:
    background 0.16s ease,
    border-color 0.16s ease;
}

.management-explorer-column-tab-title {
  flex: 0 0 auto;
  font-size: var(--muyun-management-collapsed-tab-title-font-size);
  font-weight: 600;
  line-height: var(--muyun-management-collapsed-tab-title-line-height);
  writing-mode: vertical-rl;
  text-orientation: mixed;
}

.management-explorer-column-tab-expand {
  flex: 0 0 auto;
  width: var(--muyun-management-collapsed-tab-icon-hit-area);
  min-width: var(--muyun-management-collapsed-tab-icon-hit-area);
  height: var(--muyun-management-collapsed-tab-icon-hit-area);
  padding: 0;
}

.management-explorer-column-tab-expand :deep(.anticon),
.management-explorer-column-tab-expand :deep(.anticon svg) {
  width: var(--muyun-management-collapsed-tab-icon-size);
  height: var(--muyun-management-collapsed-tab-icon-size);
  font-size: var(--muyun-management-collapsed-tab-icon-size);
}

.management-explorer-column-tab-expand :deep(.anticon) {
  transform: none !important;
}

.management-explorer-column-tab-expand :deep(.anticon svg) {
  display: block;
}

.management-explorer-column-tab-trigger {
  width: var(--muyun-management-collapsed-rail-width, 44px);
  transition:
    opacity 0.12s ease,
    transform 0.12s ease;
}

.management-explorer-column--collapsing .management-explorer-column-tab-trigger {
  opacity: 0;
  pointer-events: none;
  transform: translateX(-4px);
}

.management-explorer-tab-leave-active {
  position: absolute;
  z-index: 4;
  top: var(--muyun-management-tab-transition-offset, 0px);
  left: calc(0px - var(--muyun-management-collapsed-rail-offset, 0px));
  width: var(--muyun-management-tab-transition-width);
  pointer-events: none;
}

.management-explorer-tab-leave-to {
  opacity: 0;
  transform: translateX(-4px);
}

.management-explorer-column-tab:hover,
.management-explorer-column-tab:focus-within {
  border-color: var(--muyun-theme-hover);
  background: var(--muyun-hover);
}

.management-explorer-column-tab--selected {
  border-color: var(--muyun-border-subtle);
  background: var(--muyun-theme-subtle, var(--muyun-hover));
  color: var(--muyun-theme-text, var(--muyun-text));
}

/* The collapsed layout commits first; this overlay then exits as the rest of the workspace backfills. */
.management-explorer-column--collapse-leaving .management-explorer-column-content {
  will-change: opacity, transform;
  opacity: 0;
  transform: translateX(-36px);
  pointer-events: none;
}

/* Restore the panel as a left-edge drawer instead of an abrupt layout replacement. */
.management-explorer-column--expanding .management-explorer-column-content {
  will-change: opacity, transform;
  opacity: 0;
  transform: translateX(-36px);
  pointer-events: none;
}

.management-explorer-column--collapsed .management-explorer-column-content {
  position: absolute;
  top: 0;
  left: 100%;
  visibility: hidden;
  width: var(--muyun-management-explorer-width, 280px);
  height: min(600px, calc(100vh - 120px));
  min-height: 320px;
  border-radius: 8px;
  opacity: 0;
  pointer-events: none;
  transform: translateX(-18px);
  transition:
    opacity 0.18s ease,
    transform 0.18s cubic-bezier(0.2, 0, 0, 1),
    visibility 0s linear 0.18s;
}

.management-explorer-column--collapsed.management-explorer-column--collapsing
  .management-explorer-column-content {
  left: 0;
  visibility: visible;
  opacity: 1;
  pointer-events: none;
  transform: translateX(0);
  transition:
    opacity 0.24s ease,
    transform 0.24s cubic-bezier(0.16, 1, 0.3, 1);
}

.management-explorer-column--collapsed.management-explorer-column--collapsing.management-explorer-column--collapse-leaving
  .management-explorer-column-content {
  opacity: 0;
  transform: translateX(-36px);
}

.management-explorer-column--preview-open .management-explorer-column-content {
  visibility: visible;
  opacity: 1;
  pointer-events: auto;
  transform: translateX(0);
  box-shadow: 0 14px 28px rgb(15 23 42 / 13%);
  transition:
    box-shadow 0.18s ease,
    opacity 0.18s ease,
    transform 0.18s cubic-bezier(0.2, 0, 0, 1),
    visibility 0s;
}
</style>
