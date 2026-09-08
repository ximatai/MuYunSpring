<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { ModulePageHost } from '@muyun/dynamic-page-runtime';
import {
  useModuleContext,
  ModuleHttpProvider,
  withHttpHeaders,
  type HttpClient,
  type ModuleRuntimeContext,
} from '@muyun/web-core';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import type { StandardModulePageDescriptor } from '@muyun/web-contracts';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import { UiButton, UiEmpty, UiSpin, UiSelect } from '@muyun/vue-ui-antdv';

const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const { http } = useModuleContext({ moduleAlias: 'platform.module' });
const currentUser = useCurrentUserContext();
const tenantRequired = ref(false);
const tenantId = ref('');
const tenants = ref<Array<{ id: string; title?: string; enabled?: boolean }>>([]);
const tenantError = ref('');
const tenantLoading = ref(false);
const sessionHttp = shallowRef<HttpClient>(http);
const systemIdentity = computed(() => currentUser?.value?.system !== false);
const tenantReady = computed(() => !tenantRequired.value || Boolean(tenantId.value));

let tenantRequestId = 0;
async function loadTenants(keyword = '') {
  const id = ++tenantRequestId;
  tenantLoading.value = true;
  tenantError.value = '';
  try {
    const result = await http.request<{ records: typeof tenants.value }>({
      method: 'POST',
      path: '/iam.tenant/navigator/reference/query',
      body: { page: { pageNum: 1, pageSize: 100 }, ...(keyword ? { quickSearch: keyword } : {}) },
    });
    if (id === tenantRequestId) tenants.value = result.records;
  } catch (cause) {
    if (id === tenantRequestId) tenantError.value = cause instanceof Error ? cause.message : '租户加载失败';
  } finally {
    if (id === tenantRequestId) tenantLoading.value = false;
  }
}
function setTenant(value: string) {
  tenantId.value = value;
  // Capture the tenant in this client. Pending requests from a disposed host retain their old scope.
  sessionHttp.value = value ? withHttpHeaders(http, { 'X-MuYun-Tenant-Id': value }) : http;
  generation.value++;
}
function changeTenant(value: unknown) {
  if (reloadBlocked.value) return;
  const selected = typeof value === 'string' ? value : '';
  if (selected && !tenants.value.some((tenant) => tenant.id === selected)) return;
  if (selected !== tenantId.value) setTenant(selected);
}
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
    tenantRequired.value = context.tenantRequired ?? context.moduleKind === 'DYNAMIC';
    if (tenantRequired.value) {
      if (!systemIdentity.value) setTenant(currentUser?.value?.tenantId ?? '');
      else await loadTenants();
      if (id !== requestId) return;
      if (published.value && reloadBlocked.value) {
        changed.value = fingerprint !== next;
        return;
      }
    } else setTenant('');
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
    setTenant('');
    tenants.value = [];
    tenantRequestId++;
    tenantError.value = '';
    tenantLoading.value = false;
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
  tenantRequestId++;
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
    <label v-if="published && tenantRequired && systemIdentity" class="business-preview__tenant">
      业务租户
      <UiSelect
        aria-label="业务租户"
        :value="tenantId || undefined"
        :options="tenants.map((tenant) => ({ value: tenant.id, label: tenant.title ?? tenant.id }))"
        :disabled="reloadBlocked"
        :loading="tenantLoading"
        placeholder="请选择业务租户"
        show-search
        :filter-option="false"
        @search="loadTenants"
        @update:value="changeTenant"
      />
      <span v-if="reloadBlocked">请先保存或取消当前编辑，再切换租户</span>
      <span v-if="tenantLoading">加载租户中</span>
      <span v-else-if="!tenantError && tenants.length === 0">没有可访问的活跃租户</span>
    </label>
    <p v-if="tenantError" role="alert">
      {{ tenantError }} <UiButton @click="loadTenants()">重试加载租户</UiButton>
    </p>
    <p v-if="published && !tenantReady" role="status">请选择业务租户后加载业务数据。</p>
    <p v-if="changed" role="status">已发布配置已更新，请完成当前操作后重新加载页面。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="published && tenantReady" class="business-preview__runtime">
      <ModuleHttpProvider :key="`${moduleAlias}:${generation}`" :http="sessionHttp">
        <ModulePageHost
          :key="`${moduleAlias}:${generation}`"
          :descriptor="descriptor"
          @interaction-state-change="interaction = $event"
          require-configured-page
        />
      </ModuleHttpProvider>
    </div>
    <UiSpin v-else-if="loading" tip="加载已发布页面" />
    <UiEmpty
      v-else-if="!error && !published"
      description="当前模块尚未发布页面，请先在页面配置中发布草稿。"
    />
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
.business-preview__tenant {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.business-preview__tenant :deep(.ant-select) {
  min-width: 220px;
}
.business-preview__runtime {
  flex: 1;
  min-height: 0;
}
</style>
