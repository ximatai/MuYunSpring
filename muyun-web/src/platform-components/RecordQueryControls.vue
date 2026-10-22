<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { UiButton } from '@muyun/vue-ui-antdv';
import type { QuerySchemaField, WebSort } from '@muyun/web-contracts';
import RecordQuerySortSelector from './RecordQuerySortSelector.vue';

/** Shared query chrome; callers retain the query lifecycle and its authorized schema. */
defineOptions({ name: 'RecordQueryControls' });
defineProps<{
  sortFields: QuerySchemaField[];
  sorts?: WebSort[];
  sortVisible?: boolean;
  filterVisible: boolean;
  filterTitle: string;
  expanded: boolean;
  count: number;
  disabled: boolean;
  sortDisabled?: boolean;
}>();
const emit = defineEmits<{ toggle: []; sortChange: [sorts: WebSort[] | undefined] }>();
const language = useLanguageContext();
</script>

<template>
  <RecordQuerySortSelector
    v-if="sortVisible"
    :fields="sortFields"
    :sorts="sorts"
    :disabled="sortDisabled ?? disabled"
    @change="emit('sortChange', $event)"
  />
  <UiButton
    v-if="filterVisible"
    class="record-query-list-advanced"
    :class="{ 'is-selected': expanded }"
    type="text"
    icon-name="filter"
    :aria-expanded="expanded"
    :disabled="disabled"
    @click="emit('toggle')"
    >{{ filterTitle }}<span v-if="count"> {{ count }}</span></UiButton
  >
  <span v-if="count" class="record-query-list-query-state" role="status">{{
    language.t('platform.query.applied-filters', '已应用 {count} 条筛选', { count })
  }}</span>
</template>

<style scoped>
.record-query-list-query-state {
  color: var(--muyun-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

:deep(.record-query-list-advanced.is-selected.ant-btn) {
  border: 1px solid var(--muyun-theme-border);
  background: var(--muyun-selected);
  color: var(--muyun-theme-base);
}

:deep(.record-query-list-advanced.is-selected.ant-btn:hover) {
  border-color: var(--muyun-theme-hover);
  background: var(--muyun-theme-focus);
  color: var(--muyun-theme-base);
}
</style>
