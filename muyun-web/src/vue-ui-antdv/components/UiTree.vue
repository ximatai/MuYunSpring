<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, getCurrentInstance } from 'vue';
import { Tree as ATree } from 'ant-design-vue';
import UiCheckbox from './UiCheckbox.vue';
import UiRecordExplorerItem from './UiRecordExplorerItem.vue';
import { indexTree, treeChanges, treeSnapshot } from '../treeStructure';
import { useTreeLoader } from '../useTreeLoader';
import { useTreeMotion } from '../useTreeMotion';
import { createUiTreeInstanceId, useUiDragSource, useUiDropTarget } from '../useUiTreeDrag';
import type {
  UiRecordInlineAction,
  UiTreeCheckEvent,
  UiTreeDisplayMode,
  UiTreeNodeEvent,
  UiTreeDropEvent,
  UiTreeLoadRequest,
  UiTreeLoadResult,
  UiTreeLoadIntent,
  UiTreeLoadStrategy,
  UiTreeNode,
  UiTreeBranchState,
  UiDragSource,
  UiDropTarget,
  UiDropPosition,
  UiDropOperation,
  UiTreeChangeReason,
} from '../types';

defineOptions({ name: 'UiTree', inheritAttrs: false });
const props = withDefaults(
  defineProps<{
    nodes: UiTreeNode[];
    displayMode?: UiTreeDisplayMode;
    selectedKey?: string;
    expandedKeys?: string[];
    checkedKeys?: string[];
    checkable?: boolean;
    checkStrictly?: boolean;
    canCheck?: (node: UiTreeNode) => boolean;
    loadChildren?: (node: UiTreeNode, request: UiTreeLoadRequest) => Promise<UiTreeLoadResult>;
    loadStrategy?: UiTreeLoadStrategy;
    branchStates?: Readonly<Record<string, UiTreeBranchState>>;
    reloadOnReexpand?: boolean;
    collapseEmptyLazyBranch?: boolean;
    minLoadingDurationMs?: number;
    reloadKey?: number;
    draggable?: boolean;
    dragPayloadOf?: (node: UiTreeNode) => unknown;
    dragPayloadType?: string;
    dragOperations?: readonly UiDropOperation[];
    dropOperation?: (source: UiDragSource) => UiDropOperation;
    canDrag?: (node: UiTreeNode) => boolean;
    allowDrop?: (event: UiTreeDropEvent) => boolean;
    motion?: boolean;
    motionDurationMs?: number;
    changeReason?: UiTreeChangeReason;
    emptyDescription?: string;
  }>(),
  {
    selectedKey: undefined,
    expandedKeys: undefined,
    checkedKeys: undefined,
    canCheck: undefined,
    loadChildren: undefined,
    dragPayloadOf: undefined,
    dragPayloadType: undefined,
    dropOperation: undefined,
    canDrag: undefined,
    allowDrop: undefined,
    displayMode: 'tree',
    checkable: false,
    checkStrictly: false,
    loadStrategy: 'managed',
    branchStates: () => ({}),
    reloadOnReexpand: false,
    collapseEmptyLazyBranch: true,
    minLoadingDurationMs: 300,
    reloadKey: 0,
    draggable: false,
    dragOperations: () => ['move'],
    motion: true,
    motionDurationMs: 200,
    changeReason: 'interaction',
    emptyDescription: '暂无节点',
  },
);
const emit = defineEmits<{
  select: [node: UiTreeNode];
  deselect: [];
  action: [action: UiRecordInlineAction, node: UiTreeNode];
  'unload-children': [node: UiTreeNode];
  'load-request': [request: UiTreeLoadIntent];
  'update:expandedKeys': [keys: string[]];
  'update:checkedKeys': [keys: string[]];
  check: [event: UiTreeCheckEvent];
  'drag-start': [event: UiDragSource];
  'drag-end': [event: { cancelled: boolean }];
  'double-click': [event: UiTreeNodeEvent];
  drop: [event: UiTreeDropEvent];
}>();
const treeRoot = ref<HTMLElement>();
const instanceId = createUiTreeInstanceId();
const internalExpanded = ref<string[]>([]);
const expanded = computed(() => props.expandedKeys ?? internalExpanded.value);
const internalChecked = ref<string[]>([]);
const checked = computed(() => props.checkedKeys ?? internalChecked.value);
const component = getCurrentInstance();
const internalSelected = ref<string>();
const selected = computed(() => {
  const supplied = component?.vnode.props ?? {};
  const key =
    'selectedKey' in supplied || 'selected-key' in supplied ? props.selectedKey : internalSelected.value;
  return key ? [key] : [];
});
function setSelection(key?: string) {
  internalSelected.value = key;
  if (!key) emit('deselect');
  else {
    const node = entries.value.get(key)?.node;
    if (node) emit('select', node);
  }
}
const asyncEnabled = computed(
  () => props.displayMode === 'tree' && Boolean(props.loadChildren || props.loadStrategy === 'controlled'),
);
const loading = useTreeLoader({
  nodes: () =>
    props.displayMode === 'flat'
      ? props.nodes.map((node) => {
          const flatNode = { ...node };
          delete flatNode.children;
          return flatNode;
        })
      : props.nodes,
  controlled: () => props.loadStrategy === 'controlled',
  states: () => props.branchStates,
  loader: () => (asyncEnabled.value ? props.loadChildren : undefined),
  version: () => props.reloadKey,
  minimumDuration: () => props.minLoadingDurationMs,
  emit: (request) => emit('load-request', request),
});
const treeNodes = loading.nodes;
const entries = computed(() => indexTree(treeNodes.value, props.displayMode === 'flat'));
// Ant's expansion transition retains the old flattened tree. Only use it when structure is unchanged.
const animateExpansion = ref(true);
let previousRendererSnapshot = treeSnapshot(treeNodes.value, props.displayMode === 'flat');
watch([treeNodes, expanded], () => {
  const next = treeSnapshot(treeNodes.value, props.displayMode === 'flat');
  animateExpansion.value = treeChanges(previousRendererSnapshot, next).length === 0;
  previousRendererSnapshot = next;
});
const renderedNodes = computed(() => {
  function render(items: UiTreeNode[]): UiTreeNode[] {
    return items.map((node) => ({
      ...node,
      ...(props.reloadOnReexpand && asyncEnabled.value && stateOf(node.key).status !== 'idle'
        ? { isLeaf: false }
        : {}),
      ...(props.checkable && !isCheckable(node) ? { disableCheckbox: true } : {}),
      ...(node.children ? { children: render(node.children) } : {}),
    }));
  }
  return render(treeNodes.value);
});
const stateOf = loading.stateOf;
function expand(keys: string[]) {
  internalExpanded.value = keys;
  emit('update:expandedKeys', keys);
}
function handleExpand(keys: unknown[], event?: { expanded?: boolean; node?: { key?: unknown } }) {
  expand(keys.filter((key): key is string => typeof key === 'string'));
  const key = event?.node?.key;
  if (typeof key !== 'string') return;
  const node = entries.value.get(key)?.node;
  if (!node || !asyncEnabled.value) return;
  if (event?.expanded && props.loadStrategy === 'controlled') void loading.request(key, 'expand');
  if (event?.expanded === false && props.reloadOnReexpand) {
    if (props.loadStrategy === 'managed') loading.release(key);
    else emit('unload-children', node);
  }
}
watch(
  [expanded, () => props.nodes],
  () => {
    if (!asyncEnabled.value || props.loadStrategy !== 'managed') return;
    expanded.value.forEach((key) => {
      const node = entries.value.get(key)?.node;
      if (node && node.isLeaf !== true && (node.children === undefined || props.reloadOnReexpand))
        void loading.request(key, 'expand');
    });
  },
  { immediate: true },
);
const emptyTimers = new Map<string, ReturnType<typeof setTimeout>>();
watch([loading.states, expanded], () => {
  emptyTimers.forEach(clearTimeout);
  emptyTimers.clear();
  if (!props.collapseEmptyLazyBranch) return;
  expanded.value.forEach((key) => {
    if (
      stateOf(key).status !== 'loaded' ||
      stateOf(key).hasMore ||
      entries.value.get(key)?.node.children?.length !== 0
    )
      return;
    emptyTimers.set(
      key,
      setTimeout(() => {
        emptyTimers.delete(key);
        handleExpand(
          expanded.value.filter((id) => id !== key),
          { expanded: false, node: { key } },
        );
      }, 180),
    );
  });
});
onBeforeUnmount(() => emptyTimers.forEach(clearTimeout));
function selectNode(node: UiTreeNode) {
  if (node.disabled || draggingKey.value) return;
  setSelection(selected.value.includes(node.key) ? undefined : node.key);
}
function handleSelect(keys: unknown[]) {
  if (draggingKey.value) return;
  if (!keys.length) {
    setSelection();
    return;
  }
  const node = entries.value.get(String(keys[0]))?.node;
  if (node && !node.disabled) setSelection(node.key);
}
function isCheckable(node: UiTreeNode) {
  return props.checkable && !node.disabled && (props.canCheck?.(node) ?? true);
}
function normalizeKeys(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((key): key is string => typeof key === 'string') : [];
}
function updateCheck(
  node: UiTreeNode,
  value: boolean,
  keys: string[],
  half: string[] = [],
  nativeEvent?: Event,
) {
  internalChecked.value = keys;
  emit('update:checkedKeys', keys);
  emit('check', { node, checked: value, checkedKeys: keys, halfCheckedKeys: half, nativeEvent });
}
function handleTreeCheck(
  value: unknown,
  event: { node?: { key?: unknown }; checked?: boolean; halfCheckedKeys?: unknown; nativeEvent?: Event },
) {
  const node = entries.value.get(String(event.node?.key))?.node;
  if (!node || !isCheckable(node)) return;
  const object = value as { checked?: unknown; halfChecked?: unknown };
  const keys = normalizeKeys(Array.isArray(value) ? value : object?.checked);
  updateCheck(
    node,
    event.checked ?? keys.includes(node.key),
    keys,
    normalizeKeys(object?.halfChecked ?? event.halfCheckedKeys),
    event.nativeEvent,
  );
}
function handleFlatCheck(node: UiTreeNode, value: boolean) {
  if (!isCheckable(node)) return;
  updateCheck(
    node,
    value,
    value ? [...new Set([...checked.value, node.key])] : checked.value.filter((key) => key !== node.key),
  );
}
function action(item: UiRecordInlineAction, key: string) {
  const node = entries.value.get(key)?.node;
  if (node && !item.disabled) emit('action', item, node);
}
function canStartDrag(key: string) {
  const node = entries.value.get(key)?.node;
  return !!node && !node.disabled && props.draggable && (props.canDrag?.(node) ?? true);
}
function sourceOf(key: string): UiDragSource | undefined {
  const node = entries.value.get(key)?.node;
  if (!node || !canStartDrag(key)) return;
  return {
    instanceId,
    node,
    operations: props.dragOperations,
    payload: props.dragPayloadOf?.(node),
    payloadType: props.dragPayloadType,
  };
}
const { begin, draggingKey } = useUiDragSource(treeRoot, instanceId, sourceOf, {
  start: (source) => emit('drag-start', source),
  end: (event) => emit('drag-end', { cancelled: event }),
});
const operation = (source: UiDragSource) => props.dropOperation?.(source) ?? source.operations[0] ?? 'move';
function allow(event: UiTreeDropEvent) {
  if (event.source.instanceId === instanceId && event.target.kind === 'node') {
    let key: string | undefined = event.target.node.key;
    while (key) {
      if (key === event.source.node.key) return false;
      key = entries.value.get(key)?.parent;
    }
  }
  return props.allowDrop?.(event) ?? false;
}
function resolveTarget(
  origin: Element,
  y: number,
  position?: UiDropPosition,
  source?: UiDragSource,
): UiDropTarget | undefined {
  const element =
    origin.closest<HTMLElement>('[data-ui-drop-key]') ??
    origin.closest('.ant-tree-treenode')?.querySelector<HTMLElement>('[data-ui-drop-key]');
  const node = element?.dataset.uiDropKey ? entries.value.get(element.dataset.uiDropKey)?.node : undefined;
  if (!node || !element) return { instanceId, kind: 'root', position: 'inside' };
  const rect = element.getBoundingClientRect();
  const fraction = rect.height ? (y - rect.top) / rect.height : 0.5;
  const requested = position ?? (fraction < 0.25 ? 'before' : fraction > 0.75 ? 'after' : 'inside');
  const target: UiDropTarget = { instanceId, kind: 'node', node, position: requested };
  if (
    requested === 'inside' &&
    (props.displayMode === 'flat' ||
      (!position && source && !allow({ source, target, operation: operation(source) })))
  ) {
    return { ...target, position: fraction < 0.5 ? 'before' : 'after' };
  }
  return target;
}
const { hovered, rejected } = useUiDropTarget(treeRoot, {
  resolve: resolveTarget,
  allow,
  operation,
  drop: (event) => emit('drop', event),
});
function dragClasses(key: string) {
  const target = hovered.value;
  const active = target?.kind === 'node' && target.node.key === key;
  return {
    'ui-tree-node--draggable': canStartDrag(key),
    'ui-tree-node--dragging': draggingKey.value === key,
    'ui-tree-node--drop-before': active && !rejected.value && target.position === 'before',
    'ui-tree-node--drop-after': active && !rejected.value && target.position === 'after',
    'ui-tree-node--drop-inside': active && !rejected.value && target.position === 'inside',
    'ui-tree-node--drop-rejected': active && rejected.value,
  };
}
function keydown(node: UiTreeNode, event: KeyboardEvent) {
  if (event.target !== event.currentTarget) return;
  if (event.key === ' ' && canStartDrag(node.key)) {
    begin(node.key, event);
    return;
  }
  if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
    event.preventDefault();
    event.stopPropagation();
    const rows = [...(treeRoot.value?.querySelectorAll<HTMLElement>('[data-ui-tree-key]') ?? [])].filter(
      (row) => !entries.value.get(row.dataset.uiTreeKey!)?.node.disabled,
    );
    const index = rows.findIndex((row) => row.dataset.uiTreeKey === node.key);
    const focusKey = (key?: string) => rows.find((row) => row.dataset.uiTreeKey === key)?.focus();
    if (event.key === 'ArrowUp') rows[Math.max(0, index - 1)]?.focus();
    if (event.key === 'ArrowDown') rows[Math.min(rows.length - 1, index + 1)]?.focus();
    if (event.key === 'Home') rows[0]?.focus();
    if (event.key === 'End') rows.at(-1)?.focus();
    if (props.displayMode === 'tree' && event.key === 'ArrowRight') {
      if (!expanded.value.includes(node.key) && (node.children?.length || node.isLeaf === false))
        handleExpand([...expanded.value, node.key], { expanded: true, node: { key: node.key } });
      else focusKey(node.children?.[0]?.key);
    }
    if (props.displayMode === 'tree' && event.key === 'ArrowLeft') {
      if (expanded.value.includes(node.key))
        handleExpand(
          expanded.value.filter((key) => key !== node.key),
          { expanded: false, node: { key: node.key } },
        );
      else focusKey(entries.value.get(node.key)?.parent);
    }
    return;
  }
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    selectNode(node);
  }
}
function doubleClick(key: string, event: MouseEvent) {
  const node = entries.value.get(key)?.node;
  if (node && !node.disabled && !draggingKey.value) emit('double-click', { node, nativeEvent: event });
}
const prefersReducedMotion = useTreeMotion(treeRoot, {
  nodes: () => treeNodes.value,
  flat: () => props.displayMode === 'flat',
  enabled: () => props.motion,
  duration: () => props.motionDurationMs,
  reason: () => props.changeReason,
});
defineExpose({
  refreshNode: (key: string) => loading.request(key, 'refresh'),
  loadMore: (key: string) => loading.request(key, 'load-more'),
  retryNode: loading.retry,
});
</script>

