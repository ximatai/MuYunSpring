<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import BusinessLogListDrawer from './BusinessLogListDrawer.vue';

const language = useLanguageContext();
</script>

<template>
  <BusinessLogListDrawer
    surface="request-error"
    module-alias="platform.request_error_log"
    :title="language.t('platform.screen.BusinessLogRetentionControl.error-logs', '异常日志')"
  />
</template>
