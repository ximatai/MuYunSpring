<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed, onMounted, ref, watch } from 'vue';
import { UiDataTable } from '@muyun/vue-ui-antdv';
import type { UiDataTableColumn, UiDataTableRecord } from '@muyun/vue-ui-antdv';
import type { Application, TenantApplication, WebPageResponse } from '@muyun/web-contracts';
import { presentPlatformError } from '@muyun/platform-components';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';

const language = useLanguageContext();

defineOptions({ name: 'TenantApplicationConfigurationDrawer' });

const props = defineProps<{ context: ModulePageDrawerContext }>();
const applications = ref<Application[]>([]);
const enabledAliases = ref(new Set<string>());
const loading = ref(false);
const saving = ref(false);

const columns = computed<UiDataTableColumn[]>(() => [
  {
    key: 'title',
    title: language.t('platform.screen.TenantApplicationConfigurationDrawer.application-name', '应用名称'),
    width: 260,
  },
  {
    key: 'alias',
    title: language.t('platform.screen.TenantApplicationConfigurationDrawer.application-alias', '应用 alias'),
    width: 220,
  },
]);
const rows = computed(() => applications.value as unknown as UiDataTableRecord[]);
const selection = computed(() => ({
  selectedRowKeys: [...enabledAliases.value],
  preserveSelectedRowKeys: true,
  disabledOf: (record: UiDataTableRecord) => saving.value || record.alias === 'iam',
  onChange: (keys: (string | number)[]) => {
    enabledAliases.value = new Set(keys.map(String));
  },
}));

function syncTitleActions() {
  props.context.setCloseBlocked(saving.value);
  props.context.setTitleActions([
    {
      key: 'cancel',
      label: language.t('platform.action.cancel', '取消'),
      disabled: saving.value,
      run: () => {
        if (!saving.value) props.context.close();
      },
    },
    {
      key: 'confirm',
      label: language.t('platform.screen.UiModal.confirmText', '确认'),
      emphasis: 'primary',
      disabled: saving.value,
      loading: saving.value,
      run: save,
    },
  ]);
}

onMounted(() => {
  syncTitleActions();
  void load();
});

watch([saving, language.revision], syncTitleActions);

async function load() {
  const tenantId = String(props.context.record?.id ?? '');
  if (!tenantId) return;
  loading.value = true;
  try {
    const [applicationResponse, tenantResponse] = await Promise.all([
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
    applications.value = applicationResponse.records.filter((application) => application.enabled !== false);
    const available = new Set(applications.value.map((application) => application.alias).filter(Boolean));
    enabledAliases.value = new Set([
      'iam',
      ...tenantResponse.records
        .map((application) => application.applicationAlias)
        .filter((alias): alias is string => typeof alias === 'string' && available.has(alias)),
    ]);
  } catch (error) {
    presentPlatformError(error, { language, source: 'tenant-application-configuration', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function save() {
  const tenantId = String(props.context.record?.id ?? '');
  if (!tenantId || saving.value) return;
  saving.value = true;
  try {
    await props.context.module.http.request({
      method: 'POST',
      path: tenantApplicationsPath(tenantId, 'configure'),
      body: { applicationAliases: [...enabledAliases.value] },
    });
    props.context.refreshDetailExtensions();
    props.context.refreshList();
    props.context.close();
  } catch (error) {
    presentPlatformError(error, { language, source: 'tenant-application-configuration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

function tenantApplicationsPath(tenantId: string, suffix: 'query' | 'configure') {
  return `/iam.tenant/${encodeURIComponent(tenantId)}/applications/${suffix}`;
}
</script>

<template>
  <section class="tenant-application-configuration">
    <p>
      {{
        language.t(
          'platform.screen.TenantApplicationConfigurationDrawer.select-applications-to-provision-for-this-tenant-deselecting-an-application',
          '勾选表示向当前租户开通应用；取消勾选将移除该租户的应用开通记录。',
        )
      }}
    </p>
    <UiDataTable
      :columns="columns"
      :rows="rows"
      :loading="loading"
      :pagination="false"
      :selection="selection"
      :row-key="(record) => String(record.alias ?? record.id ?? '')"
      :empty-description="
        language.t(
          'platform.screen.TenantApplicationConfigurationDrawer.no-applications-available-for-configuration',
          '暂无可配置应用',
        )
      "
    />
  </section>
</template>

<style scoped>
.tenant-application-configuration {
  display: grid;
  gap: 12px;
}
.tenant-application-configuration p {
  margin: 0;
  color: var(--muyun-text-muted);
}
</style>
