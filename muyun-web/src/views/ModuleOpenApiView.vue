<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue';
import { presentPlatformError } from '@muyun/platform-components';
import { createBackendHttpClient } from '../platform-admin-runtime/backendHttp';
import {
  createOpenApiAuthenticatedFetch,
  loadModuleOpenApi,
  openApiBackendBaseUrl,
  type ModuleOpenApiDocument,
} from '../platform-admin-runtime/moduleOpenApi';

const props = defineProps<{ moduleAlias: string }>();
const emit = defineEmits<{ titleResolved: [title: string] }>();
const ApiReference = defineAsyncComponent(async () => {
  const [module] = await Promise.all([
    import('@scalar/api-reference'),
    import('@scalar/api-reference/style.css'),
  ]);
  return module.ApiReference;
});

const document = ref<ModuleOpenApiDocument>();
const loading = ref(false);
const error = ref<string>();
const scalarConfiguration = computed(() => {
  const backendBaseUrl = openApiBackendBaseUrl();
  return {
    content: document.value,
    title: document.value?.info.title ?? props.moduleAlias,
    baseServerURL: backendBaseUrl,
    servers: backendBaseUrl ? [{ url: backendBaseUrl }] : undefined,
    hideClientButton: false,
    customFetch: createOpenApiAuthenticatedFetch(),
    showDeveloperTools: 'never' as const,
    showToolbar: 'always' as const,
    theme: 'default' as const,
    darkMode: false,
    hideDarkModeToggle: true,
  };
});

onMounted(load);
watch(() => props.moduleAlias, load);

async function load() {
  loading.value = true;
  error.value = undefined;
  try {
    document.value = await loadModuleOpenApi(createBackendHttpClient(), props.moduleAlias);
    if (document.value.info.title) {
      emit('titleResolved', document.value.info.title);
    }
  } catch (cause) {
    error.value = presentPlatformError(cause, { source: 'module-openapi-view', phase: 'load' }).message;
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <main class="module-openapi-view">
    <p v-if="loading" class="module-openapi-view__message">正在读取模块 API 文档…</p>
    <section v-else-if="error" class="module-openapi-view__error" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="load">重试</button>
    </section>
    <ApiReference
      v-else-if="document"
      class="module-openapi-view__reference"
      :configuration="scalarConfiguration"
    />
  </main>
</template>

<style scoped>
.module-openapi-view {
  box-sizing: border-box;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  padding: 0;
  color: var(--muyun-support-text);
  overflow: hidden;
}
.module-openapi-view button {
  min-height: 34px;
  padding: 0 14px;
  border: 1px solid var(--muyun-theme-border);
  border-radius: 6px;
  background: var(--muyun-support-surface);
  color: var(--muyun-theme-base);
  cursor: pointer;
}
.module-openapi-view button:disabled {
  cursor: wait;
  opacity: 0.6;
}
.module-openapi-view__error {
  align-self: start;
  padding: 16px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
}
.module-openapi-view__message {
  align-self: start;
  margin: 0;
  color: var(--muyun-support-text-muted);
}
.module-openapi-view__reference {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 0;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  overflow: hidden;
}
:deep(.scalar-app) {
  height: 100%;
  min-height: 0;
}
:deep(.scalar-api-reference.references-layout) {
  --full-height: 100%;
  grid-template-rows: var(--scalar-header-height, 0px) minmax(0, 1fr) auto;
  height: 100%;
  min-height: 0;
}
:deep(.references-rendered) {
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
:deep(.scalar-api-reference.references-classic),
:deep(.scalar-api-reference.references-classic .references-rendered) {
  height: 100% !important;
  max-height: 100% !important;
}
.module-openapi-view__error {
  color: var(--muyun-danger-soft-text);
}
</style>
