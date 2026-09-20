<script setup lang="ts">
import { useWorkspaceSortActivity } from './managementWorkspaceContext';
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import {
  confirmAction,
  UiSpin,
  UiButton,
  type UiTreeChangeReason,
  type UiRecordInlineAction,
} from '@muyun/vue-ui-antdv';
import { canQueryRecycleBin, hasRecycleBinAbility, type ModuleContext } from '@muyun/web-core';
import RecordListExplorer, { type RecordListExplorerRecord } from './RecordListExplorer.vue';
import type { RecordExplorerItemDescriptor } from './recordExplorerItemModel';
import {
  defaultCrudRecordListMatches,
  defaultCrudRecordListTitle,
  type CrudRecordListBase,
} from './crudRecordListModel';
import { presentPlatformError } from './platformErrorFeedback';
import { recycleBinRestoreUnavailableReason, useRecycleBinState } from './recycleBinState';
import { sortPartitionKey } from './sortPartitionKey';
import type {
  RecordQueryListQueryController,
  RecordQueryListQuerySnapshot,
} from './recordQueryListQueryController';

defineOptions({ name: 'CrudRecordListExplorer' });

export type CrudRecordListMode = 'normal' | 'recycleBin';

const props = withDefaults(
  defineProps<{
    context: ModuleContext<CrudRecordListBase>;
    selectedId?: string;
    reloadKey?: number;
    /**
     * Descriptor-owned criteria from an upstream navigator selection.
     * They are forwarded as standard query values rather than filtered in the browser.
     */
    externalQueryValues?: Record<string, unknown>;
    navigatorHostModuleAlias?: string;
    navigatorTargetLevelKey?: string;
    /** Server-side search for large explorer sources; `keyword` still filters the rendered labels. */
    quickSearch?: string;
    keyword?: string;
    emptyDescription?: string;
    loadingTip?: string;
    fallbackTitle?: string;
    titleOf?: (record: CrudRecordListBase) => string;
    subtitleOf?: (record: CrudRecordListBase) => string | undefined;
    itemOf?: (record: CrudRecordListBase) => RecordExplorerItemDescriptor | undefined;
    actionsOf?: (record: CrudRecordListBase) => UiRecordInlineAction[];
    filterOption?: (record: CrudRecordListBase, normalizedKeyword: string) => boolean;
    tagOf?: (record: CrudRecordListBase) => string | undefined;
    mutedOf?: (record: CrudRecordListBase) => boolean;
    mode?: CrudRecordListMode;
    /** Enables standard flat-list drag ordering when the result is unfiltered. */
    sorting?: boolean;
    /** Exposes the rendered explorer search through the standard assistant query port. */
    queryQuickSearchEnabled?: boolean;
  }>(),
  {
    selectedId: undefined,
    reloadKey: undefined,
    externalQueryValues: undefined,
    navigatorHostModuleAlias: undefined,
    navigatorTargetLevelKey: undefined,
    quickSearch: undefined,
    keyword: '',
    emptyDescription: '暂无记录',
    loadingTip: '加载记录列表',
    fallbackTitle: '未命名记录',
    titleOf: undefined,
    subtitleOf: undefined,
    itemOf: undefined,
    actionsOf: undefined,
    filterOption: undefined,
    tagOf: undefined,
    mutedOf: undefined,
    mode: 'normal',
    sorting: false,
    queryQuickSearchEnabled: false,
  },
);

const emit = defineEmits<{
  select: [record: CrudRecordListBase];
  deselect: [];
  action: [action: UiRecordInlineAction, record: CrudRecordListBase];
  loaded: [records: CrudRecordListBase[], total?: number];
  restored: [];
  recycleBinSummary: [total: number | undefined];
  sorted: [];
  'update:keyword': [value: string];
  queryControllerChange: [controller: RecordQueryListQueryController | undefined];
}>();

