<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import BusinessLogListDrawer from './BusinessLogListDrawer.vue';

const language = useLanguageContext();
</script>

<template>
  <BusinessLogListDrawer
    surface="login"
    module-alias="iam.login_audit_log"
    :title="language.t('platform.screen.BusinessLogRetentionControl.login-audit-logs', '登录日志')"
  />
</template>
