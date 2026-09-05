<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  UiButton,
  UiEmpty,
  UiInput,
  UiSpin,
  UiTree,
  type UiRecordInlineAction,
  type UiTreeDropEvent,
  type UiTreeNode,
  type UiTreeChangeReason,
} from '@muyun/vue-ui-antdv';
import type { ModuleContext } from '@muyun/web-core';
import type { WebTreeNode } from '@muyun/web-contracts';
import type { RecordExplorerItemDescriptor } from './recordExplorerItemModel';
import { sortPartitionKey } from './sortPartitionKey';
import {
  defaultTreeRecordMatches,
  defaultTreeRecordTitle,
  expandAllTreeRecords,
  filterTreeRecords,
  firstTwoTreeLevels,
  flattenTreeRecords,
  type TreeRecordBase,
} from './treeRecordModel';
import { presentPlatformError } from './platformErrorFeedback';

defineOptions({ name: 'TreeRecordExplorer' });

type TreeRecordSearchMode = 'always' | 'collapsible' | 'none';

const props = withDefaults(
  defineProps<{
    context: ModuleContext<TreeRecordBase>;
    selectedId?: string;
    reloadKey?: number;
    /** Descriptor-owned criteria from upstream navigator levels. */
    externalQueryValues?: Record<string, unknown>;
    navigatorHostModuleAlias?: string;
    navigatorTargetLevelKey?: string;
    keyword?: string;
    searchMode?: TreeRecordSearchMode;
    searchTrigger?: 'inline' | 'external';
    searchPlaceholder?: string;
    emptyDescription?: string;
    loadingTip?: string;
    fallbackTitle?: string;
    titleOf?: (record: TreeRecordBase) => string;
    secondaryOf?: (record: TreeRecordBase) => string | undefined;
    itemOf?: (record: TreeRecordBase) => RecordExplorerItemDescriptor | undefined;
    actionsOf?: (record: TreeRecordBase) => UiRecordInlineAction[];
    filterOption?: (record: TreeRecordBase, normalizedKeyword: string) => boolean;
    tagOf?: (record: TreeRecordBase) => string | undefined;
    mutedOf?: (record: TreeRecordBase) => boolean;
    /** Enables sibling ordering and parent changes through the module's standard tree sort contract. */
    sorting?: boolean;
  }>(),
  {
    selectedId: undefined,
    reloadKey: undefined,
    externalQueryValues: undefined,
    navigatorHostModuleAlias: undefined,
    navigatorTargetLevelKey: undefined,
    keyword: undefined,
    searchMode: 'always',
    searchTrigger: 'inline',
    searchPlaceholder: '搜索名称、编码或 ID',
    emptyDescription: '暂无记录',
    loadingTip: '加载树形记录',
    fallbackTitle: '未命名记录',
    titleOf: undefined,
    secondaryOf: undefined,
    itemOf: undefined,
    actionsOf: undefined,
    filterOption: undefined,
    tagOf: undefined,
    mutedOf: undefined,
    sorting: false,
  },
);

const emit = defineEmits<{
  select: [record: TreeRecordBase];
  deselect: [];
  action: [action: UiRecordInlineAction, record: TreeRecordBase];
  loaded: [records: TreeRecordBase[]];
  sorted: [];
}>();

const loading = ref(false);
const treeChangeReason = ref<UiTreeChangeReason>('reset');
const loadError = ref(false);
const sortingRequest = ref(false);
const localKeyword = ref('');
const searchExpanded = ref(false);
const tree = ref<WebTreeNode<TreeRecordBase>[]>([]);
const expandedKeys = ref<string[]>([]);
let treeRequestSeq = 0;

