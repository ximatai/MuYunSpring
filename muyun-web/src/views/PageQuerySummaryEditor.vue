<script setup lang="ts">
import { computed, nextTick, watch } from 'vue';
import { UiButton, UiDropdown, UiEmpty, UiInput, UiSelect, UiSpin } from '@muyun/vue-ui-antdv';
import type { PageQuerySummary, PageQuerySummarySource } from './pageCompositionDraftState';

defineOptions({ name: 'PageQuerySummaryEditor' });

export interface PageQuerySummaryEditorIssues {
  source?: string;
  fieldName?: string;
  contributorKey?: string;
  groupByField?: string;
  label?: string;
}

const props = defineProps<{
  summaries: PageQuerySummary[];
  catalog?: {
    fields?: Array<{ fieldName: string; title: string }>;
    contributors?: Array<{ contributorKey: string; title: string }>;
    groupFields?: Array<{ fieldName: string; title: string; kind: 'OPTION' | 'REFERENCE' }>;
  };
  loading?: boolean;
  error?: string;
  disabled?: boolean;
  selectedKey?: string;
  focusRequest?: number;
  issues?: Record<string, PageQuerySummaryEditorIssues>;
  descriptions?: Record<string, string>;
}>();
const emit = defineEmits<{
  add: [source: PageQuerySummarySource];
  update: [index: number, patch: Partial<PageQuerySummary>];
  remove: [index: number];
  move: [index: number, offset: number];
  retry: [];
}>();
const addItems = computed(() => [
  { key: 'MATCHED_COUNT', title: '记录数' },
  ...(props.catalog?.fields?.length ? [{ key: 'SUM', title: '数值合计' }] : []),
  ...(props.catalog?.contributors?.length ? [{ key: 'CONTRIBUTOR', title: '业务指标' }] : []),
  ...(props.catalog?.groupFields?.length ? [{ key: 'GROUPED', title: '按字段分组' }] : []),
]);
function sourceOptions(summary: PageQuerySummary) {
  return [
    { value: 'MATCHED_COUNT', label: '记录数' },
    ...(props.catalog?.fields?.length || summary.source === 'SUM'
      ? [{ value: 'SUM', label: '数值合计' }]
      : []),
    ...(props.catalog?.contributors?.length || summary.source === 'CONTRIBUTOR'
      ? [{ value: 'CONTRIBUTOR', label: '业务指标' }]
      : []),
    ...(props.catalog?.groupFields?.length || summary.source === 'GROUPED'
      ? [{ value: 'GROUPED', label: '按字段分组' }]
      : []),
  ];
}

const itemElements = new Map<string, HTMLElement>();
function registerItem(key: string, element: Element | null) {
  if (element instanceof HTMLElement) itemElements.set(key, element);
  else itemElements.delete(key);
}
function issue(summary: PageQuerySummary, field: keyof PageQuerySummaryEditorIssues) {
  return props.issues?.[summary.key]?.[field];
}
watch(
  () => [props.selectedKey, props.focusRequest] as const,
  async ([key]) => {
    if (!key) return;
    await nextTick();
    const item = itemElements.get(key);
    if (typeof item?.scrollIntoView === 'function') item.scrollIntoView({ block: 'nearest' });
  },
  { immediate: true },
);
</script>

