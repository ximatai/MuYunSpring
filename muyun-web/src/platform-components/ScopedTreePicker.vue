<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  UiButton,
  UiError,
  UiModal,
  UiSearchInput,
  UiTree,
  type UiTreeLoadRequest,
  type UiTreeLoadResult,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';
import {
  scopedTreePickerValue,
  type ScopedTreePickerCandidate,
  type ScopedTreePickerProvider,
} from './scopedTreePickerModel';

defineOptions({ name: 'ScopedTreePicker' });

const props = withDefaults(
  defineProps<{
    /** The persisted target identity. The component never persists navigation scope IDs. */
    value?: string;
    provider: ScopedTreePickerProvider;
    placeholder?: string;
    searchPlaceholder?: string;
    title?: string;
    disabled?: boolean;
    /** The host already renders its own compact trigger, so this component only contributes a dialog. */
    inputHidden?: boolean;
  }>(),
  {
    value: undefined,
    placeholder: '搜索并选择',
    searchPlaceholder: '按名称、编码或路径搜索',
    title: '选择记录',
    disabled: false,
    inputHidden: false,
  },
);

const emit = defineEmits<{
  'update:value': [value: string | undefined];
  select: [candidate: ScopedTreePickerCandidate | undefined];
}>();

const open = ref(false);
const keyword = ref('');
const rootNodes = ref<UiTreeNode[]>([]);
const expandedKeys = ref<string[]>([]);
const rootLoading = ref(false);
const error = ref<string>();
const draftId = ref<string>();
const candidatesById = ref(new Map<string, ScopedTreePickerCandidate>());
const treeReloadKey = ref(0);
let rootRequestVersion = 0;
let resolveRequestVersion = 0;
let rootController: AbortController | undefined;

const selectedCandidate = computed(() => {
  const id = draftId.value;
  return id ? candidatesById.value.get(id) : undefined;
});
const externalCandidate = computed(() => {
  const id = scopedTreePickerValue(props.value);
  return id ? candidatesById.value.get(id) : undefined;
});
const summary = computed(() => {
  const id = scopedTreePickerValue(props.value);
  if (!id) return '';
  const candidate = externalCandidate.value;
  if (!candidate) return id;
  return candidate.unavailable ? `${candidate.title}（不可用）` : candidate.title;
});

watch(
  () => [props.value, props.provider] as const,
  ([value]) => {
    const id = scopedTreePickerValue(value);
    if (open.value) draftId.value = id;
    void resolveSelection(id);
  },
  { immediate: true },
);

onBeforeUnmount(() => rootController?.abort());

function remember(candidates: readonly ScopedTreePickerCandidate[]) {
  if (!candidates.length) return;
  const next = new Map(candidatesById.value);
  candidates.forEach((candidate) => next.set(candidate.id, candidate));
  candidatesById.value = next;
}

function toTreeNode(candidate: ScopedTreePickerCandidate): UiTreeNode {
  return {
    key: candidate.id,
    title: candidate.title,
    secondary: candidate.subtitle,
    disabled: candidate.disabled || candidate.unavailable,
    muted: candidate.unavailable,
    isLeaf: candidate.isLeaf,
  };
}

async function resolveSelection(id: string | undefined) {
  const requestVersion = ++resolveRequestVersion;
  if (!id) return;
  try {
    const candidates = await props.provider.resolve([id]);
    if (requestVersion !== resolveRequestVersion) return;
    remember(candidates);
  } catch {
    // Persisted values can outlive current candidate eligibility. Keep the ID visible rather than
    // silently erasing a business fact; the source owns whether the value remains submittable.
  }
}

function openPicker(value?: string) {
  if (props.disabled) return;
  keyword.value = value ?? keyword.value;
  draftId.value = scopedTreePickerValue(props.value);
  open.value = true;
  void reloadRoot();
}

function closePicker() {
  open.value = false;
  rootController?.abort();
  rootController = undefined;
  rootRequestVersion += 1;
  error.value = undefined;
  keyword.value = '';
}

