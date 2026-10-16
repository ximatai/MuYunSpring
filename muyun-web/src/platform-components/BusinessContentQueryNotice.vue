<script setup lang="ts">
import { useLanguageContext, businessContentLanguages } from '@muyun/web-core';
import type { WebBusinessContentQueryStatus } from '@muyun/web-contracts';

defineProps<{ status?: WebBusinessContentQueryStatus }>();
const language = useLanguageContext();
</script>

<template>
  <p v-if="status && status.missingRecords > 0" class="business-content-query-notice" role="status">
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
</template>

<style scoped>
.business-content-query-notice {
  margin: 8px 12px;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
</style>
