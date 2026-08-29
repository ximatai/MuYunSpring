<script setup lang="ts">
import { computed, ref, type ComponentPublicInstance, watch } from 'vue';
import {
  DrawerOperationBar,
  ManagementExplorerColumn,
  ManagementWorkspace,
  RecordDetailPanel,
  RecordExplorerCreateButton,
  RecordExplorerPanel,
  RecordListExplorer,
  RecordMetaSection,
  RecordModeDrawer,
  handlePlatformActionSuccess,
  presentPlatformError,
  presentPlatformMessage,
  type RecordExplorerItemDescriptor,
  type RecordListExplorerRecord,
} from '@muyun/platform-components';
import type { MetadataField, ModuleMetadataRelation, WebPageResponse } from '@muyun/web-contracts';
import { createStaticResourceCrudClient, useModuleContext } from '@muyun/web-core';
import {
  confirmAction,
  UiActionButton,
  UiDataTable,
  UiEmpty,
  UiInput,
  UiSelect,
  UiSpin,
  UiSwitch,
  type UiDataTableColumn,
  type UiDataTableRecord,
} from '@muyun/vue-ui-antdv';
import {
  createUiOrchestrationState,
  isValidUiConfigFieldDraft,
  isValidUiSetDraft,
  normalizeUiConfigDraft,
  normalizeUiConfigFieldDraft,
  normalizeUiSetDraft,
  pageExecutionStatusOf,
  uiConfigItemTitle,
  uiSetItemTitle,
  type PlatformUiConfig,
  type PlatformUiConfigField,
  type PlatformUiSet,
  type UiModuleFieldOption,
} from './uiOrchestrationState';

