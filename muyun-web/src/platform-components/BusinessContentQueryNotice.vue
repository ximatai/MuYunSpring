<script setup lang="ts">
import { useLanguageContext, businessContentLanguages } from '@muyun/web-core';
import type { WebBusinessContentQueryStatus } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import BusinessContentGenerationPanel, {
  type BusinessContentGenerationRecord,
} from './BusinessContentGenerationPanel.vue';

defineProps<{
  status?: WebBusinessContentQueryStatus;
  context?: Pick<ModuleContext<unknown>, 'http' | 'moduleAlias' | 'runtime'>;
  records?: BusinessContentGenerationRecord[];
  fields?: Record<string, string>;
  initiallySelected?: string[];
}>();
defineEmits<{ updated: [] }>();
const language = useLanguageContext();
</script>

<template>
  <details v-if="status && status.missingRecords > 0" class="business-content-query-notice">
    <summary>
      <span role="status">{{
        language.t(
          'platform.content.query-incomplete-summary',
          '{count} 条记录缺少 {language} 阅读内容，查询可能不完整',
          {
            count: status.missingRecords,
            language:
              businessContentLanguages.find((entry) => entry.value === status?.language)?.label ??
              status.language,
          },
        )
      }}</span>
    </summary>
    <p>
      {{
        language.t(
          'platform.content.query-incomplete',
          '文本筛选前的业务范围内仍有 {count} 条记录缺少 {language} 阅读内容，查询可能不完整，缺失项按原文排序。',
          {
            count: status.missingRecords,
            language:
              businessContentLanguages.find((entry) => entry.value === status?.language)?.label ??
              status.language,
          },
        )
      }}
    </p>
    <BusinessContentGenerationPanel
      v-if="status?.generationScope && context && records?.length"
      :status="status"
      :context="context"
      :records="records"
      :fields="fields"
      :initially-selected="initiallySelected"
      @updated="$emit('updated')"
    />
  </details>
</template>

<style scoped>
.business-content-query-notice {
  margin: 8px 12px;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.business-content-query-notice > summary {
  cursor: pointer;
}
</style>
