<script setup lang="ts">
import { computed, onBeforeUnmount, onBeforeUpdate, onMounted, onUpdated, ref, watch } from 'vue';
import { Tree as ATree } from 'ant-design-vue';
import UiCheckbox from './UiCheckbox.vue';
import UiRecordExplorerItem from './UiRecordExplorerItem.vue';
import type {
  UiRecordInlineAction,
  UiTreeCheckEvent,
  UiTreeDisplayMode,
  UiTreeDragEvent,
  UiTreeDropEvent,
  UiTreeExternalDropEvent,
  UiTreeLoadRequest,
  UiTreeLoadResult,
  UiTreeLoadReason,
  UiTreeLoadStrategy,
  UiTreeNode,
} from '../types';

defineOptions({ name: 'UiTree', inheritAttrs: false });

const DEFAULT_MIN_LOADING_DURATION_MS = 300;

type UiTreePointerDragSession = {
  owner: symbol;
  payload?: unknown;
  payloadType?: string;
};

let activePointerDragSession: UiTreePointerDragSession | undefined;

const props = withDefaults(
  defineProps<{
    nodes: UiTreeNode[];
    displayMode?: UiTreeDisplayMode;
    selectedKey?: string;
    expandedKeys?: string[];
    checkedKeys?: string[];
    /** Shows checkboxes. Tree mode delegates cascade behavior to the tree renderer. */
    checkable?: boolean;
    /** Tree-only strict checkbox mode; flat mode is always independent. */
    checkStrictly?: boolean;
    /** Restricts which nodes can be checked without leaking renderer-specific checkbox state. */
    canCheck?: (node: UiTreeNode) => boolean;
    /** Resolves children only when a non-leaf node is expanded; legacy loaders may return nothing. */
    loadChildren?: (
      node: UiTreeNode,
      request?: UiTreeLoadRequest,
    ) => Promise<void | UiTreeLoadResult> | void | UiTreeLoadResult;
    /** Managed applies UiTreeLoadResult; controlled emits load-request and leaves data ownership to the parent. */
    loadStrategy?: UiTreeLoadStrategy;
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
    /** Adds a native title drag source for consumers that need a DataTransfer payload. */
    nativeDragSource?: boolean;
    /** Creates a serializable payload for cross-instance native drops. */
    dragPayloadOf?: (node: UiTreeNode) => unknown;
    /** MIME type used by dragPayloadOf. */
    dragPayloadType?: string;
    /** Restricts which nodes can begin a drag without exposing adapter data nodes. */
    canDrag?: (node: UiTreeNode) => boolean;
    /** Restricts built-in drop targets before a page editor applies its own domain rules. */
    allowDrop?: (
      event: Pick<UiTreeDropEvent, 'dragNode' | 'dropNode' | 'dropPosition' | 'dropToGap'>,
    ) => boolean;
    /** Controls externally-originated payloads, for example a metadata tree dropped onto a UI tree. */
    allowExternalDrop?: (event: Omit<UiTreeExternalDropEvent, 'nativeEvent'>) => boolean;
    /** Enables structural FLIP motion for both renderers. */
    motion?: boolean;
    /** Duration of structural movement in milliseconds. */
    motionDurationMs?: number;
  }>(),
  {
    displayMode: 'tree',
    selectedKey: undefined,
    expandedKeys: undefined,
    checkedKeys: undefined,
    checkable: false,
    checkStrictly: false,
    canCheck: undefined,
    loadChildren: undefined,
    loadStrategy: 'managed',
    collapseEmptyLazyBranch: true,
    minLoadingDurationMs: DEFAULT_MIN_LOADING_DURATION_MS,
    reloadKey: 0,
    draggable: false,
    nativeDragSource: false,
    dragPayloadOf: undefined,
    dragPayloadType: 'application/x-muyun-ui-tree',
    canDrag: undefined,
    allowDrop: undefined,
    allowExternalDrop: undefined,
    motion: true,
    motionDurationMs: 200,
  },
);

const emit = defineEmits<{
  select: [node: UiTreeNode];
  deselect: [];
  action: [action: UiRecordInlineAction, node: UiTreeNode];
  'unload-children': [node: UiTreeNode];
  'load-request': [request: UiTreeLoadRequest];
  'update:expandedKeys': [keys: string[]];
  'update:checkedKeys': [keys: string[]];
  check: [event: UiTreeCheckEvent];
  'drag-start': [event: UiTreeDragEvent];
  'double-click': [event: UiTreeDragEvent];
  drop: [event: UiTreeDropEvent];
  'external-drop': [event: UiTreeExternalDropEvent];
}>();

