<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  DrawerOperationBar,
  RecordDetailPanel,
  RecordExplorerCreateButton,
  RecordModeDrawer,
  RecordRelationTabs,
  handlePlatformActionSuccess,
  presentPlatformError,
  presentPlatformMessage,
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
  entityTitleOf,
  isValidFieldDraft,
  isValidMainMetadataDraft,
  normalizeFieldDraft,
  normalizeMainMetadataDraft,
} from './metadataOrchestrationState';
import {
  createMetadataModelEditSession,
  isSessionEditableMetadataField,
  metadataFieldGovernanceKind,
  metadataFieldGovernanceLabel,
} from './metadataModelEditSession';
import type { MetadataRelationChangeSetProposal } from './metadataModelEditSession';

defineOptions({ name: 'MetadataGovernanceSurface' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string; title?: string }>();

type CreationResult = { metadata: Metadata; relation: ModuleMetadataRelation };
const ORCHESTRATION_QUERY_PAGE_SIZE = 200;

const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const metadataClient = createStaticResourceCrudClient<Metadata>(moduleContext.http, '/platform.metadata');
const state = createMetadataOrchestrationState();
const editSession = createMetadataModelEditSession();
const loading = ref(false);
const saving = ref(false);
const capabilitySnapshot = ref<ModuleMetadataCapabilitySnapshot>();

type ModuleMetadataCapabilityFact = {
  capability: string;
  enabled: boolean;
  configurable: boolean;
  reason: string;
  fieldContributions: string[];
  defaultKind: string;
  defaultDescription: string;
};
type ModuleMetadataSystemFieldFact = { fieldName: string; title: string; fieldSpecAlias: string };
type ModuleMetadataCapabilitySnapshot = {
  systemFields: ModuleMetadataSystemFieldFact[];
  capabilities: ModuleMetadataCapabilityFact[];
};
type MetadataChangeSetIssue = {
  severity: 'WARNING' | 'ERROR' | string;
  code: string;
  subject: string;
  message: string;
};
type MetadataChangeSetPreview = {
  proposalFingerprint: string;
  fieldImpacts: Array<{
    operation: string;
    fieldName: string;
    columnName: string;
    platformManaged: boolean;
    description: string;
  }>;
  schemaImpacts: Array<{
    operation: string;
    schemaName: string;
    tableName: string;
    columnName: string;
    description: string;
  }>;
  warnings: MetadataChangeSetIssue[];
  errors: MetadataChangeSetIssue[];
};
const sessionFields = computed(() => editSession.fieldsForDisplay(state.allFields.value));
const firstReleaseDeclaredCapabilities = new Set(['TREE', 'SORT', 'ENABLE']);
const capabilityFieldNames = computed(
  () =>
    new Set(
      capabilityItems.value
        .filter((fact) => firstReleaseDeclaredCapabilities.has(fact.capability))
        .flatMap((fact) => fact.fieldContributions),
    ),
);
const displayedFields = computed(() => {
  const configuredNames = new Set(sessionFields.value.map((field) => field.fieldName));
  const systemFields = (capabilitySnapshot.value?.systemFields ?? [])
    .filter((field) => !configuredNames.has(field.fieldName))
    .map(
      (field): MetadataField => ({
        id: `system:${field.fieldName}`,
        fieldName: field.fieldName,
        title: field.title,
        fieldSpecAlias: field.fieldSpecAlias,
        fieldOwnership: 'PLATFORM',
        fieldForm: 'PHYSICAL',
        systemManaged: true,
        enabled: true,
      }),
    );
  return [...sessionFields.value, ...systemFields];
});
const metadataTabs = computed(() =>
  state.relations.value
    .filter((relation) => relation.id)
    .map((relation) => ({
      key: relation.id as string,
      title: entityTitleOf(relation, relationMetadataOf(relation)),
    })),
);
const capabilityItems = computed(() =>
  (capabilitySnapshot.value?.capabilities ?? []).map((fact) => ({
    ...fact,
    title: capabilityTitleOf(fact.capability),
  })),
);
const selectedRelationIsMain = computed(() => state.selectedRelation.value?.relationRole === 'MAIN');

const fieldColumns: UiDataTableColumn[] = [
  { key: 'title', title: '字段' },
  { key: 'fieldName', title: '字段名', width: 150 },
  { key: 'fieldSpecAlias', title: '字段规格', width: 140 },
  { key: 'source', title: '治理归属', width: 100 },
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
    await Promise.all(
      state.relations.value.map(async (relation) => {
        if (!relation.metadataId) return;
        state.handleMetadataLoaded(await metadataClient.view(relation.metadataId));
      }),
    );
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

async function selectMetadataTab(relationId: string) {
  const relation = state.relations.value.find((item) => item.id === relationId);
  if (relation && state.selectRelation(relation)) {
    await loadSelectedMetadata();
  }
}

async function loadSelectedMetadata() {
  const metadataId = state.selectedRelation.value?.metadataId;
  state.handleFieldsLoaded([]);
  capabilitySnapshot.value = undefined;
  editSession.cancel();
  if (!metadataId) return;
  try {
    const metadata = await metadataClient.view(metadataId);
    state.handleMetadataLoaded(metadata);
    state.handleFieldsLoaded(
      await loadAllRecords(`/platform.metadata/${encodeURIComponent(metadataId)}/fields/query`),
    );
    const relationId = state.selectedRelation.value?.id;
    if (relationId) {
      capabilitySnapshot.value = await moduleContext.http.request<ModuleMetadataCapabilitySnapshot>({
        method: 'GET',
        path: relationPath(`/${encodeURIComponent(relationId)}/capabilities`),
      });
    }
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
  }
}

function startEditSession() {
  const metadataId = state.selectedMetadata.value?.id;
  const relationId = state.selectedRelation.value?.id;
  if (!metadataId || !relationId) return;
  editSession.begin(
    metadataId,
    relationId,
    state.selectedMetadata.value?.version ?? 0,
    state.allFields.value,
    capabilityItems.value.map((fact) => ({
      capability: fact.capability,
      enabled: fact.enabled,
      selectable: capabilitySelectable(fact),
      reason: fact.reason,
    })),
  );
}

function capabilitySelectable(fact: ModuleMetadataCapabilityFact): boolean {
  return selectedRelationIsMain.value && fact.configurable && !fact.enabled;
}

function capabilityChecked(fact: ModuleMetadataCapabilityFact): boolean {
  return editSession.draft.value?.capabilitySelections[fact.capability] ?? fact.enabled;
}

async function previewAndApply() {
  const relationId = state.selectedRelation.value?.id;
  const proposal = editSession.buildProposal();
  if (!relationId || !proposal) {
    presentPlatformMessage('当前草稿包含首批不支持的删除操作；请取消编辑后重新调整。', {
      source: 'metadata-orchestration',
      phase: 'validation',
    });
    return;
  }
  saving.value = true;
  try {
    const preview = await moduleContext.http.request<MetadataChangeSetPreview>({
      method: 'POST',
      path: relationPath(`/${encodeURIComponent(relationId)}/change-set-preview`),
      body: proposal,
    });
    if (preview.errors.length > 0) {
      presentPlatformMessage(preview.errors.map((item) => item.message).join('；'), {
        source: 'metadata-orchestration',
        phase: 'validation',
      });
      return;
    }
    const impacts = [
      ...preview.fieldImpacts.map((item) => `${item.operation} ${item.fieldName}：${item.description}`),
      ...preview.schemaImpacts.map((item) => `${item.operation} ${item.columnName}：${item.description}`),
    ].join('\n');
    if (
      !(await confirmAction({
        title: '确认发布数据模型',
        content: `确认后将写入元数据并同步数据库结构，失败将整体回滚。\n${impacts || '没有结构变更。'}`,
        okText: '确认发布',
        danger: true,
      }))
    )
      return;
    await moduleContext.http.request({
      method: 'POST',
      path: relationPath(`/${encodeURIComponent(relationId)}/change-set-apply`),
      body: {
        proposal: proposal as MetadataRelationChangeSetProposal,
        proposalFingerprint: preview.proposalFingerprint,
      },
    });
    await loadSelectedMetadata();
    await handlePlatformActionSuccess(
      { success: true, message: '数据模型已发布并生效' },
      { source: 'metadata-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

function cancelEditSession() {
  state.cancelEditor();
  editSession.cancel();
}

function stageFieldDraft() {
  const draft = normalizeFieldDraft(state.fieldDraft.value);
  if (!isValidFieldDraft(draft)) {
    presentPlatformMessage('请填写字段名、物理列名和字段规格', {
      source: 'metadata-orchestration',
      phase: 'validation',
    });
    return;
  }
  editSession.stageField({ ...draft, fieldOwnership: 'BUSINESS', fieldForm: 'PHYSICAL' });
  state.cancelEditor();
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

function fieldCellValue(column: UiDataTableColumn, record: UiDataTableRecord) {
  const field = record as MetadataField;
  if (column.key === 'source') return fieldSourceOf(field);
  if (column.key === 'required') return field.required ? '是' : '否';
  if (column.key === 'enabled') return field.enabled === false ? '停用' : '启用';
  return String(field[column.key as keyof MetadataField] ?? '');
}

function fieldSourceOf(field: MetadataField): string {
  return metadataFieldGovernanceLabel(
    metadataFieldGovernanceKind(field, state.selectedRelation.value, capabilityFieldNames.value),
  );
}

function fieldEditableInSession(field: MetadataField): boolean {
  return (
    editSession.editing.value &&
    isSessionEditableMetadataField(field, state.selectedRelation.value, capabilityFieldNames.value)
  );
}

function fieldProtectionReason(field: MetadataField): string | undefined {
  const kind = metadataFieldGovernanceKind(field, state.selectedRelation.value, capabilityFieldNames.value);
  return (
    {
      BUSINESS: undefined,
      CAPABILITY_DERIVED: '由已启用能力维护，不能作为业务字段编辑。',
      PLATFORM_SYSTEM: '平台系统字段，不能在数据模型会话中修改。',
      RELATION_FOREIGN_KEY: '由实体关系维护，不能作为独立字段修改。',
    }[kind] ?? undefined
  );
}

function capabilityTitleOf(capability: string): string {
  return (
    {
      TREE: '树结构',
      SORT: '排序',
      REFERENCE: '引用标题',
      ENABLE: '启停',
      DATA_SCOPE: '数据权限',
      APPROVAL: '审批',
    }[capability] ?? capability
  );
}
</script>

<template>
  <RecordDetailPanel
    v-if="state.selectedMetadata.value && state.selectedRelation.value"
    title="数据模型"
    :show-header="false"
  >
    <section class="metadata-toolbar">
      <RecordRelationTabs
        :tabs="metadataTabs"
        :active-key="state.selectedRelationId.value"
        @update:active-key="selectMetadataTab"
      />
      <div v-if="editSession.editing.value" class="metadata-edit-status" role="status">
        <strong>编辑中</strong>
        <span>改动仅保存在当前草稿，尚未写入数据模型。</span>
      </div>
      <div class="metadata-toolbar__actions">
        <UiActionButton
          v-if="!editSession.editing.value"
          emphasis="primary"
          :disabled="saving || loading"
          @click="startEditSession"
        >
          编辑数据模型
        </UiActionButton>
        <template v-else>
          <UiActionButton emphasis="quiet" @click="cancelEditSession">取消编辑</UiActionButton>
          <UiActionButton emphasis="quiet" :disabled="saving" @click="state.startCreateField">
            新增字段
          </UiActionButton>
          <UiActionButton
            emphasis="primary"
            :disabled="!editSession.isDirty.value || saving"
            @click="previewAndApply"
          >
            预检并发布
          </UiActionButton>
        </template>
      </div>
    </section>

    <section class="capability-summary">
      <header class="capability-summary__header">
        <h3>能力</h3>
        <span v-if="editSession.editing.value">选择结果将随字段草稿一并预览与提交。</span>
        <span v-else>默认只读；进入编辑后可调整治理目录允许的能力。</span>
      </header>
      <UiSpin v-if="loading" tip="加载能力" />
      <div v-else class="capability-options">
        <div v-for="fact in capabilityItems" :key="fact.capability" class="capability-option">
          <UiCheckbox
            :checked="capabilityChecked(fact)"
            :disabled="!editSession.editing.value || !capabilitySelectable(fact)"
            :aria-label="`${fact.title}：${capabilityChecked(fact) ? '已启用' : '未启用'}。${fact.reason}`"
            @change="editSession.stageCapability(fact.capability, $event, capabilitySelectable(fact))"
          >
            {{ fact.title }}
          </UiCheckbox>
          <span v-if="!capabilitySelectable(fact)" class="capability-option__reason">
            {{ fact.reason }}
          </span>
        </div>
      </div>
    </section>

    <section class="field-section">
      <header class="field-section-header">
        <h3>字段</h3>
        <span v-if="editSession.editing.value">仅业务字段可编辑；平台、能力和关系字段受保护。</span>
      </header>
      <UiSpin v-if="loading" tip="加载字段" />
      <UiEmpty v-else-if="!displayedFields.length" description="暂无字段，点击右上角新增字段" />
      <UiDataTable
        v-else
        :columns="fieldColumns"
        :rows="displayedFields as unknown as UiDataTableRecord[]"
        :row-muted="(record) => !fieldEditableInSession(record as MetadataField)"
        :show-action-column="editSession.editing.value"
        action-column-title="操作"
      >
        <template #cell="{ column, record }">{{ fieldCellValue(column, record) }}</template>
        <template #rowActions="{ record }">
          <UiActionButton
            emphasis="quiet"
            density="compact"
            :disabled="!fieldEditableInSession(record as MetadataField)"
            :title="fieldProtectionReason(record as MetadataField)"
            @click="state.startEditField(record as MetadataField)"
          >
            编辑
          </UiActionButton>
        </template>
      </UiDataTable>
    </section>
  </RecordDetailPanel>
  <RecordDetailPanel v-else title="数据模型">
    <template #actions>
      <RecordExplorerCreateButton
        v-if="!state.hasMainMetadata.value"
        title="新建主实体"
        :disabled="loading || saving"
        @click="state.startCreateMain"
      />
    </template>
    <UiEmpty description="请先创建动态模块的主实体" />
  </RecordDetailPanel>

  <RecordModeDrawer
    :open="state.mainEditorOpen.value"
    title="新建主实体"
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
    :subtitle="state.selectedMetadata.value?.title"
    mode="create"
    @close="state.cancelEditor"
  >
    <template #form>
      <form class="orchestration-form" @submit.prevent="stageFieldDraft">
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
        <UiActionButton emphasis="primary" submit :loading="saving" @click="stageFieldDraft">
          加入编辑草稿
        </UiActionButton>
      </DrawerOperationBar>
    </template>
  </RecordModeDrawer>
</template>

<style scoped>
.metadata-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

.metadata-toolbar > :first-child {
  min-width: 0;
}

.metadata-toolbar__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.metadata-edit-status {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  color: var(--muyun-color-primary);
  font-size: 12px;
}

.metadata-edit-status span,
.capability-summary__header span,
.field-section-header span {
  color: var(--muyun-text-muted);
}

.field-section {
  display: grid;
  gap: 10px;
  padding-top: 8px;
}

.capability-summary {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 8px 0 2px;
}

.capability-summary__header,
.field-section-header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}

.capability-summary__header h3,
.field-section-header h3 {
  margin: 0;
  color: var(--muyun-text-body);
  font-size: 13px;
  font-weight: 700;
}

.capability-summary__header span,
.field-section-header span {
  font-size: 12px;
}

.capability-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 22px;
}

.capability-option {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
}

.capability-option__reason {
  color: var(--muyun-text-muted);
  font-size: 12px;
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
