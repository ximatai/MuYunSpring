<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  UiButton,
  UiDataTable,
  UiError,
  UiModal,
  UiSearchInput,
  UiSelect,
  UiTagList,
  UiTree,
  type UiDataTableColumn,
  type UiDataTablePagination,
  type UiDataTableRecord,
  type UiDataTableSelection,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';
import ObjectPickerInput from './ObjectPickerInput.vue';
import RecordExplorerPanel from './RecordExplorerPanel.vue';
import {
  normalizeReferencePickerIds,
  referencePickerSummary,
  referencePickerValue,
  type ReferencePickerAxisSelection,
  type ReferencePickerCandidate,
  type ReferencePickerColumn,
  type ReferencePickerId,
  type ReferencePickerNavigationAxis,
  type ReferencePickerNavigationItem,
  type ReferencePickerPage,
  type ReferencePickerProvider,
  type ReferencePickerSelectionSummary,
  type ReferencePickerTreeNode,
  type ReferencePickerValidity,
} from './referencePickerModel';
import { referencePickerReadErrorOf, type ReferencePickerReadError } from './referencePickerReadError';

defineOptions({ name: 'ReferencePicker' });

const props = withDefaults(
  defineProps<{
    value?: ReferencePickerId | readonly ReferencePickerId[];
    multiple?: boolean;
    maxSelection?: number;
    provider: ReferencePickerProvider;
    /** Changes in source dependencies or authorization must isolate all remembered candidates. */
    reloadKey?: string | number;
    mode?: 'dialog' | 'dropdown';
    columns?: readonly ReferencePickerColumn[];
    placeholder?: string;
    disabled?: boolean;
    allowClear?: boolean;
    pageSize?: number;
    title?: string;
    searchPlaceholder?: string;
    emptyDescription?: string;
    selectionNoun?: string;
  }>(),
  {
    value: undefined,
    multiple: false,
    maxSelection: undefined,
    reloadKey: undefined,
    mode: 'dialog',
    columns: () => [{ key: 'title', title: '名称' }],
    placeholder: '搜索并选择',
    disabled: false,
    allowClear: true,
    pageSize: 20,
    title: '选择引用记录',
    searchPlaceholder: '搜索',
    emptyDescription: '没有可选择的记录',
    selectionNoun: '记录',
  },
);

const emit = defineEmits<{
  'update:value': [value: ReferencePickerId | ReferencePickerId[] | undefined];
  /** Only a user action (confirmation, double click, dropdown choice, or clear) emits select. */
  select: [candidates: ReferencePickerCandidate[]];
  /** Resolution is display-only and deliberately distinct from user selection. */
  'selection-resolved': [candidates: ReferencePickerCandidate[]];
  'validity-change': [validity: ReferencePickerValidity];
}>();

const open = ref(false);
const keyword = ref('');
const pageNum = ref(1);
const page = ref<ReferencePickerPage>({ records: [], total: 0 });
const tree = ref<ReferencePickerTreeNode[]>([]);
const scopeSelections = ref<ReferencePickerAxisSelection[]>([]);
const loading = ref(false);
const pageError = ref<ReferencePickerReadError>();
const pageReadFailed = ref(false);
const failedCompletionKeyword = ref<string>();
const resolveError = ref<ReferencePickerReadError>();
const draftIds = ref<ReferencePickerId[]>([]);
const selectionVersion = ref(0);
const validity = ref<ReferencePickerValidity>({ valid: true, status: 'ready' });
const candidatesById = ref(new Map<ReferencePickerId, ReferencePickerCandidate>());
const resolvedById = ref(new Map<ReferencePickerId, ReferencePickerCandidate>());
let pageRequestVersion = 0;
let treeRequestVersion = 0;
let resolveRequestVersion = 0;
let completionRequestVersion = 0;
let unmounted = false;
let clearEmittedForDraft = false;

