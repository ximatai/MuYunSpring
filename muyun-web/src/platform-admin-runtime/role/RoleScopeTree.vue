<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RecordExplorerPanel, presentPlatformError } from '@muyun/platform-components';
import { UiSpin, UiTree, useTreeData, type UiTreeLoadRequest, type UiTreeNode } from '@muyun/vue-ui-antdv';
import type { ModulePageNavigatorExtensionContext } from '@muyun/dynamic-page-runtime';
import { createBackendHttpClient } from '../backendHttp';
import {
  createRoleScopeSelectionClient,
  type RoleScopeSelectionCandidate,
  type RoleScopeSelectionDescriptor,
  type RoleScopeSelectionLevel,
} from './roleScopeSelectionClient';

defineOptions({ name: 'RoleScopeTree' });

const props = defineProps<{ context: ModulePageNavigatorExtensionContext }>();

interface ScopeTreeNode {
  ui: UiTreeNode;
  selection?: { key: string; label: string; secondaryLabel?: string };
  children?: RoleScopeChildrenQuery;
}

interface RoleScopeChildrenQuery {
  navigationKey: string;
  level: RoleScopeSelectionLevel;
  parentId?: string;
}

const client = createRoleScopeSelectionClient(createBackendHttpClient());
const descriptor = ref<RoleScopeSelectionDescriptor>();
const keyword = ref('');
const loading = ref(false);
const nodes = ref<UiTreeNode[]>([]);
const expandedKeys = ref<string[]>([]);
const treeReloadKey = ref(0);
const entries = ref(new Map<string, ScopeTreeNode>());
const selectedTreeKey = computed(() => props.context.selectionKey);
const tenantNavigationVisible = computed(
  () => descriptor.value?.navigations.some((navigation) => navigation.level === 'TENANT') === true,
);
const searchable = computed(() => descriptor.value?.navigations.length !== 0);
const panelTitle = computed(() => (tenantNavigationVisible.value ? '租户' : '机构'));
const branchData = useTreeData({
  nodes: () => nodes.value,
  loader: () => loadChildren,
  version: () => treeReloadKey.value,
});
const visibleNodes = branchData.nodes;
const branchStates = branchData.states;
let rootLoadRevision = 0;
let searchTimer: ReturnType<typeof setTimeout> | undefined;

onMounted(() => void initialize());
onBeforeUnmount(() => {
  rootLoadRevision += 1;
  if (searchTimer) clearTimeout(searchTimer);
});

watch(keyword, () => {
  if (!searchable.value) return;
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => void reloadForSearch(), 240);
});

