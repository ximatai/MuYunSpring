<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { pinyin } from 'pinyin-pro';
import {
  ManagementExplorerColumn,
  ManagementWorkspace,
  RecordDetailPanel,
  RecordDetailFields,
  RecordContentSectionHeading,
  RecordFormGrid,
  RecordExplorerPanel,
  reconcileSelectedKey,
  handlePlatformActionSuccess,
  presentPlatformError,
  presentPlatformMessage,
} from '@muyun/platform-components';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
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
  UiButton,
  UiCheckbox,
  UiEmpty,
  UiInput,
  UiRadioGroup,
  UiSelect,
  UiSpin,
  UiSwitch,
  UiTextArea,
  UiTree,
  confirmAction,
  type UiTreeDropEvent,
  type UiTreeNode,
  type UiRadioOption,
} from '@muyun/vue-ui-antdv';
import {
  createMetadataOrchestrationState,
  fieldSpecDisplayLabel,
  isValidFieldDraft,
  isValidFieldPropertyDraft,
  isValidMainMetadataDraft,
  isMainRelation,
  dataSafeFieldSpecOptions,
  metadataFieldPropertyLabel,
  normalizeFieldDraft,
  normalizeFieldPropertyDraft,
  normalizeMainMetadataDraft,
  propertyDraftFromSummary,
  storageFieldSpecAliasOf,
  type MetadataFieldPropertyDraft,
  type MetadataFieldPropertySummary,
} from './metadataOrchestrationState';
import {
  createMetadataModelWorkspaceEditSession,
  isSessionEditableMetadataField,
  metadataFieldGovernanceKind,
  metadataFieldGovernanceLabel,
} from './metadataModelEditSession';
import type { MetadataModelChangeSetProposal } from './metadataModelEditSession';
import {
  buildMetadataModelTree,
  canReorderMetadataModelTree,
  metadataNodeKey,
  parseMetadataModelTreeKey,
  reorderedIds,
  type MetadataModelTreeNode,
} from './metadataModelTree';

defineOptions({ name: 'MetadataGovernanceSurface' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string; title?: string }>();

type CreationResult = { metadata: Metadata; relation: ModuleMetadataRelation };
type ChildMetadataDraft = { alias: string; title: string; schemaName?: string; tableName?: string };
const ORCHESTRATION_QUERY_PAGE_SIZE = 200;

const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const metadataClient = createStaticResourceCrudClient<Metadata>(moduleContext.http, '/platform.metadata');
const state = createMetadataOrchestrationState();
const editSession = createMetadataModelWorkspaceEditSession();
useWorkspaceViewUnsavedState('数据模型', () => editSession.isDirty.value || state.mode.value !== 'view');
const mainMetadataDraft = state.mainMetadataDraft;
const fieldDraft = state.fieldDraft;
const fieldPropertyDraft = state.fieldPropertyDraft;
const loading = ref(false);
const saving = ref(false);
const sorting = ref(false);
const showSystemFields = ref(false);
const capabilitySnapshot = ref<ModuleMetadataCapabilitySnapshot>();
const fieldProperties = ref<MetadataFieldPropertySummary[]>([]);
const fieldsByRelation = ref<Record<string, MetadataField[]>>({});
const fieldPropertiesByRelation = ref<Record<string, MetadataFieldPropertySummary[]>>({});
const capabilitiesByRelation = ref<Record<string, ModuleMetadataCapabilitySnapshot>>({});
const recordCountsByRelation = ref<Record<string, number>>({});
const selectedTreeKey = ref<string>();
const expandedTreeKeys = ref<string[]>([]);
const childNodeType = ref<'FIELD' | 'CHILD_METADATA'>('FIELD');
const editorMode = ref<'SIMPLE' | 'ADVANCED'>('SIMPLE');
const editorModeOptions: UiRadioOption[] = [
  { value: 'SIMPLE', label: '简单模式' },
  { value: 'ADVANCED', label: '高级模式' },
];
const childMetadataDraft = ref<ChildMetadataDraft>({ alias: '', title: '' });

type ModuleMetadataCapabilityFact = {
  capability: string;
  enabled: boolean;
  configurable: boolean;
  reason: string;
  fieldContributions: string[];
  defaultKind: string;
  defaultDescription: string;
};
type ModuleMetadataRelationRecordCount = { relationId: string; recordCount: number };
type ModuleMetadataCapabilitySnapshot = {
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
  orderImpacts: Array<{
    operation: string;
    relationId?: string;
    parentMetadataId?: string;
    orderedIds: string[];
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
let referenceTargetFieldCatalogRequestToken = 0;
const selectedRelationId = computed(() => state.selectedRelation.value?.id);
const sessionFields = computed(() =>
  selectedRelationId.value
    ? editSession.fieldsForDisplay(selectedRelationId.value, state.allFields.value)
    : state.allFields.value,
);
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
  return visibleFields(sessionFields.value);
});
function visibleFields(fields: MetadataField[]): MetadataField[] {
  return showSystemFields.value ? fields : fields.filter((field) => !field.systemManaged);
}
const capabilityItems = computed(() =>
  (capabilitySnapshot.value?.capabilities ?? []).map((fact) => ({
    ...fact,
    title: capabilityTitleOf(fact.capability),
  })),
);
const selectedField = computed(() => {
  const parsed = selectedTreeKey.value ? parseMetadataModelTreeKey(selectedTreeKey.value) : undefined;
  if (parsed?.kind !== 'FIELD' || parsed.relationId !== selectedRelationId.value) return undefined;
  return displayedFields.value.find((field) => (field.id ?? field.fieldName) === parsed.fieldId);
});
const selectedNodeIsField = computed(() => Boolean(selectedField.value));
const metadataTreeNodes = computed(() =>
  buildMetadataModelTree({
    relations: state.relations.value.map((relation) => {
      const order = editSession.relationOrder.value[relation.parentMetadataId ?? ''];
      const position = relation.id ? (order?.indexOf(relation.id) ?? -1) : -1;
      return position >= 0 ? { ...relation, sortOrder: position } : relation;
    }),
    metadataById: state.metadataById.value,
    fieldsByRelation: Object.fromEntries(
      state.relations.value
        .filter((relation): relation is ModuleMetadataRelation & { id: string } => Boolean(relation.id))
        .map((relation) => [
          relation.id,
          // The navigator is a persisted-model view. A staged field participates in the pending
          // change-set only; it must not look like a saved metadata field before confirmation.
          visibleFields(fieldsByRelation.value[relation.id] ?? []),
        ]),
    ),
    fieldLocked: (relation, field) => fieldProtectionReasonFor(relation, field) !== undefined,
  }),
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
const selectedRelationHasBusinessRecords = computed(
  () => (recordCountsByRelation.value[selectedRelationId.value ?? ''] ?? 0) > 0,
);
const editableFieldSpecOptions = computed(() =>
  editorMode.value === 'SIMPLE' && Boolean(fieldDraft.value.id) && selectedRelationHasBusinessRecords.value
    ? dataSafeFieldSpecOptions(state.fieldSpecs.value, fieldDraft.value.fieldSpecAlias)
    : state.fieldSpecOptions.value,
);
const fieldPropertyKindOptions: Option[] = [
  { value: 'BASIC', label: '普通字段' },
  { value: 'MODULE_REFERENCE', label: '模块引用' },
  { value: 'DICTIONARY', label: '数据字典' },
];

function selectNewFieldPropertyKind(kind: unknown) {
  if (state.fieldDraft.value.id || typeof kind !== 'string') return;
  startCreateField(kind as MetadataFieldPropertyDraft['kind']);
}
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

function updateReferenceTargetModuleAlias(targetModuleAlias: string) {
  const reference = fieldPropertyDraft.value.referenceConfig;
  if (!reference) return;
  if (reference.targetModuleAlias?.trim() !== targetModuleAlias.trim()) {
    referenceTargetFieldCatalogRequestToken += 1;
    reference.targetMetadataId = undefined;
    referenceTargetFieldCatalog.value = undefined;
    referenceTargetFieldCatalogError.value = undefined;
    referenceTargetFieldCatalogLoading.value = false;
  }
  reference.targetModuleAlias = targetModuleAlias;
}

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
      referenceTargetFieldCatalogRequestToken += 1;
      referenceTargetFieldCatalog.value = undefined;
      referenceTargetFieldCatalogError.value = undefined;
      referenceTargetFieldCatalogLoading.value = false;
      return;
    }
    void loadReferenceTargetFieldCatalog(targetModuleAlias, targetMetadataId);
  },
);