const externalIds = computed(() => normalizeReferencePickerIds(props.value, props.multiple));
const pageCount = computed(() => Math.max(1, Math.ceil(page.value.total / props.pageSize)));
const dataColumns = computed<UiDataTableColumn[]>(() => props.columns.map((column) => ({ ...column })));
const rows = computed<UiDataTableRecord[]>(() =>
  page.value.records.map((candidate) => ({
    ...(candidate.projections ?? {}),
    id: candidate.id,
    title: candidate.title,
    subtitle: candidate.subtitle,
  })),
);
const browsingTree = computed(() => open.value && !keyword.value.trim() && Boolean(props.provider.loadTree));
const treeNodes = computed<UiTreeNode[]>(() => tree.value.map(treeNode));
const summary = computed(() => summariesFor(externalIds.value, resolvedById.value));
const inputSummary = computed(() => {
  if (!summary.value.length) return '';
  if (props.multiple) return `已选择 ${summary.value.length} 个${props.selectionNoun}`;
  const selected = summary.value[0]!;
  return selected.unavailable ? `${selected.title}（不可用）` : selected.title;
});
const tags = computed(() =>
  selectedCandidates(draftIds.value).map((candidate) => ({
    key: candidate.id,
    label: candidate.unavailable ? `${candidate.title}（不可用）` : candidate.title,
  })),
);
const selection = computed<UiDataTableSelection | undefined>(() =>
  props.multiple
    ? {
        selectedRowKeys: draftIds.value,
        preserveSelectedRowKeys: true,
        disabledOf: (record) => !canSelect(String(record.id)),
        onChange: (keys) => updateDraft(keys.map(String)),
      }
    : undefined,
);
const tablePagination = computed<UiDataTablePagination>(() => ({
  current: pageNum.value,
  total: page.value.total,
  pageSize: props.pageSize,
  showSizeChanger: false,
  showQuickJumper: false,
  onChange: changePage,
}));
const dropdownOptions = computed(() =>
  [
    ...page.value.records.filter((candidate) => !externalIds.value.includes(candidate.id)),
    ...externalIds.value.map(selectionCandidate),
  ].map((candidate) => ({
    value: candidate.id,
    label: candidate.unavailable ? `${candidate.title}（不可用）` : candidate.title,
    disabled:
      candidate.unavailable ||
      candidate.disabled ||
      (externalIds.value.includes(candidate.id) && !resolvedById.value.has(candidate.id)) ||
      (!externalIds.value.includes(candidate.id) && !canSelect(candidate.id)),
  })),
);

watch(
  externalIds,
  (ids, previousIds) => {
    if (previousIds && sameIds(ids, previousIds)) return;
    invalidateCompletion();
    void resolveSelection(ids);
    draftIds.value = [...ids];
    clearEmittedForDraft = false;
    if (!sameIds(ids, previousIds ?? [])) selectionVersion.value += 1;
    setValidity('ready');
  },
  { immediate: true },
);
watch(validity, (next) => emit('validity-change', next), { immediate: true, flush: 'sync' });
watch(
  () => [props.provider, providerIdentityKey(props.provider), props.reloadKey] as const,
  () => resetProviderState(),
);
watch(
  () => props.disabled,
  () => invalidateCompletion(),
);

onMounted(() => {
  if (props.mode === 'dropdown') void loadPage();
});

onBeforeUnmount(() => {
  unmounted = true;
  invalidateCompletion();
  pageRequestVersion += 1;
  treeRequestVersion += 1;
  resolveRequestVersion += 1;
});

function summariesFor(
  ids: readonly ReferencePickerId[],
  candidates = candidatesById.value,
): ReferencePickerSelectionSummary[] {
  return ids.map((id) => referencePickerSummary(candidates.get(id) ?? unresolvedCandidate(id)));
}

function unresolvedCandidate(id: ReferencePickerId): ReferencePickerCandidate {
  return { id, title: id };
}

function selectionCandidate(id: ReferencePickerId): ReferencePickerCandidate {
  return resolvedById.value.get(id) ?? unresolvedCandidate(id);
}

function selectedCandidates(ids: readonly ReferencePickerId[]): ReferencePickerCandidate[] {
  return ids.map(
    (id) =>
      (externalIds.value.includes(id) ? selectionCandidate(id) : candidatesById.value.get(id)) ??
      unresolvedCandidate(id),
  );
}

function remember(candidates: readonly ReferencePickerCandidate[]) {
  if (!candidates.length) return;
  const next = new Map(candidatesById.value);
  for (const candidate of candidates) next.set(candidate.id, candidate);
  candidatesById.value = next;
}

