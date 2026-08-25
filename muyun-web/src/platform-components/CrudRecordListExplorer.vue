<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { confirmAction, UiSpin, type UiRecordInlineAction } from '@muyun/vue-ui-antdv';
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
  }>(),
  {
    selectedId: undefined,
    reloadKey: undefined,
    externalQueryValues: undefined,
    navigatorHostModuleAlias: undefined,
    navigatorTargetLevelKey: undefined,
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
  },
);

const emit = defineEmits<{
  select: [record: CrudRecordListBase];
  deselect: [];
  action: [action: UiRecordInlineAction, record: CrudRecordListBase];
  loaded: [records: CrudRecordListBase[]];
  restored: [];
  recycleBinSummary: [total: number | undefined];
}>();

const loading = ref(false);
const records = ref<CrudRecordListBase[]>([]);
let recordsRequestSeq = 0;
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

onMounted(loadRecords);

watch(
  () => recycleBinState.summaryTotal.value,
  (total) => {
    if (recycleBinEnabled.value) emit('recycleBinSummary', total);
  },
  { immediate: true },
);

watch(
  () => props.reloadKey,
  () => loadRecords(),
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
  () => props.mode,
  () => loadRecords(),
);

async function loadRecords() {
  const requestSeq = ++recordsRequestSeq;
  loading.value = true;
  try {
    await props.context.runtime.ready;
    if (props.mode === 'recycleBin') {
      await recycleBinState.load();
      if (requestSeq !== recordsRequestSeq) return;
      records.value = recycleBinState.items.value.map((item) => item.record);
      emit('loaded', records.value);
      return;
    }
    const response = await props.context.abilities.crud().query({
      page: { pageNum: 1, pageSize: 200 },
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
    records.value = response.records;
    emit('loaded', response.records);
    if (canQueryRecycleBin(props.context)) void recycleBinState.refreshSummary();
  } catch (cause) {
    if (requestSeq !== recordsRequestSeq) return;
    records.value = [];
    emit('loaded', []);
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
      await loadRecords();
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
      await loadRecords();
    }
  }
}
</script>

<template>
  <div class="crud-record-list-explorer">
    <UiSpin v-if="loading" :tip="loadingTip" />
    <RecordListExplorer
      v-else
      :records="listRecords"
      :selected-id="selectedId"
      :keyword="keyword"
      :empty-description="emptyDescription"
      :title-of="(record) => recordTitle(record as CrudRecordListBase)"
      :code-of="(record) => recordCode(record as CrudRecordListBase)"
      :item-of="(record) => itemOf?.(record as CrudRecordListBase)"
      :filter-option="(record, normalized) => matchesKeyword(record as CrudRecordListBase, normalized)"
      :actions-of="(record) => recordActions(record as CrudRecordListBase)"
      :tag-of="(record) => tagOf?.(record as CrudRecordListBase)"
      :muted-of="(record) => mutedOf?.(record as CrudRecordListBase) ?? record.enabled === false"
      @select="emit('select', $event as CrudRecordListBase)"
      @deselect="emit('deselect')"
      @action="(action, record) => handleAction(action, record as CrudRecordListBase)"
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