onMounted(() => void loadFieldSpecs());

async function loadWorkspace() {
  const selectionBeforeRefresh = selectedTreeKey.value;
  loading.value = true;
  try {
    state.handleRelationsLoaded(await loadAllRecords(relationPath('/query')));
    await Promise.all(
      state.relations.value.map(async (relation) => {
        if (!relation.metadataId) return;
        state.handleMetadataLoaded(await metadataClient.view(relation.metadataId));
      }),
    );
    const loaded = await Promise.all(
      state.relations.value.map(async (relation) => {
        if (!relation.id || !relation.metadataId) return undefined;
        const [fields, properties, capabilities, recordCount] = await Promise.all([
          loadAllRecords<MetadataField>(
            `/platform.metadata/${encodeURIComponent(relation.metadataId)}/fields/query`,
          ),
          moduleContext.http.request<MetadataFieldPropertySummary[]>({
            method: 'GET',
            path: relationPath(`/${encodeURIComponent(relation.id)}/field-properties`),
          }),
          moduleContext.http.request<ModuleMetadataCapabilitySnapshot>({
            method: 'GET',
            path: relationPath(`/${encodeURIComponent(relation.id)}/capabilities`),
          }),
          moduleContext.http.request<ModuleMetadataRelationRecordCount>({
            method: 'GET',
            path: relationPath(`/${encodeURIComponent(relation.id)}/record-count`),
          }),
        ]);
        return { relationId: relation.id, fields, properties, capabilities, recordCount };
      }),
    );
    fieldsByRelation.value = Object.fromEntries(
      loaded
        .filter((item): item is NonNullable<typeof item> => Boolean(item))
        .map((item) => [item.relationId, item.fields]),
    );
    fieldPropertiesByRelation.value = Object.fromEntries(
      loaded
        .filter((item): item is NonNullable<typeof item> => Boolean(item))
        .map((item) => [item.relationId, item.properties]),
    );
    capabilitiesByRelation.value = Object.fromEntries(
      loaded
        .filter((item): item is NonNullable<typeof item> => Boolean(item))
        .map((item) => [item.relationId, item.capabilities]),
    );
    recordCountsByRelation.value = Object.fromEntries(
      loaded
        .filter((item): item is NonNullable<typeof item> => Boolean(item))
        .map((item) => [item.relationId, item.recordCount.recordCount]),
    );
    restoreTreeSelection(selectionBeforeRefresh);
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
  } finally {
    loading.value = false;
  }
}