function resetProviderState() {
  invalidateCompletion();
  pageRequestVersion += 1;
  treeRequestVersion += 1;
  resolveRequestVersion += 1;
  candidatesById.value = new Map();
  resolvedById.value = new Map();
  page.value = { records: [], total: 0 };
  tree.value = [];
  scopeSelections.value = [];
  draftIds.value = [...externalIds.value];
  keyword.value = '';
  loading.value = false;
  pageError.value = undefined;
  pageReadFailed.value = false;
  resolveError.value = undefined;
  // The provider has invalidated an in-flight completion, but the compact draft is
  // still visible. Let its next blur start a fresh completion instead of leaving it
  // permanently "resolving" against an abandoned provider.
  const validityChanged = validity.value.status === 'resolving' && setValidity('editing', '请完成引用选择');
  if (!validityChanged) emit('validity-change', validity.value);
  void resolveSelection(externalIds.value);
  if (open.value || props.mode === 'dropdown') {
    pageNum.value = 1;
    void loadBrowse();
  }
}

function invalidateCompletion() {
  completionRequestVersion += 1;
  failedCompletionKeyword.value = undefined;
}

function sameIds(left: readonly ReferencePickerId[], right: readonly ReferencePickerId[]) {
  return left.length === right.length && left.every((id, index) => id === right[index]);
}

function setValidity(status: ReferencePickerValidity['status'], message?: string) {
  const next: ReferencePickerValidity =
    status === 'ready' ? { valid: true, status } : { valid: false, status, ...(message ? { message } : {}) };
  if (
    validity.value.valid === next.valid &&
    validity.value.status === next.status &&
    validity.value.message === next.message
  )
    return false;
  validity.value = next;
  return true;
}

function providerIdentityKey(provider: ReferencePickerProvider) {
  const identity = provider.identity;
  return [
    identity.targetModuleAlias,
    identity.source.kind,
    identity.source.id,
    identity.authorizationScope ?? '',
  ].join('\u0000');
}

function forget(ids: readonly ReferencePickerId[]) {
  if (!ids.some((id) => candidatesById.value.has(id))) return;
  const next = new Map(candidatesById.value);
  for (const id of ids) next.delete(id);
  candidatesById.value = next;
}

function rememberResolved(
  ids: readonly ReferencePickerId[],
  candidates: readonly ReferencePickerCandidate[],
) {
  const byId = new Map(candidates.map((candidate) => [candidate.id, candidate]));
  const next = new Map(resolvedById.value);
  for (const id of ids) next.set(id, byId.get(id) ?? { id, title: id, unavailable: true });
  resolvedById.value = next;
}

function forgetResolved(ids: readonly ReferencePickerId[]) {
  if (!ids.some((id) => resolvedById.value.has(id))) return;
  const next = new Map(resolvedById.value);
  for (const id of ids) next.delete(id);
  resolvedById.value = next;
}

async function resolveSelection(ids: readonly ReferencePickerId[]) {
  const requestVersion = ++resolveRequestVersion;
  if (!ids.length) {
    resolvedById.value = new Map();
    resolveError.value = undefined;
    emit('selection-resolved', []);
    return;
  }
  // A resolver is the authority for historical display. Do not retain a title from
  // a prior source/range when it cannot resolve the same persisted ID now.
  forget(ids);
  forgetResolved(ids);
  resolveError.value = undefined;
  try {
    const allowedIds = new Set(ids);
    const candidates = (await props.provider.resolve([...ids])).filter((candidate) =>
      allowedIds.has(candidate.id),
    );
    if (requestVersion !== resolveRequestVersion) return;
    remember(candidates);
    rememberResolved(ids, candidates);
    resolveError.value = undefined;
    emit(
      'selection-resolved',
      ids.map((id) => resolvedById.value.get(id)!),
    );
  } catch (cause) {
    if (requestVersion !== resolveRequestVersion) return;
    // A transient resolver failure must retain the persisted ID without claiming
    // the value is unavailable. Only a successful resolver response can do that.
    emit('selection-resolved', ids.map(selectionCandidate));
    resolveError.value = referencePickerReadErrorOf(cause, '引用值回显失败');
  }
}

