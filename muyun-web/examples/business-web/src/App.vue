<script setup lang="ts">
import type { AppRealtimeOptions } from '@ximatai/muyun-web-app';
import BusinessCustomerView from './views/BusinessCustomerView.vue';

// Consumer applications pass their own runtime connection values instead of relying on
// build-time values embedded in the platform package.
const realtimeOptions: AppRealtimeOptions = {
  baseUrl: import.meta.env.VITE_MUYUN_API_BASE_URL,
  token: import.meta.env.VITE_MUYUN_AUTH_TOKEN,
};
void realtimeOptions;
</script>

<template>
  <main class="business-workbench">
    <header class="business-header">
      <p>Business project example</p>
      <h1>业务项目消费 MuYun 平台包</h1>
    </header>
    <BusinessCustomerView />
  </main>
</template>
