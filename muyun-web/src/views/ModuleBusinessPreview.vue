<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, ref, watch } from 'vue';
import { ModulePageHost } from '@muyun/dynamic-page-runtime';
import { useModuleContext, type ModuleRuntimeContext } from '@muyun/web-core';
import type { StandardModulePageDescriptor } from '@muyun/web-contracts';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import { UiButton, UiEmpty, UiSpin } from '@muyun/vue-ui-antdv';

const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const { http } = useModuleContext({ moduleAlias: 'platform.module' });
const loading = ref(false);
const error = ref('');
const published = ref(false);
const changed = ref(false);
const generation = ref(0);
const interaction = ref({ editing: false, busy: false });
const reloadBlocked = computed(() => interaction.value.editing || interaction.value.busy);
useWorkspaceViewUnsavedState('业务预览', () => reloadBlocked.value);
let fingerprint: string | undefined;
let requestId = 0;
const descriptor = computed<StandardModulePageDescriptor>(() => ({
  pageType: 'dynamic-module',
  openMode: 'dynamic-runner',
  hostType: 'module-page-host',
  title: props.moduleTitle ?? props.moduleAlias,
  layout: 'workspace',
  target: { moduleAlias: props.moduleAlias, pageMode: 'LIST' },
  tabPolicy: { identity: 'by-menu', cacheable: true },
}));

async function load(reload = false) {
  if (reload && reloadBlocked.value) return;
  const id = ++requestId;
  loading.value = true;
  error.value = '';
  try {
    const context = await http.request<ModuleRuntimeContext>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(props.moduleAlias)}/context`,
    });
    if (id !== requestId) return;
    const next = JSON.stringify(context);
    // Keep an in-progress business form intact when returning from configuration.
    if (fingerprint !== undefined && published.value && (!reload || reloadBlocked.value)) {
      changed.value = fingerprint !== next;
      return;
    }
    fingerprint = next;
    published.value = Boolean(context.uiDescriptor?.page);
    changed.value = false;
    generation.value++;
  } catch (cause) {
    if (id === requestId) error.value = cause instanceof Error ? cause.message : '业务页面加载失败';
  } finally {
    if (id === requestId) loading.value = false;
  }
}

watch(
  () => props.moduleAlias,
  () => {
    interaction.value = { editing: false, busy: false };
    fingerprint = undefined;
    published.value = false;
    changed.value = false;
    void load();
  },
  { immediate: true },
);
onActivated(() => {
  if (!loading.value) void load();
});
onBeforeUnmount(() => {
  requestId++;
});
</script>

<template>
  <section class="business-preview">
    <header class="business-preview__toolbar">
      <span>已发布页面 · 操作将保存到真实业务数据</span>
      <UiButton
        :loading="loading"
        :disabled="reloadBlocked"
        :title="reloadBlocked ? '请先保存或取消当前编辑，再重新加载' : undefined"
        @click="load(true)"
        >重新加载已发布页面</UiButton
      >
    </header>
    <p v-if="changed" role="status">已发布配置已更新，请完成当前操作后重新加载页面。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="published" class="business-preview__runtime">
      <ModulePageHost
        :key="`${moduleAlias}:${generation}`"
        :descriptor="descriptor"
        @interaction-state-change="interaction = $event"
        require-configured-page
      />
    </div>
    <UiSpin v-else-if="loading" tip="加载已发布页面" />
    <UiEmpty v-else-if="!error" description="当前模块尚未发布页面，请先在页面配置中发布草稿。" />
  </section>
</template>

<style scoped>
.business-preview {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  gap: 10px;
}
.business-preview__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.business-preview__runtime {
  flex: 1;
  min-height: 0;
}
</style>