async function reloadRoot() {
  if (!open.value) return;
  rootController?.abort();
  const controller = new AbortController();
  rootController = controller;
  const requestVersion = ++rootRequestVersion;
  rootLoading.value = true;
  error.value = undefined;
  try {
    const page = await props.provider.loadRoot({ keyword: keyword.value.trim(), signal: controller.signal });
    if (controller.signal.aborted || requestVersion !== rootRequestVersion || !open.value) return;
    remember(page.records);
    rootNodes.value = page.records.map(toTreeNode);
    expandedKeys.value = [];
    treeReloadKey.value += 1;
  } catch (cause) {
    if (controller.signal.aborted || requestVersion !== rootRequestVersion || !open.value) return;
    rootNodes.value = [];
    error.value = cause instanceof Error ? cause.message : '树候选加载失败';
  } finally {
    if (requestVersion === rootRequestVersion) rootLoading.value = false;
  }
}

function searchInDialog(value: string) {
  keyword.value = value;
  void reloadRoot();
}

async function loadChildren(node: UiTreeNode, request: UiTreeLoadRequest): Promise<UiTreeLoadResult> {
  const parent = candidatesById.value.get(node.key);
  if (!parent)
    return { mode: request.reason === 'load-more' ? 'append' : 'replace', nodes: [], hasMore: false };
  const page = await props.provider.loadChildren({
    parent,
    keyword: keyword.value.trim(),
    cursor: request.cursor,
    signal: request.signal,
  });
  if (request.signal.aborted || !open.value) {
    return { mode: request.reason === 'load-more' ? 'append' : 'replace', nodes: [], hasMore: false };
  }
  remember(page.records);
  return {
    mode: request.reason === 'load-more' ? 'append' : 'replace',
    nodes: page.records.map(toTreeNode),
    hasMore: page.hasMore === true,
    ...(page.hasMore && page.nextCursor ? { nextCursor: page.nextCursor } : {}),
  };
}

function selectNode(node: UiTreeNode) {
  const candidate = candidatesById.value.get(node.key);
  if (!candidate || candidate.disabled || candidate.unavailable) return;
  draftId.value = candidate.id;
}

function confirm() {
  const candidate = selectedCandidate.value;
  emit('update:value', candidate?.id);
  emit('select', candidate);
  closePicker();
}

defineExpose({ open: openPicker });
</script>

<template>
  <div class="scoped-tree-picker">
    <UiSearchInput
      v-if="!inputHidden"
      :value="keyword"
      :placeholder="placeholder"
      :disabled="disabled"
      search-text="搜索"
      @update:value="keyword = $event"
      @search="openPicker"
    />
    <p v-if="!inputHidden && summary" class="scoped-tree-picker-summary">{{ summary }}</p>

    <UiModal
      :open="open"
      :title="title"
      :width="680"
      :confirm-disabled="rootLoading"
      :closable="!rootLoading"
      @confirm="confirm"
      @cancel="closePicker"
    >
      <div class="scoped-tree-picker-dialog">
        <UiSearchInput
          :value="keyword"
          :placeholder="searchPlaceholder"
          :loading="rootLoading"
          search-text="搜索"
          @update:value="keyword = $event"
          @search="searchInDialog"
        />
        <div class="scoped-tree-picker-selection">
          <span>当前选择</span>
          <strong>{{ selectedCandidate?.title ?? '尚未选择' }}</strong>
          <UiButton v-if="draftId" type="link" size="small" @click="draftId = undefined">清除</UiButton>
        </div>
        <div v-if="error" class="scoped-tree-picker-error">
          <UiError :message="error" />
          <UiButton size="small" @click="reloadRoot">重试</UiButton>
        </div>
        <UiTree
          :nodes="rootNodes"
          :selected-key="draftId"
          :expanded-keys="expandedKeys"
          :load-children="loadChildren"
          :reload-key="treeReloadKey"
          :min-loading-duration-ms="0"
          empty-description="没有可选择的记录"
          @update:expanded-keys="expandedKeys = $event"
          @select="selectNode"
        />
      </div>
    </UiModal>
  </div>
</template>

<style scoped>
.scoped-tree-picker {
  min-width: 0;
}

.scoped-tree-picker-summary {
  margin: 4px 0 0;
  color: var(--muyun-text-secondary);
  font-size: 12px;
  line-height: 18px;
}

.scoped-tree-picker-dialog {
  display: grid;
  gap: 12px;
  min-height: 360px;
}

.scoped-tree-picker-selection,
.scoped-tree-picker-error {
  display: flex;
  align-items: center;
  gap: 8px;
}

.scoped-tree-picker-selection {
  color: var(--muyun-text-secondary);
  font-size: 12px;
}

.scoped-tree-picker-selection strong {
  color: var(--muyun-text);
  font-weight: 500;
}

.scoped-tree-picker-error {
  align-items: flex-start;
}
</style>