function openPicker(initialKeyword = '') {
  if (props.disabled) return;
  invalidateCompletion();
  draftIds.value = [...externalIds.value];
  keyword.value = initialKeyword;
  pageNum.value = 1;
  scopeSelections.value = [];
  pageError.value = undefined;
  pageReadFailed.value = false;
  open.value = true;
  void loadBrowse();
}

function openPickerWithPage(initialKeyword: string, result: ReferencePickerPage) {
  if (props.disabled || unmounted) return;
  invalidateCompletion();
  draftIds.value = [...externalIds.value];
  keyword.value = initialKeyword;
  pageNum.value = 1;
  scopeSelections.value = [];
  page.value = result;
  remember(result.records);
  pageError.value = undefined;
  pageReadFailed.value = false;
  open.value = true;
}

function closePicker() {
  invalidateCompletion();
  open.value = false;
  keyword.value = '';
  pageRequestVersion += 1;
  treeRequestVersion += 1;
  loading.value = false;
}

function treeNode(node: ReferencePickerTreeNode): UiTreeNode {
  const record = node.record;
  return {
    key: record.id,
    title: record.unavailable ? `${record.title}（不可用）` : record.title,
    secondary: record.subtitle,
    disabled: record.disabled || record.unavailable,
    isLeaf: !node.children?.length,
    ...(node.children?.length ? { children: node.children.map(treeNode) } : {}),
  };
}

function treeCandidates(nodes: readonly ReferencePickerTreeNode[]): ReferencePickerCandidate[] {
  return nodes.flatMap((node) => [node.record, ...treeCandidates(node.children ?? [])]);
}

function loadBrowse() {
  if (browsingTree.value) return loadTree();
  return loadPage();
}

async function loadPage() {
  treeRequestVersion += 1;
  const requestVersion = ++pageRequestVersion;
  loading.value = true;
  pageError.value = undefined;
  pageReadFailed.value = false;
  // Page rows are only authorized for the exact keyword, scope, and page being requested.
  page.value = { records: [], total: 0 };
  try {
    const result = await props.provider.searchPage({
      keyword: keyword.value.trim(),
      pageNum: pageNum.value,
      pageSize: props.pageSize,
      scope: { selections: [...scopeSelections.value] },
    });
    if (requestVersion !== pageRequestVersion) return;
    page.value = result;
    remember(result.records);
  } catch (cause) {
    if (requestVersion !== pageRequestVersion) return;
    pageReadFailed.value = true;
    pageError.value = referencePickerReadErrorOf(cause, '引用候选加载失败');
  } finally {
    if (requestVersion === pageRequestVersion) loading.value = false;
  }
}

async function loadTree() {
  const loadTree = props.provider.loadTree;
  if (!loadTree) return loadPage();
  const requestVersion = ++treeRequestVersion;
  pageRequestVersion += 1;
  loading.value = true;
  pageError.value = undefined;
  pageReadFailed.value = false;
  tree.value = [];
  page.value = { records: [], total: 0 };
  try {
    const result = await loadTree({ scope: { selections: [...scopeSelections.value] } });
    if (requestVersion !== treeRequestVersion) return;
    tree.value = result;
    remember(treeCandidates(result));
  } catch (cause) {
    if (requestVersion !== treeRequestVersion) return;
    pageReadFailed.value = true;
    pageError.value = referencePickerReadErrorOf(cause, '引用树加载失败');
  } finally {
    if (requestVersion === treeRequestVersion) loading.value = false;
  }
}

function search(value: string) {
  invalidateCompletion();
  keyword.value = value;
  pageNum.value = 1;
  void loadBrowse();
}

function changePage(nextPage: number) {
  if (nextPage < 1 || nextPage > pageCount.value || nextPage === pageNum.value) return;
  pageNum.value = nextPage;
  void loadBrowse();
}

function canSelect(id: ReferencePickerId) {
  if (externalIds.value.includes(id)) return true;
  const candidate = candidatesById.value.get(id);
  if (!candidate || candidate.disabled || candidate.unavailable) return false;
  return (
    !props.multiple ||
    draftIds.value.includes(id) ||
    props.maxSelection === undefined ||
    draftIds.value.length < props.maxSelection
  );
}

