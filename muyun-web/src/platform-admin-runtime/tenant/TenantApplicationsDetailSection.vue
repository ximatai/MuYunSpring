<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiTagList, type UiTagListItem } from '@muyun/vue-ui-antdv';
import type { Application, TenantApplication, WebPageResponse } from '@muyun/web-contracts';
import { presentPlatformError } from '@muyun/platform-components';
import type { ModulePageDetailSectionContext } from '@muyun/dynamic-page-runtime';

defineOptions({ name: 'TenantApplicationsDetailSection' });

const props = defineProps<{ context: ModulePageDetailSectionContext }>();
const records = ref<TenantApplication[]>([]);
const loading = ref(false);
let loadRevision = 0;

const applicationTitles = ref(new Map<string, string>());
const applicationTags = computed<UiTagListItem[]>(() =>
  records.value
    .map((record) => record.applicationAlias)
    .filter((alias): alias is string => typeof alias === 'string' && alias.length > 0)
    .map((alias) => ({
      key: alias,
      label: applicationTitles.value.get(alias) ? `${applicationTitles.value.get(alias)} · ${alias}` : alias,
    })),
);

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
    applicationTitles.value = new Map();
    loading.value = false;
    return;
  }
  loading.value = true;
  records.value = [];
  applicationTitles.value = new Map();
  try {
    const [applicationsResponse, tenantApplicationsResponse] = await Promise.all([
      props.context.module.http.request<WebPageResponse<Application>>({
        method: 'POST',
        path: '/platform.application/query',
        body: { page: { pageNum: 1, pageSize: 200 } },
      }),
      props.context.module.http.request<WebPageResponse<TenantApplication>>({
        method: 'POST',
        path: tenantApplicationsPath(tenantId, 'query'),
        body: { page: { pageNum: 1, pageSize: 200 } },
      }),
    ]);
    if (revision === loadRevision) {
      applicationTitles.value = new Map(
        applicationsResponse.records
          .filter((application) => application.alias && application.title)
          .map((application) => [application.alias!, application.title!]),
      );
      records.value = tenantApplicationsResponse.records;
    }
  } catch (error) {
    if (revision === loadRevision) {
      presentPlatformError(error, { source: 'tenant-applications-detail', phase: 'load' });
    }
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
    <div class="tenant-applications-summary">
      <span v-if="loading" class="tenant-applications-status">正在加载…</span>
      <UiTagList v-else :items="applicationTags" :max-visible="8" empty-text="暂无记录" />
    </div>
  </section>
</template>

<style scoped>
.tenant-applications-detail {
  min-height: 28px;
}

.tenant-applications-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.tenant-applications-status {
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.tenant-applications-summary :deep(.ui-tag-list-item) {
  padding-inline: 10px;
  font-size: 14px;
  line-height: 24px;
}
</style>
