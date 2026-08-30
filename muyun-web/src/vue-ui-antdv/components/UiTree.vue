<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { Tree as ATree } from 'ant-design-vue';
import UiRecordExplorerItem from './UiRecordExplorerItem.vue';
import type {
  UiRecordInlineAction,
  UiTreeDragEvent,
  UiTreeDropEvent,
  UiTreeExternalDropEvent,
  UiTreeNode,
} from '../types';

defineOptions({ name: 'UiTree', inheritAttrs: false });

const DEFAULT_MIN_LOADING_DURATION_MS = 300;

const props = withDefaults(
  defineProps<{
    nodes: UiTreeNode[];
    selectedKey?: string;
    expandedKeys?: string[];
    /** Resolves children only when a non-leaf node is expanded. */
    loadChildren?: (node: UiTreeNode) => Promise<void>;
    /** Re-fetches a node's children after it was explicitly collapsed. */
    reloadOnReexpand?: boolean;
    /** Set false only when an empty lazy branch should remain visibly expanded. */
    collapseEmptyLazyBranch?: boolean;
    /** Keeps the built-in lazy-loading indicator visible long enough to be perceived. */
    minLoadingDurationMs?: number;
    /** Replaces the current lazy snapshot, including Ant Tree's loaded-node bookkeeping. */
    reloadKey?: number;
    /** Enables platform-normalized node dragging while keeping adapter events private. */
    draggable?: boolean;
    /** Restricts which nodes can begin a drag without exposing adapter data nodes. */
    canDrag?: (node: UiTreeNode) => boolean;
    /** Restricts built-in drop targets before a page editor applies its own domain rules. */
    allowDrop?: (
      event: Pick<UiTreeDropEvent, 'dragNode' | 'dropNode' | 'dropPosition' | 'dropToGap'>,
    ) => boolean;
    /** Controls externally-originated payloads, for example a metadata tree dropped onto a UI tree. */
    allowExternalDrop?: (event: Omit<UiTreeExternalDropEvent, 'nativeEvent'>) => boolean;
  }>(),
  {
    selectedKey: undefined,
    expandedKeys: undefined,
    loadChildren: undefined,
    collapseEmptyLazyBranch: true,
    minLoadingDurationMs: DEFAULT_MIN_LOADING_DURATION_MS,
    reloadKey: 0,
    draggable: false,
    canDrag: undefined,
    allowDrop: undefined,
    allowExternalDrop: undefined,
  },
);

const emit = defineEmits<{
  select: [node: UiTreeNode];
  deselect: [];
  action: [action: UiRecordInlineAction, node: UiTreeNode];
  'unload-children': [node: UiTreeNode];
  'update:expandedKeys': [keys: string[]];
  'drag-start': [event: UiTreeDragEvent];
  'double-click': [event: UiTreeDragEvent];
  drop: [event: UiTreeDropEvent];
  'external-drop': [event: UiTreeExternalDropEvent];
}>();

const selectedKeys = computed(() => (props.selectedKey ? [props.selectedKey] : []));
const managesLoadedKeys = computed(() => props.reloadOnReexpand || props.collapseEmptyLazyBranch);
const loadedKeys = ref<string[]>([]);
const pendingBranchReleases = new Map<string, ReturnType<typeof setTimeout>>();
const pendingEmptyBranchCollapses = new Map<string, ReturnType<typeof setTimeout>>();
const branchLoadGenerations = new Map<string, number>();
const internalDragging = ref(false);

watch(
  () => props.reloadKey,
  () => {
    pendingBranchReleases.forEach(clearTimeout);
    pendingBranchReleases.clear();
    pendingEmptyBranchCollapses.forEach(clearTimeout);
    pendingEmptyBranchCollapses.clear();
    loadedKeys.value = [];
    branchLoadGenerations.clear();
  },
);

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

