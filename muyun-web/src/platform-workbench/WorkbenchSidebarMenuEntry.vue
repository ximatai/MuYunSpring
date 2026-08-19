<script setup lang="ts">
import { computed, ref } from 'vue';
import type { WorkbenchMenuNode } from './menuTreeModel';

defineOptions({ name: 'WorkbenchSidebarMenuEntry' });

const props = defineProps<{
  node: WorkbenchMenuNode;
  mode: 'inline' | 'flyout';
  selected?: boolean;
  selectedPath?: boolean;
  active?: boolean;
}>();

const emit = defineEmits<{
  select: [node: WorkbenchMenuNode];
  openChildren: [node: WorkbenchMenuNode, event: MouseEvent, anchor: HTMLElement];
  toggleChildren: [node: WorkbenchMenuNode, anchor: HTMLElement];
}>();

const entryRoot = ref<HTMLElement>();
const controlsChildren = computed(() => props.mode === 'flyout' && props.node.hasChildren);
const splitActions = computed(() => controlsChildren.value && props.node.navigable);
const structuralGroup = computed(() => props.mode === 'inline' && !props.node.navigable);

function openChildren(event: MouseEvent) {
  if (controlsChildren.value && entryRoot.value) {
    emit('openChildren', props.node, event, entryRoot.value);
  }
}

function toggleChildren() {
  if (controlsChildren.value && entryRoot.value) {
    emit('toggleChildren', props.node, entryRoot.value);
  }
}

function handleMainClick() {
  if (props.node.navigable) {
    emit('select', props.node);
  } else {
    toggleChildren();
  }
}
</script>

<template>
  <div
    v-if="structuralGroup"
    ref="entryRoot"
    class="sidebar-menu-entry sidebar-menu-entry--group"
    :class="{ 'selected-path': selectedPath }"
  >
    <span>{{ node.record.title }}</span>
  </div>

  <div
    v-else-if="splitActions"
    ref="entryRoot"
    class="sidebar-menu-entry sidebar-menu-entry--split navigable branch"
    :class="{ selected, 'selected-path': selectedPath, active }"
    @mouseenter="openChildren"
  >
    <button
      class="sidebar-menu-entry-main navigable"
      :data-testid="`menu-${node.record.id}`"
      type="button"
      :aria-current="selected ? 'page' : undefined"
      @click="emit('select', node)"
    >
      <span>{{ node.record.title }}</span>
    </button>
    <button
      class="sidebar-menu-entry-trigger"
      type="button"
      :aria-label="`${active ? '收起' : '展开'}${node.record.title}下级菜单`"
      :aria-expanded="active"
      aria-controls="workbench-sidebar-submenu-panel"
      @click.stop="toggleChildren"
    >
      <i class="sidebar-menu-entry-indicator" aria-hidden="true" />
    </button>
  </div>

  <button
    v-else
    ref="entryRoot"
    class="sidebar-menu-entry"
    :class="{
      navigable: node.navigable,
      selected,
      'selected-path': selectedPath,
      branch: controlsChildren,
      active,
    }"
    type="button"
    :disabled="!node.navigable && !controlsChildren"
    :aria-current="selected ? 'page' : undefined"
    :data-testid="node.navigable ? `menu-${node.record.id}` : undefined"
    :aria-expanded="controlsChildren ? active : undefined"
    :aria-controls="controlsChildren ? 'workbench-sidebar-submenu-panel' : undefined"
    @mouseenter="openChildren"
    @click="handleMainClick"
  >
    <span>{{ node.record.title }}</span>
    <i v-if="controlsChildren" class="sidebar-menu-entry-indicator" aria-hidden="true" />
  </button>
</template>

<style scoped>
.sidebar-menu-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
  min-height: 29px;
  padding: 5px 8px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--muyun-support-text-muted);
  font: inherit;
  font-size: 12px;
  text-align: left;
}