function updateDraft(ids: ReferencePickerId[]) {
  const next = normalizeReferencePickerIds(ids, props.multiple).filter((id) => {
    if (externalIds.value.includes(id)) return true;
    const candidate = candidatesById.value.get(id);
    return Boolean(candidate && !candidate.disabled && !candidate.unavailable);
  });
  draftIds.value = props.maxSelection === undefined ? next : next.slice(0, props.maxSelection);
}

function updateTreeDraft(ids: string[]) {
  if (props.multiple) updateDraft(ids);
}

function selectTree(node: UiTreeNode) {
  if (!props.multiple && canSelect(node.key)) updateDraft([node.key]);
}

function deselectTree() {
  if (!props.multiple) updateDraft([]);
}

function canCommit() {
  if (pageReadFailed.value) return false;
  return draftIds.value.every((id) => {
    if (externalIds.value.includes(id)) return true;
    const candidate = candidatesById.value.get(id);
    return Boolean(candidate && !candidate.disabled && !candidate.unavailable);
  });
}

function selectSingle(record: UiDataTableRecord) {
  if (!props.disabled && !loading.value && !props.multiple && canSelect(String(record.id))) {
    draftIds.value = [String(record.id)];
  }
}

function completeSingle(record: UiDataTableRecord) {
  if (props.disabled || loading.value || props.multiple || !canSelect(String(record.id))) return;
  draftIds.value = [String(record.id)];
  commit();
}

function commit() {
  if (props.disabled || loading.value || !canCommit()) return;
  const candidates = selectedCandidates(draftIds.value);
  clearEmittedForDraft = false;
  setValidity('ready');
  emit('update:value', referencePickerValue(draftIds.value, props.multiple));
  emit('select', candidates);
  selectionVersion.value += 1;
  closePicker();
}

function clearSelection() {
  if (!props.allowClear || props.disabled || loading.value || clearEmittedForDraft) return;
  clearEmittedForDraft = true;
  invalidateCompletion();
  draftIds.value = [];
  if (!externalIds.value.length) selectionVersion.value += 1;
  setValidity('ready');
  emit('update:value', referencePickerValue([], props.multiple));
  emit('select', []);
}

function draftChanged(draft: string) {
  invalidateCompletion();
  pageError.value = undefined;
  pageReadFailed.value = false;
  if (!draft.trim()) {
    if (props.allowClear) clearSelection();
    else setValidity('editing', '请完成引用选择');
    return;
  }
  if (externalIds.value.length === 1 && draft.trim() === inputSummary.value) {
    setValidity('ready');
    return;
  }
  clearEmittedForDraft = false;
  setValidity('editing', '请完成引用选择');
}

async function completeDraft(draft: string) {
  const keywordForCompletion = draft.trim();
  if (!keywordForCompletion || props.disabled || open.value) return;
  if (externalIds.value.length === 1 && keywordForCompletion === inputSummary.value) {
    selectionVersion.value += 1;
    setValidity('ready');
    return;
  }
  const requestVersion = ++completionRequestVersion;
  failedCompletionKeyword.value = undefined;
  pageError.value = undefined;
  pageReadFailed.value = false;
  setValidity('resolving', '正在确认引用选择');
  try {
    const result = await props.provider.searchPage({
      keyword: keywordForCompletion,
      pageNum: 1,
      pageSize: props.pageSize,
      scope: { selections: [] },
    });
    if (unmounted || requestVersion !== completionRequestVersion || props.disabled || open.value) return;
    if (result.total === 0) {
      setValidity('unmatched', '未找到可选择的记录');
      return;
    }
    const candidate = result.records[0];
    if (
      !props.multiple &&
      result.total === 1 &&
      result.records.length === 1 &&
      candidate &&
      !candidate.disabled &&
      !candidate.unavailable
    ) {
      pageError.value = undefined;
      pageReadFailed.value = false;
      remember(result.records);
      rememberResolved([candidate.id], [candidate]);
      draftIds.value = [candidate.id];
      commit();
      return;
    }
    if (result.total > 1 || props.multiple) {
      setValidity('editing', '请选择引用记录');
      openPickerWithPage(keywordForCompletion, result);
      return;
    }
    setValidity('unmatched', '未找到可选择的记录');
  } catch (cause) {
    if (unmounted || requestVersion !== completionRequestVersion) return;
    failedCompletionKeyword.value = keywordForCompletion;
    const error = referencePickerReadErrorOf(cause, '引用候选加载失败');
    pageError.value = error;
    setValidity('error', error?.message ?? '引用候选加载失败');
  }
}

