<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiButton, UiCheckbox, UiModal } from '@muyun/vue-ui-antdv';
import {
  useLanguageContext,
  useBusinessContentGenerationBatch,
  normalizeError,
  userFacingErrorMessage,
  businessContentLanguages,
  type ModuleContext,
} from '@muyun/web-core';
import type {
  BusinessContentGenerationBatchResult,
  BusinessContentGenerationStatus,
  WebBusinessContentQueryStatus,
} from '@muyun/web-contracts';
import { useRecordSurfaceActivity } from './recordSurfaceActivity';

export interface BusinessContentGenerationRecord {
  id: string;
  version?: unknown;
  label: string;
  secondary?: string;
}
const props = defineProps<{
  status: WebBusinessContentQueryStatus;
  context: Pick<ModuleContext<unknown>, 'http' | 'moduleAlias' | 'runtime'>;
  records: BusinessContentGenerationRecord[];
  fields?: Record<string, string>;
  initiallySelected?: string[];
}>();
const emit = defineEmits<{ updated: [] }>();
const language = useLanguageContext();
const surfaceActive = useRecordSurfaceActivity();
const open = ref(false);
const selected = ref<string[]>([]);
const includeChildren = ref(true);
const source = computed(() => props.status.generationScope);
const targetLabel = computed(
  () =>
    businessContentLanguages.find((row) => row.value === props.status.language)?.label ??
    props.status.language,
);
const available = computed(
  () => source.value?.moduleAlias === props.context.moduleAlias && props.records.length > 0,
);
const chosen = computed(() => props.records.filter((row) => selected.value.includes(row.id)));
function recordIdentity(record: BusinessContentGenerationRecord) {
  return (
    record.secondary ||
    (props.records.filter((row) => row.label === record.label).length > 1 ? record.id : undefined)
  );
}
function recordCaption(record: BusinessContentGenerationRecord) {
  return [record.label, recordIdentity(record)].filter(Boolean).join(' · ');
}
const batch = useBusinessContentGenerationBatch(() => ({
  active: open.value && available.value && surfaceActive.value,
  http: props.context.http,
  source: source.value,
  records: chosen.value,
  language: props.status.language,
  includeChildren: !source.value?.parentId && includeChildren.value,
  sessionKey: props.context,
}));
const { result, loading, failed, pending, pollingPaused, canGenerate, canRetry } = batch;
function selectionKey() {
  return JSON.stringify([
    source.value,
    props.status.language,
    chosen.value.map((row) => [row.id, row.version]),
    !source.value?.parentId && includeChildren.value,
  ]);
}
let requestedSelection: string | undefined;
let openedSelectionScope: string | undefined;
watch(result, (value) => {
  // Closing stops observation, while durable work still belongs to this exact selection.
  if (value && requestedSelection === selectionKey() && !pending.value) {
    requestedSelection = undefined;
    emit('updated');
  }
});
watch(
  () => [
    props.context,
    props.context.http,
    props.status.language,
    source.value?.moduleAlias,
    source.value?.parentId,
    source.value?.relationCode,
  ],
  () => {
    open.value = false;
    requestedSelection = undefined;
    openedSelectionScope = undefined;
  },
);
const failureMessage = computed(() =>
  batch.error.value ? userFacingErrorMessage(normalizeError(batch.error.value), language) : '',
);
const summary = computed(() => {
  const rows = batch.items.value;
  const count = (states: string[]) => rows.filter((row) => states.includes(row.status.state)).length;
  return language.t(
    'platform.content.batch-summary',
    '文本项：已就绪 {ready}，处理中 {active}，待生成 {missing}，失败 {failed}，需处理 {blocked}',
    {
      ready: count(['READY', 'ORIGINAL']),
      active: count(['PENDING', 'RUNNING', 'RETRY']),
      missing: count(['MISSING']),
      failed: count(['FAILED', 'CANCELLED', 'STALE']),
      blocked: count(['DISABLED', 'SOURCE_UNKNOWN', 'REMOVED', 'UNSUPPORTED', 'UNAVAILABLE']),
    },
  );
});
type Item = BusinessContentGenerationBatchResult['items'][number];
function recordLabel(item: Item) {
  const direct = props.records.find((row) => row.id === item.recordId);
  if (direct) return recordCaption(direct);
  const parent = props.records.find((row) => row.id === item.ownerId);
  const owner = parent ? recordCaption(parent) : language.t('platform.content.batch-record', '所选记录');
  const relation = props.context.runtime
    .snapshot()
    ?.uiDescriptor?.detailRelations?.find((row) => row.code === item.relationCode);
  const children = [
    ...new Set(
      batch.items.value
        .filter((row) => row.ownerId === item.ownerId && row.relationCode === item.relationCode)
        .map((row) => row.childId),
    ),
  ];
  return `${owner} / ${relation?.title ?? language.t('platform.content.batch-child', '明细')} ${children.indexOf(item.childId) + 1}`;
}
function fieldLabel(item: Item) {
  if (!item.field) return '—';
  const relation = props.context.runtime
    .snapshot()
    ?.uiDescriptor?.detailRelations?.find((row) => row.code === item.relationCode);
  const fields = relation?.listProjection?.fields ?? relation?.queryContract?.listProjection?.fields;
  return (
    fields?.find((row) => row.fieldName === item.field)?.title ??
    props.fields?.[item.field] ??
    language.t('platform.content.batch-text', '业务文字')
  );
}
function stateLabel(state: BusinessContentGenerationStatus['state']) {
  switch (state) {
    case 'READY':
      return language.t('platform.content.batch-ready', '已有有效译文');
    case 'ORIGINAL':
      return language.t('platform.content.batch-original', '原文可直接阅读');
    case 'MISSING':
      return language.t('platform.content.batch-missing', '待生成');
    case 'PENDING':
    case 'RUNNING':
      return language.t('platform.content.batch-running', '处理中');
    case 'RETRY':
      return language.t('platform.content.batch-retrying', '等待重试');
    case 'DISABLED':
      return language.t('platform.content.batch-manual', '无自动翻译字段');
    case 'SOURCE_UNKNOWN':
      return language.t('platform.content.batch-source-unknown', '需登记来源语言');
    case 'REMOVED':
      return language.t('platform.content.batch-removed', '已明确删除，需人工维护');
    case 'UNSUPPORTED':
      return language.t('platform.content.batch-unsupported', '超出自动翻译范围');
    case 'UNAVAILABLE':
      return language.t('platform.content.batch-unavailable', '当前无法生成，可核实配置及读取资格');
    case 'FAILED':
      return language.t('platform.content.batch-failed', '生成失败，可重试');
    case 'CANCELLED':
      return language.t('platform.content.batch-cancelled', '任务已撤回，可核实后重试');
    case 'STALE':
      return language.t('platform.content.batch-stale', '来源已变化，可核实后重试');
  }
}
function start() {
  const scope = JSON.stringify([
    props.records.map((row) => [row.id, row.version]),
    props.initiallySelected ?? [],
  ]);
  // Reopening the same page keeps the subset whose durable work is being observed.
  if (openedSelectionScope !== scope) {
    const initial = props.initiallySelected?.filter((id) => props.records.some((row) => row.id === id)) ?? [];
    selected.value =
      initial.length > 0 && initial.length <= 50
        ? initial
        : props.records.length <= 50
          ? props.records.map((row) => row.id)
          : [];
    openedSelectionScope = scope;
    requestedSelection = undefined;
  }
  open.value = true;
}
function choose(id: string, checked: boolean) {
  selected.value = checked ? [...selected.value, id] : selected.value.filter((value) => value !== id);
}
async function generate(retry: boolean) {
  requestedSelection = selectionKey();
  await batch.request(retry);
}
</script>

