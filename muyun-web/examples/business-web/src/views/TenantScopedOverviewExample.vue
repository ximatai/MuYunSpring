<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import {
  ManagementWorkspace,
  ManagementExplorerColumn,
  TenantScopeExplorer,
  UiButton,
  createModuleContext,
  withHttpHeaders,
  type CurrentUser,
  type HttpClient,
  type ModuleContext,
  type QueryListRecord,
} from '@ximatai/muyun-web-app';

// The owning business supplies its authorized overview endpoint and current session transport.
const props = defineProps<{ currentUser?: CurrentUser; http: HttpClient; overviewPath: string }>();
const identity = computed(() =>
  JSON.stringify([props.currentUser?.userId, props.currentUser?.system, props.currentUser?.tenantId]),
);
const selectedTenantId = ref('');
const tenantContext = shallowRef<ModuleContext<QueryListRecord>>();
const tenantReloadKey = ref(0);
const reloadKey = ref(0);
const tenantId = computed(() => {
  const user = props.currentUser;
  if (!user) return '';
  return user.system ? selectedTenantId.value : (user.tenantId ?? '');
});
const overview = shallowRef<Record<string, unknown>>();
const loading = ref(false);
const failed = ref(false);

watch(
  [identity, () => props.http],
  () => {
    selectedTenantId.value = '';
    tenantContext.value =
      props.currentUser?.system === true
        ? createModuleContext<QueryListRecord>({
            moduleAlias: 'iam.tenant',
            runtimeAccess: 'REFERENCE',
            http: props.http,
          })
        : undefined;
  },
  { immediate: true, flush: 'sync' },
);

watch(
  [identity, tenantId, () => props.http, () => props.overviewPath, reloadKey],
  async (_, __, onCleanup) => {
    overview.value = undefined;
    failed.value = false;
    loading.value = false;
    if (!tenantId.value) return;
    const controller = new AbortController();
    let active = true;
    onCleanup(() => {
      active = false;
      controller.abort();
    });
    loading.value = true;
    try {
      // Clear old facts synchronously, but wait for the host's props to settle before sending.
      await Promise.resolve();
      if (!active) return;
      // Freeze the execution tenant for this request; do not mutate the shared session client.
      const scopedHttp = withHttpHeaders(props.http, { 'X-MuYun-Tenant-Id': tenantId.value });
      const result = await scopedHttp.request<Record<string, unknown>>({
        path: props.overviewPath,
        signal: controller.signal,
      });
      if (active) overview.value = result;
    } catch {
      if (active) failed.value = true;
    } finally {
      if (active) loading.value = false;
    }
  },
  { immediate: true, flush: 'sync' },
);

function selectTenant(record: QueryListRecord) {
  selectedTenantId.value = record.id == null ? '' : String(record.id);
}
</script>

<template>
  <ManagementWorkspace :explorer-count="tenantContext ? 1 : 0">
    <ManagementExplorerColumn
      v-if="tenantContext"
      :key="identity"
      collapsible
      title="租户"
      :has-selection="Boolean(tenantId)"
    >
      <TenantScopeExplorer
        :context="tenantContext"
        :selected-id="tenantId || undefined"
        :reload-key="tenantReloadKey"
        @select="selectTenant"
        @deselect="selectedTenantId = ''"
        @refresh="tenantReloadKey++"
      />
    </ManagementExplorerColumn>
    <section class="tenant-overview-example">
      <h2>业务概览</h2>
      <p v-if="!currentUser">等待身份加载</p>
      <p v-else-if="!tenantId">{{ currentUser.system ? '请选择执行租户' : '当前身份缺少执行租户' }}</p>
      <template v-else>
        <UiButton :disabled="loading" @click="reloadKey++">刷新概览</UiButton>
        <p v-if="loading">正在加载</p>
        <p v-else-if="failed" role="alert">概览加载失败，请重试。</p>
        <pre v-else-if="overview">{{ JSON.stringify(overview, null, 2) }}</pre>
      </template>
    </section>
  </ManagementWorkspace>
</template>

<style scoped>
.tenant-overview-example {
  min-width: 0;
  padding: 16px;
  background: var(--muyun-surface);
}
</style>