function handleExpand(keys: unknown[], event?: { expanded?: boolean; node?: { key?: unknown } }) {
  const expandedKeys = keys.filter((key): key is string => typeof key === 'string');
  emit('update:expandedKeys', expandedKeys);
  const nodeKey = event?.node?.key;
  if (typeof nodeKey !== 'string') return;
  if (event?.expanded) {
    const pendingEmptyCollapse = pendingEmptyBranchCollapses.get(nodeKey);
    if (pendingEmptyCollapse) clearTimeout(pendingEmptyCollapse);
    pendingEmptyBranchCollapses.delete(nodeKey);
  }
  if (!props.reloadOnReexpand) return;
  const pendingRelease = pendingBranchReleases.get(nodeKey);
  if (event?.expanded) {
    if (pendingRelease) clearTimeout(pendingRelease);
    pendingBranchReleases.delete(nodeKey);
    return;
  }
  if (event?.expanded === false) {
    invalidateBranchLoad(nodeKey);
    loadedKeys.value = loadedKeys.value.filter((key) => key !== nodeKey);
    if (pendingRelease) clearTimeout(pendingRelease);
    // Do not mutate tree data while ATree is processing its collapse transition.
    // The small delay also coalesces accidental double-clicks on the switcher.
    pendingBranchReleases.set(
      nodeKey,
      setTimeout(() => {
        pendingBranchReleases.delete(nodeKey);
        loadedKeys.value = loadedKeys.value.filter((key) => key !== nodeKey);
        const node = findNode(props.nodes, nodeKey);
        if (node) {
          emit('unload-children', node);
        }
      }, 180),
    );
  }
}

async function handleLoad(node: { key?: unknown }) {
  if (!props.loadChildren || typeof node.key !== 'string') {
    return;
  }
  const treeNode = findNode(props.nodes, node.key);
  if (
    treeNode &&
    treeNode.isLeaf !== true &&
    (props.reloadOnReexpand ||
      treeNode.children === undefined ||
      (props.collapseEmptyLazyBranch && treeNode.children.length === 0))
  ) {
    const generation = nextBranchLoadGeneration(treeNode.key);
    const startedAt = Date.now();
    await props.loadChildren(treeNode);
    await waitForMinimumDuration(startedAt, props.minLoadingDurationMs);
    if (
      branchLoadGenerations.get(treeNode.key) !== generation ||
      !props.expandedKeys?.includes(treeNode.key)
    ) {
      return;
    }
    if (managesLoadedKeys.value && !loadedKeys.value.includes(treeNode.key)) {
      loadedKeys.value = [...loadedKeys.value, treeNode.key];
    }
    if (props.collapseEmptyLazyBranch && findNode(props.nodes, treeNode.key)?.children?.length === 0) {
      scheduleEmptyBranchCollapse(treeNode.key);
    }
  }
}

function nextBranchLoadGeneration(nodeKey: string) {
  const next = (branchLoadGenerations.get(nodeKey) ?? 0) + 1;
  branchLoadGenerations.set(nodeKey, next);
  return next;
}

function invalidateBranchLoad(nodeKey: string) {
  nextBranchLoadGeneration(nodeKey);
}

onBeforeUnmount(() => {
  pendingBranchReleases.forEach(clearTimeout);
  pendingEmptyBranchCollapses.forEach(clearTimeout);
});

function scheduleEmptyBranchCollapse(nodeKey: string) {
  const pendingCollapse = pendingEmptyBranchCollapses.get(nodeKey);
  if (pendingCollapse) clearTimeout(pendingCollapse);
  // Keep the empty state visible until ATree has completed its expand transition.
  pendingEmptyBranchCollapses.set(
    nodeKey,
    setTimeout(() => {
      pendingEmptyBranchCollapses.delete(nodeKey);
      const node = findNode(props.nodes, nodeKey);
      if (!node || node.children?.length !== 0 || !props.expandedKeys?.includes(nodeKey)) return;
      emit(
        'update:expandedKeys',
        props.expandedKeys.filter((expandedKey) => expandedKey !== nodeKey),
      );
      loadedKeys.value = loadedKeys.value.filter((loadedKey) => loadedKey !== nodeKey);
      if (props.reloadOnReexpand) {
        emit('unload-children', node);
      }
    }, 180),
  );
}