function axisSelection(axisId: string) {
  return scopeSelections.value.find((selection) => selection.axisId === axisId)?.itemId;
}

function navigationNodes(items: readonly ReferencePickerNavigationItem[]): UiTreeNode[] {
  return items.map((item) => ({ key: item.id, title: item.title, disabled: item.disabled, isLeaf: true }));
}

function removeDependentSelections(axisId: string, axes: readonly ReferencePickerNavigationAxis[]) {
  const dependent = new Set<string>([axisId]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const axis of axes) {
      if (!dependent.has(axis.id) && axis.dependsOn?.some((parent) => dependent.has(parent))) {
        dependent.add(axis.id);
        changed = true;
      }
    }
  }
  return dependent;
}

function selectAxis(axis: ReferencePickerNavigationAxis, node: UiTreeNode) {
  const axes = page.value.navigation ?? [];
  const blocked = removeDependentSelections(axis.id, axes);
  scopeSelections.value = [
    ...scopeSelections.value.filter((selection) => !blocked.has(selection.axisId)),
    { axisId: axis.id, itemId: String(node.key) },
  ];
  // Browse navigation narrows discovery; it does not change the source's business dependencies.
  pageNum.value = 1;
  void loadPage();
}

function deselectAxis(axis: ReferencePickerNavigationAxis) {
  const blocked = removeDependentSelections(axis.id, page.value.navigation ?? []);
  scopeSelections.value = scopeSelections.value.filter((selection) => !blocked.has(selection.axisId));
  pageNum.value = 1;
  void loadPage();
}

function updateDropdown(value: string | number | (string | number)[] | null) {
  if (props.disabled || loading.value) return;
  const ids = Array.isArray(value) ? value.map(String) : value == null ? [] : [String(value)];
  updateDraft(ids);
  const selected = props.multiple ? draftIds.value : draftIds.value.slice(0, 1);
  if (ids.length === 0) clearSelection();
  else {
    if (!selected.length) {
      draftIds.value = [...externalIds.value];
      return;
    }
    const candidates = selectedCandidates(selected);
    clearEmittedForDraft = false;
    setValidity('ready');
    emit('update:value', referencePickerValue(selected, props.multiple));
    emit('select', candidates);
  }
}

function retryCompactError() {
  if (resolveError.value?.retryable) void resolveSelection(externalIds.value);
  else if (pageError.value?.retryable) {
    if (failedCompletionKeyword.value) void completeDraft(failedCompletionKeyword.value);
    else void loadBrowse();
  }
}
</script>