defineOptions({ name: 'UiOrchestrationView' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string; title?: string }>();
const QUERY_PAGE_SIZE = 200;
type ModuleMetadataField = { id?: string; metadataFieldId?: string };
const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const state = createUiOrchestrationState();
const loading = ref(false);
const saving = ref(false);
const pageHost = ref<ComponentPublicInstance | null>(null);
const setKeyword = ref('');
const configKeyword = ref('');
const moduleFieldOptions = ref<UiModuleFieldOption[]>([]);
const mainRelation = ref<ModuleMetadataRelation>();
const publishedConfigCatalog = ref<PlatformUiConfig[]>([]);
const pageRoot = computed(() => (pageHost.value?.$el instanceof HTMLElement ? pageHost.value.$el : null));

const setTypeOptions = [
  { value: 'LIST', label: '列表' },
  { value: 'FORM', label: '表单' },
  { value: 'DETAIL', label: '详情' },
  { value: 'REFERENCE', label: '引用' },
];
const clientTypeOptions = [
  { value: 'WEB', label: 'Web' },
  { value: 'APP', label: 'App' },
];
const fieldColumns: UiDataTableColumn[] = [
  { key: 'moduleMetadataFieldId', title: '模块字段' },
  { key: 'fieldUiControlAlias', title: '控件', width: 140 },
  { key: 'visible', title: '可见', width: 70 },
  { key: 'readOnly', title: '只读', width: 70 },
  { key: 'enabled', title: '状态', width: 70 },
];

const selectedSetEnabled = computed(() => state.selectedUiSet.value?.enabled !== false);
const canPublish = computed(
  () =>
    state.selectedUiConfig.value &&
    selectedSetEnabled.value &&
    state.selectedUiConfig.value.enabled !== false,
);
const pageExecutionStatus = computed(() =>
  pageExecutionStatusOf(state.uiSets.value, publishedConfigCatalog.value),
);
const setMutationLocked = computed(() => state.configs.value.some((config) => config.published));

watch(
  () => props.moduleAlias,
  () => {
    void loadWorkspace();
    void loadModuleFieldOptions();
  },
  { immediate: true },
);

function uiSetClient() {
  return createStaticResourceCrudClient<PlatformUiSet>(
    moduleContext.http,
    `/platform.module/${encodeURIComponent(props.moduleAlias)}/ui-sets`,
  );
}

function configClient(uiSetId: string) {
  return createStaticResourceCrudClient<PlatformUiConfig>(
    moduleContext.http,
    `/platform.ui-set/${encodeURIComponent(uiSetId)}/configs`,
  );
}

function fieldClient(uiConfigId: string) {
  return createStaticResourceCrudClient<PlatformUiConfigField>(
    moduleContext.http,
    `/platform.ui-config/${encodeURIComponent(uiConfigId)}/fields`,
  );
}

async function loadWorkspace() {
  loading.value = true;
  try {
    state.handleUiSetsLoaded(await loadAllRecords<PlatformUiSet>(uiSetPath('/query')));
    await loadPublishedConfigCatalog();
    await loadConfigs();
  } catch (cause) {
    presentPlatformError(cause, { source: 'ui-orchestration', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function loadConfigs() {
  const uiSetId = state.selectedUiSet.value?.id;
  state.handleConfigsLoaded([]);
  state.handleFieldsLoaded([]);
  if (!uiSetId) return;
  try {
    state.handleConfigsLoaded(await loadAllRecords<PlatformUiConfig>(configPath(uiSetId, '/query')));
    await loadFields();
  } catch (cause) {
    presentPlatformError(cause, { source: 'ui-orchestration', phase: 'load' });
  }
}

async function loadFields() {
  const uiConfigId = state.selectedUiConfig.value?.id;
  state.handleFieldsLoaded([]);
  if (!uiConfigId) return;
  try {
    state.handleFieldsLoaded(await loadAllRecords<PlatformUiConfigField>(fieldPath(uiConfigId, '/query')));
  } catch (cause) {
    presentPlatformError(cause, { source: 'ui-orchestration', phase: 'load' });
  }
}

async function loadModuleFieldOptions() {
  try {
    const relations = await loadAllRecords<ModuleMetadataRelation>(
      `/platform.module/${encodeURIComponent(props.moduleAlias)}/metadata-relations/query`,
    );
    const relation = relations.find((item) => item.relationRole === 'MAIN');
    mainRelation.value = relation;
    if (!relation?.id || !relation.metadataId) {
      moduleFieldOptions.value = [];
      return;
    }
    const [moduleFields, metadataFields] = await Promise.all([
      loadAllRecords<ModuleMetadataField>(
        `/platform.module/${encodeURIComponent(props.moduleAlias)}/metadata-relations/${encodeURIComponent(relation.id)}/fields/query`,
      ),
      loadAllRecords<MetadataField>(
        `/platform.metadata/${encodeURIComponent(relation.metadataId)}/fields/query`,
      ),
    ]);
    const metadataById = new Map(metadataFields.map((field) => [field.id, field]));
    moduleFieldOptions.value = moduleFields.flatMap((field) => {
      if (!field.id) return [];
      const metadata = field.metadataFieldId ? metadataById.get(field.metadataFieldId) : undefined;
      const title = metadata?.title ?? metadata?.fieldName ?? field.metadataFieldId ?? field.id;
      const secondary = metadata?.fieldName && metadata.title ? ` · ${metadata.fieldName}` : '';
      return [{ value: field.id, label: `${relation.relationAlias ?? '主实体'} · ${title}${secondary}` }];
    });
  } catch (cause) {
    moduleFieldOptions.value = [];
    presentPlatformError(cause, { source: 'ui-orchestration', phase: 'load' });
  }
}

async function ensureMainRelationFields() {
  const relationId = mainRelation.value?.id;
  if (!relationId) {
    presentPlatformMessage('请先在元数据编排中创建动态模块的主实体', {
      source: 'ui-orchestration',
      phase: 'validation',
    });
    return;
  }
  await runMutation(async () => {
    await moduleContext.http.request({
      method: 'POST',
      path: `/platform.module/${encodeURIComponent(props.moduleAlias)}/metadata-relations/${encodeURIComponent(relationId)}/fields/ensure`,
    });
    await loadModuleFieldOptions();
    await handlePlatformActionSuccess(
      { success: true, message: '主实体模块字段已同步，可加入 UI 配置' },
      { source: 'ui-orchestration' },
    );
  });
}

async function loadPublishedConfigCatalog() {
  const all = await Promise.all(
    state.uiSets.value
      .filter((uiSet): uiSet is PlatformUiSet & { id: string } => Boolean(uiSet.id))
      .map((uiSet) => loadAllRecords<PlatformUiConfig>(configPath(uiSet.id, '/query'))),
  );
  publishedConfigCatalog.value = all.flat();
}

async function selectUiSet(record: RecordListExplorerRecord) {
  if (state.selectUiSet(record as PlatformUiSet)) await loadConfigs();
}

async function selectUiConfig(record: RecordListExplorerRecord) {
  if (state.selectUiConfig(record as PlatformUiConfig)) await loadFields();
}

async function saveUiSet() {
  const draft = normalizeUiSetDraft(state.uiSetDraft.value);
  if (!isValidUiSetDraft(draft)) {
    presentPlatformMessage('请填写 UI 配置集 alias、名称和类型', {
      source: 'ui-orchestration',
      phase: 'validation',
    });
    return;
  }
  saving.value = true;
  try {
    const result = draft.id ? await uiSetClient().update(draft.id, draft) : await uiSetClient().insert(draft);
    state.cancelEditor();
    state.handleUiSetsLoaded(await loadAllRecords<PlatformUiSet>(uiSetPath('/query')));
    state.selectUiSet(result.record);
    await loadConfigs();
    await handlePlatformActionSuccess(
      { success: true, message: draft.id ? 'UI 配置集已保存' : 'UI 配置集已创建' },
      { source: 'ui-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'ui-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function saveUiConfig() {
  const uiSetId = state.selectedUiSet.value?.id;
  if (!uiSetId || state.configPublished.value) return;
  const draft = { ...normalizeUiConfigDraft(state.uiConfigDraft.value), uiSetId, published: false };
  saving.value = true;
  try {
    const result = draft.id
      ? await configClient(uiSetId).update(draft.id, draft)
      : await configClient(uiSetId).insert(draft);
    state.cancelEditor();
    state.handleConfigsLoaded(await loadAllRecords<PlatformUiConfig>(configPath(uiSetId, '/query')));
    state.selectUiConfig(result.record);
    await loadFields();
    await handlePlatformActionSuccess(
      { success: true, message: draft.id ? 'UI 配置已保存' : 'UI 配置已创建' },
      { source: 'ui-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'ui-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function saveField() {
  const uiConfigId = state.selectedUiConfig.value?.id;
  if (!uiConfigId || state.configPublished.value) return;
  const draft = { ...normalizeUiConfigFieldDraft(state.fieldDraft.value), uiConfigId };
  if (!isValidUiConfigFieldDraft(draft)) {
    presentPlatformMessage('请选择模块字段', { source: 'ui-orchestration', phase: 'validation' });
    return;
  }
  saving.value = true;
  try {
    if (draft.id) await fieldClient(uiConfigId).update(draft.id, draft);
    else await fieldClient(uiConfigId).insert(draft);
    state.cancelEditor();
    await loadFields();
    await handlePlatformActionSuccess(
      { success: true, message: draft.id ? '字段编排已保存' : '字段已加入 UI 配置' },
      { source: 'ui-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'ui-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function removeUiSet() {
  const uiSet = state.selectedUiSet.value;
  if (!uiSet?.id || !(await confirmDelete('UI 配置集', uiSetItemTitle(uiSet)))) return;
  await runMutation(async () => {
    await uiSetClient().delete(uiSet.id!, versionRequest(uiSet));
    await loadWorkspace();
  });
}

async function removeUiConfig() {
  const uiSetId = state.selectedUiSet.value?.id;
  const config = state.selectedUiConfig.value;
  if (
    !uiSetId ||
    !config?.id ||
    config.published ||
    !(await confirmDelete('UI 配置', uiConfigItemTitle(config)))
  )
    return;
  await runMutation(async () => {
    await configClient(uiSetId).delete(config.id!, versionRequest(config));
    await loadConfigs();
  });
}

async function removeField(field: PlatformUiConfigField) {
  const uiConfigId = state.selectedUiConfig.value?.id;
  if (
    !uiConfigId ||
    !field.id ||
    state.configPublished.value ||
    !(await confirmDelete('字段编排', fieldLabelOf(field)))
  )
    return;
  await runMutation(async () => {
    await fieldClient(uiConfigId).delete(field.id!, versionRequest(field));
    await loadFields();
  });
}

async function toggleUiSet() {
  const uiSet = state.selectedUiSet.value;
  if (!uiSet?.id) return;
  await runMutation(async () => {
    const client = uiSetClient();
    if (uiSet.enabled === false) await client.enable(uiSet.id!, versionRequest(uiSet));
    else await client.disable(uiSet.id!, versionRequest(uiSet));
    await loadWorkspace();
  });
}

async function toggleUiConfig() {
  const uiSetId = state.selectedUiSet.value?.id;
  const config = state.selectedUiConfig.value;
  if (!uiSetId || !config?.id || config.published) return;
  await runMutation(async () => {
    const client = configClient(uiSetId);
    if (config.enabled === false) await client.enable(config.id!, versionRequest(config));
    else await client.disable(config.id!, versionRequest(config));
    await loadConfigs();
  });
}

async function toggleField(field: PlatformUiConfigField) {
  const uiConfigId = state.selectedUiConfig.value?.id;
  if (!uiConfigId || !field.id || state.configPublished.value) return;
  await runMutation(async () => {
    const client = fieldClient(uiConfigId);
    if (field.enabled === false) await client.enable(field.id!, versionRequest(field));
    else await client.disable(field.id!, versionRequest(field));
    await loadFields();
  });
}

async function publish() {
  const config = state.selectedUiConfig.value;
  if (!config?.id || !canPublish.value) return;
  await runMutation(async () => {
    await moduleContext.http.request({
      method: 'POST',
      path: `/platform.page_config_publish/ui-configs/${encodeURIComponent(config.id!)}/publish`,
      query: versionRequest(config),
    });
    await loadWorkspace();
    await handlePlatformActionSuccess(
      { success: true, message: 'UI 配置已发布' },
      { source: 'ui-orchestration' },
    );
  });
}

async function unpublish() {
  const config = state.selectedUiConfig.value;
  if (!config?.id || !config.published) return;
  await runMutation(async () => {
    await moduleContext.http.request({
      method: 'POST',
      path: `/platform.page_config_publish/ui-configs/${encodeURIComponent(config.id!)}/unpublish`,
      query: versionRequest(config),
    });
    await loadWorkspace();
    await handlePlatformActionSuccess(
      { success: true, message: 'UI 配置已取消发布' },
      { source: 'ui-orchestration' },
    );
  });
}

async function runMutation(action: () => Promise<void>) {
  saving.value = true;
  try {
    await action();
  } catch (cause) {
    presentPlatformError(cause, { source: 'ui-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

function uiSetPath(suffix: string) {
  return `/platform.module/${encodeURIComponent(props.moduleAlias)}/ui-sets${suffix}`;
}

function configPath(uiSetId: string, suffix: string) {
  return `/platform.ui-set/${encodeURIComponent(uiSetId)}/configs${suffix}`;
}

function fieldPath(uiConfigId: string, suffix: string) {
  return `/platform.ui-config/${encodeURIComponent(uiConfigId)}/fields${suffix}`;
}

async function loadAllRecords<T>(path: string): Promise<T[]> {
  const records: T[] = [];
  for (let pageNum = 1; ; pageNum += 1) {
    const response = await moduleContext.http.request<WebPageResponse<T>>({
      method: 'POST',
      path,
      body: { page: { pageNum, pageSize: QUERY_PAGE_SIZE } },
    });
    records.push(...response.records);
    if (response.totalKnown ? pageNum >= response.pages : response.records.length < QUERY_PAGE_SIZE)
      return records;
  }
}

function uiSetItemOf(record: RecordListExplorerRecord): RecordExplorerItemDescriptor {
  const uiSet = record as PlatformUiSet;
  return {
    title: uiSetItemTitle(uiSet),
    secondary: uiSet.alias,
    tag: uiSet.setType ? setTypeLabel(uiSet.setType) : undefined,
    muted: uiSet.enabled === false,
  };
}

function uiConfigItemOf(record: RecordListExplorerRecord): RecordExplorerItemDescriptor {
  const config = record as PlatformUiConfig;
  return {
    title: uiConfigItemTitle(config),
    secondary: config.clientType,
    tag: config.published ? '已发布' : '草稿',
    muted: config.enabled === false,
  };
}

function fieldCellValue(column: UiDataTableColumn, record: UiDataTableRecord) {
  const field = record as PlatformUiConfigField;
  if (column.key === 'moduleMetadataFieldId') return fieldLabelOf(field);
  if (column.key === 'visible') return field.visible === false ? '隐藏' : '显示';
  if (column.key === 'readOnly') return field.readOnly ? '是' : '否';
  if (column.key === 'enabled') return field.enabled === false ? '停用' : '启用';
  return String(field[column.key as keyof PlatformUiConfigField] ?? '-');
}

function fieldLabelOf(field: PlatformUiConfigField) {
  return (
    moduleFieldOptions.value.find((item) => item.value === field.moduleMetadataFieldId)?.label ??
    field.moduleMetadataFieldId ??
    '-'
  );
}

function setTypeLabel(type: string) {
  return setTypeOptions.find((item) => item.value === type)?.label ?? type;
}

function configStatus(config: PlatformUiConfig) {
  return config.published ? '已发布（只读）' : '草稿';
}

function confirmDelete(recordName: string, title: string) {
  return confirmAction({
    title: `删除${recordName}`,
    content: `确认删除“${title}”？`,
    okText: '删除',
    danger: true,
  });
}

function versionRequest(record: { version?: number }): { version: number } {
  if (record.version == null) throw new Error('记录版本缺失，已拒绝覆盖可能由其他用户更新的配置');
  return { version: record.version };
}
</script>

<template>
  <ManagementWorkspace ref="pageHost" class="ui-orchestration-workspace" :explorer-count="2">
    <ManagementExplorerColumn>
      <RecordExplorerPanel
        v-model:search-keyword="setKeyword"
        title="UI 配置集"
        search-placeholder="搜索名称或 alias"
        @refresh="loadWorkspace"
      >
        <template #actions>
          <RecordExplorerCreateButton
            title="新建 UI 配置集"
            :disabled="loading || saving"
            @click="state.startCreateSet"
          />
        </template>
        <UiSpin v-if="loading" tip="加载 UI 配置集" />
        <UiEmpty v-else-if="!state.uiSets.value.length" description="暂无 UI 配置集" />
        <RecordListExplorer
          v-else
          :records="state.uiSets.value as unknown as RecordListExplorerRecord[]"
          :selected-id="state.selectedUiSetId.value"
          :keyword="setKeyword"
          empty-description="暂无匹配的 UI 配置集"
          :item-of="uiSetItemOf"
          @select="selectUiSet"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <ManagementExplorerColumn>
      <RecordExplorerPanel
        v-model:search-keyword="configKeyword"
        title="UI 配置"
        :subtitle="state.selectedUiSet.value ? uiSetItemTitle(state.selectedUiSet.value) : undefined"
        search-placeholder="搜索配置名称"
        @refresh="loadConfigs"
      >
        <template #actions>
          <RecordExplorerCreateButton
            title="新建 UI 配置"
            :disabled="!state.selectedUiSet.value || loading || saving"
            @click="state.startCreateConfig"
          />
        </template>
        <UiEmpty v-if="!state.selectedUiSet.value" description="先选择 UI 配置集" />
        <UiSpin v-else-if="loading" tip="加载 UI 配置" />
        <UiEmpty v-else-if="!state.configs.value.length" description="暂无 UI 配置" />
        <RecordListExplorer
          v-else
          :records="state.configs.value as unknown as RecordListExplorerRecord[]"
          :selected-id="state.selectedUiConfigId.value"
          :keyword="configKeyword"
          empty-description="暂无匹配的 UI 配置"
          :item-of="uiConfigItemOf"
          @select="selectUiConfig"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <RecordDetailPanel
      v-if="state.selectedUiConfig.value"
      :title="uiConfigItemTitle(state.selectedUiConfig.value)"
      :subtitle="configStatus(state.selectedUiConfig.value)"
    >
      <template #actions>
        <UiActionButton emphasis="quiet" :disabled="saving || setMutationLocked" @click="state.startEditSet">
          编辑配置集
        </UiActionButton>
        <UiActionButton emphasis="quiet" :disabled="saving || setMutationLocked" @click="toggleUiSet">
          {{ selectedSetEnabled ? '停用配置集' : '启用配置集' }}
        </UiActionButton>
        <UiActionButton
          emphasis="quiet"
          intent="danger"
          :disabled="saving || setMutationLocked"
          @click="removeUiSet"
        >
          删除配置集
        </UiActionButton>
        <UiActionButton
          v-if="state.configPublished.value"
          emphasis="primary"
          :disabled="saving"
          @click="unpublish"
        >
          取消发布
        </UiActionButton>
        <UiActionButton v-else emphasis="primary" :disabled="saving || !canPublish" @click="publish">
          发布
        </UiActionButton>
        <UiActionButton
          emphasis="quiet"
          :disabled="saving || state.configPublished.value"
          @click="state.startEditConfig"
        >
          编辑配置
        </UiActionButton>
        <UiActionButton
          emphasis="quiet"
          :disabled="saving || state.configPublished.value"
          @click="toggleUiConfig"
        >
          {{ state.selectedUiConfig.value.enabled === false ? '启用配置' : '停用配置' }}
        </UiActionButton>
        <UiActionButton
          emphasis="quiet"
          intent="danger"
          :disabled="saving || state.configPublished.value"
          @click="removeUiConfig"
        >
          删除配置
        </UiActionButton>
        <UiActionButton
          emphasis="primary"
          :disabled="saving || state.configPublished.value || !moduleFieldOptions.length"
          @click="state.startCreateField"
        >
          新增字段
        </UiActionButton>
      </template>

      <p
        class="execution-status"
        :class="{ 'execution-status--ready': !pageExecutionStatus.startsWith('已发布但未') }"
      >
        {{ pageExecutionStatus }}
      </p>
      <p v-if="setMutationLocked" class="published-hint">
        此 UI 配置集包含已发布配置；请先取消发布后再修改、启停或删除配置集。
      </p>
      <p v-if="state.configPublished.value" class="published-hint">
        已发布配置为只读；取消发布后才能修改配置、字段与启停状态。
      </p>
      <section class="field-section">
        <header class="field-section-header"><h3>字段编排</h3></header>
        <UiSpin v-if="loading" tip="加载字段编排" />
        <div v-else-if="!moduleFieldOptions.length" class="module-field-preparation">
          <p>
            {{
              mainRelation
                ? '主实体模块字段尚未同步，字段编排必须引用模块字段配置。'
                : '动态模块尚无主实体，请先完成元数据编排。'
            }}
          </p>
          <UiActionButton
            emphasis="primary"
            :disabled="saving || !mainRelation"
            @click="ensureMainRelationFields"
          >
            同步主实体模块字段
          </UiActionButton>
        </div>
        <UiEmpty v-else-if="!state.fields.value.length" description="暂无字段，点击右上角新增字段开始编排" />
        <UiDataTable
          v-else
          :columns="fieldColumns"
          :rows="state.fields.value as unknown as UiDataTableRecord[]"
          show-action-column
          action-column-title="操作"
        >
          <template #cell="{ column, record }">{{ fieldCellValue(column, record) }}</template>
          <template #rowActions="{ record }">
            <UiActionButton
              emphasis="quiet"
              density="compact"
              :disabled="state.configPublished.value"
              @click="state.startEditField(record as PlatformUiConfigField)"
            >
              编辑
            </UiActionButton>
            <UiActionButton
              emphasis="quiet"
              density="compact"
              :disabled="state.configPublished.value || saving"
              @click="toggleField(record as PlatformUiConfigField)"
            >
              {{ (record as PlatformUiConfigField).enabled === false ? '启用' : '停用' }}
            </UiActionButton>
            <UiActionButton
              emphasis="quiet"
              density="compact"
              intent="danger"
              :disabled="state.configPublished.value || saving"
              @click="removeField(record as PlatformUiConfigField)"
            >
              删除
            </UiActionButton>
          </template>
        </UiDataTable>
      </section>
      <RecordMetaSection :record="state.selectedUiConfig.value" />
    </RecordDetailPanel>
    <RecordDetailPanel
      v-else-if="state.selectedUiSet.value"
      :title="uiSetItemTitle(state.selectedUiSet.value)"
      subtitle="选择或新建 UI 配置后开始字段编排"
    >
      <template #actions>
        <UiActionButton emphasis="quiet" :disabled="saving || setMutationLocked" @click="state.startEditSet">
          编辑配置集
        </UiActionButton>
        <UiActionButton emphasis="quiet" :disabled="saving || setMutationLocked" @click="toggleUiSet">
          {{ selectedSetEnabled ? '停用配置集' : '启用配置集' }}
        </UiActionButton>
        <UiActionButton
          emphasis="quiet"
          intent="danger"
          :disabled="saving || setMutationLocked"
          @click="removeUiSet"
        >
          删除配置集
        </UiActionButton>
      </template>
      <p
        class="execution-status"
        :class="{ 'execution-status--ready': !pageExecutionStatus.startsWith('已发布但未') }"
      >
        {{ pageExecutionStatus }}
      </p>
      <p v-if="setMutationLocked" class="published-hint">
        此 UI 配置集包含已发布配置；请先取消发布后再修改、启停或删除配置集。
      </p>
      <UiEmpty description="此配置集尚无 UI 配置" />
      <RecordMetaSection :record="state.selectedUiSet.value" />
    </RecordDetailPanel>
    <RecordDetailPanel v-else title="动态模块 UI 编排">
      <UiEmpty description="新建或选择 UI 配置集后开始编排" />
    </RecordDetailPanel>
  </ManagementWorkspace>

  <RecordModeDrawer
    :open="state.setEditorOpen.value"
    :title="state.mode.value === 'edit-set' ? '编辑 UI 配置集' : '新建 UI 配置集'"
    :container="pageRoot"
    :subtitle="moduleTitle ?? moduleAlias"
    mode="create"
    @close="state.cancelEditor"
  >
    <template #form>
      <form class="orchestration-form" @submit.prevent="saveUiSet">
        <label
          ><span>名称</span><UiInput v-model:value="state.uiSetDraft.value.title" placeholder="例如 客户列表"
        /></label>
        <label
          ><span>Alias</span
          ><UiInput
            v-model:value="state.uiSetDraft.value.alias"
            :disabled="Boolean(state.uiSetDraft.value.id)"
            placeholder="例如 customer_list"
        /></label>
        <label
          ><span>类型</span
          ><UiSelect
            v-model:value="state.uiSetDraft.value.setType"
            :disabled="Boolean(state.uiSetDraft.value.id)"
            :options="setTypeOptions"
            style="width: 100%"
        /></label>
        <UiSwitch
          v-model:checked="state.uiSetDraft.value.defaultSet"
          checked-children="默认配置集"
          un-checked-children="非默认"
        />
        <UiSwitch
          v-model:checked="state.uiSetDraft.value.enabled"
          checked-children="启用"
          un-checked-children="停用"
        />
      </form>
    </template>
    <template #operation>
      <DrawerOperationBar>
        <UiActionButton :disabled="saving" @click="state.cancelEditor">取消</UiActionButton
        ><UiActionButton emphasis="primary" submit :loading="saving" @click="saveUiSet">
          保存
        </UiActionButton>
      </DrawerOperationBar>
    </template>
  </RecordModeDrawer>

  <RecordModeDrawer
    :open="state.configEditorOpen.value"
    :title="state.mode.value === 'edit-config' ? '编辑 UI 配置' : '新建 UI 配置'"
    :container="pageRoot"
    :subtitle="state.selectedUiSet.value ? uiSetItemTitle(state.selectedUiSet.value) : undefined"
    mode="create"
    @close="state.cancelEditor"
  >
    <template #form>
      <form class="orchestration-form" @submit.prevent="saveUiConfig">
        <label
          ><span>名称（可选）</span
          ><UiInput v-model:value="state.uiConfigDraft.value.title" placeholder="默认按配置集和客户端生成"
        /></label>
        <label
          ><span>客户端</span
          ><UiSelect
            v-model:value="state.uiConfigDraft.value.clientType"
            :disabled="Boolean(state.uiConfigDraft.value.id)"
            :options="clientTypeOptions"
            style="width: 100%"
        /></label>
        <UiSwitch
          v-model:checked="state.uiConfigDraft.value.enabled"
          checked-children="启用"
          un-checked-children="停用"
        />
      </form>
    </template>
    <template #operation>
      <DrawerOperationBar>
        <UiActionButton :disabled="saving" @click="state.cancelEditor">取消</UiActionButton
        ><UiActionButton emphasis="primary" submit :loading="saving" @click="saveUiConfig">
          保存
        </UiActionButton>
      </DrawerOperationBar>
    </template>
  </RecordModeDrawer>

  <RecordModeDrawer
    :open="state.fieldEditorOpen.value"
    :title="state.mode.value === 'edit-field' ? '编辑字段编排' : '新增字段编排'"
    :container="pageRoot"
    :subtitle="state.selectedUiConfig.value ? uiConfigItemTitle(state.selectedUiConfig.value) : undefined"
    mode="create"
    @close="state.cancelEditor"
  >
    <template #form>
      <form class="orchestration-form" @submit.prevent="saveField">
        <label
          ><span>模块字段</span
          ><UiSelect
            v-model:value="state.fieldDraft.value.moduleMetadataFieldId"
            :disabled="Boolean(state.fieldDraft.value.id)"
            :options="moduleFieldOptions"
            placeholder="选择已同步的模块字段"
            style="width: 100%"
        /></label>
        <label
          ><span>控件 alias（可选）</span
          ><UiInput
            v-model:value="state.fieldDraft.value.fieldUiControlAlias"
            placeholder="留空使用字段规格默认控件"
        /></label>
        <UiSwitch
          v-model:checked="state.fieldDraft.value.visible"
          checked-children="显示"
          un-checked-children="隐藏"
        />
        <UiSwitch
          v-model:checked="state.fieldDraft.value.readOnly"
          checked-children="只读"
          un-checked-children="可编辑"
        />
        <UiSwitch
          v-model:checked="state.fieldDraft.value.enabled"
          checked-children="启用"
          un-checked-children="停用"
        />
      </form>
    </template>
    <template #operation>
      <DrawerOperationBar>
        <UiActionButton :disabled="saving" @click="state.cancelEditor">取消</UiActionButton
        ><UiActionButton emphasis="primary" submit :loading="saving" @click="saveField">
          保存
        </UiActionButton>
      </DrawerOperationBar>
    </template>
  </RecordModeDrawer>
</template>

<style scoped>
.field-section {
  display: grid;
  gap: 10px;
  padding-top: 8px;
}
.field-section-header h3 {
  margin: 0;
  color: var(--muyun-text-body);
  font-size: 13px;
  font-weight: 700;
}
.published-hint {
  margin: 0 0 10px;
  color: var(--muyun-warning-text);
  font-size: 13px;
}
.execution-status {
  margin: 0 0 10px;
  color: var(--muyun-warning-text);
  font-size: 13px;
}
.execution-status--ready {
  color: var(--muyun-success-text);
}
.module-field-preparation {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px dashed var(--muyun-border);
  border-radius: 8px;
}
.module-field-preparation p {
  margin: 0;
  color: var(--muyun-text-muted);
}
.orchestration-form {
  display: grid;
  gap: 16px;
  padding: 4px 2px;
}
.orchestration-form label {
  display: grid;
  gap: 7px;
  color: var(--muyun-text-body);
}
</style>