async function waitForMinimumDuration(startedAt: number, minimumDurationMs: number) {
  const remaining = Math.max(0, minimumDurationMs - (Date.now() - startedAt));
  if (remaining > 0) {
    await new Promise<void>((resolve) => setTimeout(resolve, remaining));
  }
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

type AntTreeNode = { key?: unknown; dataRef?: unknown; pos?: unknown };
type AntTreeDropEvent = {
  dragNode?: AntTreeNode;
  node?: AntTreeNode;
  dropPosition?: unknown;
  dropToGap?: unknown;
  event?: unknown;
};

function unwrapNode(node: AntTreeNode): UiTreeNode | undefined {
  if (isUiTreeNode(node.dataRef)) return node.dataRef;
  return typeof node.key === 'string' ? findNode(props.nodes, node.key) : undefined;
}

function isUiTreeNode(value: unknown): value is UiTreeNode {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { key?: unknown }).key === 'string' &&
    typeof (value as { title?: unknown }).title === 'string'
  );
}

function normalizedDropEvent(event: AntTreeDropEvent): UiTreeDropEvent | undefined {
  const dragNode = event.dragNode ? unwrapNode(event.dragNode) : undefined;
  const dropNode = event.node ? unwrapNode(event.node) : undefined;
  if (!dragNode || !dropNode) return undefined;
  const nodePosition = Number(
    String(event.node?.pos ?? '')
      .split('-')
      .at(-1),
  );
  const rawPosition = Number(event.dropPosition);
  const relativePosition =
    Number.isFinite(nodePosition) && Number.isFinite(rawPosition) ? rawPosition - nodePosition : 0;
  return {
    dragNode,
    dropNode,
    dropPosition: relativePosition < 0 ? -1 : relativePosition > 0 ? 1 : 0,
    dropToGap: event.dropToGap === true,
    nativeEvent: event.event as Event | undefined,
  };
}

function handleDragStart(event: { node?: AntTreeNode; event?: unknown }) {
  const node = event.node ? unwrapNode(event.node) : undefined;
  if (node && (!props.canDrag || props.canDrag(node))) {
    internalDragging.value = true;
    emit('drag-start', { node, nativeEvent: event.event as Event | undefined });
  } else {
    (event.event as DragEvent | undefined)?.preventDefault();
  }
}

function handleDragEnd() {
  internalDragging.value = false;
}

function handleTitleDoubleClick(key: unknown, event: MouseEvent) {
  if (typeof key !== 'string') return;
  const node = findNode(props.nodes, key);
  if (node) emit('double-click', { node, nativeEvent: event });
}

/**
 * Ant Tree owns its reorder gesture, but its drag adapter does not consistently preserve a
 * sibling-tree payload (and empty branches are particularly fragile).  The title is therefore
 * also a platform-native drag source.  It intentionally does not set internalDragging: the
 * target must use the public external-drop contract with the real DataTransfer payload.
 */
function handleTitleNativeDragStart(key: unknown, event: DragEvent) {
  if (typeof key !== 'string') return;
  const node = findNode(props.nodes, key);
  if (!node || (props.canDrag && !props.canDrag(node))) {
    event.preventDefault();
    return;
  }
  emit('drag-start', { node, nativeEvent: event });
}

function isNativeDraggable(key: unknown) {
  if (typeof key !== 'string' || !props.draggable) return false;
  const node = findNode(props.nodes, key);
  return Boolean(node && (!props.canDrag || props.canDrag(node)));
}

function handleDrop(event: AntTreeDropEvent) {
  const normalized = normalizedDropEvent(event);
  if (normalized) {
    emit('drop', normalized);
    return;
  }
  // Some Ant Tree versions keep the foreign adapter node opaque.  Do not lose the drop merely
  // because the source cannot be unwrapped by the target tree; the page composer owns that
  // payload through the native DataTransfer (and its drag-session fallback).
  if (!internalDragging.value && props.allowExternalDrop) {
    const dropNode = event.node ? unwrapNode(event.node) : undefined;
    if (!dropNode) return;
    const external = {
      dropNode,
      dropPosition: 0 as const,
      dropToGap: event.dropToGap === true,
    };
    if (props.allowExternalDrop(external)) {
      emit('external-drop', { ...external, nativeEvent: event.event as DragEvent });
    }
  }
}