<template>
  <div class="reference-picker">
    <template v-if="mode === 'dropdown'">
      <UiSelect
        class="reference-picker-select"
        :value="multiple ? externalIds : externalIds[0]"
        :options="dropdownOptions"
        :mode="multiple ? 'multiple' : undefined"
        :placeholder="placeholder"
        :disabled="disabled"
        :allow-clear="allowClear"
        :show-search="true"
        :filter-option="false"
        :loading="loading"
        @search="search"
        @update:value="updateDropdown"
      >
        <template #suffixAction>
          <UiButton
            type="text"
            size="small"
            icon-name="search"
            icon-only
            :aria-label="title"
            :title="title"
            :disabled="disabled"
            @click="openPicker(keyword)"
          />
        </template>
      </UiSelect>
    </template>
    <ObjectPickerInput
      v-else
      :value="inputSummary"
      :selection-version="selectionVersion"
      :unmatched="validity.status === 'unmatched'"
      :preserve-draft="!validity.valid"
      :linked="summary.length > 0 && summary.every((candidate) => !candidate.unavailable)"
      :placeholder="placeholder"
      :disabled="disabled"
      :browse-label="title"
      @browse="openPicker"
      @clear="clearSelection"
      @draft-change="draftChanged"
      @blur="completeDraft"
    />
    <div v-if="!open && (resolveError || pageError)" class="reference-picker-error">
      <UiError :message="(resolveError ?? pageError)?.message ?? ''" />
      <UiButton v-if="(resolveError ?? pageError)?.retryable" size="small" @click="retryCompactError">
        重试
      </UiButton>
    </div>

    <UiModal
      :open="open"
      :title="title"
      :width="page.navigation?.length ? 1120 : 760"
      :confirm-disabled="loading || !canCommit()"
      :closable="true"
      @confirm="commit"
      @cancel="closePicker"
    >
      <div class="reference-picker-dialog">
        <div v-if="resolveError" class="reference-picker-error">
          <UiError :message="resolveError.message" />
          <UiButton v-if="resolveError.retryable" size="small" @click="resolveSelection(externalIds)">
            重试回显
          </UiButton>
        </div>
        <UiSearchInput
          :value="keyword"
          :placeholder="searchPlaceholder"
          :loading="loading"
          search-text="搜索"
          @update:value="keyword = $event"
          @search="search"
        />
        <div v-if="multiple" class="reference-picker-selection-summary">
          <UiTagList :items="tags" empty-text="尚未选择" />
          <span>已选 {{ draftIds.length }} 个{{ selectionNoun }}</span>
          <UiButton v-if="draftIds.length && allowClear" type="link" size="small" @click="draftIds = []">
            清空选择
          </UiButton>
        </div>
        <div class="reference-picker-browse">
          <aside v-if="page.navigation?.length" class="reference-picker-navigation" aria-label="候选范围导航">
            <RecordExplorerPanel
              v-for="axis in page.navigation"
              :key="axis.id"
              class="reference-picker-navigation-column"
              :title="axis.title"
              embedded
              :refreshable="false"
              :searchable="false"
              :collapse-action="false"
            >
              <UiTree
                v-if="axis.items.length"
                display-mode="flat"
                :nodes="navigationNodes(axis.items)"
                :selected-key="axisSelection(axis.id)"
                @select="selectAxis(axis, $event)"
                @deselect="deselectAxis(axis)"
              />
            </RecordExplorerPanel>
          </aside>
          <div class="reference-picker-results">
            <div v-if="pageError" class="reference-picker-error">
              <UiError :message="pageError.message" />
              <UiButton v-if="pageError.retryable" size="small" @click="loadBrowse">重试</UiButton>
            </div>
            <UiTree
              v-if="browsingTree"
              :nodes="treeNodes"
              :selected-key="multiple ? undefined : draftIds[0]"
              :checkable="multiple"
              :check-strictly="multiple"
              :checked-keys="multiple ? draftIds : undefined"
              :can-check="multiple ? (node) => canSelect(node.key) : undefined"
              :empty-description="emptyDescription"
              @select="selectTree"
              @deselect="deselectTree"
              @update:checked-keys="updateTreeDraft"
            />
            <UiDataTable
              v-else
              :columns="dataColumns"
              :rows="rows"
              :loading="loading"
              :pagination="tablePagination"
              :selection="selection"
              :selected-row-key="multiple ? undefined : draftIds[0]"
              :clickable-rows="!multiple"
              fill-height
              horizontal-scroll
              :empty-description="emptyDescription"
              @row-click="selectSingle"
              @row-dblclick="completeSingle"
            />
          </div>
        </div>
      </div>
    </UiModal>
  </div>
</template>

<style scoped>
.reference-picker {
  min-width: 0;
  width: min(100%, var(--muyun-standard-input-width, 280px));
}
.reference-picker-select {
  width: 100%;
}
.reference-picker-dialog {
  display: grid;
  gap: 12px;
  min-height: 430px;
}
.reference-picker-browse {
  display: grid;
  min-height: 350px;
}
.reference-picker-browse:has(.reference-picker-navigation) {
  grid-template-columns: minmax(390px, 0.8fr) minmax(0, 1.8fr);
  gap: 12px;
}
.reference-picker-navigation {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  border-right: 1px solid var(--muyun-color-border-secondary);
}
.reference-picker-navigation-column {
  min-width: 0;
  border-right: 1px solid var(--muyun-color-border-secondary);
}
.reference-picker-navigation-column:last-child {
  border-right: 0;
}
.reference-picker-results {
  min-width: 0;
}
.reference-picker-error {
  margin-bottom: 8px;
}
.reference-picker-selection-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.reference-picker-selection-summary > :first-child {
  flex: 1;
  min-width: 180px;
}
</style>
