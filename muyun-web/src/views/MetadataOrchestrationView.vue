<script setup lang="ts">
import { computed, onMounted, ref, type ComponentPublicInstance, watch } from 'vue';
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
import type { Metadata, MetadataField, ModuleMetadataRelation, WebPageResponse } from '@muyun/web-contracts';
import { createStaticResourceCrudClient, useModuleContext } from '@muyun/web-core';
import {
  UiActionButton,
  UiCheckbox,
  UiDataTable,
  UiEmpty,
  UiInput,
  UiSelect,
  UiSpin,
  UiSwitch,
  confirmAction,
  type UiDataTableColumn,
  type UiDataTableRecord,
} from '@muyun/vue-ui-antdv';
import {
  createMetadataOrchestrationState,
  entityExplorerItem,
  entityTitleOf,
  isValidFieldDraft,
  isValidMainMetadataDraft,
  metadataSubtitleOf,
  normalizeFieldDraft,
  normalizeMainMetadataDraft,
} from './metadataOrchestrationState';

defineOptions({ name: 'MetadataOrchestrationView' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string; title?: string }>();

type CreationResult = { metadata: Metadata; relation: ModuleMetadataRelation };
const ORCHESTRATION_QUERY_PAGE_SIZE = 200;

const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const metadataClient = createStaticResourceCrudClient<Metadata>(moduleContext.http, '/platform.metadata');
const state = createMetadataOrchestrationState();
const loading = ref(false);
const pageHost = ref<ComponentPublicInstance | null>(null);
const pageRoot = computed(() => (pageHost.value?.$el instanceof HTMLElement ? pageHost.value.$el : null));
const saving = ref(false);
const searchKeyword = ref('');

const fieldColumns: UiDataTableColumn[] = [
  { key: 'title', title: '字段' },
  { key: 'fieldName', title: '字段名', width: 150 },
  { key: 'fieldSpecAlias', title: '字段规格', width: 140 },
  { key: 'required', title: '必填', width: 70 },
  { key: 'enabled', title: '状态', width: 70 },
];

watch(
  () => props.moduleAlias,
  () => void loadWorkspace(),
  { immediate: true },
);

onMounted(() => void loadFieldSpecs());

async function loadWorkspace() {
  loading.value = true;
  try {
    state.handleRelationsLoaded(await loadAllRecords(relationPath('/query')));
    await loadSelectedMetadata();
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

async function loadFieldSpecs() {
  try {
    state.handleFieldSpecsLoaded(await loadAllRecords('/platform.field_spec/query'));
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
  }
}

async function handleSelectRelation(record: RecordListExplorerRecord) {
  if (state.selectRelation(record as ModuleMetadataRelation)) {
    await loadSelectedMetadata();
  }
}

async function loadSelectedMetadata() {
  const metadataId = state.selectedRelation.value?.metadataId;
  state.handleFieldsLoaded([]);
  if (!metadataId) return;
  try {
    const metadata = await metadataClient.view(metadataId);
    state.handleMetadataLoaded(metadata);
    state.handleFieldsLoaded(
      await loadAllRecords(`/platform.metadata/${encodeURIComponent(metadataId)}/fields/query`),
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
  }
}

async function createMainMetadata() {
  const draft = normalizeMainMetadataDraft(state.mainMetadataDraft.value);
  if (!isValidMainMetadataDraft(draft)) {
    presentPlatformMessage('请填写实体 alias 和名称', {
      source: 'metadata-orchestration',
      phase: 'validation',
    });
    return;
  }
  saving.value = true;
  try {
    const result = await moduleContext.http.request<CreationResult>({
      method: 'POST',
      path: relationPath('/create-main-metadata'),
      body: draft,
    });
    state.cancelEditor();
    await loadWorkspace();
    state.focusRelation(result.relation.id);
    await loadSelectedMetadata();
    await handlePlatformActionSuccess(
      { success: true, message: '主实体已创建' },
      { source: 'metadata-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function save() {
  const metadataId = state.selectedMetadata.value?.id;
  if (!metadataId) return;
  const draft = normalizeFieldDraft(state.fieldDraft.value);
  if (!isValidFieldDraft(draft)) {
    presentPlatformMessage('请填写字段名、物理列名和字段规格', {
      source: 'metadata-orchestration',
      phase: 'validation',
    });
    return;
  }
  saving.value = true;
  try {
    const record = { ...draft, metadataId, fieldOwnership: 'BUSINESS', fieldForm: 'PHYSICAL' };
    if (record.id)
      await moduleContext.http.request({
        method: 'POST',
        path: `/platform.metadata/${encodeURIComponent(metadataId)}/fields/update/${encodeURIComponent(record.id)}`,
        body: record,
      });
    else
      await moduleContext.http.request({
        method: 'POST',
        path: `/platform.metadata/${encodeURIComponent(metadataId)}/fields/insert`,
        body: record,
      });
    state.cancelEditor();
    await loadSelectedMetadata();
    await handlePlatformActionSuccess(
      { success: true, message: '字段已保存' },
      { source: 'metadata-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function deleteField(field: MetadataField) {
  const metadataId = state.selectedMetadata.value?.id;
  if (
    !metadataId ||
    !field.id ||
    !(await confirmAction({
      title: '删除字段',
      content: `确认删除字段“${field.title ?? field.fieldName}”？`,
      okText: '删除',
      danger: true,
    }))
  )
    return;
  saving.value = true;
  try {
    await moduleContext.http.request({
      method: 'POST',
      path: `/platform.metadata/${encodeURIComponent(metadataId)}/fields/delete/${encodeURIComponent(field.id)}`,
      body: { version: field.version },
    });
    await loadSelectedMetadata();
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function ensureModuleFields() {
  const relation = state.selectedRelation.value;
  if (!relation?.id) return;
  saving.value = true;
  try {
    await moduleContext.http.request({
      method: 'POST',
      path: relationPath(`/${encodeURIComponent(relation.id)}/fields/ensure`),
    });
    await handlePlatformActionSuccess(
      { success: true, message: '模块字段配置已同步' },
      { source: 'metadata-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

function relationPath(suffix: string) {
  return `/platform.module/${encodeURIComponent(props.moduleAlias)}/metadata-relations${suffix}`;
}

async function loadAllRecords<T>(path: string): Promise<T[]> {
  const records: T[] = [];
  for (let pageNum = 1; ; pageNum += 1) {
    const response = await moduleContext.http.request<WebPageResponse<T>>({
      method: 'POST',
      path,
      body: { page: { pageNum, pageSize: ORCHESTRATION_QUERY_PAGE_SIZE } },
    });
    records.push(...response.records);
    if (
      response.totalKnown
        ? pageNum >= response.pages
        : response.records.length < ORCHESTRATION_QUERY_PAGE_SIZE
    ) {
      return records;
    }
  }
}

function relationMetadataOf(relation: ModuleMetadataRelation): Metadata | undefined {
  return relation.metadataId ? state.metadataById.value[relation.metadataId] : undefined;
}

function entityItemOf(record: RecordListExplorerRecord): RecordExplorerItemDescriptor | undefined {
  const relation = record as ModuleMetadataRelation;
  return entityExplorerItem(relation, relationMetadataOf(relation));
}

function fieldCellValue(column: UiDataTableColumn, record: UiDataTableRecord) {
  const field = record as MetadataField;
  if (column.key === 'required') return field.required ? '是' : '否';
  if (column.key === 'enabled') return field.enabled === false ? '停用' : '启用';
  return String(field[column.key as keyof MetadataField] ?? '');
}
</script>

<template>
  <ManagementWorkspace ref="pageHost" class="metadata-orchestration-workspace" :explorer-count="1">
    <ManagementExplorerColumn>
      <RecordExplorerPanel
        v-model:search-keyword="searchKeyword"
        title="实体"
        search-placeholder="搜索实体名称或 alias"
        @refresh="loadWorkspace"
      >
        <template #actions>
          <RecordExplorerCreateButton
            v-if="!state.hasMainMetadata.value"
            title="新建主实体"
            :disabled="loading || saving"
            @click="state.startCreateMain"
          />
        </template>
        <UiSpin v-if="loading" tip="加载实体" />
        <UiEmpty v-else-if="!state.relations.value.length" description="请先创建动态模块的主实体" />
        <RecordListExplorer
          v-else
          :records="state.relations.value as unknown as RecordListExplorerRecord[]"
          :selected-id="state.selectedRelationId.value"
          :keyword="searchKeyword"
          empty-description="暂无实体"
          :item-of="entityItemOf"
          @select="handleSelectRelation"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <RecordDetailPanel
      v-if="state.selectedMetadata.value && state.selectedRelation.value"
      :title="entityTitleOf(state.selectedRelation.value, state.selectedMetadata.value)"
      :subtitle="metadataSubtitleOf(state.selectedMetadata.value)"
    >
      <template #actions>
        <UiActionButton emphasis="quiet" :disabled="saving || loading" @click="ensureModuleFields">
          同步模块字段配置
        </UiActionButton>
        <UiActionButton emphasis="primary" :disabled="saving || loading" @click="state.startCreateField">
          新增字段
        </UiActionButton>
      </template>

      <dl class="entity-overview">
        <div>
          <dt>实体 alias</dt>
          <dd>{{ state.selectedMetadata.value.alias ?? '-' }}</dd>
        </div>
        <div>
          <dt>物理表</dt>
          <dd>
            {{
              state.selectedMetadata.value.tableName
                ? `${state.selectedMetadata.value.schemaName ?? 'public'}.${state.selectedMetadata.value.tableName}`
                : '-'
            }}
          </dd>
        </div>
        <div>
          <dt>数据权限范围</dt>
          <dd>{{ state.selectedMetadata.value.dataScopeEnabled ? '已启用' : '未启用' }}</dd>
        </div>
      </dl>

      <section class="field-section">
        <header class="field-section-header">
          <h3>业务字段</h3>
        </header>
        <UiSpin v-if="loading" tip="加载字段" />
        <UiEmpty v-else-if="!state.fields.value.length" description="暂无业务字段，点击右上角新增字段" />
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
              @click="state.startEditField(record as MetadataField)"
            >
              编辑
            </UiActionButton>
            <UiActionButton
              emphasis="quiet"
              density="compact"
              intent="danger"
              :disabled="saving"
              @click="deleteField(record as MetadataField)"
            >
              删除
            </UiActionButton>
          </template>
        </UiDataTable>
      </section>

      <RecordMetaSection :record="state.selectedMetadata.value" />
    </RecordDetailPanel>
    <RecordDetailPanel v-else title="字段编排">
      <UiEmpty description="从左侧选择实体后开始字段编排" />
    </RecordDetailPanel>
  </ManagementWorkspace>

  <RecordModeDrawer
    :open="state.mainEditorOpen.value"
    title="新建主实体"
    :container="pageRoot"
    :subtitle="moduleTitle ?? moduleAlias"
    mode="create"
    @close="state.cancelEditor"
  >
    <template #form>
      <form class="orchestration-form" @submit.prevent="createMainMetadata">
        <label>
          <span>实体 alias</span>
          <UiInput v-model:value="state.mainMetadataDraft.value.alias" placeholder="例如 customer" />
        </label>
        <label>
          <span>实体名称</span>
          <UiInput v-model:value="state.mainMetadataDraft.value.title" placeholder="例如 客户" />
        </label>
        <label>
          <span>Schema（可选）</span>
          <UiInput v-model:value="state.mainMetadataDraft.value.schemaName" placeholder="默认 public" />
        </label>
        <label>
          <span>物理表名（可选）</span>
          <UiInput
            v-model:value="state.mainMetadataDraft.value.tableName"
            placeholder="默认按应用和 alias 生成"
          />
        </label>
        <UiCheckbox v-model:checked="state.mainMetadataDraft.value.dataScopeEnabled">
          启用数据权限范围
        </UiCheckbox>
      </form>
    </template>
    <template #operation>
      <DrawerOperationBar>
        <UiActionButton :disabled="saving" @click="state.cancelEditor">取消</UiActionButton>
        <UiActionButton emphasis="primary" submit :loading="saving" @click="createMainMetadata">
          创建
        </UiActionButton>
      </DrawerOperationBar>
    </template>
  </RecordModeDrawer>

  <RecordModeDrawer
    :open="state.fieldEditorOpen.value"
    :title="state.mode.value === 'edit-field' ? '编辑字段' : '新增字段'"
    :container="pageRoot"
    :subtitle="state.selectedMetadata.value?.title"
    mode="create"
    @close="state.cancelEditor"
  >
    <template #form>
      <form class="orchestration-form" @submit.prevent="save">
        <label>
          <span>字段名称</span>
          <UiInput
            v-model:value="state.fieldDraft.value.fieldName"
            :disabled="Boolean(state.fieldDraft.value.id)"
            placeholder="例如 customerName"
          />
        </label>
        <label>
          <span>物理列名</span>
          <UiInput
            v-model:value="state.fieldDraft.value.columnName"
            :disabled="Boolean(state.fieldDraft.value.id)"
            placeholder="例如 customer_name"
          />
        </label>
        <label>
          <span>显示名称</span>
          <UiInput v-model:value="state.fieldDraft.value.title" placeholder="例如 客户名称" />
        </label>
        <label>
          <span>字段规格</span>
          <UiSelect
            v-model:value="state.fieldDraft.value.fieldSpecAlias"
            :options="state.fieldSpecOptions.value"
            placeholder="选择字段规格"
            style="width: 100%"
          />
        </label>
        <div class="orchestration-form-flags">
          <UiCheckbox v-model:checked="state.fieldDraft.value.required">必填</UiCheckbox>
          <UiCheckbox v-model:checked="state.fieldDraft.value.uniqueField">唯一</UiCheckbox>
          <UiCheckbox v-model:checked="state.fieldDraft.value.indexed">建立索引</UiCheckbox>
          <UiCheckbox v-model:checked="state.fieldDraft.value.sortableField">排序字段</UiCheckbox>
          <UiCheckbox v-model:checked="state.fieldDraft.value.titleField">标题字段</UiCheckbox>
          <UiSwitch
            v-model:checked="state.fieldDraft.value.enabled"
            checked-children="启用"
            un-checked-children="停用"
          />
        </div>
      </form>
    </template>
    <template #operation>
      <DrawerOperationBar>
        <UiActionButton :disabled="saving" @click="state.cancelEditor">取消</UiActionButton>
        <UiActionButton emphasis="primary" submit :loading="saving" @click="save">保存</UiActionButton>
      </DrawerOperationBar>
    </template>
  </RecordModeDrawer>
</template>

<style scoped>
.entity-overview {
  display: grid;
  gap: 10px;
  margin: 0 0 8px;
}

.entity-overview > div {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 12px;
  align-items: baseline;
}

.entity-overview dt {
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.entity-overview dd {
  margin: 0;
  overflow-wrap: anywhere;
}

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

.orchestration-form-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 18px;
  align-items: center;
}
</style>
