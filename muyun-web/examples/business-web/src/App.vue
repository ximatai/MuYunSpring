<script setup lang="ts">
import {
  createLanguageContext,
  createHttpClient,
  withLanguageContext,
  UiLanguageProvider,
  LanguageSelector,
  type AppRealtimeOptions,
} from '@ximatai/muyun-web-app';
import BusinessCustomerView from './views/BusinessCustomerView.vue';

// Consumer applications pass their own runtime connection values instead of relying on
// build-time values embedded in the platform package.
const realtimeOptions: AppRealtimeOptions = {
  baseUrl: import.meta.env.VITE_MUYUN_API_BASE_URL,
  token: import.meta.env.VITE_MUYUN_AUTH_TOKEN,
};
void realtimeOptions;
const language = createLanguageContext();
language.registerResources('zh-CN', { 'business.example.title': '业务项目消费 MuYun 平台包' });
language.registerResources('en', { 'business.example.title': 'Business application using MuYun' });
const http = withLanguageContext(
  createHttpClient({ baseUrl: realtimeOptions.baseUrl, token: realtimeOptions.token }),
  language,
);
void http;
</script>

<template>
  <UiLanguageProvider :context="language">
    <main class="business-workbench">
      <header class="business-header">
        <p>Business project example</p>
        <h1>{{ language.t('business.example.title') }}</h1>
        <LanguageSelector />
      </header>
      <BusinessCustomerView />
    </main>
  </UiLanguageProvider>
</template>