const selectedKeys = computed(() => (props.selectedKey ? [props.selectedKey] : []));
const internalCheckedKeys = ref<string[]>([]);
const effectiveCheckedKeys = computed(() => props.checkedKeys ?? internalCheckedKeys.value);
const managedNodes = ref<UiTreeNode[]>();
const renderNodes = computed(() => managedNodes.value ?? props.nodes);
const flatNodes = computed<UiFlatTreeNode[]>(() => flattenNodes(renderNodes.value));
const managesLoadedKeys = computed(
  () => props.loadStrategy === 'managed' || props.reloadOnReexpand || props.collapseEmptyLazyBranch,
);
const loadedKeys = ref<string[]>([]);
const pendingBranchReleases = new Map<string, ReturnType<typeof setTimeout>>();
const pendingEmptyBranchCollapses = new Map<string, ReturnType<typeof setTimeout>>();
const branchLoadGenerations = new Map<string, number>();
const branchAbortControllers = new Map<string, AbortController>();
const loadCursors = new Map<string, string>();
let loadRequestSequence = 0;
const internalDragging = ref(false);
const nativeTitleDraggingKey = ref<string>();
const treeRoot = ref<HTMLElement>();
const pointerDragging = ref<{
  key: string;
  startX: number;
  startY: number;
  active: boolean;
  drop?: Omit<UiTreeDropEvent, 'nativeEvent'>;
}>();
const treeInstanceId = Symbol('ui-tree-instance');
const previousTreeLayout = new Map<string, DOMRect>();
const structuralMotionEnabled = computed(() => props.motion && !windowPrefersReducedMotion());

function windowPrefersReducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
}

function captureTreeLayout() {
  previousTreeLayout.clear();
  if (props.displayMode !== 'tree' || !structuralMotionEnabled.value) return;
  treeRoot.value?.querySelectorAll<HTMLElement>('[data-ui-tree-key]').forEach((element) => {
    const key = element.dataset.uiTreeKey;
    if (key && !previousTreeLayout.has(key)) previousTreeLayout.set(key, element.getBoundingClientRect());
  });
}

function animateTreeLayout() {
  if (props.displayMode !== 'tree' || !structuralMotionEnabled.value || previousTreeLayout.size === 0) {
    previousTreeLayout.clear();
    return;
  }
  const animate = () => {
    treeRoot.value?.querySelectorAll<HTMLElement>('[data-ui-tree-key]').forEach((element) => {
      const key = element.dataset.uiTreeKey;
      const previous = key ? previousTreeLayout.get(key) : undefined;
      if (!previous) return;
      const current = element.getBoundingClientRect();
      const x = previous.left - current.left;
      const y = previous.top - current.top;
      if (Math.abs(x) < 1 && Math.abs(y) < 1) return;
      if (typeof element.animate === 'function') {
        element.animate(
          [
            { transform: `translate(${x}px, ${y}px)`, opacity: 0.78 },
            { transform: 'translate(0, 0)', opacity: 1 },
          ],
          { duration: props.motionDurationMs, easing: 'cubic-bezier(0.2, 0, 0, 1)' },
        );
      }
    });
    previousTreeLayout.clear();
  };
  if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
    window.requestAnimationFrame(animate);
  } else {
    animate();
  }
}

onBeforeUpdate(captureTreeLayout);
onUpdated(animateTreeLayout);

watch(
  () => props.checkedKeys,
  (keys) => {
    if (keys) internalCheckedKeys.value = [...keys];
  },
  { deep: true, immediate: true },
);

watch(
  () => props.reloadKey,
  () => {
    pendingBranchReleases.forEach(clearTimeout);
    pendingBranchReleases.clear();
    pendingEmptyBranchCollapses.forEach(clearTimeout);
    pendingEmptyBranchCollapses.clear();
    branchAbortControllers.forEach((controller) => controller.abort());
    branchAbortControllers.clear();
    loadedKeys.value = [];
    branchLoadGenerations.clear();
    loadCursors.clear();
    managedNodes.value = undefined;
  },
);

watch(
  () => props.nodes,
  (nodes) => {
    // A parent update is authoritative. A managed result remains local only until the parent
    // publishes its next snapshot, which keeps legacy loaders and controlled mode predictable.
    managedNodes.value = undefined;
    if (nodes.length === 0) loadedKeys.value = [];
  },
  { deep: true },
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
  const node = findNode(renderNodes.value, selected);
  if (node) {
    emit('select', node);
  }
}

