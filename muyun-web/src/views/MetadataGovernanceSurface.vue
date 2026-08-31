<script setup lang="ts">
import { computed, onMounted, ref, type ComponentPublicInstance, watch } from 'vue';
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
import type {
  Metadata,
  MetadataField,
  ModuleMetadataRelation,
  Option,
  WebPageResponse,
} from '@muyun/web-contracts';
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
  UiTextArea,
  confirmAction,
  type UiDataTableColumn,
  type UiDataTableRecord,
} from '@muyun/vue-ui-antdv';
import {
  createMetadataOrchestrationState,
  entityTitleOf,
  fieldSpecDisplayLabel,
  isValidFieldDraft,
  isValidFieldPropertyDraft,
  isValidMainMetadataDraft,
  isMainRelation,
  metadataFieldPropertyLabel,
  metadataFieldPropertySummary,
  normalizeFieldDraft,
  normalizeFieldPropertyDraft,
  normalizeMainMetadataDraft,
  propertyDraftFromSummary,
  storageFieldSpecAliasOf,
  type MetadataFieldPropertyDraft,
  type MetadataFieldPropertySummary,
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
const mainMetadataDraft = state.mainMetadataDraft;
const fieldDraft = state.fieldDraft;
const fieldPropertyDraft = state.fieldPropertyDraft;
const loading = ref(false);
const saving = ref(false);
const pageHost = ref<ComponentPublicInstance | null>(null);
const pageRoot = computed(() => (pageHost.value?.$el instanceof HTMLElement ? pageHost.value.$el : null));
const capabilitySnapshot = ref<ModuleMetadataCapabilitySnapshot>();
const fieldProperties = ref<MetadataFieldPropertySummary[]>([]);

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
type ReferenceTargetFieldCandidate = {
  fieldName: string;
  title?: string;
  defaultField?: boolean;
  selectable?: boolean;
};
type ReferenceTargetFieldCatalog = {
  targetModuleAlias: string;
  targetMetadataId?: string;
  keyFields: ReferenceTargetFieldCandidate[];
  labelFields: ReferenceTargetFieldCandidate[];
};
const referenceTargetFieldCatalog = ref<ReferenceTargetFieldCatalog>();
const referenceTargetFieldCatalogLoading = ref(false);
const referenceTargetFieldCatalogError = ref<string>();
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
const fieldGroups = computed(() => {
  const businessFields = displayedFields.value.filter(fieldIsBusiness);
  const groups = (['BASIC', 'MODULE_REFERENCE', 'DICTIONARY'] as const)
    .map((kind) => ({
      kind,
      title: metadataFieldPropertyLabel(kind),
      fields: businessFields.filter((field) => fieldPropertyOf(field).kind === kind),
    }))
    .filter((group) => group.fields.length > 0);
  const protectedFields = displayedFields.value.filter((field) => !fieldIsBusiness(field));
  return protectedFields.length > 0
    ? [...groups, { kind: 'PROTECTED' as const, title: '受保护字段', fields: protectedFields }]
    : groups;
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
const selectedRelationIsMain = computed(() => isMainRelation(state.selectedRelation.value?.relationRole));
const fieldPropertyEditorKind = computed(() => state.fieldPropertyDraft.value.kind);
const fieldStorageSpecAlias = computed(() =>
  storageFieldSpecAliasOf(
    fieldPropertyEditorKind.value,
    fieldPropertyDraft.value.dictionaryConfig?.selectionMode,
  ),
);
const fieldStorageSpecLabel = computed(() =>
  fieldSpecDisplayLabel(fieldStorageSpecAlias.value, state.fieldSpecs.value),
);
const fieldEditorTitle = computed(() => {
  if (state.mode.value === 'edit-field')
    return `编辑${metadataFieldPropertyLabel(fieldPropertyEditorKind.value)}`;
  return `新增${metadataFieldPropertyLabel(fieldPropertyEditorKind.value)}`;
});
const projectionMappingsText = computed({
  get: () => state.fieldPropertyDraft.value.referenceConfig?.projectionMappings?.join('\n') ?? '',
  set: (value: string) => {
    const reference = state.fieldPropertyDraft.value.referenceConfig;
    if (!reference) return;
    reference.projectionMappings = value
      .split(/[\n,;]/)
      .map((item) => item.trim())
      .filter(Boolean);
  },
});
const referenceKeyFieldOptions = computed(() =>
  referenceFieldOptions(
    referenceTargetFieldCatalog.value?.keyFields ?? [],
    fieldPropertyDraft.value.referenceConfig?.targetKeyField,
  ),
);
const referenceLabelFieldOptions = computed(() =>
  referenceFieldOptions(
    referenceTargetFieldCatalog.value?.labelFields ?? [],
    fieldPropertyDraft.value.referenceConfig?.targetLabelField,
  ),
);
const referenceTargetFieldCatalogProblem = computed(() => {
  if (fieldPropertyEditorKind.value !== 'MODULE_REFERENCE') return undefined;
  const reference = fieldPropertyDraft.value.referenceConfig;
  if (!reference?.targetModuleAlias?.trim()) return undefined;
  if (referenceTargetFieldCatalogError.value) return referenceTargetFieldCatalogError.value;
  const catalog = referenceTargetFieldCatalog.value;
  if (!catalog) return '目标字段目录尚未加载完成。';
  if (reference.cardinality === 'MANY') {
    return '本期仅支持单选模块引用；现有多选配置需迁移后才能发布。';
  }
  if (!candidateIsSelectable(catalog.keyFields, reference.targetKeyField)) {
    return `目标键字段“${reference.targetKeyField || '未选择'}”不在可选目录中，请调整。`;
  }
  if (!candidateIsSelectable(catalog.labelFields, reference.targetLabelField)) {
    return `目标展示字段“${reference.targetLabelField || '未选择'}”不在可选目录中，请调整。`;
  }
  return undefined;
});

const fieldColumns: UiDataTableColumn[] = [
  { key: 'title', title: '字段' },
  { key: 'fieldName', title: '字段名', width: 150 },
  { key: 'propertyKind', title: '数据属性', width: 120 },
  { key: 'propertySummary', title: '属性配置', width: 240 },
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

watch(
  () => [fieldPropertyEditorKind.value, fieldPropertyDraft.value.dictionaryConfig?.selectionMode] as const,
  ([kind, selectionMode]) => {
    const storageSpecAlias = storageFieldSpecAliasOf(kind, selectionMode);
    if (storageSpecAlias) fieldDraft.value.fieldSpecAlias = storageSpecAlias;
  },
);

watch(
  () => [
    fieldPropertyEditorKind.value,
    fieldPropertyDraft.value.referenceConfig?.targetModuleAlias,
    fieldPropertyDraft.value.referenceConfig?.targetMetadataId,
  ],
  ([kind, targetModuleAlias, targetMetadataId]) => {
    if (kind !== 'MODULE_REFERENCE' || !targetModuleAlias?.trim()) {
      referenceTargetFieldCatalog.value = undefined;
      referenceTargetFieldCatalogError.value = undefined;
      return;
    }
    void loadReferenceTargetFieldCatalog(targetModuleAlias, targetMetadataId);
  },
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
  fieldProperties.value = [];
  capabilitySnapshot.value = undefined;
  editSession.cancel();
  if (!metadataId) return;
  try {
    const metadata = await metadataClient.view(metadataId);
    state.handleMetadataLoaded(metadata);
    const relationId = state.selectedRelation.value?.id;
    const [loadedFields, loadedProperties] = await Promise.all([
      loadAllRecords<MetadataField>(`/platform.metadata/${encodeURIComponent(metadataId)}/fields/query`),
      relationId
        ? moduleContext.http.request<MetadataFieldPropertySummary[]>({
            method: 'GET',
            path: relationPath(`/${encodeURIComponent(relationId)}/field-properties`),
          })
        : Promise.resolve([]),
    ]);
    state.handleFieldsLoaded(loadedFields);
    fieldProperties.value = loadedProperties;
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
    fieldProperties.value,
  );
}

function capabilitySelectable(fact: ModuleMetadataCapabilityFact): boolean {
  return (
    selectedRelationIsMain.value &&
    firstReleaseDeclaredCapabilities.has(fact.capability) &&
    fact.configurable &&
    !fact.enabled
  );
}

function capabilityReason(fact: ModuleMetadataCapabilityFact): string {
  return firstReleaseDeclaredCapabilities.has(fact.capability)
    ? fact.reason
    : '首期仅支持树结构、排序和启停能力的声明发布。';
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
  const property = normalizeFieldPropertyDraft(state.fieldPropertyDraft.value);
  if (!isValidFieldDraft(draft)) {
    presentPlatformMessage('请填写字段名、物理列名和字段规格', {
      source: 'metadata-orchestration',
      phase: 'validation',
    });
    return;
  }
  if (!isValidFieldPropertyDraft(property)) {
    presentPlatformMessage(
      property.kind === 'MODULE_REFERENCE' ? '请配置目标模块。' : '请配置字典应用和类别。',
      { source: 'metadata-orchestration', phase: 'validation' },
    );
    return;
  }
  if (property.kind === 'MODULE_REFERENCE' && referenceTargetFieldCatalogProblem.value) {
    presentPlatformMessage(referenceTargetFieldCatalogProblem.value, {
      source: 'metadata-orchestration',
      phase: 'validation',
    });
    return;
  }
  editSession.stageField({ ...draft, fieldOwnership: 'BUSINESS', fieldForm: 'PHYSICAL' }, property);
  state.cancelEditor();
}

async function loadReferenceTargetFieldCatalog(
  targetModuleAlias: string,
  targetMetadataId: string | undefined,
) {
  const relationId = state.selectedRelation.value?.id;
  if (!relationId) return;
  const requestedTarget = targetModuleAlias.trim();
  referenceTargetFieldCatalogLoading.value = true;
  referenceTargetFieldCatalogError.value = undefined;
  try {
    const query = new URLSearchParams({ targetModuleAlias: requestedTarget });
    if (targetMetadataId?.trim()) query.set('targetMetadataId', targetMetadataId.trim());
    const catalog = await moduleContext.http.request<ReferenceTargetFieldCatalog>({
      method: 'GET',
      path: relationPath(
        `/${encodeURIComponent(relationId)}/reference-target-field-catalog?${query.toString()}`,
      ),
    });
    // Do not let an earlier request overwrite the catalog for a subsequently selected target.
    const reference = fieldPropertyDraft.value.referenceConfig;
    if (
      fieldPropertyEditorKind.value !== 'MODULE_REFERENCE' ||
      reference?.targetModuleAlias?.trim() !== requestedTarget ||
      (reference?.targetMetadataId?.trim() || undefined) !== (targetMetadataId?.trim() || undefined)
    ) {
      return;
    }
    referenceTargetFieldCatalog.value = catalog;
    // Target metadata identity is resolved by the server with the same authorization and target rules
    // as candidate fields. It is an internal binding, never a user-entered identifier.
    reference.targetMetadataId = catalog.targetMetadataId;
    // `id/title` is the platform contract, not a catalog preference.  The catalog may only fill
    // a genuinely absent legacy value; it must never replace a newly-created default.
    if (!reference.targetKeyField?.trim()) {
      reference.targetKeyField = defaultCandidateField(catalog.keyFields);
    }
    if (!reference.targetLabelField?.trim()) {
      reference.targetLabelField = defaultCandidateField(catalog.labelFields);
    }
  } catch (cause) {
    referenceTargetFieldCatalog.value = undefined;
    referenceTargetFieldCatalogError.value = `无法加载“${requestedTarget}”的目标字段目录。`;
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
  } finally {
    referenceTargetFieldCatalogLoading.value = false;
  }
}

function referenceFieldOptions(
  candidates: ReferenceTargetFieldCandidate[],
  selectedField: string | undefined,
): Option[] {
  const options = candidates
    .filter((candidate) => candidate.selectable !== false)
    .map((candidate) => ({ value: candidate.fieldName, label: candidateLabel(candidate) }));
  if (selectedField && !options.some((option) => option.value === selectedField)) {
    options.unshift({ value: selectedField, label: `${selectedField}（当前值不在候选中，需调整）` });
  }
  return options;
}

function candidateIsSelectable(
  candidates: ReferenceTargetFieldCandidate[],
  fieldName: string | undefined,
): boolean {
  return Boolean(
    fieldName &&
    candidates.some((candidate) => candidate.fieldName === fieldName && candidate.selectable !== false),
  );
}

function defaultCandidateField(candidates: ReferenceTargetFieldCandidate[]): string | undefined {
  return candidates.find((candidate) => candidate.defaultField && candidate.selectable !== false)?.fieldName;
}

function candidateLabel(candidate: ReferenceTargetFieldCandidate): string {
  return candidate.title ? `${candidate.title}（${candidate.fieldName}）` : candidate.fieldName;
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
  if (column.key === 'fieldSpecAlias')
    return fieldSpecDisplayLabel(field.fieldSpecAlias, state.fieldSpecs.value);
  if (column.key === 'source') return fieldSourceOf(field);
  if (column.key === 'propertyKind') return metadataFieldPropertyLabel(fieldPropertyOf(field).kind);
  if (column.key === 'propertySummary') return metadataFieldPropertySummary(fieldPropertyOf(field));
  if (column.key === 'required') return field.required ? '是' : '否';
  if (column.key === 'enabled') return field.enabled === false ? '停用' : '启用';
  return String(field[column.key as keyof MetadataField] ?? '');
}

function fieldPropertyOf(field: MetadataField): MetadataFieldPropertyDraft {
  if (editSession.editing.value) return editSession.propertyForField(field);
  const summary = fieldProperties.value.find(
    (item) => item.fieldId === field.id || item.fieldName === field.fieldName,
  );
  return summary ? propertyDraftFromSummary(summary) : { kind: 'BASIC' };
}

function fieldSourceOf(field: MetadataField): string {
  return metadataFieldGovernanceLabel(
    metadataFieldGovernanceKind(field, state.selectedRelation.value, capabilityFieldNames.value),
  );
}

function fieldIsBusiness(field: MetadataField): boolean {
  return (
    metadataFieldGovernanceKind(field, state.selectedRelation.value, capabilityFieldNames.value) === 'BUSINESS'
  );
}

function fieldEditableInSession(field: MetadataField): boolean {
  return (
    editSession.editing.value &&
    isSessionEditableMetadataField(field, state.selectedRelation.value, capabilityFieldNames.value) &&
    fieldPropertyOf(field).kind !== 'LEGACY_LOCKED'
  );
}

function fieldProtectionReason(field: MetadataField): string | undefined {
  if (fieldPropertyOf(field).kind === 'LEGACY_LOCKED') {
    return '该字段仍由旧配置链路维护，已锁定，不能改为新的字段属性。';
  }
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
    ref="pageHost"
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
          <UiActionButton emphasis="quiet" :disabled="saving" @click="state.startCreateField('BASIC')">
            新增普通字段
          </UiActionButton>
          <UiActionButton
            emphasis="quiet"
            :disabled="saving"
            @click="state.startCreateField('MODULE_REFERENCE')"
          >
            新增模块引用
          </UiActionButton>
          <UiActionButton emphasis="quiet" :disabled="saving" @click="state.startCreateField('DICTIONARY')">
            新增数据字典
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
            :aria-label="`${fact.title}：${capabilityChecked(fact) ? '已启用' : '未启用'}。${capabilityReason(fact)}`"
            @change="editSession.stageCapability(fact.capability, $event, capabilitySelectable(fact))"
          >
            {{ fact.title }}
          </UiCheckbox>
          <span v-if="!capabilitySelectable(fact)" class="capability-option__reason">
            {{ capabilityReason(fact) }}
          </span>
        </div>
      </div>
    </section>

    <section class="field-section">
      <header class="field-section-header">
        <h3>字段</h3>
        <span v-if="editSession.editing.value">数据属性与字段规格分开编排；平台、能力和关系字段受保护。</span>
      </header>
      <UiSpin v-if="loading" tip="加载字段" />
      <UiEmpty v-else-if="!displayedFields.length" description="暂无字段，点击右上角新增字段" />
      <div v-else class="field-property-groups">
        <section v-for="group in fieldGroups" :key="group.kind" class="field-property-group">
          <h4>{{ group.title }}</h4>
          <UiDataTable
            :columns="fieldColumns"
            :rows="group.fields as unknown as UiDataTableRecord[]"
            :row-muted="(record) => !fieldEditableInSession(record as MetadataField)"
            :show-action-column="editSession.editing.value"
            action-column-title="操作"
          >
            <template #cell="{ column, record }">{{ fieldCellValue(column, record) }}</template>
            <template #rowActions="{ record }">
              <template v-if="fieldPropertyOf(record as MetadataField).kind === 'BASIC'">
                <UiActionButton
                  emphasis="quiet"
                  density="compact"
                  :disabled="!fieldEditableInSession(record as MetadataField)"
                  @click="state.startConfigureFieldProperty(record as MetadataField, 'MODULE_REFERENCE')"
                >
                  配置引用
                </UiActionButton>
                <UiActionButton
                  emphasis="quiet"
                  density="compact"
                  :disabled="!fieldEditableInSession(record as MetadataField)"
                  @click="state.startConfigureFieldProperty(record as MetadataField, 'DICTIONARY')"
                >
                  配置字典
                </UiActionButton>
              </template>
              <UiActionButton
                emphasis="quiet"
                density="compact"
                :disabled="!fieldEditableInSession(record as MetadataField)"
                :title="fieldProtectionReason(record as MetadataField)"
                @click="state.startEditField(record as MetadataField, fieldPropertyOf(record as MetadataField))"
              >
                编辑
              </UiActionButton>
            </template>
          </UiDataTable>
        </section>
      </div>
    </section>
  </RecordDetailPanel>
  <RecordDetailPanel v-else ref="pageHost" title="数据模型">
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
    :container="pageRoot"
    title="新建主实体"
    :subtitle="moduleTitle ?? moduleAlias"
    mode="create"
    @close="state.cancelEditor"
  >
    <template #form>
      <form class="orchestration-form" @submit.prevent="createMainMetadata">
        <label>
          <span>实体 alias</span>
          <UiInput v-model:value="mainMetadataDraft.alias" placeholder="例如 customer" />
        </label>
        <label>
          <span>实体名称</span>
          <UiInput v-model:value="mainMetadataDraft.title" placeholder="例如 客户" />
        </label>
        <label>
          <span>Schema（可选）</span>
          <UiInput v-model:value="mainMetadataDraft.schemaName" placeholder="默认 public" />
        </label>
        <label>
          <span>物理表名（可选）</span>
          <UiInput v-model:value="mainMetadataDraft.tableName" placeholder="默认按应用和 alias 生成" />
        </label>
        <UiCheckbox v-model:checked="mainMetadataDraft.dataScopeEnabled"> 启用数据权限范围 </UiCheckbox>
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
    :container="pageRoot"
    :title="fieldEditorTitle"
    :subtitle="state.selectedMetadata.value?.title"
    mode="create"
    @close="state.cancelEditor"
  >
    <template #form>
      <form class="orchestration-form" @submit.prevent="stageFieldDraft">
        <label>
          <span>字段名称</span>
          <UiInput
            v-model:value="fieldDraft.fieldName"
            :disabled="Boolean(fieldDraft.id)"
            placeholder="例如 customerName"
          />
        </label>
        <label>
          <span>物理列名</span>
          <UiInput
            v-model:value="fieldDraft.columnName"
            :disabled="Boolean(fieldDraft.id)"
            placeholder="例如 customer_name"
          />
        </label>
        <label>
          <span>显示名称</span>
          <UiInput v-model:value="fieldDraft.title" placeholder="例如 客户名称" />
        </label>
        <label>
          <span>存储字段规格</span>
          <UiSelect
            v-if="fieldPropertyEditorKind === 'BASIC'"
            v-model:value="fieldDraft.fieldSpecAlias"
            :options="state.fieldSpecOptions.value"
            placeholder="选择字段规格"
            style="width: 100%"
          />
          <UiInput
            v-else
            :value="fieldStorageSpecLabel"
            disabled
            :title="fieldStorageSpecAlias"
          />
        </label>
        <template v-if="fieldPropertyEditorKind === 'MODULE_REFERENCE'">
          <div class="field-property-heading">
            <strong>模块引用</strong>
            <span>源字段存储目标键；默认读取目标记录的 id，并以 title 展示。</span>
          </div>
          <label>
            <span>目标模块</span>
            <UiInput
              v-model:value="fieldPropertyDraft.referenceConfig!.targetModuleAlias"
              placeholder="例如 education.subject_category"
            />
          </label>
          <div v-if="fieldPropertyDraft.referenceConfig!.targetMetadataId" class="field-property-binding">
            <strong>目标实体绑定</strong>
            <span>{{ fieldPropertyDraft.referenceConfig!.targetMetadataId }}</span>
          </div>
          <div class="orchestration-form-grid">
            <label>
              <span>目标键字段</span>
              <UiSelect
                v-model:value="fieldPropertyDraft.referenceConfig!.targetKeyField"
                :options="referenceKeyFieldOptions"
                :loading="referenceTargetFieldCatalogLoading"
                :disabled="Boolean(referenceTargetFieldCatalogError)"
                placeholder="请选择目标键字段"
                style="width: 100%"
              />
            </label>
            <label>
              <span>目标展示字段</span>
              <UiSelect
                v-model:value="fieldPropertyDraft.referenceConfig!.targetLabelField"
                :options="referenceLabelFieldOptions"
                :loading="referenceTargetFieldCatalogLoading"
                :disabled="Boolean(referenceTargetFieldCatalogError)"
                placeholder="请选择目标展示字段"
                style="width: 100%"
              />
            </label>
          </div>
          <p v-if="referenceTargetFieldCatalogProblem" class="field-property-error" role="alert">
            {{ referenceTargetFieldCatalogProblem }}
          </p>
          <div class="orchestration-form-grid">
            <label>
              <span>基数</span>
              <UiInput value="单选" disabled />
              <small class="field-property-note">本期模块引用仅支持单选。</small>
            </label>
            <label>
              <span>目标不可用策略</span>
              <UiSelect
                v-model:value="fieldPropertyDraft.referenceConfig!.targetUnavailablePolicy"
                :options="[
                  { value: 'PRESERVE_HISTORY', label: '保留历史' },
                  { value: 'RESTRICT', label: '限制删除' },
                  { value: 'CASCADE_DELETE', label: '级联删除' },
                ]"
                style="width: 100%"
              />
            </label>
          </div>
          <label>
            <span>展示投影与自动带出字段</span>
            <UiTextArea
              v-model:value="projectionMappingsText"
              :rows="3"
              placeholder="每行一项，例如 title:subjectCategoryIdTitle"
            />
          </label>
        </template>
        <template v-else-if="fieldPropertyEditorKind === 'DICTIONARY'">
          <div class="field-property-heading">
            <strong>数据字典</strong>
            <span>固定以字典 code 存储、title 展示。</span>
          </div>
          <label>
            <span>字典应用</span>
            <UiInput
              v-model:value="fieldPropertyDraft.dictionaryConfig!.dictionaryApplicationAlias"
              placeholder="例如 education"
            />
          </label>
          <label>
            <span>字典类别</span>
            <UiInput
              v-model:value="fieldPropertyDraft.dictionaryConfig!.dictionaryCategoryAlias"
              placeholder="例如 exam_attendance_status"
            />
          </label>
          <label>
            <span>选择方式</span>
            <UiSelect
              v-model:value="fieldPropertyDraft.dictionaryConfig!.selectionMode"
              :options="[
                { value: 'SINGLE', label: '单选' },
                { value: 'MULTIPLE', label: '多选' },
              ]"
              style="width: 100%"
            />
          </label>
        </template>
        <div class="orchestration-form-flags">
          <UiCheckbox v-model:checked="fieldDraft.required">必填</UiCheckbox>
          <UiCheckbox v-model:checked="fieldDraft.uniqueField">唯一</UiCheckbox>
          <UiCheckbox v-model:checked="fieldDraft.indexed">建立索引</UiCheckbox>
          <UiCheckbox v-model:checked="fieldDraft.sortableField">排序字段</UiCheckbox>
          <UiCheckbox v-model:checked="fieldDraft.titleField">标题字段</UiCheckbox>
          <UiSwitch v-model:checked="fieldDraft.enabled" checked-children="启用" un-checked-children="停用" />
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

.field-property-groups {
  display: grid;
  gap: 18px;
}

.field-property-group {
  display: grid;
  gap: 8px;
}

.field-property-group h4 {
  margin: 0;
  color: var(--muyun-text-body);
  font-size: 13px;
  font-weight: 700;
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

.orchestration-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.field-property-heading {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--muyun-color-primary-soft, #f0f6ff);
  color: var(--muyun-text-body);
}

.field-property-heading span {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.field-property-binding {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.field-property-binding strong {
  color: var(--muyun-text-body);
}

.field-property-note {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.field-property-error {
  margin: -6px 0 0;
  color: var(--muyun-color-danger, #d32029);
  font-size: 12px;
}
</style>