const loading = ref(false);
const loadError = ref(false);
const assistantKeyword = ref(props.keyword.trim());
const changeReason = ref<UiTreeChangeReason>('reset');
const sortingRequest = ref(false);
useWorkspaceSortActivity(sortingRequest);
const records = ref<CrudRecordListBase[]>([]);
let recordsRequestSeq = 0;
let queryControllerRevision = 0;
const loadedTotal = ref<number>();
const loadedTotalKnown = ref(false);
interface QueryControllerSettlement {
  resolve(): void;
  reject(cause: Error): void;
}
const queryControllerSettlements = new Set<QueryControllerSettlement>();
const recycleBinState = useRecycleBinState({
  context: () => props.context,
  recordTitle: (record) => recordTitle(record),
});
const recycleBinItems = computed(
  () =>
    new Map(
      recycleBinState.items.value
        .filter((item) => Boolean(item.record.id))
        .map((item) => [String(item.record.id), item] as const),
    ),
);
const recycleBinEnabled = computed(() => hasRecycleBinAbility(props.context));

const listRecords = computed<RecordListExplorerRecord[]>(() => records.value);
const sortingEnabled = computed(
  () =>
    props.sorting &&
    props.mode === 'normal' &&
    !loading.value &&
    !sortingRequest.value &&
    !props.keyword.trim(),
);

/**
 * Mirrors the module's server-declared sort partition. Page compositions enable ordering but
 * never need to know its fields; the service remains authoritative for validating each move.
 */
function sortPartitionOf(record: CrudRecordListBase) {
  const runtime = props.context.runtime.snapshot?.();
  if (!runtime) return undefined;
  const fields = runtime.sortPartitionFields ?? [];
  const values = record as Record<string, unknown>;
  if (fields.some((field) => !Object.prototype.hasOwnProperty.call(values, field))) return undefined;
  return sortPartitionKey(fields.map((field) => values[field]));
}

onMounted(() => {
  emit('queryControllerChange', queryController);
  void loadRecords();
});

onUnmounted(() => {
  emit('queryControllerChange', undefined);
  for (const settlement of queryControllerSettlements) {
    queryControllerSettlements.delete(settlement);
    settlement.reject(new DOMException('List query was disposed', 'AbortError'));
  }
});

watch(loading, (active) => {
  if (active) return;
  for (const settlement of queryControllerSettlements) {
    queryControllerSettlements.delete(settlement);
    settlement.resolve();
  }
});

watch(
  () => recycleBinState.summaryTotal.value,
  (total) => {
    if (recycleBinEnabled.value) emit('recycleBinSummary', total);
  },
  { immediate: true },
);

watch(
  () => props.reloadKey,
  () => loadRecords('interaction'),
);

watch(
  () => props.context,
  () => loadRecords(),
);

watch(
  () => props.externalQueryValues,
  () => loadRecords(),
  { deep: true },
);

watch(
  () => props.quickSearch,
  () => loadRecords('interaction'),
);

watch(
  () => props.mode,
  () => loadRecords(),
);

watch(
  () => props.keyword,
  (value) => {
    const normalized = value.trim();
    if (assistantKeyword.value === normalized) return;
    assistantKeyword.value = normalized;
    queryControllerRevision += 1;
  },
);