.sidebar-menu-entry span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-menu-entry.navigable,
.sidebar-menu-entry-main.navigable,
.sidebar-menu-entry-trigger {
  cursor: pointer;
}

.sidebar-menu-entry.navigable:hover {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
}

.sidebar-menu-entry--split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 28px;
  align-items: stretch;
  gap: 0;
  padding: 0;
  overflow: hidden;
}

.sidebar-menu-entry-main,
.sidebar-menu-entry-trigger {
  min-width: 0;
  min-height: 29px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
}

.sidebar-menu-entry-main {
  display: flex;
  align-items: center;
  padding: 5px 8px;
  text-align: left;
}

.sidebar-menu-entry-trigger {
  display: grid;
  place-items: center;
  padding: 0;
  border-radius: 0 5px 5px 0;
}

.sidebar-menu-entry-trigger:hover {
  background: var(--muyun-theme-soft);
}

.sidebar-menu-entry.navigable > span,
.sidebar-menu-entry-main.navigable > span {
  position: relative;
  display: inline-block;
  max-width: 100%;
}

.sidebar-menu-entry.navigable > span::after,
.sidebar-menu-entry-main.navigable > span::after {
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

.sidebar-menu-entry.navigable:hover > span::after,
.sidebar-menu-entry.navigable:focus-visible > span::after,
.sidebar-menu-entry--split.navigable:hover .sidebar-menu-entry-main > span::after,
.sidebar-menu-entry-main.navigable:focus-visible > span::after {
  opacity: 0.62;
  transform: scaleX(1);
}

.sidebar-menu-entry:focus-visible,
.sidebar-menu-entry-main:focus-visible,
.sidebar-menu-entry-trigger:focus-visible {
  outline: 0;
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
  box-shadow: inset 0 0 0 2px var(--muyun-theme-focus);
}

.sidebar-menu-entry.active {
  z-index: 2;
  border-radius: 5px 0 0 5px;
  background: var(--muyun-support-surface);
  color: var(--muyun-theme-base);
  box-shadow: inset var(--workbench-menu-selection-indicator-width, 4px) 0 0 var(--muyun-theme-base);
}

.sidebar-menu-entry-indicator {
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

.sidebar-menu-entry.active .sidebar-menu-entry-indicator {
  opacity: 1;
  transform: rotate(45deg) translate(-1px, -1px);
}

.sidebar-menu-entry--group {
  color: var(--muyun-support-text-muted);
  font-size: 11px;
  letter-spacing: 0.02em;
}

.sidebar-menu-entry.selected {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
}

.sidebar-menu-entry.selected,
.sidebar-menu-entry.selected-path {
  position: relative;
}

.sidebar-menu-entry.selected::before,
.sidebar-menu-entry.selected-path::before {
  position: absolute;
  top: var(--workbench-menu-selection-indicator-inset, 3px);
  bottom: var(--workbench-menu-selection-indicator-inset, 3px);
  left: 0;
  z-index: 1;
  width: var(--workbench-menu-selection-indicator-width, 4px);
  border-radius: 0 999px 999px 0;
  background: var(--muyun-theme-base);
  content: '';
}

.sidebar-menu-entry.selected-path::before {
  background: var(--muyun-theme-hover);
  opacity: 0.58;
}

.sidebar-menu-entry.selected-path {
  background: var(--muyun-theme-soft);
  color: var(--muyun-support-text-muted);
}

.sidebar-menu-entry.active.selected,
.sidebar-menu-entry.active.selected-path {
  background: var(--muyun-support-surface);
  color: var(--muyun-theme-base);
  box-shadow: inset var(--workbench-menu-selection-indicator-width, 4px) 0 0 var(--muyun-theme-base);
}

.sidebar-menu-entry:disabled {
  cursor: default;
}

@media (prefers-reduced-motion: reduce) {
  .sidebar-menu-entry-indicator,
  .sidebar-menu-entry.navigable > span::after,
  .sidebar-menu-entry-main.navigable > span::after {
    transition: none !important;
  }
}
</style>