const currentKeyword = computed(() => props.keyword ?? localKeyword.value);
const effectiveKeyword = computed(() =>
  props.searchMode === 'none' && props.keyword === undefined ? '' : currentKeyword.value,
);
const searchVisible = computed(
  () =>
    props.searchMode === 'always' ||
    (props.searchMode === 'collapsible' && (searchExpanded.value || localKeyword.value.trim().length > 0)),
);
const searchRowVisible = computed(
  () =>
    props.searchMode !== 'none' &&
    (searchVisible.value || (props.searchMode === 'collapsible' && props.searchTrigger === 'inline')),
);
const filteredTree = computed(() =>
  filterTreeRecords(tree.value, effectiveKeyword.value, (record, normalized) =>
    matchesKeyword(record, normalized),
  ),
);
const nodes = computed(() => filteredTree.value.map(toUiTreeNode));
const records = computed(() => flattenTreeRecords(tree.value));
const dragOrderingEnabled = computed(
  () => props.sorting && !sortingRequest.value && !loading.value && !effectiveKeyword.value.trim(),
);

onMounted(loadTree);

watch(
  () => props.reloadKey,
  () => loadTree(),
);

watch(
  () => props.context,
  () => loadTree(),
);

watch(
  () => props.externalQueryValues,
  () => loadTree(),
  { deep: true },
);

watch(effectiveKeyword, () => {
  treeChangeReason.value = 'filter';
  if (effectiveKeyword.value.trim()) {
    expandedKeys.value = filteredTree.value.flatMap(expandAllTreeRecords);
  }
});

async function loadTree(reason: UiTreeChangeReason = 'reset', expandParentId?: string) {
  const requestSeq = ++treeRequestSeq;
  if (reason === 'reset') {
    tree.value = [];
    expandedKeys.value = [];
  }
  loading.value = true;
  loadError.value = false;
  try {
    await props.context.runtime.ready;
    const treeCapability = props.context.abilities.tree();
    const response = await treeCapability.tree(
      props.externalQueryValues && Object.keys(props.externalQueryValues).length > 0
        ? {
            externalQueryValues: props.externalQueryValues,
            ...(props.navigatorHostModuleAlias && props.navigatorTargetLevelKey
              ? {
                  navigatorHostModuleAlias: props.navigatorHostModuleAlias,
                  navigatorTargetLevelKey: props.navigatorTargetLevelKey,
                }
              : {}),
          }
        : props.navigatorHostModuleAlias && props.navigatorTargetLevelKey
          ? {
              navigatorHostModuleAlias: props.navigatorHostModuleAlias,
              navigatorTargetLevelKey: props.navigatorTargetLevelKey,
            }
          : undefined,
    );
    if (requestSeq !== treeRequestSeq) {
      return;
    }
    treeChangeReason.value = reason;
    tree.value = response.records;
    const keys = new Set(flattenTreeRecords(response.records).map((record) => String(record.id)));
    expandedKeys.value =
      reason === 'interaction'
        ? expandedKeys.value.filter((key) => keys.has(key))
        : firstTwoTreeLevels(response.records);
    if (expandParentId && expandParentId !== 'root') {
      let entry = locateTreeNode(expandParentId);
      const path: string[] = [];
      while (entry) {
        path.push(String(entry.node.record.id));
        entry = entry.parent ? locateTreeNode(String(entry.parent.record.id)) : undefined;
      }
      expandedKeys.value = [...new Set([...expandedKeys.value, ...path])];
    }
    emit('loaded', flattenTreeRecords(response.records));
  } catch (cause) {
    if (requestSeq !== treeRequestSeq) {
      return;
    }
    loadError.value = true;
    if (reason === 'reset') {
      tree.value = [];
      expandedKeys.value = [];
      emit('loaded', []);
    }
    presentPlatformError(cause, { source: 'tree-record-explorer', phase: 'load' });
  } finally {
    if (requestSeq === treeRequestSeq) {
      loading.value = false;
    }
  }
}

function recordTitle(record: TreeRecordBase) {
  const item = props.itemOf?.(record);
  return item?.title ?? props.titleOf?.(record) ?? defaultTreeRecordTitle(record, props.fallbackTitle);
}

function matchesKeyword(record: TreeRecordBase, normalized: string) {
  return (
    props.filterOption?.(record, normalized) ?? defaultTreeRecordMatches(record, normalized, recordTitle)
  );
}

function handleSelect(node: UiTreeNode) {
  if (String(node.key) === props.selectedId) {
    emit('deselect');
    return;
  }
  const record = records.value.find((item) => item.id === node.key);
  if (record) {
    emit('select', record);
  }
}