async function loadRecords(reason: UiTreeChangeReason = 'reset') {
  queryControllerRevision += 1;
  const requestSeq = ++recordsRequestSeq;
  loading.value = true;
  loadError.value = false;
  if (reason === 'reset') records.value = [];
  try {
    await props.context.runtime.ready;
    if (props.mode === 'recycleBin') {
      await recycleBinState.load();
      if (requestSeq !== recordsRequestSeq) return;
      records.value = recycleBinState.items.value.map((item) => item.record);
      loadedTotal.value = records.value.length;
      loadedTotalKnown.value = false;
      emit('loaded', records.value);
      return;
    }
    const response = await props.context.abilities.crud().query({
      page: { pageNum: 1, pageSize: 200 },
      ...(props.quickSearch?.trim() ? { quickSearch: props.quickSearch.trim() } : {}),
      ...(props.externalQueryValues && Object.keys(props.externalQueryValues).length > 0
        ? { externalQueryValues: props.externalQueryValues }
        : {}),
      ...(props.navigatorHostModuleAlias && props.navigatorTargetLevelKey
        ? {
            navigatorHostModuleAlias: props.navigatorHostModuleAlias,
            navigatorTargetLevelKey: props.navigatorTargetLevelKey,
          }
        : {}),
    });
    if (requestSeq !== recordsRequestSeq) return;
    changeReason.value = reason;
    records.value = response.records;
    loadedTotal.value = response.total;
    loadedTotalKnown.value = response.totalKnown !== false;
    emit('loaded', response.records, response.totalKnown === false ? undefined : response.total);
    if (canQueryRecycleBin(props.context)) void recycleBinState.refreshSummary();
  } catch (cause) {
    if (requestSeq !== recordsRequestSeq) return;
    loadError.value = true;
    loadedTotal.value = undefined;
    loadedTotalKnown.value = false;
    if (reason === 'reset') {
      records.value = [];
      emit('loaded', []);
    }
    presentPlatformError(cause, { source: 'crud-record-list-explorer', phase: 'load' });
  } finally {
    if (requestSeq === recordsRequestSeq) loading.value = false;
  }
}

function recordTitle(record: CrudRecordListBase) {
  const item = props.itemOf?.(record);
  return item?.title ?? props.titleOf?.(record) ?? defaultCrudRecordListTitle(record, props.fallbackTitle);
}

function recordCode(record: CrudRecordListBase) {
  const item = props.itemOf?.(record);
  return item
    ? item.secondary
    : props.subtitleOf
      ? props.subtitleOf(record)
      : (record.alias ?? record.code ?? record.id);
}

function matchesKeyword(record: CrudRecordListBase, normalized: string) {
  return (
    props.filterOption?.(record, normalized) ??
    defaultCrudRecordListMatches(record, normalized, recordTitle, recordCode)
  );
}

function queryControllerSnapshot(): RecordQueryListQuerySnapshot {
  const keyword = assistantKeyword.value;
  const matchingRecords = keyword
    ? records.value.filter((record) => matchesKeyword(record, keyword.toLowerCase()))
    : records.value;
  const cacheComplete = loadedTotalKnown.value && (loadedTotal.value ?? 0) <= records.value.length;
  const rows = matchingRecords.slice(0, 20).map((record) => ({
    ...(record.id == null ? {} : { id: String(record.id) }),
    cells: [
      { fieldName: 'title', title: '标题', value: recordTitle(record).slice(0, 500) },
      ...(recordCode(record)
        ? [
            {
              fieldName: 'secondary',
              title: '辅助标识',
              value: String(recordCode(record)).slice(0, 500),
            },
          ]
        : []),
    ],
  }));
  const totalKnown = keyword ? cacheComplete : loadedTotalKnown.value;
  const total = keyword ? matchingRecords.length : (loadedTotal.value ?? records.value.length);
  return {
    mode: props.mode,
    status: loading.value ? 'loading' : loadError.value ? 'error' : 'ready',
    quickSearchEnabled: props.mode === 'normal' && !loadError.value && props.queryQuickSearchEnabled,
    quickSearchFields: [
      { name: 'title', title: '标题', valueType: 'STRING' },
      { name: 'secondary', title: '辅助标识', valueType: 'STRING' },
    ],
    ...(keyword ? { appliedQuickSearch: keyword } : {}),
    pageNum: 1,
    pageSize: rows.length,
    total,
    totalKnown,
    rows,
    truncated: (totalKnown ? total : matchingRecords.length) > rows.length || !totalKnown,
  };
}

