<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { presentPlatformError } from '@muyun/platform-components';
import { createBackendHttpClient } from '../platform-admin-runtime/backendHttp';
import { loadOpenApiCatalog, type OpenApiModuleCatalogItem } from '../platform-admin-runtime/moduleOpenApi';

const emit = defineEmits<{ open: [moduleAlias: string, title: string]; back: [] }>();
const modules = ref<OpenApiModuleCatalogItem[]>([]);
const loading = ref(false);
const error = ref<string>();

onMounted(load);

async function load() {
  loading.value = true;
  error.value = undefined;
  try {
    modules.value = await loadOpenApiCatalog(createBackendHttpClient());
  } catch (cause) {
    error.value = presentPlatformError(cause, { source: 'openapi-catalog', phase: 'load' }).message;
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <main class="openapi-catalog-view">
    <header>
      <div>
        <p>API Directory</p>
        <h1>开放模块 API</h1>
        <span>仅展示当前账号可见且已启用文档交付的模块。</span>
      </div>
      <button type="button" @click="emit('back')">返回工作台</button>
    </header>
    <p v-if="loading">正在读取 API 目录…</p>
    <section v-else-if="error" class="error" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="load">重试</button>
    </section>
    <section v-else class="module-grid">
      <button
        v-for="module in modules"
        :key="module.moduleAlias"
        type="button"
        @click="emit('open', module.moduleAlias, module.title)"
      >
        <span class="module-kind">{{ module.moduleKind === 'dynamic' ? '动态' : '静态' }}</span>
        <strong>{{ module.title }}</strong>
        <code>{{ module.moduleAlias }}</code>
      </button>
      <p v-if="modules.length === 0">当前没有可访问的模块 API。</p>
    </section>
  </main>
</template>

<style scoped>
.openapi-catalog-view {
  max-width: 1120px;
  margin: 0 auto;
  padding: 32px 24px;
  color: var(--muyun-support-text);
}
header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
header p,
h1,
header span {
  margin: 0;
}
header p,
header span {
  color: var(--muyun-support-text-muted);
  font-size: 14px;
}
h1 {
  margin: 4px 0;
}
button {
  min-height: 34px;
  padding: 0 14px;
  border: 1px solid var(--muyun-theme-border);
  border-radius: 6px;
  background: var(--muyun-support-surface);
  color: var(--muyun-theme-base);
  cursor: pointer;
}
.module-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 14px;
}
.module-grid > button {
  display: grid;
  gap: 8px;
  min-height: 144px;
  padding: 18px;
  border-color: var(--muyun-support-border);
  color: var(--muyun-support-text);
  text-align: left;
}
.module-grid > button:hover {
  border-color: var(--muyun-theme-border);
  box-shadow: 0 8px 20px color-mix(in srgb, var(--muyun-theme-base) 12%, transparent);
}
.module-kind {
  justify-self: start;
  padding: 2px 7px;
  border-radius: 12px;
  background: var(--muyun-theme-soft);
  color: var(--muyun-theme-base);
  font-size: 12px;
}
code {
  color: var(--muyun-support-text-muted);
  font-size: 12px;
}
.error {
  color: var(--muyun-danger-soft-text);
}
</style>