<template>
  <UiButton v-if="available" type="text" @click="start">{{
    language.t('platform.content.batch-open', '补齐阅读译文')
  }}</UiButton>
  <UiModal
    :open="open"
    :title="language.t('platform.content.batch-title', '补齐所选业务的阅读译文')"
    :width="860"
    :confirm-text="language.t('platform.content.close', '关闭')"
    @confirm="open = false"
    @cancel="open = false"
  >
    <p>
      {{
        language.t(
          'platform.content.batch-scope',
          '目标语言：{language}；已选 {count} 条记录（每次最多 50 条）。',
          { language: targetLabel, count: selected.length },
        )
      }}
    </p>
    <p>
      {{
        language.t(
          'platform.content.batch-rule',
          '本次补齐可自动生成的文字；人工维护内容请使用译文维护。有效译文保留，关闭窗口后已登记的任务继续处理。',
        )
      }}
    </p>
    <div class="content-generation-selection">
      <UiCheckbox
        v-for="record in records"
        :key="record.id"
        :checked="selected.includes(record.id)"
        :disabled="loading || (!selected.includes(record.id) && selected.length >= 50)"
        @update:checked="choose(record.id, $event)"
        ><span>{{ record.label }}</span
        ><small v-if="recordIdentity(record)" class="content-generation-identity">{{
          recordIdentity(record)
        }}</small></UiCheckbox
      >
    </div>
    <UiCheckbox v-if="!source?.parentId" v-model:checked="includeChildren" :disabled="loading">{{
      language.t('platform.content.batch-include-children', '包含所属明细')
    }}</UiCheckbox>
    <p v-if="failed" role="alert">
      {{
        failureMessage ||
        language.t('platform.content.batch-load-failed', '暂时无法检查所选范围，请缩小选择或稍后重试')
      }}
    </p>
    <p v-if="result" role="status">{{ summary }}</p>
    <p v-if="pollingPaused">
      {{ language.t('platform.content.batch-polling-paused', '后台仍在处理，可稍后刷新状态') }}
    </p>
    <div class="content-generation-actions">
      <UiButton :disabled="loading || selected.length === 0" @click="batch.preview">{{
        language.t('platform.content.batch-refresh', '刷新状态')
      }}</UiButton>
      <UiButton type="primary" :disabled="loading || failed || !canGenerate" @click="generate(false)">{{
        language.t('platform.content.batch-generate', '生成缺失译文')
      }}</UiButton>
      <UiButton :disabled="loading || failed || !canRetry" @click="generate(true)">{{
        language.t('platform.content.batch-retry', '补齐并重试失败项')
      }}</UiButton>
    </div>
    <div v-if="result" class="content-generation-results">
      <table>
        <thead>
          <tr>
            <th>{{ language.t('platform.content.batch-record', '所选记录') }}</th>
            <th>{{ language.t('platform.content.batch-text', '业务文字') }}</th>
            <th>{{ language.t('platform.content.batch-status', '处理状态') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in result.items"
            :key="`${item.ownerId}:${item.relationCode}:${item.childId}:${item.field}`"
          >
            <td>{{ recordLabel(item) }}</td>
            <td>{{ fieldLabel(item) }}</td>
            <td>{{ stateLabel(item.status.state) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </UiModal>
</template>

<style scoped>
.content-generation-identity {
  margin-inline-start: 8px;
  color: var(--muyun-text-muted);
  overflow-wrap: anywhere;
}
.content-generation-selection {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 150px;
  overflow: auto;
  margin: 12px 0;
}
.content-generation-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}
.content-generation-results {
  max-height: 340px;
  overflow: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th,
td {
  text-align: start;
  padding: 8px;
  border-bottom: 1px solid var(--muyun-border);
  vertical-align: top;
  overflow-wrap: anywhere;
}
td:first-child {
  max-width: 360px;
}
</style>