function allowsDrop(event: AntTreeDropEvent) {
  // Ant Tree reports a node dragged from a sibling tree as a normal drop.  The target tree has
  // no local drag state in that case, so apply its external contract instead of rejecting it with
  // the internal reorder rules.  Its foreign drag node is not guaranteed to be resolvable from
  // this tree's node snapshot, therefore the admission decision deliberately depends only on
  // the target node.
  if (!internalDragging.value && props.allowExternalDrop) {
    const dropNode = event.node ? unwrapNode(event.node) : undefined;
    return dropNode
      ? props.allowExternalDrop({
          dropNode,
          dropPosition: 0,
          dropToGap: event.dropToGap === true,
        })
      : false;
  }
  const normalized = normalizedDropEvent(event);
  if (!normalized) return false;
  return props.allowDrop?.(normalized) ?? true;
}

function externalDropTarget(
  nativeEvent: DragEvent,
): Omit<UiTreeExternalDropEvent, 'nativeEvent'> | undefined {
  const origin = nativeEvent.target as Element | null;
  const target =
    typeof origin?.closest === 'function' ? origin.closest<HTMLElement>('[data-ui-tree-key]') : null;
  const key = target?.dataset.uiTreeKey;
  const dropNode = key ? findNode(props.nodes, key) : undefined;
  if (!target || !dropNode) return undefined;
  const rect = target.getBoundingClientRect();
  const position = rect.height === 0 ? 0 : (nativeEvent.clientY - rect.top) / rect.height;
  return {
    dropNode,
    dropPosition: position < 0.25 ? -1 : position > 0.75 ? 1 : 0,
    dropToGap: position < 0.25 || position > 0.75,
  };
}

function handleExternalDragOver(nativeEvent: DragEvent) {
  if (internalDragging.value) return;
  const target = externalDropTarget(nativeEvent);
  if (target && (props.allowExternalDrop?.(target) ?? false)) {
    nativeEvent.preventDefault();
    if (nativeEvent.dataTransfer) {
      nativeEvent.dataTransfer.dropEffect = 'copy';
    }
  }
}

function handleExternalDrop(nativeEvent: DragEvent) {
  if (internalDragging.value) return;
  const target = externalDropTarget(nativeEvent);
  if (!target || !(props.allowExternalDrop?.(target) ?? false)) return;
  nativeEvent.preventDefault();
  emit('external-drop', { ...target, nativeEvent });
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
  <div
    class="ui-tree"
    :class="$attrs.class"
    :style="$attrs.style"
    @dragover="handleExternalDragOver"
    @drop="handleExternalDrop"
  >
    <ATree
      block-node
      :tree-data="nodes"
      :selected-keys="selectedKeys"
      :expanded-keys="expandedKeys"
      :loaded-keys="managesLoadedKeys ? loadedKeys : undefined"
      :load-data="loadChildren ? handleLoad : undefined"
      :draggable="draggable"
      :allow-drop="draggable ? allowsDrop : undefined"
      @select="handleSelect"
      @expand="handleExpand"
      @dragstart="handleDragStart"
      @dragend="handleDragEnd"
      @drop="handleDrop"
    >
      <template #title="{ key, title, secondary, tag, muted, actions }">
        <div
          :data-ui-tree-key="key"
          :draggable="isNativeDraggable(key)"
          @dblclick.stop="handleTitleDoubleClick(key, $event)"
          @dragstart.stop="handleTitleNativeDragStart(key, $event)"
        >
          <UiRecordExplorerItem
            :title="title"
            :secondary="secondary"
            :tag="tag"
            :muted="muted"
            :selected="selectedKeys.includes(key)"
            :actions="actions"
            @action="handleAction($event, key)"
          />
        </div>
      </template>
    </ATree>
  </div>
</template>
