<script setup lang="ts">
import { computed } from 'vue';
import type { MenuNavigationTarget, MenuRecord } from '@muyun/web-contracts';
import type { WorkbenchMenuNode } from './menuTreeModel';

defineOptions({ name: 'WorkbenchMenuTree' });

const props = defineProps<{
  node: WorkbenchMenuNode;
  level?: number;
  selectedMenuId?: string;
  selectedPathIds?: string[];
}>();

const emit = defineEmits<{
  selectMenu: [menu: MenuRecord, target: MenuNavigationTarget];
}>();

const depth = computed(() => props.level ?? 0);
const selected = computed(() => props.node.record.id === props.selectedMenuId);
const selectedPath = computed(
  () => !selected.value && props.selectedPathIds?.includes(props.node.record.id) === true,
);

function handleClick() {
  if (props.node.target) {
    emit('selectMenu', props.node.record, props.node.target);
  }
}

function handleChildSelect(menu: MenuRecord, menuTarget: MenuNavigationTarget) {
  emit('selectMenu', menu, menuTarget);
}
</script>

<template>
  <li class="deep-node" :style="{ '--depth': depth }">
    <component
      :is="node.navigable ? 'button' : 'div'"
      class="deep-node-button"
      :class="{
        navigable: node.navigable,
        branch: node.hasChildren,
        selected,
        'selected-path': selectedPath,
      }"
      :type="node.navigable ? 'button' : undefined"
      :aria-current="node.navigable && selected ? 'page' : undefined"
      @click="node.navigable && handleClick()"
    >
      <span>{{ node.record.title }}</span>
    </component>

    <ul v-if="node.hasChildren" class="deep-children">
      <WorkbenchMenuTree
        v-for="child in node.children"
        :key="child.record.id"
        :node="child"
        :level="depth + 1"
        :selected-menu-id="selectedMenuId"
        :selected-path-ids="selectedPathIds"
        @select-menu="handleChildSelect"
      />
    </ul>
  </li>
</template>

<style scoped>
.deep-node,
.deep-children {
  margin: 0;
  padding: 0;
  list-style: none;
}

.deep-node-button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-height: 30px;
  padding: 5px 8px 5px calc(8px + var(--depth) * 12px);
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muyun-support-text-muted);
  font: inherit;
  font-size: 12px;
  text-align: left;
}

.deep-node-button span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.deep-node-button.navigable {
  cursor: pointer;
}

.deep-node-button.navigable > span {
  position: relative;
  display: inline-block;
  max-width: 100%;
}

.deep-node-button.navigable > span::after {
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

.deep-node-button.navigable:hover {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
}

.deep-node-button.navigable:hover > span::after,
.deep-node-button.navigable:focus-visible > span::after {
  opacity: 0.62;
  transform: scaleX(1);
}

.deep-node-button:focus-visible {
  outline: 0;
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
  box-shadow: inset 0 0 0 2px var(--muyun-theme-focus);
}

.deep-node-button.selected {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
}

.deep-node-button.selected,
.deep-node-button.selected-path {
  position: relative;
}

.deep-node-button.selected::before,
.deep-node-button.selected-path::before {
  position: absolute;
  top: 5px;
  bottom: 5px;
  left: 0;
  width: 3px;
  border-radius: 0 999px 999px 0;
  background: var(--muyun-theme-base);
  content: '';
}

.deep-node-button.selected-path::before {
  background: var(--muyun-theme-hover);
  opacity: 0.58;
}

.deep-node-button.selected-path {
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-soft-text);
}
</style>
