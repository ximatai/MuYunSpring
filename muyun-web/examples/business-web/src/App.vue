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
import deployment from './locales/deployment.json';

// Consumer applications pass their own runtime connection values instead of relying on
// build-time values embedded in the platform package.
const realtimeOptions: AppRealtimeOptions = {
  baseUrl: import.meta.env.VITE_MUYUN_API_BASE_URL,
  token: import.meta.env.VITE_MUYUN_AUTH_TOKEN,
};
void realtimeOptions;
const language = createLanguageContext();
language.setCatalog({
  ...language.catalog.value,
  defaultTag: deployment.defaultTag,
  languages: language.catalog.value.languages.map((entry) => ({
    ...entry,
    enabled: deployment.enabledTags.includes(entry.tag),
  })),
});
for (const [tag, messages] of Object.entries(deployment.messages)) language.registerResources(tag, messages);
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