<template>
  <div
    ref="treeRoot"
    class="ui-tree"
    :class="$attrs.class"
    :style="[$attrs.style, { '--ui-tree-motion-duration': `${motionDurationMs}ms` }]"
    @dragstart.prevent
  >
    <p v-if="draggable" class="ui-tree__instructions">
      空格开始拖动，↑↓选择目标，←→选择前后或内部，回车放置，Esc 取消。
    </p>
    <ul v-if="displayMode === 'flat'" class="ui-tree__flat-list" role="list">
      <li
        v-for="node in treeNodes"
        :key="node.key"
        class="ui-tree-node"
        :class="dragClasses(node.key)"
        :data-ui-tree-key="node.key"
        :data-ui-drop-key="node.key"
        :tabindex="node.disabled ? -1 : 0"
        :aria-disabled="node.disabled || undefined"
        @keydown="keydown(node, $event)"
        @mousedown="begin(node.key, $event)"
        @dblclick.stop="doubleClick(node.key, $event)"
      >
        <span v-if="checkable" class="ui-tree-flat-checkbox" @mousedown.stop @click.stop>
          <UiCheckbox
            :checked="checked.includes(node.key)"
            :disabled="!isCheckable(node)"
            :aria-label="`选择${node.title}`"
            @update:checked="handleFlatCheck(node, $event)"
          />
        </span>
        <UiRecordExplorerItem
          :title="node.title"
          :secondary="node.secondary"
          :tag="node.tag"
          :muted="node.muted || node.disabled"
          :selected="selected.includes(node.key)"
          :actions="node.actions"
          :clickable="!node.disabled"
          @click="selectNode(node)"
          @action="action($event, node.key)"
        />
      </li>
    </ul>
    <ATree
      v-else
      block-node
      :motion="motion && animateExpansion && !prefersReducedMotion ? undefined : null"
      :tree-data="renderedNodes"
      :selected-keys="selected"
      :expanded-keys="expanded"
      :checked-keys="checked"
      :checkable="checkable"
      :check-strictly="checkStrictly"
      :draggable="false"
      @select="handleSelect"
      @expand="handleExpand"
      @check="handleTreeCheck"
    >
      <template #title="{ key, title, secondary, tag, muted, disabled, actions }">
        <div
          class="ui-tree-node"
          :class="dragClasses(key)"
          :data-ui-tree-key="key"
          :data-ui-drop-key="key"
          :tabindex="disabled ? -1 : 0"
          @mousedown="begin(key, $event)"
          @keydown="keydown(entries.get(key)!.node, $event)"
          @dblclick.stop="doubleClick(key, $event)"
        >
          <UiRecordExplorerItem
            :title="title"
            :secondary="secondary"
            :tag="tag"
            :muted="muted || disabled"
            :selected="selected.includes(key)"
            :actions="actions"
            :clickable="!disabled"
            @action="action($event, key)"
          />
          <div
            v-if="asyncEnabled && stateOf(key).status !== 'idle'"
            class="ui-tree__branch"
            @mousedown.stop
            @click.stop
            @keydown.stop
          >
            <span v-if="stateOf(key).status === 'loading'" role="status">加载中…</span>
            <template v-else-if="stateOf(key).status === 'error'">
              <span role="alert">{{ stateOf(key).error }}</span>
              <button type="button" @click="loading.retry(key)">重试</button>
            </template>
            <span v-else-if="entries.get(key)?.node.children?.length === 0 && !stateOf(key).hasMore"
              >暂无子节点</span
            >
            <button
              v-if="
                stateOf(key).hasMore && stateOf(key).status !== 'loading' && stateOf(key).status !== 'error'
              "
              type="button"
              @click="loading.request(key, 'load-more')"
            >
              加载更多
            </button>
          </div>
        </div>
      </template>
    </ATree>
    <div
      v-if="allowDrop || treeNodes.length === 0"
      class="ui-tree__root-target"
      data-ui-drop-root
      tabindex="0"
      :class="{
        'ui-tree-node--drop-inside': hovered?.kind === 'root' && !rejected,
        'ui-tree-node--drop-rejected': hovered?.kind === 'root' && rejected,
      }"
    >
      {{ treeNodes.length === 0 ? emptyDescription : '根层末尾' }}
    </div>
    <span class="ui-tree__instructions" aria-live="polite">{{
      hovered
        ? rejected
          ? '不可放置'
          : `可以放置：${hovered.position === 'inside' ? '内部' : hovered.position === 'before' ? '之前' : '之后'}`
        : ''
    }}</span>
  </div>
