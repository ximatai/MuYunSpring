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
    <button
      class="deep-node-button"
      :class="{
        navigable: node.navigable,
        branch: node.hasChildren,
        selected,
        'selected-path': selectedPath,
      }"
      type="button"
      :disabled="!node.navigable && !node.hasChildren"
      :aria-current="selected ? 'page' : undefined"
      @click="handleClick"
    >
      <span>{{ node.record.title }}</span>
      <small v-if="node.navigable">打开</small>
    </button>

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
  color: #64748b;
  font: inherit;
  font-size: 12px;
  text-align: left;
}

.deep-node-button span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.deep-node-button small {
  flex: 0 0 auto;
  color: #0f766e;
  font-size: 10px;
}

.deep-node-button.navigable {
  color: #1e293b;
  cursor: pointer;
}

.deep-node-button.branch {
  font-weight: 600;
}

.deep-node-button.navigable:hover {
  background: #eaf5f2;
  color: #0f766e;
}

.deep-node-button.selected {
  background: #e4f2ef;
  color: #0f766e;
  font-weight: 700;
}

.deep-node-button.selected-path {
  color: #334155;
  font-weight: 600;
}

.deep-node-button:disabled {
  cursor: default;
}
</style>