function handleAction(action: UiRecordInlineAction, node: UiTreeNode) {
  const record = records.value.find((item) => item.id === node.key);
  if (record) {
    emit('action', action, record);
  }
}

function canDropForSort(event: UiTreeDropEvent) {
  return resolveTreeMove(event) !== undefined;
}

function canDragForSort() {
  return dragOrderingEnabled.value;
}

function sortPartitionOf(record: TreeRecordBase): string | undefined {
  const runtime = props.context.runtime.snapshot?.();
  if (!runtime) return undefined;
  // Parent is the destination of a tree move, not an immutable business partition.
  const fields = (runtime.sortPartitionFields ?? []).filter((field) => field !== 'parentId');
  const values = record as Record<string, unknown>;
  if (fields.some((field) => !Object.prototype.hasOwnProperty.call(values, field))) return undefined;
  return sortPartitionKey(fields.map((field) => values[field]));
}

function resolveTreeMove(event: UiTreeDropEvent) {
  if (
    !dragOrderingEnabled.value ||
    event.source.instanceId !== event.target.instanceId ||
    event.operation !== 'move'
  )
    return undefined;
  const source = locateTreeNode(String(event.source.node.key));
  const target = event.target.kind === 'node' ? locateTreeNode(String(event.target.node.key)) : undefined;
  if (!source || (event.target.kind === 'node' && !target) || source.node === target?.node) return undefined;
  const inside = event.target.position === 'inside';
  const parent = inside ? target?.node : target?.parent;
  // Reject cycles before offering a drop indicator or issuing a mutation.
  for (let ancestor = parent; ancestor; ) {
    if (ancestor === source.node) return undefined;
    ancestor = locateTreeNode(String(ancestor.record.id))?.parent;
  }
  const partition = sortPartitionOf(source.node.record);
  if (partition === undefined || (target && sortPartitionOf(target.node.record) !== partition))
    return undefined;
  if (parent && sortPartitionOf(parent.record) !== partition) return undefined;
  const siblings = (target ? (inside ? target.node.children : target.siblings) : tree.value).filter(
    (node) => node !== source.node && sortPartitionOf(node.record) === partition,
  );
  const targetIndex = inside ? siblings.length : target ? siblings.indexOf(target.node) : -1;
  if (targetIndex < 0) return undefined;
  const index = targetIndex + (!inside && event.target.position === 'after' ? 1 : 0);
  const currentSiblings = source.siblings.filter((node) => sortPartitionOf(node.record) === partition);
  if (source.parent === parent && currentSiblings.indexOf(source.node) === index) return undefined;
  const id = source.node.record.id;
  if (id == null) return undefined;
  return {
    id: String(id),
    request: {
      previousId: siblings[index - 1]?.record.id == null ? null : String(siblings[index - 1].record.id),
      nextId: siblings[index]?.record.id == null ? null : String(siblings[index].record.id),
      parentId: parent?.record.id == null ? 'root' : String(parent.record.id),
    },
  };
}

async function handleDropForSort(event: UiTreeDropEvent) {
  const move = resolveTreeMove(event);
  if (!move) return;
  sortingRequest.value = true;
  const context = props.context;
  const scopeVersion = treeRequestSeq;
  try {
    await context.runtime.ready;
    await context.abilities.tree().sort(move.id, move.request);
    if (context !== props.context || scopeVersion !== treeRequestSeq) return;
    await loadTree('interaction', move.request.parentId);
    emit('sorted');
  } catch (cause) {
    presentPlatformError(cause, { source: 'tree-record-explorer', phase: 'action' });
  } finally {
    sortingRequest.value = false;
  }
}

function locateTreeNode(key: string):
  | {
      node: WebTreeNode<TreeRecordBase>;
      parent?: WebTreeNode<TreeRecordBase>;
      siblings: WebTreeNode<TreeRecordBase>[];
    }
  | undefined {
  function visit(
    candidates: WebTreeNode<TreeRecordBase>[],
    parent?: WebTreeNode<TreeRecordBase>,
  ):
    | {
        node: WebTreeNode<TreeRecordBase>;
        parent?: WebTreeNode<TreeRecordBase>;
        siblings: WebTreeNode<TreeRecordBase>[];
      }
    | undefined {
    for (const candidate of candidates) {
      if (String(candidate.record.id) === key) return { node: candidate, parent, siblings: candidates };
      const located = visit(candidate.children, candidate);
      if (located) return located;
    }
    return undefined;
  }
  return visit(tree.value);
}