</template>
<style scoped>
.ui-tree {
  position: relative;
}
.ui-tree__instructions {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
.ui-tree__root-target {
  padding: 6px;
  min-height: 28px;
  color: var(--muyun-text-muted);
}
.ui-tree-node--drop-rejected {
  cursor: not-allowed;
  outline: 1px dashed var(--muyun-danger-text);
}
.ui-tree-node:focus-visible,
.ui-tree__root-target:focus-visible {
  outline: 2px solid var(--muyun-primary);
}
.ui-tree__branch {
  display: flex;
  gap: 8px;
  font-size: 12px;
}

/* Tree and Flat share the row component's selection and hover surface. */
.ui-tree :deep(.ant-tree-node-content-wrapper.ant-tree-node-selected),
.ui-tree :deep(.ant-tree-node-content-wrapper:hover) {
  background: transparent;
}

.ui-tree-node {
  position: relative;
}

.ui-tree__flat-list {
  display: grid;
  gap: 2px;
  min-width: 0;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.ui-tree__flat-list > li {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
  min-height: 28px;
  padding: 2px 4px;
  border-radius: 5px;
  transition:
    background-color 140ms ease,
    box-shadow 140ms ease,
    opacity 140ms ease;
}

.ui-tree-node::before,
.ui-tree-node::after {
  position: absolute;
  right: 4px;
  left: 4px;
  height: 2px;
  border-radius: 2px;
  background: var(--muyun-primary);
  box-shadow: 0 0 0 2px rgb(22 119 255 / 10%);
  content: '';
  opacity: 0;
  pointer-events: none;
  transition: opacity 120ms ease;
}

.ui-tree-node--drop-before::before {
  top: -2px;
  opacity: 1;
}

.ui-tree-node--drop-after::after {
  bottom: -2px;
  opacity: 1;
}

.ui-tree-node--draggable {
  cursor: grab;
  user-select: none;
}

.ui-tree-node--draggable :deep(.ui-record-explorer-item) {
  cursor: inherit;
}

.ui-tree-node--draggable:active {
  cursor: grabbing;
}

.ui-tree-node--dragging {
  opacity: 0.55;
  outline: 1px dashed var(--muyun-primary, #1677ff);
  outline-offset: -1px;
  background: color-mix(in srgb, var(--muyun-primary, #1677ff) 12%, transparent);
}

.ui-tree-node--drop-inside {
  background: color-mix(in srgb, var(--muyun-primary) 7%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--muyun-primary) 70%, transparent);
}

.ui-tree__flat-list > li:focus-within,
.ui-tree__flat-list > li:hover {
  background: var(--muyun-hover);
}

.ui-tree-flat-checkbox {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 28px;
  margin-inline-end: 4px;
}

.ui-tree-flat-checkbox :deep(.ant-checkbox-wrapper) {
  margin-inline-end: 0;
}

.ui-tree__flat-list > li > :deep(.ui-record-explorer-item) {
  flex: 1 1 auto;
  min-width: 0;
}

@media (prefers-reduced-motion: reduce) {
  .ui-tree__flat-list > li {
    transition: none;
  }
}
</style>
