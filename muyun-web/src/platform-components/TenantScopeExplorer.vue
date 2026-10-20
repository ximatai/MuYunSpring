<script setup lang="ts">
import { useLanguageContext, type ModuleContext } from '@muyun/web-core';
import CrudRecordListExplorer from './CrudRecordListExplorer.vue';
import RecordExplorerPanel from './RecordExplorerPanel.vue';
import type { QueryListRecord } from './RecordQueryListPanel.vue';

import { ref } from 'vue';

const language = useLanguageContext();

defineOptions({ name: 'TenantScopeExplorer' });

defineProps<{
  context: ModuleContext<QueryListRecord>;
  selectedId?: string;
  reloadKey: number;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  select: [record: QueryListRecord];
  deselect: [];
  /** Only the first unfiltered response represents the user's full candidate set. */
  loaded: [records: QueryListRecord[], initialFullResult: boolean, total?: number];
  refresh: [];
}>();

const keyword = ref('');
const initialFullResultDelivered = ref(false);

function handleLoaded(records: QueryListRecord[], total?: number) {
  const initialFullResult = !initialFullResultDelivered.value && !keyword.value.trim();
  initialFullResultDelivered.value = true;
  emit('loaded', records, initialFullResult, total);
}
</script>

<template>
  <RecordExplorerPanel
    class="tenant-scope-explorer"
    :title="language.t('platform.screen.TenantScopeExplorer.tenant', '租户')"
    :refresh-title="language.t('platform.screen.TenantScopeExplorer.refresh-tenants', '刷新租户列表')"
    :search-keyword="keyword"
    :search-placeholder="language.t('platform.screen.TenantScopeExplorer.search-tenant', '搜索租户')"
    :refresh-disabled="disabled"
    @update:search-keyword="keyword = $event"
    @refresh="!disabled && emit('refresh')"
  >
    <CrudRecordListExplorer
      :context="context"
      :selected-id="selectedId"
      :reload-key="reloadKey"
      :keyword="keyword"
      :empty-description="
        language.t('platform.screen.TenantScopeExplorer.no-accessible-active-tenants', '没有可访问的活跃租户')
      "
      @loaded="(records, total) => handleLoaded(records as QueryListRecord[], total)"
      @select="!disabled && emit('select', $event as QueryListRecord)"
      @deselect="!disabled && emit('deselect')"
    />
  </RecordExplorerPanel>
</template>