function openSearch() {
  searchExpanded.value = true;
}

function closeSearch() {
  localKeyword.value = '';
  searchExpanded.value = false;
}

function toggleSearch() {
  if (props.searchMode === 'collapsible' && searchVisible.value) {
    closeSearch();
    return;
  }
  openSearch();
}

function handleSearchBlur() {
  if (props.searchMode === 'collapsible' && !localKeyword.value.trim()) {
    searchExpanded.value = false;
  }
}

function toUiTreeNode(node: WebTreeNode<TreeRecordBase>): UiTreeNode {
  const record = node.record;
  const item = props.itemOf?.(record);
  return {
    key: record.id ?? '',
    title: item?.title ?? recordTitle(record),
    secondary: item?.secondary ?? props.secondaryOf?.(record),
    tag: item?.tag ?? props.tagOf?.(record) ?? (record.enabled === false ? '停用' : undefined),
    muted: item?.muted ?? props.mutedOf?.(record) ?? record.enabled === false,
    actions: item?.actions ?? props.actionsOf?.(record),
    isLeaf: node.children.length === 0,
    children: node.children.map(toUiTreeNode),
  };
}

defineExpose({ openSearch, toggleSearch });
</script>

<template>
  <div class="tree-record-explorer">
    <Transition name="tree-record-search">
      <div v-if="searchRowVisible" class="tree-record-search">
        <UiInput
          v-if="searchVisible"
          v-model:value="localKeyword"
          allow-clear
          :autofocus="searchMode === 'collapsible'"
          :placeholder="searchPlaceholder"
          @blur="handleSearchBlur"
        />
        <UiButton
          v-else-if="searchTrigger === 'inline'"
          class="tree-record-search-trigger"
          icon-name="search"
          type="text"
          title="搜索"
          @click="openSearch"
        />
      </div>
    </Transition>
    <UiButton v-if="loadError" type="text" @click="loadTree()">重新加载</UiButton>
    <UiSpin v-if="loading && nodes.length === 0" :tip="loadingTip" />
    <UiEmpty v-else-if="nodes.length === 0" :description="emptyDescription" />
    <UiTree
      v-else
      v-model:expanded-keys="expandedKeys"
      :nodes="nodes"
      :change-reason="effectiveKeyword.trim() ? 'filter' : treeChangeReason"
      :selected-key="selectedId"
      :draggable="dragOrderingEnabled"
      :can-drag="canDragForSort"
      :allow-drop="canDropForSort"
      @select="handleSelect"
      @deselect="emit('deselect')"
      @action="handleAction"
      @drop="handleDropForSort"
    />
  </div>
</template>

<style scoped>
.tree-record-explorer {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.tree-record-search {
  display: flex;
  justify-content: flex-end;
  min-width: 0;
  margin-bottom: 10px;
  overflow: hidden;
}

.tree-record-search-enter-active,
.tree-record-search-leave-active {
  max-height: 40px;
  transition:
    max-height 0.16s ease,
    margin-bottom 0.16s ease,
    opacity 0.16s ease,
    transform 0.16s ease;
}

.tree-record-search-enter-from,
.tree-record-search-leave-to {
  max-height: 0;
  margin-bottom: 0;
  opacity: 0;
  transform: translateY(-4px);
}

.tree-record-search-enter-to,
.tree-record-search-leave-from {
  max-height: 40px;
  margin-bottom: 10px;
  opacity: 1;
  transform: translateY(0);
}

.tree-record-search :deep(.ant-input) {
  width: 100%;
}

.tree-record-search-trigger {
  color: var(--muyun-text-muted);
}

.tree-record-explorer > :not(.tree-record-search) {
  flex: 1 1 auto;
  min-height: 0;
}

.tree-record-explorer :deep(.ant-tree) {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
}
</style>