const queryController: RecordQueryListQueryController = {
  revision: () => queryControllerRevision,
  interactionRevision: () =>
    JSON.stringify({ mode: props.mode, keyword: assistantKeyword.value, reloadKey: props.reloadKey }),
  snapshot: queryControllerSnapshot,
  async settle(signal?: AbortSignal) {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      throwIfQuerySettlementAborted(signal);
      await nextTick();
      throwIfQuerySettlementAborted(signal);
      if (loading.value) await waitForQueryControllerLoad(signal);
      const settledRevision = queryControllerRevision;
      await nextTick();
      throwIfQuerySettlementAborted(signal);
      if (!loading.value && queryControllerRevision === settledRevision) return queryControllerSnapshot();
    }
    throw new Error('List query did not settle on a stable revision');
  },
  async applyQuickSearch(keyword: string) {
    const normalized = keyword.trim();
    if (props.mode !== 'normal' || !props.queryQuickSearchEnabled) {
      throw new Error('Quick search is unavailable for the current list');
    }
    if (normalized.length > 500) throw new Error('Quick search keyword is too long');
    const previous = assistantKeyword.value;
    if (previous !== normalized) {
      assistantKeyword.value = normalized;
      queryControllerRevision += 1;
    }
    emit('update:keyword', normalized);
    for (let attempt = 0; attempt < 8; attempt += 1) {
      await nextTick();
      if (props.keyword.trim() === normalized) return queryControllerSnapshot();
    }
    if (assistantKeyword.value === normalized) {
      assistantKeyword.value = previous;
      queryControllerRevision += 1;
    }
    throw new Error('Quick search did not reach the rendered explorer');
  },
};

function waitForQueryControllerLoad(signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    let settlement!: QueryControllerSettlement;
    const cleanup = () => signal?.removeEventListener('abort', abort);
    const abort = () => {
      queryControllerSettlements.delete(settlement);
      cleanup();
      reject(new DOMException('Assistant invocation was cancelled', 'AbortError'));
    };
    settlement = {
      resolve: () => {
        cleanup();
        resolve();
      },
      reject: (cause) => {
        cleanup();
        reject(cause);
      },
    };
    queryControllerSettlements.add(settlement);
    signal?.addEventListener('abort', abort, { once: true });
  });
}

function throwIfQuerySettlementAborted(signal?: AbortSignal) {
  if (signal?.aborted) throw new DOMException('Assistant invocation was cancelled', 'AbortError');
}

function recordActions(record: CrudRecordListBase): UiRecordInlineAction[] {
  if (props.mode !== 'recycleBin') {
    return props.actionsOf?.(record) ?? [];
  }
  const item = recycleBinItems.value.get(String(record.id ?? ''));
  if (!item) return [];
  return [
    ...(props.context.can('recycleBinRestore') === true
      ? [
          {
            key: 'restore',
            title: '恢复',
            iconName: 'reload' as const,
            showLabel: true,
            disabled: !item.restorable || recycleBinState.acting.value,
            disabledReason: recycleBinRestoreUnavailableReason(item),
          },
        ]
      : []),
    ...(item.purgeable && props.context.can('recycleBinPurge') === true
      ? [
          {
            key: 'purge',
            title: '彻底删除',
            iconName: 'delete' as const,
            danger: true,
            disabled: recycleBinState.acting.value,
          },
        ]
      : []),
  ];
}

async function handleAction(action: UiRecordInlineAction, record: CrudRecordListBase) {
  if (props.mode === 'recycleBin') {
    await handleRecycleBinAction(action, record);
    return;
  }
  emit('action', action, record);
}