function normalizeKeyList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((key): key is string => typeof key === 'string') : [];
}

function updateCheckedState(
  node: UiTreeNode,
  checked: boolean,
  checkedKeys: string[],
  halfCheckedKeys: string[] = [],
  nativeEvent?: Event,
) {
  const nextCheckedKeys = [...checkedKeys];
  const nextHalfCheckedKeys = [...halfCheckedKeys];
  internalCheckedKeys.value = nextCheckedKeys;
  emit('update:checkedKeys', nextCheckedKeys);
  emit('check', {
    node,
    checked,
    checkedKeys: nextCheckedKeys,
    halfCheckedKeys: nextHalfCheckedKeys,
    nativeEvent,
  });
}

function handleTreeCheck(
  value: unknown,
  event?: {
    node?: AntTreeNode;
    checked?: unknown;
    halfCheckedKeys?: unknown;
    nativeEvent?: unknown;
  },
) {
  const node = event?.node ? unwrapNode(event.node) : undefined;
  if (!node || (props.canCheck && !props.canCheck(node))) return;
  const checkedKeys = Array.isArray(value)
    ? normalizeKeyList(value)
    : normalizeKeyList((value as { checked?: unknown } | undefined)?.checked);
  const halfCheckedKeys = normalizeKeyList(
    (value as { halfChecked?: unknown } | undefined)?.halfChecked ?? event?.halfCheckedKeys,
  );
  const checked = typeof event?.checked === 'boolean' ? event.checked : checkedKeys.includes(node.key);
  updateCheckedState(node, checked, checkedKeys, halfCheckedKeys, event?.nativeEvent as Event | undefined);
}

function isCheckable(node: UiTreeNode) {
  return props.checkable && !node.disabled && (!props.canCheck || props.canCheck(node));
}

function handleFlatCheck(node: UiTreeNode, checked: boolean) {
  if (!isCheckable(node)) return;
  const checkedKeys = new Set(effectiveCheckedKeys.value);
  if (checked) checkedKeys.add(node.key);
  else checkedKeys.delete(node.key);
  updateCheckedState(node, checked, [...checkedKeys]);
}

function handleFlatSelect(node: UiTreeNode) {
  if (node.disabled) return;
  if (selectedKeys.value.includes(node.key)) emit('deselect');
  else emit('select', node);
}

interface UiFlatTreeNode {
  node: UiTreeNode;
  depth: number;
}

type UiRenderTreeNode = UiTreeNode & {
  disableCheckbox?: boolean;
  children?: UiRenderTreeNode[];
};

function flattenNodes(nodes: UiTreeNode[], depth = 0): UiFlatTreeNode[] {
  return nodes.flatMap((node) => [
    { node, depth },
    ...(node.children ? flattenNodes(node.children, depth + 1) : []),
  ]);
}

function treeRenderNodes(nodes: UiTreeNode[]): UiRenderTreeNode[] {
  return nodes.map((node) => ({
    ...node,
    ...(props.checkable && !isCheckable(node) ? { disableCheckbox: true } : {}),
    ...(node.children ? { children: treeRenderNodes(node.children) } : {}),
  }));
}

const renderedTreeNodes = computed(() => treeRenderNodes(renderNodes.value));

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
        const node = findNode(renderNodes.value, nodeKey);
        if (node) {
          emit('unload-children', node);
        }
      }, 180),
    );
  }
}

async function handleLoad(node: { key?: unknown }) {
  if ((!props.loadChildren && props.loadStrategy !== 'controlled') || typeof node.key !== 'string') {
    return;
  }
  const treeNode = findNode(renderNodes.value, node.key);
  if (!treeNode || treeNode.isLeaf === true) return;
  if (
    props.reloadOnReexpand ||
    treeNode.children === undefined ||
    (props.collapseEmptyLazyBranch && treeNode.children.length === 0)
  ) {
    await loadTreeNode(treeNode, 'expand');
  }
}

