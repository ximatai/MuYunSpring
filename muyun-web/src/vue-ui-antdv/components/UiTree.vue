<script setup lang="ts">
import { computed } from 'vue';
import { Tree as ATree } from 'ant-design-vue';
import UiRecordExplorerItem from './UiRecordExplorerItem.vue';
import type { UiRecordInlineAction, UiTreeNode } from '../types';

defineOptions({ name: 'UiTree', inheritAttrs: false });

const props = defineProps<{
  nodes: UiTreeNode[];
  selectedKey?: string;
  expandedKeys?: string[];
}>();

const emit = defineEmits<{
  select: [node: UiTreeNode];
  deselect: [];
  action: [action: UiRecordInlineAction, node: UiTreeNode];
  'update:expandedKeys': [keys: string[]];
}>();

const selectedKeys = computed(() => (props.selectedKey ? [props.selectedKey] : []));

function handleSelect(keys: unknown[]) {
  if (keys.length === 0) {
    emit('deselect');
    return;
  }
  const selected = keys[0];
  if (typeof selected !== 'string') {
    return;
  }
  const node = findNode(props.nodes, selected);
  if (node) {
    emit('select', node);
  }
}

function handleExpand(keys: unknown[]) {
  emit(
    'update:expandedKeys',
    keys.filter((key): key is string => typeof key === 'string'),
  );
}

function handleAction(action: UiRecordInlineAction, nodeKey: string) {
  if (action.disabled) {
    return;
  }
  const node = findNode(props.nodes, nodeKey);
  if (node) {
    emit('action', action, node);
  }
}

function findNode(nodes: UiTreeNode[], key: string): UiTreeNode | undefined {
  for (const node of nodes) {
    if (node.key === key) {
      return node;
    }
    const child = node.children ? findNode(node.children, key) : undefined;
    if (child) {
      return child;
    }
  }
  return undefined;
}
</script>

<template>
  <ATree
    block-node
    :tree-data="nodes"
    :selected-keys="selectedKeys"
    :expanded-keys="expandedKeys"
    :class="$attrs.class"
    :style="$attrs.style"
    @select="handleSelect"
    @expand="handleExpand"
  >
    <template #title="{ key, title, secondary, tag, muted, actions }">
      <UiRecordExplorerItem
        :title="title"
        :secondary="secondary"
        :tag="tag"
        :muted="muted"
        :selected="selectedKeys.includes(key)"
        :actions="actions"
        @action="handleAction($event, key)"
      />
    </template>
  </ATree>
</template>