/** Keeps a field-level focus across a read-model refresh; only removed nodes fall back to their entity. */
function restoreTreeSelection(previousKey: string | undefined) {
  const parsed = previousKey ? parseMetadataModelTreeKey(previousKey) : undefined;
  const relationId = parsed?.relationId ?? state.selectedRelation.value?.id;
  if (!relationId) return;
  const relation = state.relations.value.find((item) => item.id === relationId);
  if (!relation) return;
  if (state.selectRelation(relation)) hydrateSelectedRelation(relationId);
  else hydrateSelectedRelation(relationId);

  selectedTreeKey.value = reconcileSelectedKey(
    previousKey,
    metadataTreeKeys(metadataTreeNodes.value),
    metadataNodeKey(relationId),
  );
  const relationKey = metadataNodeKey(relationId);
  expandedTreeKeys.value = [...new Set([...expandedTreeKeys.value, relationKey])];
}

function metadataTreeKeys(nodes: UiTreeNode[]): string[] {
  return nodes.flatMap((node) => [node.key, ...metadataTreeKeys(node.children ?? [])]);
}

async function loadFieldSpecs() {
  try {
    state.handleFieldSpecsLoaded(await loadAllRecords('/platform.field_spec/query'));
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
  }
}

function hydrateSelectedRelation(relationId: string) {
  state.handleFieldsLoaded(fieldsByRelation.value[relationId] ?? []);
  fieldProperties.value = fieldPropertiesByRelation.value[relationId] ?? [];
  capabilitySnapshot.value = capabilitiesByRelation.value[relationId];
}

async function selectMetadataTreeNode(node: UiTreeNode) {
  const parsed = parseMetadataModelTreeKey(node.key);
  if (!parsed) return;
  const relationId = parsed.relationId;
  const relation = state.relations.value.find((item) => item.id === relationId);
  if (relation && state.selectRelation(relation)) {
    hydrateSelectedRelation(relationId);
  }
  selectedTreeKey.value = node.key;
}

/**
 * The change-set remains the server-side transaction envelope, but it is no
 * longer a user-facing "edit the whole model" mode.  A node command opens the
 * smallest possible envelope and saves it from that node's drawer/card.
 */
function startNodeEditSession() {
  editSession.begin(
    state.relations.value.flatMap((relation) => {
      if (!relation.id || !relation.metadataId) return [];
      return [
        {
          relationId: relation.id,
          metadataId: relation.metadataId,
          parentMetadataId: relation.parentMetadataId,
          sortOrder: relation.sortOrder,
          expectedMetadataVersion: state.metadataById.value[relation.metadataId]?.version ?? 0,
          fields: fieldsByRelation.value[relation.id] ?? [],
          sortableFieldIds: (fieldsByRelation.value[relation.id] ?? [])
            .filter((field) => fieldSortableInTree(relation, field))
            .map((field) => field.id ?? field.fieldName)
            .filter((fieldId): fieldId is string => Boolean(fieldId)),
          fieldProperties: fieldPropertiesByRelation.value[relation.id] ?? [],
        },
      ];
    }),
  );
}

function startCreateField(kind: MetadataFieldPropertyDraft['kind'] = 'BASIC') {
  editorMode.value = 'SIMPLE';
  startNodeEditSession();
  state.startCreateField(kind);
}

function startCreateMainMetadata() {
  editorMode.value = 'SIMPLE';
  state.startCreateMain();
  mainMetadataDraft.value.alias = props.moduleAlias.split('.').at(-1) ?? props.moduleAlias;
  mainMetadataDraft.value.title = props.moduleTitle?.trim() || props.title?.trim() || props.moduleAlias;
}

function startCreateChildNode() {
  childNodeType.value = 'FIELD';
  childMetadataDraft.value = { alias: '', title: '' };
  startCreateField();
}

function startCreateChildMetadataNode() {
  childNodeType.value = 'CHILD_METADATA';
  childMetadataDraft.value = { alias: '', title: '' };
  startCreateField();
}

function startEditField(field: MetadataField, property: MetadataFieldPropertyDraft) {
  editorMode.value = 'SIMPLE';
  startNodeEditSession();
  state.startEditField(field, property);
}

function cancelNodeEditor() {
  state.cancelEditor();
  editSession.cancel();
  sorting.value = false;
}

function startSorting() {
  startNodeEditSession();
  sorting.value = true;
}

