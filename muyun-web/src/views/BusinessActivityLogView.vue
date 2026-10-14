<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import BusinessLogListDrawer from './BusinessLogListDrawer.vue';

const language = useLanguageContext();
</script>

<template>
  <BusinessLogListDrawer
    surface="activity"
    module-alias="platform.business_activity_log"
    :title="language.t('platform.screen.BusinessActivityLogView.business-audit-logs', '操作日志')"
  />
</template>