async function handleSort(event: {
  dragRecord: RecordListExplorerRecord;
  dropRecord: RecordListExplorerRecord;
  position: -1 | 1;
}) {
  if (!sortingEnabled.value) return;
  const dragId = event.dragRecord.id == null ? undefined : String(event.dragRecord.id);
  const dropId = event.dropRecord.id == null ? undefined : String(event.dropRecord.id);
  if (!dragId || !dropId) return;
  const partition = sortPartitionOf(event.dragRecord as CrudRecordListBase);
  if (partition === undefined) return;
  const reordered = records.value.filter((record) => sortPartitionOf(record) === partition);
  const sourceIndex = reordered.findIndex((record) => String(record.id) === dragId);
  const targetIndex = reordered.findIndex((record) => String(record.id) === dropId);
  if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) return;
  const [moving] = reordered.splice(sourceIndex, 1);
  const adjustedTargetIndex = reordered.findIndex((record) => String(record.id) === dropId);
  reordered.splice(event.position < 0 ? adjustedTargetIndex : adjustedTargetIndex + 1, 0, moving);
  const movedIndex = reordered.indexOf(moving);
  if (movedIndex === sourceIndex) return;
  const sort = props.context.abilities.crud().sort;
  if (!sort) return;

  sortingRequest.value = true;
  const context = props.context;
  const scopeVersion = recordsRequestSeq;
  try {
    await props.context.runtime.ready;
    await sort(dragId, {
      previousId: reordered[movedIndex - 1]?.id == null ? null : String(reordered[movedIndex - 1].id),
      nextId: reordered[movedIndex + 1]?.id == null ? null : String(reordered[movedIndex + 1].id),
    });
    if (context !== props.context || scopeVersion !== recordsRequestSeq) return;
    await loadRecords('interaction');
    emit('sorted');
  } catch (cause) {
    presentPlatformError(cause, { source: 'crud-record-list-explorer', phase: 'action' });
  } finally {
    sortingRequest.value = false;
  }
}

async function handleRecycleBinAction(action: UiRecordInlineAction, record: CrudRecordListBase) {
  const item = recycleBinItems.value.get(String(record.id ?? ''));
  if (!item) return;
  const title = recycleBinState.recordTitleOf(item);
  if (action.key === 'restore') {
    const confirmed = await confirmAction({
      title: '恢复记录',
      content: `确认恢复「${title}」及其关联资源？`,
      okText: '恢复',
    });
    if (confirmed && (await recycleBinState.restore(item, false))) {
      emit('restored');
      await loadRecords('interaction');
    }
    return;
  }
  if (action.key === 'purge') {
    const confirmed = await confirmAction({
      title: '彻底删除',
      content: `彻底删除后数据不可恢复。确认彻底删除「${title}」及其关联资源？`,
      okText: '彻底删除',
      danger: true,
      requiredText: title,
    });
    if (confirmed && (await recycleBinState.purge(item, false))) {
      await loadRecords('interaction');
    }
  }
}
</script>

<template>
  <div class="crud-record-list-explorer">
    <UiButton v-if="loadError" type="text" @click="loadRecords()">重新加载</UiButton>
    <UiSpin v-if="loading && records.length === 0" :tip="loadingTip" />
    <RecordListExplorer
      v-else
      :records="listRecords"
      :change-reason="changeReason"
      :selected-id="selectedId"
      :key-of="(record) => record.id"
      :keyword="keyword"
      :empty-description="emptyDescription"
      :title-of="(record) => recordTitle(record as CrudRecordListBase)"
      :code-of="(record) => recordCode(record as CrudRecordListBase)"
      :item-of="(record) => itemOf?.(record as CrudRecordListBase)"
      :filter-option="(record, normalized) => matchesKeyword(record as CrudRecordListBase, normalized)"
      :actions-of="(record) => recordActions(record as CrudRecordListBase)"
      :tag-of="(record) => tagOf?.(record as CrudRecordListBase)"
      :muted-of="(record) => mutedOf?.(record as CrudRecordListBase) ?? record.enabled === false"
      :sorting="sortingEnabled"
      :sort-partition-of="sortPartitionOf"
      @select="emit('select', $event as CrudRecordListBase)"
      @deselect="emit('deselect')"
      @action="(action, record) => handleAction(action, record as CrudRecordListBase)"
      @sort="handleSort"
    />
  </div>
</template>

<style scoped>
.crud-record-list-explorer {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.crud-record-list-explorer :deep(.record-list-explorer) {
  flex: 1 1 auto;
  min-height: 0;
}
</style>