async function previewAndApply(operationName = '保存数据模型') {
  const proposal = editSession.buildProposal();
  if (!proposal) {
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
      path: `/platform.module/${encodeURIComponent(props.moduleAlias)}/metadata-model/change-set-preview`,
      body: proposal,
    });
    if (preview.errors.length > 0) {
      presentPlatformMessage(preview.errors.map((item) => item.message).join('；'), {
        source: 'metadata-orchestration',
        phase: 'validation',
      });
      return;
    }
    const impacts = metadataChangeConfirmationText(preview);
    if (
      !(await confirmAction({
        title: `确认${operationName}`,
        content: impacts || '将保存当前配置。',
        okText: '保存',
      }))
    )
      return;
    await moduleContext.http.request({
      method: 'POST',
      path: `/platform.module/${encodeURIComponent(props.moduleAlias)}/metadata-model/change-set-apply`,
      body: {
        proposal: proposal as MetadataModelChangeSetProposal,
        proposalFingerprint: preview.proposalFingerprint,
      },
    });
    editSession.cancel();
    state.cancelEditor();
    sorting.value = false;
    await loadWorkspace();
    await handlePlatformActionSuccess(
      { success: true, message: `${operationName}成功并已同步生效` },
      { source: 'metadata-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

function metadataChangeConfirmationText(preview: MetadataChangeSetPreview): string {
  const fieldChanges = preview.fieldImpacts.map((item) => {
    if (item.operation === 'ADD') return `新增字段「${item.fieldName}」并创建对应物理列。`;
    if (item.operation === 'UPDATE') return `更新字段「${item.fieldName}」的配置。`;
    if (item.operation === 'DELETE') return `删除字段「${item.fieldName}」及其物理列。`;
    return `保存字段「${item.fieldName}」的变更。`;
  });
  if (fieldChanges.length > 0) return fieldChanges.join('\n');
  if (preview.orderImpacts.length > 0) return '保存当前排序调整。';
  if (preview.schemaImpacts.length > 0) return '同步数据库结构变更。';
  return '';
}

function physicalNameOf(fieldName?: string): string {
  return (fieldName ?? '')
    .trim()
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .toLowerCase();
}

function generatedFieldName(title?: string): string {
  const normalized = (title ?? '').trim();
  if (/[\u3400-\u9fff]/.test(normalized)) {
    const [first, ...rest] = pinyin(normalized, { toneType: 'none', type: 'array' })
      .map((part) => part.replace(/[^a-zA-Z0-9]/g, ''))
      .filter(Boolean);
    if (first)
      return `${first.toLowerCase()}${rest.map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1).toLowerCase()}`).join('')}`;
  }
  const ascii = normalized
    .normalize('NFKD')
    .replace(/[^a-zA-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .toLowerCase();
  if (ascii) {
    const [first, ...rest] = ascii.split('_').filter(Boolean);
    return `${first}${rest.map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1)}`).join('')}`;
  }
  let hash = 0;
  for (const character of normalized) hash = ((hash << 5) - hash + character.codePointAt(0)!) | 0;
  const suffix = Math.abs(hash).toString(36);
  return `field${suffix.charAt(0).toUpperCase()}${suffix.slice(1)}`;
}

function stageFieldDraft() {
  if (!fieldDraft.value.id && childNodeType.value === 'CHILD_METADATA') {
    void createChildMetadata();
    return;
  }
  if (editorMode.value === 'SIMPLE') {
    if (!state.fieldDraft.value.fieldName?.trim()) {
      state.fieldDraft.value.fieldName = generatedFieldName(state.fieldDraft.value.title);
    }
    if (!state.fieldDraft.value.columnName?.trim()) {
      state.fieldDraft.value.columnName = physicalNameOf(state.fieldDraft.value.fieldName);
    }
  }
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
  const relationId = selectedRelationId.value;
  if (!relationId) return;
  editSession.stageField(
    relationId,
    { ...draft, fieldOwnership: 'BUSINESS', fieldForm: 'PHYSICAL' },
    property,
  );
  void previewAndApply('保存字段');
}