async function initialize() {
  loading.value = true;
  try {
    descriptor.value = await client.descriptor();
    const defaultSelection = descriptor.value.directSelections[0];
    if (!props.context.selectionKey && defaultSelection) {
      selectSelection({ key: defaultSelection.selectionKey, label: defaultSelection.label });
    }
    await loadRoots();
  } catch (cause) {
    presentPlatformError(cause, { source: 'role-scope-tree', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function loadRoots(options: { append?: boolean } = {}) {
  const currentDescriptor = descriptor.value;
  if (!currentDescriptor) return;
  const revision = ++rootLoadRevision;
  if (options.append) return;
  expandedKeys.value = [];
  treeReloadKey.value += 1;
  loading.value = true;
  try {
    const roots = rootNodesOf(currentDescriptor);
    if (revision !== rootLoadRevision) return;
    entries.value = new Map();
    remember(roots);
    nodes.value = roots.map((node) => node.ui);
  } catch (cause) {
    if (revision !== rootLoadRevision) return;
    nodes.value = [];
    presentPlatformError(cause, { source: 'role-scope-tree', phase: 'load' });
  } finally {
    if (revision === rootLoadRevision) {
      loading.value = false;
    }
  }
}

async function reloadForSearch() {
  await loadRoots();
  if (!keyword.value.trim()) return;
  const navigationKeys = (descriptor.value?.navigations ?? []).map(
    (navigation) => `navigation:${navigation.navigationKey}`,
  );
  expandedKeys.value = navigationKeys;
  await nextTick();
  await Promise.all(navigationKeys.map((key) => branchData.request(key, 'refresh')));
}

function rootNodesOf(currentDescriptor: RoleScopeSelectionDescriptor) {
  return [
    ...currentDescriptor.directSelections.map((selection) => ({
      ui: { key: selection.selectionKey, title: selection.label, isLeaf: true },
      selection: { key: selection.selectionKey, label: selection.label },
    })),
    ...currentDescriptor.navigations
      .filter((navigation) => navigation.level === 'ORGANIZATION')
      .map((navigation) => navigationTreeNode(navigation)),
    ...currentDescriptor.navigations
      .filter((navigation) => navigation.level === 'TENANT')
      .map((navigation) => navigationTreeNode(navigation)),
  ];
}

function navigationTreeNode(navigation: RoleScopeSelectionDescriptor['navigations'][number]): ScopeTreeNode {
  return {
    ui: { key: `navigation:${navigation.navigationKey}`, title: navigation.label, isLeaf: false },
    children: { navigationKey: navigation.navigationKey, level: navigation.level },
  };
}

function candidateTreeNode(
  candidate: RoleScopeSelectionCandidate,
  level: RoleScopeSelectionLevel,
  navigationKey: string,
): ScopeTreeNode {
  const childQuery = childQueryOf(candidate, level, navigationKey);
  return {
    ui: {
      key: candidate.selectionKey,
      title: candidate.label,
      secondary: candidate.secondaryLabel,
      isLeaf: !childQuery,
    },
    selection: {
      key: candidate.selectionKey,
      label: candidate.label,
      secondaryLabel: candidate.secondaryLabel,
    },
    children: childQuery,
  };
}

function childQueryOf(
  candidate: RoleScopeSelectionCandidate,
  level: RoleScopeSelectionLevel,
  navigationKey: string,
): RoleScopeChildrenQuery | undefined {
  if (level === 'TENANT') {
    return { navigationKey: candidate.selectionKey, level: 'ORGANIZATION' };
  }
  return candidate.expandable ? { navigationKey, level: 'ORGANIZATION', parentId: candidate.id } : undefined;
}

async function loadChildren(node: UiTreeNode, request: UiTreeLoadRequest) {
  const entry = entries.value.get(node.key);
  if (!entry?.children) throw new Error('角色归属范围已失效');
  try {
    const page = await client.candidates({
      ...entry.children,
      keyword: keyword.value.trim() || undefined,
      pageNum: request.reason === 'load-more' ? Number(request.cursor) : 1,
    });
    request.signal.throwIfAborted();
    const loaded = page.records.map((candidate) =>
      candidateTreeNode(candidate, entry.children!.level, entry.children!.navigationKey),
    );
    remember(loaded);
    return {
      mode: request.reason === 'load-more' ? ('append' as const) : ('replace' as const),
      nodes: loaded.map((item) => item.ui),
      hasMore: page.hasMore,
      nextCursor: page.nextPage == null ? undefined : String(page.nextPage),
    };
  } catch (cause) {
    if (!request.signal.aborted) presentPlatformError(cause, { source: 'role-scope-tree', phase: 'load' });
    throw cause;
  }
}

function remember(loaded: ScopeTreeNode[]) {
  const next = new Map(entries.value);
  loaded.forEach((entry) => next.set(entry.ui.key, entry));
  entries.value = next;
}

function handleSelect(node: UiTreeNode) {
  const selection = entries.value.get(node.key)?.selection;
  if (selection) selectSelection(selection);
}

function selectSelection(selection: { key: string; label: string; secondaryLabel?: string }) {
  props.context.selectSelectionKey(selection.key, {
    label: selection.label,
    secondaryLabel: selection.secondaryLabel,
  });
}

function clearSelection() {
  const fallback = descriptor.value?.directSelections[0];
  if (fallback) selectSelection({ key: fallback.selectionKey, label: fallback.label });
}
</script>

<template>
  <RecordExplorerPanel
    class="role-scope-tree"
    :title="panelTitle"
    refresh-title="刷新角色归属范围"
    :search-keyword="keyword"
    search-placeholder="搜索名称、编码或标识"
    :searchable="searchable"
    @refresh="loadRoots"
    @update:search-keyword="keyword = $event"
  >
    <UiSpin v-if="loading" tip="加载角色归属范围" />
    <UiTree
      v-else
      v-model:expanded-keys="expandedKeys"
      :nodes="visibleNodes"
      :selected-key="selectedTreeKey"
      :reload-key="treeReloadKey"
      load-strategy="controlled"
      reload-on-reexpand
      :branch-states="branchStates"
      @load-request="
        branchData.request(
          $event.node.key,
          $event.reason,
          branchData.stateOf($event.node.key).status === 'error',
        )
      "
      @select="handleSelect"
      @deselect="clearSelection"
      @unload-children="branchData.release($event.key)"
    />
  </RecordExplorerPanel>
</template>