<template>
  <div class="page-query-summary-editor">
    <p>统计范围是当前筛选的完整结果，不受当前分页限制。</p>
    <UiSpin v-if="loading" tip="加载汇总目录" />
    <template v-if="error">
      <p class="page-query-summary-editor__error">{{ error }}</p>
      <UiButton size="small" @click="emit('retry')">重试</UiButton>
    </template>
    <template v-if="!loading && !error">
      <UiDropdown
        v-slot="{ toggle }"
        :items="addItems"
        @select="emit('add', $event as PageQuerySummarySource)"
      >
        <UiButton size="small" :disabled="disabled" @click.stop="toggle">添加统计</UiButton>
      </UiDropdown>
    </template>
    <UiEmpty v-if="!summaries.length" description="尚未配置汇总统计" />
    <div
      v-for="(summary, index) in summaries"
      :key="summary.key"
      :ref="(element) => registerItem(summary.key, element as Element | null)"
      class="page-query-summary-editor__item"
      :class="{ 'page-query-summary-editor__item--selected': selectedKey === summary.key }"
      :data-summary-key="summary.key"
    >
      <p class="page-query-summary-editor__description">{{ descriptions?.[summary.key] }}</p>
      <label
        ><span>统计方式</span
        ><UiSelect
          :value="summary.source"
          :disabled="disabled || Boolean(error)"
          :options="sourceOptions(summary)"
          @update:value="emit('update', index, { source: $event as PageQuerySummarySource })"
        /><span v-if="issue(summary, 'source')" class="page-query-summary-editor__field-error" role="alert">{{
          issue(summary, 'source')
        }}</span></label
      >
      <label v-if="summary.source === 'SUM'"
        ><span>字段</span
        ><UiSelect
          :value="summary.fieldName"
          :disabled="disabled || Boolean(error)"
          :options="(catalog?.fields ?? []).map((field) => ({ value: field.fieldName, label: field.title }))"
          placeholder="选择数值字段"
          @update:value="
            emit('update', index, { fieldName: typeof $event === 'string' ? $event : undefined })
          "
        /><span
          v-if="issue(summary, 'fieldName')"
          class="page-query-summary-editor__field-error"
          role="alert"
          >{{ issue(summary, 'fieldName') }}</span
        ><small>不同币种或单位的原值不能直接相加。</small></label
      >
      <label v-if="summary.source === 'CONTRIBUTOR'"
        ><span>业务指标</span
        ><UiSelect
          :value="summary.contributorKey"
          :disabled="disabled || Boolean(error)"
          :options="
            (catalog?.contributors ?? []).map((item) => ({ value: item.contributorKey, label: item.title }))
          "
          placeholder="选择业务指标"
          @update:value="
            emit('update', index, { contributorKey: typeof $event === 'string' ? $event : undefined })
          "
        /><span
          v-if="issue(summary, 'contributorKey')"
          class="page-query-summary-editor__field-error"
          role="alert"
          >{{ issue(summary, 'contributorKey') }}</span
        ></label
      >
      <label v-if="summary.source === 'GROUPED'"
        ><span>分组字段</span
        ><UiSelect
          :value="summary.groupByField"
          :disabled="disabled || Boolean(error)"
          :options="
            (catalog?.groupFields ?? []).map((field) => ({ value: field.fieldName, label: field.title }))
          "
          placeholder="选择分组字段"
          @update:value="
            emit('update', index, { groupByField: typeof $event === 'string' ? $event : undefined })
          "
        /><span
          v-if="issue(summary, 'groupByField')"
          class="page-query-summary-editor__field-error"
          role="alert"
          >{{ issue(summary, 'groupByField') }}</span
        ></label
      >
      <label v-if="summary.source === 'GROUPED'"
        ><span>数值合计（可选）</span
        ><UiSelect
          :value="summary.fieldName"
          :disabled="disabled || Boolean(error)"
          :options="(catalog?.fields ?? []).map((field) => ({ value: field.fieldName, label: field.title }))"
          placeholder="仅显示每组记录数"
          allow-clear
          @update:value="
            emit('update', index, { fieldName: typeof $event === 'string' ? $event : undefined })
          "
        /><span
          v-if="issue(summary, 'fieldName')"
          class="page-query-summary-editor__field-error"
          role="alert"
          >{{ issue(summary, 'fieldName') }}</span
        ><small>每组固定显示记录数，可选一个数值字段合计。不同币种或单位不能直接相加。</small></label
      >
      <label
        ><span>展示名称</span
        ><UiInput
          :value="summary.label"
          :disabled="disabled"
          placeholder="展示标题"
          @update:value="emit('update', index, { label: $event })"
        /><span v-if="issue(summary, 'label')" class="page-query-summary-editor__field-error" role="alert">{{
          issue(summary, 'label')
        }}</span></label
      >
      <div class="page-query-summary-editor__actions">
        <UiButton size="small" :disabled="disabled || index === 0" @click="emit('move', index, -1)"
          >上移</UiButton
        ><UiButton
          size="small"
          :disabled="disabled || index === summaries.length - 1"
          @click="emit('move', index, 1)"
          >下移</UiButton
        ><UiButton size="small" :disabled="disabled" @click="emit('remove', index)">删除</UiButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-query-summary-editor {
  display: grid;
  gap: 10px;
}
.page-query-summary-editor > p {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.page-query-summary-editor__item {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--muyun-border-color);
  border-radius: 6px;
}
.page-query-summary-editor__item--selected {
  border-color: var(--muyun-theme-base);
  box-shadow: 0 0 0 2px var(--muyun-theme-soft);
}
.page-query-summary-editor__description {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.page-query-summary-editor__item label {
  display: grid;
  gap: 4px;
}
.page-query-summary-editor__item :deep(.ant-select),
.page-query-summary-editor__item :deep(.ant-input) {
  width: 100%;
}
.page-query-summary-editor__item small {
  color: var(--muyun-text-muted);
}
.page-query-summary-editor__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.page-query-summary-editor__error {
  color: var(--muyun-danger-base);
}
.page-query-summary-editor__field-error {
  color: var(--muyun-danger-base);
  font-size: 12px;
}
</style>