async function createChildMetadata() {
  const relationId = selectedRelationId.value;
  const alias = childMetadataDraft.value.alias.trim();
  const title = childMetadataDraft.value.title.trim();
  if (!relationId || !alias || !title) {
    presentPlatformMessage('请填写子实体 alias 和名称', {
      source: 'metadata-orchestration',
      phase: 'validation',
    });
    return;
  }
  saving.value = true;
  try {
    const result = await moduleContext.http.request<CreationResult>({
      method: 'POST',
      path: relationPath(`/${encodeURIComponent(relationId)}/create-child-metadata`),
      body: {
        alias,
        title,
        schemaName: childMetadataDraft.value.schemaName?.trim() || undefined,
        tableName: childMetadataDraft.value.tableName?.trim() || undefined,
      },
    });
    state.cancelEditor();
    editSession.cancel();
    await loadWorkspace();
    state.focusRelation(result.relation.id);
    if (result.relation.id) {
      hydrateSelectedRelation(result.relation.id);
      selectedTreeKey.value = metadataNodeKey(result.relation.id);
      expandedTreeKeys.value = [...new Set([...expandedTreeKeys.value, metadataNodeKey(relationId)])];
    }
    await handlePlatformActionSuccess(
      { success: true, message: '子元数据已创建，并已生成父外键和物理表' },
      { source: 'metadata-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function deleteSelectedNode() {
  const relationId = selectedRelationId.value;
  if (!relationId) return;
  const field = selectedField.value;
  const deletingField = Boolean(field);
  if (field && !fieldEditableInSession(field)) {
    presentPlatformMessage(fieldProtectionReason(field) ?? '该字段不能删除。', {
      source: 'metadata-orchestration',
      phase: 'validation',
    });
    return;
  }
  if (
    !(await confirmAction({
      title: deletingField ? '删除字段' : '删除元数据',
      content: deletingField
        ? `将删除字段“${field!.title}”及其物理列。仅在未被配置引用且没有业务数据时可以继续。`
        : `将删除元数据及其物理表。仅在没有业务字段、子元数据和业务数据时可以继续。`,
      okText: '确认删除',
      danger: true,
    }))
  )
    return;
  saving.value = true;
  try {
    await moduleContext.http.request({
      method: 'DELETE',
      path: deletingField
        ? relationPath(`/${encodeURIComponent(relationId)}/fields/${encodeURIComponent(field!.id!)}`)
        : relationPath(`/${encodeURIComponent(relationId)}`),
    });
    // A deleted relation must disappear from the current tree immediately.
    // During dynamic-runtime activation the relation read model can briefly
    // return its pre-delete snapshot, so do not reload it into this workspace.
    if (!deletingField) {
      state.handleRelationsLoaded(state.relations.value.filter((relation) => relation.id !== relationId));
      fieldsByRelation.value = {};
      fieldPropertiesByRelation.value = {};
      capabilitiesByRelation.value = {};
      fieldProperties.value = [];
      capabilitySnapshot.value = undefined;
      selectedTreeKey.value = undefined;
      editSession.cancel();
      state.cancelEditor();
    } else {
      await loadWorkspace();
    }
    await handlePlatformActionSuccess(
      { success: true, message: deletingField ? '字段及物理列已删除' : '元数据及物理表已删除' },
      { source: 'metadata-orchestration' },
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
  } finally {
    saving.value = false;
  }
}

async function loadReferenceTargetFieldCatalog(
  targetModuleAlias: string,
  targetMetadataId: string | undefined,
) {
  const relationId = state.selectedRelation.value?.id;
  if (!relationId) return;
  const requestedTarget = targetModuleAlias.trim();
  const requestToken = ++referenceTargetFieldCatalogRequestToken;
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
      requestToken !== referenceTargetFieldCatalogRequestToken ||
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
    if (requestToken !== referenceTargetFieldCatalogRequestToken) return;
    referenceTargetFieldCatalog.value = undefined;
    referenceTargetFieldCatalogError.value = `无法加载“${requestedTarget}”的目标字段目录。`;
    presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
  } finally {
    if (requestToken === referenceTargetFieldCatalogRequestToken) {
      referenceTargetFieldCatalogLoading.value = false;
    }
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
    if (result.relation.id) {
      hydrateSelectedRelation(result.relation.id);
      selectedTreeKey.value = metadataNodeKey(result.relation.id);
    }
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

function fieldPropertyOf(field: MetadataField): MetadataFieldPropertyDraft {
  if (editSession.editing.value && selectedRelationId.value)
    return editSession.propertyForField(selectedRelationId.value, field);
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

function fieldEditableInSession(field: MetadataField): boolean {
  return (
    isSessionEditableMetadataField(field, state.selectedRelation.value, capabilityFieldNames.value) &&
    fieldPropertyOf(field).kind !== 'LEGACY_LOCKED'
  );
}

function fieldProtectionReason(field: MetadataField): string | undefined {
  return fieldProtectionReasonFor(state.selectedRelation.value, field);
}

function fieldProtectionReasonFor(
  relation: ModuleMetadataRelation | undefined,
  field: MetadataField,
): string | undefined {
  const capabilityFields = capabilityFieldNamesFor(relation);
  if (relation && propertyForRelationField(relation, field).kind === 'LEGACY_LOCKED') {
    return '该字段仍由旧配置链路维护，已锁定，不能改为新的字段属性。';
  }
  const kind = metadataFieldGovernanceKind(field, relation, capabilityFields);
  return (
    {
      BUSINESS: undefined,
      CAPABILITY_DERIVED: '由已启用能力维护，不能作为业务字段编辑。',
      PLATFORM_SYSTEM: '平台系统字段，不能在数据模型会话中修改。',
      RELATION_FOREIGN_KEY: '由实体关系维护，不能作为独立字段修改。',
    }[kind] ?? undefined
  );
}

function fieldSortableInTree(relation: ModuleMetadataRelation, field: MetadataField): boolean {
  return (
    Boolean(field.id) &&
    metadataFieldGovernanceKind(field, relation, capabilityFieldNamesFor(relation)) === 'BUSINESS' &&
    propertyForRelationField(relation, field).kind !== 'LEGACY_LOCKED'
  );
}

function propertyForRelationField(
  relation: ModuleMetadataRelation,
  field: MetadataField,
): MetadataFieldPropertyDraft {
  if (!relation.id) return { kind: 'BASIC' };
  if (editSession.editing.value) return editSession.propertyForField(relation.id, field);
  const summary = (fieldPropertiesByRelation.value[relation.id] ?? []).find(
    (item) => item.fieldId === field.id || item.fieldName === field.fieldName,
  );
  return summary ? propertyDraftFromSummary(summary) : { kind: 'BASIC' };
}

function capabilityFieldNamesFor(relation: ModuleMetadataRelation | undefined) {
  if (!relation?.id) return new Set<string>();
  return new Set(
    (capabilitiesByRelation.value[relation.id]?.capabilities ?? [])
      .filter((fact) => firstReleaseDeclaredCapabilities.has(fact.capability))
      .flatMap((fact) => fact.fieldContributions),
  );
}

function canDragMetadataNode(node: UiTreeNode): boolean {
  return editSession.editing.value && (node as MetadataModelTreeNode).draggable === true;
}

function allowMetadataModelDrop(event: UiTreeDropEvent) {
  return editSession.editing.value && canReorderMetadataModelTree(event);
}

function handleMetadataModelDrop(event: UiTreeDropEvent) {
  if (!allowMetadataModelDrop(event)) return;
  const drag = event.dragNode as MetadataModelTreeNode;
  const drop = event.dropNode as MetadataModelTreeNode;
  if (!drag.relationId || !drop.relationId || !event.dropToGap || event.dropPosition === 0) return;
  if (drag.modelKind === 'FIELD') {
    const relationId = drag.relationId;
    const relation = state.relations.value.find((item) => item.id === relationId);
    if (!relation) return;
    const fields = editSession
      .fieldsForDisplay(relationId, fieldsByRelation.value[relationId] ?? [])
      .filter((field) => fieldSortableInTree(relation, field));
    const order = reorderedIds(fields, drag.fieldId!, drop.fieldId!, event.dropPosition);
    editSession.stageFieldOrder(relationId, order);
    return;
  }
  const relation = state.relations.value.find((item) => item.id === drag.relationId);
  const siblings = state.relations.value
    .filter((item) => item.parentMetadataId === relation?.parentMetadataId)
    .sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0));
  const order = reorderedIds(siblings, drag.relationId, drop.relationId, event.dropPosition);
  editSession.stageRelationOrder(relation?.parentMetadataId, order);
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
  <ManagementWorkspace class="metadata-model-workspace" layout="default" :explorer-count="1" list-surface>
    <ManagementExplorerColumn title="数据模型" collapsible :has-selection="Boolean(selectedTreeKey)">
      <RecordExplorerPanel
        title="数据模型"
        :searchable="false"
        :collapse-action="false"
        @refresh="loadWorkspace"
      >
        <template #actions>
          <UiButton
            v-if="!state.fieldEditorOpen.value && !sorting"
            icon-name="swap-vertical"
            icon-only
            size="small"
            type="text"
            title="调整排序"
            aria-label="调整排序"
            :disabled="saving || loading"
            @click="startSorting"
          />
          <label class="metadata-system-fields-toggle">
            <span>系统字段</span>
            <UiSwitch
              v-model:checked="showSystemFields"
              size="small"
              :title="showSystemFields ? '隐藏系统字段' : '显示系统字段'"
              :aria-label="showSystemFields ? '隐藏系统字段' : '显示系统字段'"
            />
          </label>
        </template>
        <UiSpin v-if="loading" tip="加载数据模型" />
        <UiTree
          v-else
          v-model:expanded-keys="expandedTreeKeys"
          :nodes="metadataTreeNodes"
          :selected-key="selectedTreeKey"
          :draggable="editSession.editing.value"
          :can-drag="canDragMetadataNode"
          :allow-drop="allowMetadataModelDrop"
          @select="selectMetadataTreeNode"
          @drop="handleMetadataModelDrop"
        />
      </RecordExplorerPanel>
    </ManagementExplorerColumn>

    <RecordDetailPanel
      v-if="state.selectedMetadata.value && state.selectedRelation.value"
      class="module-tree-card"
      :title="
        selectedNodeIsField
          ? (selectedField?.title ?? '字段')
          : (state.selectedMetadata.value.title ?? '元数据')
      "
      :subtitle="selectedNodeIsField ? selectedField?.fieldName : state.selectedMetadata.value.alias"
    >
      <template v-if="state.fieldEditorOpen.value" #status>
        <UiRadioGroup v-model:value="editorMode" :options="editorModeOptions" size="small" />
      </template>
      <template #actions>
        <template v-if="state.fieldEditorOpen.value">
          <UiActionButton :disabled="saving" @click="cancelNodeEditor">取消</UiActionButton>
          <UiActionButton emphasis="primary" :loading="saving" @click="stageFieldDraft">
            保存
          </UiActionButton>
        </template>
        <template v-else-if="sorting">
          <UiActionButton :disabled="saving" @click="cancelNodeEditor">取消</UiActionButton>
          <UiActionButton emphasis="primary" :loading="saving" @click="previewAndApply('保存排序')">
            保存排序
          </UiActionButton>
        </template>
        <template v-else-if="!selectedNodeIsField">
          <UiActionButton :disabled="saving || loading" @click="startCreateChildNode">＋ 字段</UiActionButton>
          <UiActionButton :disabled="saving || loading" @click="startCreateChildMetadataNode"
            >＋ 子元数据</UiActionButton
          >
        </template>
        <UiActionButton
          v-else
          :disabled="!fieldEditableInSession(selectedField!)"
          :title="fieldProtectionReason(selectedField!)"
          @click="startEditField(selectedField!, fieldPropertyOf(selectedField!))"
          >编辑</UiActionButton
        >
        <UiActionButton
          v-if="!state.fieldEditorOpen.value"
          intent="danger"
          :disabled="saving || (selectedNodeIsField ? !fieldEditableInSession(selectedField!) : false)"
          :title="selectedNodeIsField ? fieldProtectionReason(selectedField!) : undefined"
          @click="deleteSelectedNode"
        >
          删除
        </UiActionButton>
      </template>

      <section v-if="state.fieldEditorOpen.value" class="metadata-inline-editor">
        <RecordFormGrid @submit.prevent="stageFieldDraft">
          <template v-if="!fieldDraft.id && childNodeType === 'CHILD_METADATA'">
            <label v-if="editorMode === 'ADVANCED' || Boolean(fieldDraft.id)">
              <span>子实体 alias</span>
              <UiInput v-model:value="childMetadataDraft.alias" placeholder="例如 exam_participant" />
            </label>
            <label>
              <span>子实体名称</span>
              <UiInput v-model:value="childMetadataDraft.title" placeholder="例如 参考学生" />
            </label>
            <label v-if="editorMode === 'ADVANCED'">
              <span>Schema（可选）</span>
              <UiInput v-model:value="childMetadataDraft.schemaName" placeholder="默认 public" />
            </label>
            <label v-if="editorMode === 'ADVANCED'">
              <span>物理表名（可选）</span>
              <UiInput v-model:value="childMetadataDraft.tableName" placeholder="默认按应用和 alias 生成" />
            </label>
          </template>
          <template v-else>
            <label v-if="editorMode === 'ADVANCED' || Boolean(fieldDraft.id)">
              <span>字段名称</span>
              <UiInput
                v-model:value="fieldDraft.fieldName"
                :disabled="Boolean(fieldDraft.id)"
                placeholder="例如 customerName"
              />
            </label>
            <label v-if="editorMode === 'ADVANCED'">
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
            <label v-if="editorMode === 'ADVANCED' && !fieldDraft.id">
              <span>数据属性</span>
              <UiSelect
                :value="fieldPropertyEditorKind"
                :options="fieldPropertyKindOptions"
                style="width: 100%"
                @update:value="selectNewFieldPropertyKind"
              />
            </label>
            <label>
              <span>存储字段规格</span>
              <UiSelect
                v-if="fieldPropertyEditorKind === 'BASIC'"
                v-model:value="fieldDraft.fieldSpecAlias"
                :options="editableFieldSpecOptions"
                placeholder="选择字段规格"
                style="width: 100%"
              />
              <UiInput v-else :value="fieldStorageSpecLabel" disabled :title="fieldStorageSpecAlias" />
            </label>
            <template v-if="editorMode === 'ADVANCED' && fieldPropertyEditorKind === 'MODULE_REFERENCE'">
              <div class="field-property-heading record-form-full-row">
                <strong>模块引用</strong
                ><span>源字段存储目标键；默认读取目标记录的 id，并以 title 展示。</span>
              </div>
              <label
                ><span>目标模块</span
                ><UiInput
                  :value="fieldPropertyDraft.referenceConfig!.targetModuleAlias"
                  placeholder="例如 education.subject_category"
                  @update:value="updateReferenceTargetModuleAlias"
              /></label>
              <div
                v-if="fieldPropertyDraft.referenceConfig!.targetMetadataId"
                class="field-property-binding record-form-full-row"
              >
                <strong>目标实体绑定</strong
                ><span>{{ fieldPropertyDraft.referenceConfig!.targetMetadataId }}</span>
              </div>
              <div class="orchestration-form-grid record-form-full-row">
                <label
                  ><span>目标键字段</span
                  ><UiSelect
                    v-model:value="fieldPropertyDraft.referenceConfig!.targetKeyField"
                    :options="referenceKeyFieldOptions"
                    :loading="referenceTargetFieldCatalogLoading"
                    :disabled="Boolean(referenceTargetFieldCatalogError)"
                    placeholder="请选择目标键字段"
                    style="width: 100%"
                /></label>
                <label
                  ><span>目标展示字段</span
                  ><UiSelect
                    v-model:value="fieldPropertyDraft.referenceConfig!.targetLabelField"
                    :options="referenceLabelFieldOptions"
                    :loading="referenceTargetFieldCatalogLoading"
                    :disabled="Boolean(referenceTargetFieldCatalogError)"
                    placeholder="请选择目标展示字段"
                    style="width: 100%"
                /></label>
              </div>
              <p
                v-if="referenceTargetFieldCatalogProblem"
                class="field-property-error record-form-full-row"
                role="alert"
              >
                {{ referenceTargetFieldCatalogProblem }}
              </p>
              <div class="orchestration-form-grid record-form-full-row">
                <label
                  ><span>基数</span><UiInput value="单选" disabled /><small class="field-property-note"
                    >本期模块引用仅支持单选。</small
                  ></label
                >
                <label
                  ><span>目标不可用策略</span
                  ><UiSelect
                    v-model:value="fieldPropertyDraft.referenceConfig!.targetUnavailablePolicy"
                    :options="[
                      { value: 'PRESERVE_HISTORY', label: '保留历史' },
                      { value: 'RESTRICT', label: '限制删除' },
                      { value: 'CASCADE_DELETE', label: '级联删除' },
                    ]"
                    style="width: 100%"
                /></label>
              </div>
              <label class="record-form-full-row"
                ><span>展示投影与自动带出字段</span
                ><UiTextArea
                  v-model:value="projectionMappingsText"
                  :rows="3"
                  placeholder="每行一项，例如 title:subjectCategoryIdTitle"
              /></label>
            </template>
            <template v-else-if="editorMode === 'ADVANCED' && fieldPropertyEditorKind === 'DICTIONARY'">
              <div class="field-property-heading record-form-full-row">
                <strong>数据字典</strong><span>固定以字典 code 存储、title 展示。</span>
              </div>
              <label
                ><span>字典应用</span
                ><UiInput
                  v-model:value="fieldPropertyDraft.dictionaryConfig!.dictionaryApplicationAlias"
                  placeholder="例如 education"
              /></label>
              <label
                ><span>字典类别</span
                ><UiInput
                  v-model:value="fieldPropertyDraft.dictionaryConfig!.dictionaryCategoryAlias"
                  placeholder="例如 exam_attendance_status"
              /></label>
              <label
                ><span>选择方式</span
                ><UiSelect
                  v-model:value="fieldPropertyDraft.dictionaryConfig!.selectionMode"
                  :options="[
                    { value: 'SINGLE', label: '单选' },
                    { value: 'MULTIPLE', label: '多选' },
                  ]"
                  style="width: 100%"
              /></label>
            </template>
            <div v-if="editorMode === 'ADVANCED'" class="orchestration-form-flags record-form-full-row">
              <UiCheckbox v-model:checked="fieldDraft.required">必填</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.uniqueField">唯一</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.indexed">建立索引</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.sortableField">排序字段</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.titleField">标题字段</UiCheckbox>
              <UiCheckbox v-model:checked="fieldDraft.enabled">启用字段</UiCheckbox>
            </div>
          </template>
        </RecordFormGrid>
      </section>

      <section v-else-if="!selectedNodeIsField" class="metadata-node-summary">
        <RecordContentSectionHeading title="实体身份" />
        <RecordDetailFields
          :record="{
            entityRole: selectedRelationIsMain ? '主实体' : '子实体',
            physicalTable: state.selectedMetadata.value.tableName || '物理表由平台生成',
            parentMetadata: selectedRelationIsMain
              ? undefined
              : state.selectedRelation.value.parentMetadataId || '未绑定',
          }"
          :field-names="
            selectedRelationIsMain
              ? ['entityRole', 'physicalTable']
              : ['entityRole', 'physicalTable', 'parentMetadata']
          "
          :fallback="{
            entityRole: { label: '实体类型' },
            physicalTable: { label: '物理表' },
            parentMetadata: { label: '父实体' },
          }"
        />
      </section>

      <section v-else-if="!state.fieldEditorOpen.value" class="field-node-card">
        <RecordContentSectionHeading
          title="字段事实"
          :subtitle="fieldProtectionReason(selectedField!) || '业务字段，可在模型编辑会话中调整。'"
        />
        <RecordDetailFields
          :record="{
            fieldName: selectedField?.fieldName,
            columnName: selectedField?.columnName || '—',
            fieldSpec: fieldSpecDisplayLabel(selectedField?.fieldSpecAlias, state.fieldSpecs.value),
            property: metadataFieldPropertyLabel(fieldPropertyOf(selectedField!).kind),
            constraints: `${selectedField?.required ? '必填' : '非必填'}${selectedField?.uniqueField ? ' · 唯一' : ''}${selectedField?.indexed ? ' · 索引' : ''}`,
            ownership: fieldSourceOf(selectedField!),
          }"
          :field-names="['fieldName', 'columnName', 'fieldSpec', 'property', 'constraints', 'ownership']"
          :fallback="{
            fieldName: { label: '字段名' },
            columnName: { label: '物理列' },
            fieldSpec: { label: '字段规格' },
            property: { label: '数据属性' },
            constraints: { label: '约束' },
            ownership: { label: '治理归属' },
          }"
        />
      </section>
    </RecordDetailPanel>
    <RecordDetailPanel
      v-else
      :title="state.mainEditorOpen.value ? '新建根元数据' : '数据模型'"
      :subtitle="state.mainEditorOpen.value ? (moduleTitle ?? moduleAlias) : undefined"
    >
      <template v-if="state.mainEditorOpen.value" #status>
        <UiRadioGroup v-model:value="editorMode" :options="editorModeOptions" size="small" />
      </template>
      <template #actions>
        <template v-if="state.mainEditorOpen.value">
          <UiActionButton :disabled="saving" @click="cancelNodeEditor">取消</UiActionButton>
          <UiActionButton emphasis="primary" :loading="saving" @click="createMainMetadata">
            保存
          </UiActionButton>
        </template>
        <UiActionButton
          v-else
          emphasis="primary"
          :disabled="loading || saving"
          @click="startCreateMainMetadata"
        >
          新建根元数据
        </UiActionButton>
      </template>
      <section v-if="state.mainEditorOpen.value" class="metadata-inline-editor">
        <RecordFormGrid @submit.prevent="createMainMetadata">
          <label v-if="editorMode === 'ADVANCED'">
            <span>实体 alias</span>
            <UiInput v-model:value="mainMetadataDraft.alias" placeholder="例如 customer" />
          </label>
          <label>
            <span>实体名称</span>
            <UiInput v-model:value="mainMetadataDraft.title" placeholder="例如 客户" />
          </label>
          <label v-if="editorMode === 'ADVANCED'">
            <span>Schema（可选）</span>
            <UiInput v-model:value="mainMetadataDraft.schemaName" placeholder="默认 public" />
          </label>
          <label v-if="editorMode === 'ADVANCED'">
            <span>物理表名（可选）</span>
            <UiInput v-model:value="mainMetadataDraft.tableName" placeholder="默认按应用和 alias 生成" />
          </label>
        </RecordFormGrid>
      </section>
      <UiEmpty v-else description="从右上角“新建根元数据”开始配置数据模型" />
    </RecordDetailPanel>
  </ManagementWorkspace>
</template>

<style scoped>
.metadata-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

.metadata-toolbar__identity {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.metadata-toolbar__identity strong {
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-toolbar__identity span {
  overflow: hidden;
  color: var(--muyun-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-toolbar > :first-child {
  min-width: 0;
}

.metadata-toolbar__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.metadata-system-fields-toggle {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.metadata-edit-status {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  color: var(--muyun-color-primary);
  font-size: 12px;
}

.metadata-edit-status span {
  color: var(--muyun-text-muted);
}

.metadata-node-summary,
.field-node-card {
  display: grid;
  gap: 10px;
  padding: 2px 0;
}

.metadata-inline-editor {
  display: grid;
  min-width: 0;
  padding: 0;
}

.record-form-full-row {
  grid-column: 1 / -1;
}

.orchestration-form-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  align-items: center;
  font-size: 13px;
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
  background: var(--muyun-info-soft);
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
  color: var(--muyun-danger-base);
  font-size: 12px;
}
</style>