async function loadTreeNode(treeNode: UiTreeNode, reason: UiTreeLoadReason) {
  const generation = nextBranchLoadGeneration(treeNode.key);
  branchAbortControllers.get(treeNode.key)?.abort();
  const controller = new AbortController();
  branchAbortControllers.set(treeNode.key, controller);
  if (reason === 'refresh') loadCursors.delete(treeNode.key);
  const request: UiTreeLoadRequest = {
    node: treeNode,
    reason,
    ...(reason === 'load-more' && loadCursors.get(treeNode.key)
      ? { cursor: loadCursors.get(treeNode.key) }
      : {}),
    requestId: `ui-tree-load-${++loadRequestSequence}`,
    signal: controller.signal,
  };
  const startedAt = Date.now();
  try {
    emit('load-request', request);
    const result = props.loadChildren
      ? await (props.loadChildren.length >= 2
          ? props.loadChildren(treeNode, request)
          : props.loadChildren(treeNode))
      : undefined;
    await waitForMinimumDuration(startedAt, props.minLoadingDurationMs);
    if (
      branchLoadGenerations.get(treeNode.key) !== generation ||
      controller.signal.aborted ||
      (reason === 'expand' && props.expandedKeys !== undefined && !props.expandedKeys.includes(treeNode.key))
    ) {
      return;
    }
    if (props.loadStrategy === 'managed' && isUiTreeLoadResult(result)) {
      managedNodes.value = applyLoadResult(renderNodes.value, treeNode.key, result);
      if (result.nextCursor !== undefined) loadCursors.set(treeNode.key, result.nextCursor);
    }
    if (reason === 'expand' && managesLoadedKeys.value && !loadedKeys.value.includes(treeNode.key)) {
      loadedKeys.value = [...loadedKeys.value, treeNode.key];
    }
    // Controlled mode cannot infer whether an empty result is final: the parent may still be
    // resolving the request and will publish the authoritative children snapshot later.
    if (
      reason === 'expand' &&
      props.loadStrategy === 'managed' &&
      props.collapseEmptyLazyBranch &&
      findNode(renderNodes.value, treeNode.key)?.children?.length === 0
    ) {
      scheduleEmptyBranchCollapse(treeNode.key);
    }
  } finally {
    if (branchAbortControllers.get(treeNode.key) === controller) {
      branchAbortControllers.delete(treeNode.key);
    }
  }
}

async function refreshNode(nodeKey: string) {
  const node = findNode(renderNodes.value, nodeKey);
  if (node && canRequestLoad()) await loadTreeNode(node, 'refresh');
}

async function loadMore(nodeKey: string) {
  const node = findNode(renderNodes.value, nodeKey);
  if (node && canRequestLoad()) await loadTreeNode(node, 'load-more');
}

function canRequestLoad() {
  return Boolean(props.loadChildren) || props.loadStrategy === 'controlled';
}

function isUiTreeLoadResult(value: unknown): value is UiTreeLoadResult {
  return (
    typeof value === 'object' &&
    value !== null &&
    ((value as { mode?: unknown }).mode === 'replace' || (value as { mode?: unknown }).mode === 'append') &&
    Array.isArray((value as { nodes?: unknown }).nodes)
  );
}

function applyLoadResult(nodes: UiTreeNode[], nodeKey: string, result: UiTreeLoadResult): UiTreeNode[] {
  return nodes.map((node) => {
    if (node.key === nodeKey) {
      const children = result.mode === 'append' ? [...(node.children ?? []), ...result.nodes] : result.nodes;
      return {
        ...node,
        children,
        isLeaf: result.hasMore === false && children.length === 0 ? true : false,
      };
    }
    return node.children ? { ...node, children: applyLoadResult(node.children, nodeKey, result) } : node;
  });
}

function nextBranchLoadGeneration(nodeKey: string) {
  const next = (branchLoadGenerations.get(nodeKey) ?? 0) + 1;
  branchLoadGenerations.set(nodeKey, next);
  return next;
}

function invalidateBranchLoad(nodeKey: string) {
  branchAbortControllers.get(nodeKey)?.abort();
  branchAbortControllers.delete(nodeKey);
  nextBranchLoadGeneration(nodeKey);
}

onMounted(() => {
  document.addEventListener('mousemove', handleDocumentMouseMove);
  document.addEventListener('mouseup', handleDocumentMouseUp);
});

onBeforeUnmount(() => {
  pendingBranchReleases.forEach(clearTimeout);
  pendingEmptyBranchCollapses.forEach(clearTimeout);
  branchAbortControllers.forEach((controller) => controller.abort());
  branchAbortControllers.clear();
  if (activePointerDragSession?.owner === treeInstanceId) activePointerDragSession = undefined;
  previousTreeLayout.clear();
  document.removeEventListener('mousemove', handleDocumentMouseMove);
  document.removeEventListener('mouseup', handleDocumentMouseUp);
});

