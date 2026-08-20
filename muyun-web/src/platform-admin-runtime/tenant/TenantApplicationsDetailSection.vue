<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiDataTable } from '@muyun/vue-ui-antdv';
import type { UiDataTableColumn, UiDataTableRecord } from '@muyun/vue-ui-antdv';
import type { TenantApplication, WebPageResponse } from '@muyun/web-contracts';
import { presentPlatformError } from '@muyun/platform-components';
import type { ModulePageDetailSectionContext } from '@muyun/dynamic-page-runtime';

defineOptions({ name: 'TenantApplicationsDetailSection' });

const props = defineProps<{ context: ModulePageDetailSectionContext }>();
const records = ref<TenantApplication[]>([]);
const loading = ref(false);
let loadRevision = 0;

const columns: UiDataTableColumn[] = [{ key: 'applicationAlias', title: '已开通应用' }];
const rows = computed(() => records.value as unknown as UiDataTableRecord[]);

watch(
  () => [props.context.record.id, props.context.refreshKey],
  () => void load(),
  { immediate: true },
);

async function load() {
  const tenantId = String(props.context.record.id ?? '');
  const revision = ++loadRevision;
  if (!tenantId) {
    records.value = [];
    return;
  }
  loading.value = true;
  try {
    const response = await props.context.module.http.request<WebPageResponse<TenantApplication>>({
      method: 'POST',
      path: tenantApplicationsPath(tenantId, 'query'),
      body: { page: { pageNum: 1, pageSize: 200 } },
    });
    if (revision === loadRevision) records.value = response.records;
  } catch (error) {
    presentPlatformError(error, { source: 'tenant-applications-detail', phase: 'load' });
  } finally {
    if (revision === loadRevision) loading.value = false;
  }
}

function tenantApplicationsPath(tenantId: string, suffix: 'query' | 'configure') {
  return `/iam.tenant/${encodeURIComponent(tenantId)}/applications/${suffix}`;
}
</script>

<template>
  <section class="tenant-applications-detail">
    <p>应用是否可用以“是否开通”为准，不再维护租户侧启停状态。</p>
    <UiDataTable
      :columns="columns"
      :rows="rows"
      :loading="loading"
      :pagination="false"
      empty-description="暂未开通应用"
    />
  </section>
</template>

<style scoped>
.tenant-applications-detail {
  display: grid;
  gap: 12px;
}
.tenant-applications-detail p {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
</style>
