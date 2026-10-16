<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed, ref } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';

const language = useLanguageContext();

defineOptions({ name: 'QueryGroupedSummary' });

export interface QueryGroupedSummaryRow {
  value: unknown;
  label: string;
  count: number;
  sum?: number | null;
}
const props = withDefaults(
  defineProps<{
    title: string;
    groupByTitle?: string;
    sumFieldTitle?: string;
    value?: unknown;
    exampleRows?: QueryGroupedSummaryRow[];
    loading?: boolean;
    error?: boolean;
    ready?: boolean;
  }>(),
  { ready: true },
);
const open = ref(false);
const rows = computed<QueryGroupedSummaryRow[]>(() => {
  if (props.exampleRows) return props.exampleRows;
  const value = props.value as { kind?: string; rows?: QueryGroupedSummaryRow[] } | undefined;
  return value?.kind === 'GROUPED' && Array.isArray(value.rows) ? value.rows : [];
});
</script>
<template>
  <div class="query-grouped-summary">
    <UiButton size="small" type="text" :aria-expanded="open" @click="open = !open"
      >{{ title }} {{ language.t('platform.screen.QueryGroupedSummary.view-groups', '· 查看分组') }}</UiButton
    >
    <div
      v-if="open"
      class="query-grouped-summary__table"
      role="region"
      :aria-label="
        language.t('platform.screen.QueryGroupedSummary.value-grouped-results', '{value1}分组结果', {
          value1: title,
        })
      "
    >
      <p v-if="exampleRows" class="query-grouped-summary__hint">
        {{
          language.t(
            'platform.screen.QueryGroupedSummary.sample-data-for-display-preview-only',
            '示例数据，仅预览展示效果',
          )
        }}
      </p>
      <p v-if="loading" role="status">
        {{ language.t('platform.screen.QueryGroupedSummary.loading-grouped-summary', '正在加载分组统计…') }}
      </p>
      <p v-else-if="error" role="alert">
        {{
          language.t(
            'platform.screen.QueryGroupedSummary.could-not-load-grouped-summary-refresh-to-retry',
            '分组统计加载失败，请刷新重试',
          )
        }}
      </p>
      <p v-else-if="ready === false" role="status">
        {{ language.t('platform.screen.QueryGroupedSummary.select-a-query-scope', '请选择查询范围') }}
      </p>
      <table v-else>
        <thead>
          <tr>
            <th>{{ groupByTitle ?? language.t('platform.screen.QueryGroupedSummary.group', '分组') }}</th>
            <th>{{ language.t('platform.screen.QueryGroupedSummary.record-count', '记录数') }}</th>
            <th v-if="sumFieldTitle">
              {{ sumFieldTitle }}{{ language.t('platform.screen.QueryGroupedSummary.total', '合计') }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="`${String(row.value)}:${index}`">
            <td>{{ row.label }}</td>
            <td>{{ row.count }}</td>
            <td v-if="sumFieldTitle">{{ row.sum ?? '—' }}</td>
          </tr>
          <tr v-if="!rows.length">
            <td :colspan="sumFieldTitle ? 3 : 2">
              {{ language.t('platform.screen.QueryGroupedSummary.no-grouped-results', '暂无分组结果') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
<style scoped>
.query-grouped-summary {
  min-width: 0;
  max-width: 100%;
}
.query-grouped-summary__hint {
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.query-grouped-summary__table {
  max-height: 220px;
  overflow: auto;
  margin-top: 6px;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
th,
td {
  padding: 4px 8px;
  text-align: left;
  white-space: nowrap;
}
th {
  color: var(--muyun-text-muted);
}
</style>