function scheduleEmptyBranchCollapse(nodeKey: string) {
  const pendingCollapse = pendingEmptyBranchCollapses.get(nodeKey);
  if (pendingCollapse) clearTimeout(pendingCollapse);
  // Keep the empty state visible until ATree has completed its expand transition.
  pendingEmptyBranchCollapses.set(
    nodeKey,
    setTimeout(() => {
      pendingEmptyBranchCollapses.delete(nodeKey);
      const node = findNode(renderNodes.value, nodeKey);
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
  const node = findNode(renderNodes.value, nodeKey);
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
  return typeof node.key === 'string' ? findNode(renderNodes.value, node.key) : undefined;
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

function clearPointerDragSession() {
  pointerDragging.value = undefined;
  internalDragging.value = false;
  if (activePointerDragSession?.owner === treeInstanceId) activePointerDragSession = undefined;
}

function handleTitleDoubleClick(key: unknown, event: MouseEvent) {
  if (typeof key !== 'string') return;
  const node = findNode(renderNodes.value, key);
  if (node) emit('double-click', { node, nativeEvent: event });
}

/**
 * Ant Tree owns its reorder gesture, but its drag adapter does not consistently preserve a
 * sibling-tree payload (and empty branches are particularly fragile).  Consumers that explicitly
 * opt into nativeDragSource also get the title-level payload adapter.  It intentionally does not
 * set internalDragging: the target must use the public external-drop contract with the real
 * DataTransfer payload.
 */
function setDragPayload(node: UiTreeNode, event: DragEvent) {
  const payload = props.dragPayloadOf?.(node);
  if (payload === undefined || !event.dataTransfer) return;
  let serialized: string | undefined;
  try {
    serialized = typeof payload === 'string' ? payload : JSON.stringify(payload);
  } catch {
    return;
  }
  if (serialized === undefined) return;
  event.dataTransfer.setData(props.dragPayloadType, serialized);
  event.dataTransfer.setData('text/plain', serialized);
  event.dataTransfer.effectAllowed = 'copy';
}

function handleTitleNativeDragStart(key: unknown, event: DragEvent) {
  if (typeof key !== 'string') return;
  const node = findNode(renderNodes.value, key);
  if (!node || (props.canDrag && !props.canDrag(node))) {
    event.preventDefault();
    return;
  }
  const pointerState = pointerDragging.value;
  if (pointerState?.key === key) {
    if (!pointerState.active) {
      pointerState.active = true;
      internalDragging.value = true;
      activePointerDragSession = {
        owner: treeInstanceId,
        ...(props.dragPayloadOf
          ? { payload: props.dragPayloadOf(node), payloadType: props.dragPayloadType }
          : {}),
      };
      emit('drag-start', { node, nativeEvent: event });
    }
    event.preventDefault();
    return;
  }
  // Once the pointer adapter has claimed the gesture, keep one deterministic path. Some browser
  // engines fire dragstart for a draggable element before they stop dispatching mouseup; allowing
  // that native gesture to take over would strand the cross-component fallback payload.
  if (activePointerDragSession?.owner === treeInstanceId) {
    event.preventDefault();
    return;
  }
  nativeTitleDraggingKey.value = key;
  if (props.nativeDragSource) {
    setDragPayload(node, event);
    event.stopPropagation();
  }
  emit('drag-start', { node, nativeEvent: event });
}

function handleTitlePointerDown(key: unknown, event: MouseEvent) {
  if (props.nativeDragSource || event.button !== 0 || typeof key !== 'string') return;
  if (!isTitleDraggable(key)) return;
  pointerDragging.value = { key, startX: event.clientX, startY: event.clientY, active: false };
}

function isTitleDraggable(key: unknown) {
  if (typeof key !== 'string' || !props.draggable) return false;
  const node = findNode(renderNodes.value, key);
  return Boolean(node && (!props.canDrag || props.canDrag(node)));
}

function dropEvent(
  dragKey: string,
  key: string,
  target: Element | null,
  clientY: number,
): Omit<UiTreeDropEvent, 'nativeEvent'> | undefined {
  if (!dragKey || dragKey === key) return undefined;
  const dragNode = findNode(renderNodes.value, dragKey);
  const dropNode = findNode(renderNodes.value, key);
  if (!dragNode || !dropNode || !(target instanceof HTMLElement)) return undefined;
  const rect = target.getBoundingClientRect();
  const position = rect.height === 0 ? 0.5 : (clientY - rect.top) / rect.height;
  return {
    dragNode,
    dropNode,
    dropPosition: position < 0.5 ? -1 : 1,
    dropToGap: true,
  };
}

function titleDropEvent(
  key: string,
  nativeEvent: DragEvent,
): Omit<UiTreeDropEvent, 'nativeEvent'> | undefined {
  const dragKey = nativeTitleDraggingKey.value;
  const target = nativeEvent.currentTarget instanceof Element ? nativeEvent.currentTarget : null;
  return dragKey ? dropEvent(dragKey, key, target, nativeEvent.clientY) : undefined;
}

function handleTitleDragOver(key: unknown, nativeEvent: DragEvent) {
  if (typeof key !== 'string' || !nativeTitleDraggingKey.value) return;
  const event = titleDropEvent(key, nativeEvent);
  if (!event || (props.allowDrop && !props.allowDrop(event))) return;
  nativeEvent.preventDefault();
  nativeEvent.stopPropagation();
  if (nativeEvent.dataTransfer) nativeEvent.dataTransfer.dropEffect = 'move';
}

function handleTitleDrop(key: unknown, nativeEvent: DragEvent) {
  if (typeof key !== 'string' || !nativeTitleDraggingKey.value) return;
  const event = titleDropEvent(key, nativeEvent);
  if (!event || (props.allowDrop && !props.allowDrop(event))) return;
  nativeEvent.preventDefault();
  nativeEvent.stopPropagation();
  nativeTitleDraggingKey.value = undefined;
  clearPointerDragSession();
  emit('drop', { ...event, nativeEvent });
}

function handleTitleDragEnd() {
  nativeTitleDraggingKey.value = undefined;
}

function handleTitleOrExternalDragOver(key: unknown, nativeEvent: DragEvent) {
  if (nativeTitleDraggingKey.value) {
    handleTitleDragOver(key, nativeEvent);
    return;
  }
  handleExternalDragOver(nativeEvent);
}

function handleTitleOrExternalDrop(key: unknown, nativeEvent: DragEvent) {
  if (nativeTitleDraggingKey.value) {
    handleTitleDrop(key, nativeEvent);
    return;
  }
  handleExternalDrop(nativeEvent);
}

function pointerDropTarget(event: MouseEvent) {
  const origin =
    event.target instanceof Element ? event.target : document.elementFromPoint(event.clientX, event.clientY);
  const target = origin?.closest<HTMLElement>('[data-ui-tree-key]');
  if (!target || !treeRoot.value?.contains(target)) return undefined;
  const key = target.dataset.uiTreeKey;
  return key ? { key, target } : undefined;
}

function handleDocumentMouseMove(event: MouseEvent) {
  const state = pointerDragging.value;
  if (!state || event.buttons !== 1) return;
  if (!state.active) {
    const distance = Math.hypot(event.clientX - state.startX, event.clientY - state.startY);
    if (distance < 4) return;
    state.active = true;
    const node = findNode(renderNodes.value, state.key);
    if (node) {
      internalDragging.value = true;
      activePointerDragSession = {
        owner: treeInstanceId,
        ...(props.dragPayloadOf
          ? { payload: props.dragPayloadOf(node), payloadType: props.dragPayloadType }
          : {}),
      };
      emit('drag-start', { node, nativeEvent: event });
    }
  }
  const target = pointerDropTarget(event);
  const drop = target ? dropEvent(state.key, target.key, target.target, event.clientY) : undefined;
  state.drop = drop && (!props.allowDrop || props.allowDrop(drop)) ? drop : undefined;
  event.preventDefault();
}

function handleDocumentMouseUp(event: MouseEvent) {
  const state = pointerDragging.value;
  pointerDragging.value = undefined;
  if (!state?.active) return;
  internalDragging.value = false;
  if (state.drop) {
    event.preventDefault();
    emit('drop', { ...state.drop, nativeEvent: event });
    activePointerDragSession = undefined;
    return;
  }
  const session = activePointerDragSession;
  if (session?.owner === treeInstanceId && session.payload !== undefined) {
    const target = document.elementFromPoint(event.clientX, event.clientY);
    if (target && !treeRoot.value?.contains(target)) {
      dispatchPointerExternalDrop(target, event, session);
    }
  }
  // Let every document listener observe the same session before clearing it. The source owns the
  // fallback dispatch, while UiTree targets can still consume the synthetic drop normally.
  window.setTimeout(() => {
    if (activePointerDragSession?.owner === treeInstanceId) activePointerDragSession = undefined;
  }, 0);
}

function dispatchPointerExternalDrop(
  target: Element,
  pointerEvent: MouseEvent,
  session: UiTreePointerDragSession,
) {
  let serialized: string;
  try {
    serialized = typeof session.payload === 'string' ? session.payload : JSON.stringify(session.payload);
  } catch {
    return;
  }
  if (serialized === undefined) return;
  const values: Record<string, string> = {
    ...(session.payloadType ? { [session.payloadType]: serialized } : {}),
    'text/plain': serialized,
  };
  const dataTransfer = {
    dropEffect: 'copy',
    effectAllowed: 'copy',
    types: Object.keys(values),
    getData(type: string) {
      return values[type] ?? '';
    },
  } as unknown as DataTransfer;
  const dropEvent = new Event('drop', { bubbles: true, cancelable: true }) as DragEvent;
  Object.defineProperties(dropEvent, {
    clientX: { value: pointerEvent.clientX },
    clientY: { value: pointerEvent.clientY },
    dataTransfer: { value: dataTransfer },
  });
  target.dispatchEvent(dropEvent);
}

function handleDrop(event: AntTreeDropEvent) {
  const normalized = normalizedDropEvent(event);
  if (normalized) {
    // A pointer fallback may have started before Ant Tree's native adapter reports the same
    // gesture. Let the renderer's normalized event win and prevent a second drop on mouseup.
    clearPointerDragSession();
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
  const dropNode = key ? findNode(renderNodes.value, key) : undefined;
  if (!target || !dropNode) return undefined;
  const rect = target.getBoundingClientRect();
  const position = rect.height === 0 ? 0 : (nativeEvent.clientY - rect.top) / rect.height;
  const payload = readDragPayload(nativeEvent.dataTransfer);
  return {
    dropNode,
    dropPosition: position < 0.25 ? -1 : position > 0.75 ? 1 : 0,
    dropToGap: position < 0.25 || position > 0.75,
    ...(payload ? { payload: payload.value, payloadType: payload.type } : {}),
  };
}

function readDragPayload(dataTransfer: DataTransfer | null) {
  if (!dataTransfer || typeof dataTransfer.getData !== 'function') return undefined;
  const types = Array.from(dataTransfer.types ?? []);
  const type = types.includes(props.dragPayloadType)
    ? props.dragPayloadType
    : types.includes('text/plain')
      ? 'text/plain'
      : undefined;
  if (!type) return undefined;
  const raw = dataTransfer.getData(type);
  if (!raw) return undefined;
  try {
    return { type, value: JSON.parse(raw) as unknown };
  } catch {
    return { type, value: raw };
  }
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
  nativeEvent.stopPropagation();
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

defineExpose({ loadMore, refreshNode });
</script>

<template>
  <div
    ref="treeRoot"
    class="ui-tree"
    :class="$attrs.class"
    :style="$attrs.style"
    @dragover="handleExternalDragOver"
    @drop="handleExternalDrop"
  >
    <TransitionGroup
      v-if="displayMode === 'flat'"
      tag="ul"
      :name="structuralMotionEnabled ? 'ui-tree-flat' : undefined"
      class="ui-tree__flat-list"
      role="list"
    >
      <li
        v-for="entry in flatNodes"
        :key="entry.node.key"
        :class="{
          'ui-tree-flat-node--draggable': isTitleDraggable(entry.node.key),
          'ui-tree-flat-node--dragging':
            (pointerDragging?.active && pointerDragging.key === entry.node.key) ||
            nativeTitleDraggingKey === entry.node.key,
          'ui-tree-flat-node--drop-before':
            pointerDragging?.active &&
            pointerDragging.drop?.dropNode.key === entry.node.key &&
            pointerDragging.drop.dropPosition === -1,
          'ui-tree-flat-node--drop-after':
            pointerDragging?.active &&
            pointerDragging.drop?.dropNode.key === entry.node.key &&
            pointerDragging.drop.dropPosition === 1,
        }"
        :style="{ '--ui-tree-flat-depth': entry.depth }"
        :data-ui-tree-key="entry.node.key"
        :data-ui-tree-depth="entry.depth"
        role="listitem"
        :aria-selected="selectedKeys.includes(entry.node.key)"
        :draggable="nativeDragSource && isTitleDraggable(entry.node.key) ? true : undefined"
        @dblclick.stop="handleTitleDoubleClick(entry.node.key, $event)"
        @dragstart="handleTitleNativeDragStart(entry.node.key, $event)"
        @dragover="handleTitleOrExternalDragOver(entry.node.key, $event)"
        @drop="handleTitleOrExternalDrop(entry.node.key, $event)"
        @dragend="handleTitleDragEnd"
        @mousedown="handleTitlePointerDown(entry.node.key, $event)"
      >
        <span
          v-if="checkable"
          class="ui-tree-flat-checkbox"
          data-ui-tree-checkbox
          @mousedown.stop
          @click.stop
        >
          <UiCheckbox
            :checked="effectiveCheckedKeys.includes(entry.node.key)"
            :disabled="!isCheckable(entry.node)"
            :aria-label="`选择${entry.node.title}`"
            @update:checked="handleFlatCheck(entry.node, $event)"
          />
        </span>
        <UiRecordExplorerItem
          :title="entry.node.title"
          :secondary="entry.node.secondary"
          :tag="entry.node.tag"
          :muted="entry.node.muted || entry.node.disabled"
          :selected="selectedKeys.includes(entry.node.key)"
          :actions="entry.node.actions"
          :clickable="!entry.node.disabled"
          @click="handleFlatSelect(entry.node)"
          @action="handleAction($event, entry.node.key)"
        />
      </li>
    </TransitionGroup>

    <ATree
      v-else
      block-node
      :tree-data="renderedTreeNodes"
      :selected-keys="selectedKeys"
      :expanded-keys="expandedKeys"
      :checked-keys="effectiveCheckedKeys"
      :checkable="checkable"
      :check-strictly="checkStrictly"
      :loaded-keys="managesLoadedKeys ? loadedKeys : undefined"
      :load-data="loadChildren || loadStrategy === 'controlled' ? handleLoad : undefined"
      :draggable="draggable"
      :allow-drop="draggable ? allowsDrop : undefined"
      @select="handleSelect"
      @expand="handleExpand"
      @check="handleTreeCheck"
      @dragstart="handleDragStart"
      @dragend="handleDragEnd"
      @drop="handleDrop"
    >
      <template #title="{ key, title, secondary, tag, muted, actions }">
        <div
          :data-ui-tree-key="key"
          :draggable="nativeDragSource && isTitleDraggable(key) ? true : undefined"
          @dblclick.stop="handleTitleDoubleClick(key, $event)"
          @dragstart.stop="handleTitleNativeDragStart(key, $event)"
          @dragover="handleTitleOrExternalDragOver(key, $event)"
          @drop="handleTitleOrExternalDrop(key, $event)"
          @dragend="handleTitleDragEnd"
          @mousedown="handleTitlePointerDown(key, $event)"
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

<style scoped>
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

.ui-tree__flat-list > li::before,
.ui-tree__flat-list > li::after {
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

.ui-tree__flat-list > li.ui-tree-flat-node--drop-before::before {
  top: -2px;
  opacity: 1;
}

.ui-tree__flat-list > li.ui-tree-flat-node--drop-after::after {
  bottom: -2px;
  opacity: 1;
}

.ui-tree__flat-list > li[draggable='true'] {
  cursor: grab;
}

.ui-tree__flat-list > li.ui-tree-flat-node--draggable {
  cursor: grab;
}

.ui-tree__flat-list > li.ui-tree-flat-node--draggable:active {
  cursor: grabbing;
}

.ui-tree__flat-list > li.ui-tree-flat-node--dragging {
  opacity: 0.45;
}

.ui-tree__flat-list > li:active[draggable='true'] {
  cursor: grabbing;
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

.ui-tree-flat-move,
.ui-tree-flat-enter-active,
.ui-tree-flat-leave-active {
  transition:
    transform 180ms cubic-bezier(0.2, 0, 0, 1),
    opacity 180ms ease;
}

.ui-tree-flat-enter-from,
.ui-tree-flat-leave-to {
  opacity: 0;
}

.ui-tree-flat-leave-active {
  position: absolute;
  width: 100%;
}

@media (prefers-reduced-motion: reduce) {
  .ui-tree__flat-list > li,
  .ui-tree-flat-move,
  .ui-tree-flat-enter-active,
  .ui-tree-flat-leave-active {
    transition: none;
  }
}
</style>
