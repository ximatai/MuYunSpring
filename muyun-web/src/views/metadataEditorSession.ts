import type { ReferenceTargetFieldCatalog, ReferenceTargetFieldCandidate } from '@muyun/web-contracts';
import { computed, effectScope, ref, watch } from 'vue';
import type { ModuleActivationFeedback } from './moduleRuntimeActivation';
import {
  generatedBusinessFieldName,
  generatedMetadataAlias,
  isDynamicRecordReservedFieldName,
  isPlatformFieldName,
  physicalNameOf,
  recordNameFieldProblem,
} from './metadataNaming';
import {
  reconcileSelectedKey,
  handlePlatformActionSuccess,
  presentPlatformError,
  presentPlatformMessage,
} from '@muyun/platform-components';
import type {
  Metadata,
  DictionaryCategory,
  MetadataField,
  ModuleMetadataRelation,
  Option,
  WebPageResponse,
} from '@muyun/web-contracts';
import {
  createUuid,
  OperationUsageError,
  OperationRejectedError,
  createOperationConfirmation,
  AppError,
  type HttpClient,
  type HttpRequestOptions,
  type OperationProposal,
  type OperationConfirmation,
  createStaticResourceCrudClient,
} from '@muyun/web-core';
import {
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
import { previewMetadataModelChangeSet } from './metadataModelChangeSetClient';
import {
  prepareMetadataChangeSetSubmission,
  MetadataChangeSetPrecheckError,
} from './metadataChangeSetSubmission';
import type {
  MetadataGovernanceEditor,
  MetadataFieldCandidate,
  MetadataFieldPlanInput,
  AddMetadataFieldDraftInput,
  AddMetadataPropertyFieldDraftInput,
  FindMetadataFieldTargetsInput,
  PreparedMetadataPropertyFieldDraft,
  UpdateMetadataFieldDraftInput,
  UpdateMetadataReferenceDraftInput,
} from './metadataGovernanceEditor';
import {
  buildMetadataModelTree,
  canReorderMetadataModelTree,
  metadataNodeKey,
  parseMetadataModelTreeKey,
  reorderedIds,
  type MetadataModelTreeNode,
} from './metadataModelTree';

export interface MetadataEditorSessionOptions {
  moduleAlias: string;
  moduleTitle?: string;
  valid?: () => boolean;
  confirmationScope?: () => boolean;
  refreshActivation?: (moduleAlias: string) => Promise<ModuleActivationFeedback | undefined>;
  onCommitted?: (moduleAlias: string) => void;
}

/** Shared editor state and commands; creating a session never mounts a governance page. */
export function createMetadataEditorSession(source: HttpClient, options: MetadataEditorSessionOptions) {
  const scope = effectScope(true);
  let disposed = false;
  const valid = () => !disposed && (options.valid?.() ?? true);
  function requireValid() {
    if (!valid()) throw new OperationUsageError('身份或编辑会话已变化，请重新选择模块');
  }
  const http: HttpClient = {
    async request<T>(request: HttpRequestOptions): Promise<T> {
      requireValid();
      const result = await source.request<T>(request);
      requireValid();
      return result;
    },
  };
  return scope.run(() => {
    const props = {
      moduleAlias: options.moduleAlias,
      moduleTitle: options.moduleTitle,
      title: options.moduleTitle,
    };
    type CreationResult = { metadata: Metadata; relation: ModuleMetadataRelation };
    type ChildMetadataDraft = { alias: string; title: string; schemaName?: string; tableName?: string };
    const ORCHESTRATION_QUERY_PAGE_SIZE = 200;

    const moduleContext = { http };
    const refreshActivation = options.refreshActivation;
    const metadataClient = createStaticResourceCrudClient<Metadata>(moduleContext.http, '/platform.metadata');
    const state = createMetadataOrchestrationState();
    const editSession = createMetadataModelWorkspaceEditSession();
    const submissionStatus = ref<'idle' | 'unknown' | 'current-read'>('idle');
    const committedNeedsReload = ref(false);
    const readingCurrent = ref(false);
    let submittedCandidate: MetadataModelChangeSetProposal | undefined;
    let submittedBaseline: ReturnType<typeof editSession.captureBaseline> | undefined;
    function requireKnownSubmission() {
      requireValid();
      if (submissionStatus.value === 'unknown')
        throw new OperationUsageError('原元数据提交结果未知，请先读取当前配置再重新审阅。');
      if (committedNeedsReload.value)
        throw new OperationUsageError('元数据已保存，状态待核实；请先读取当前配置，不要重复保存。');
    }
    async function applyMetadataSubmission(
      submission: Awaited<ReturnType<typeof prepareMetadataChangeSetSubmission>>,
      proposal: MetadataModelChangeSetProposal,
    ) {
      requireKnownSubmission();
      submittedBaseline = editSession.captureBaseline();
      try {
        await submission.apply();
      } catch (cause) {
        if (!(cause instanceof OperationRejectedError) && valid()) {
          submittedCandidate = JSON.parse(JSON.stringify(proposal));
          submissionStatus.value = 'unknown';
        }
        throw cause;
      }
      if (valid()) {
        submittedCandidate = JSON.parse(JSON.stringify(proposal));
        committedNeedsReload.value = true;
      }
    }
    const fieldPlanActive = ref(false);
    const fieldPlanEntries = computed(() =>
      fieldPlanActive.value ? sessionFields.value.filter((field) => !field.id) : [],
    );

    const sorting = ref(false);
    const mainMetadataDraft = state.mainMetadataDraft;
    const title = computed(() => {
      const main = state.relations.value.find((relation) => relation.relationRole?.toLowerCase() === 'main');
      return (
        props.moduleTitle ||
        (main?.metadataId ? state.metadataById.value[main.metadataId]?.title : undefined) ||
        props.moduleAlias
      );
    });
    const fieldDraft = state.fieldDraft;
    const fieldPropertyDraft = state.fieldPropertyDraft;
    const loading = ref(false);
    const workspaceReady = ref(false);
    const fieldSpecsReady = ref(false);
    const workspaceLoadFailed = ref(false);
    let workspaceLoadRevision = 0;
    const saving = ref(false);
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
    const stagedNewFieldKey = ref<string>();
    const editorModeOptions: UiRadioOption[] = [
      { value: 'SIMPLE', label: '简单模式' },
      { value: 'ADVANCED', label: '高级模式' },
    ];
    const childMetadataDraft = ref<ChildMetadataDraft>({ alias: '', title: '' });
    const childAliasManuallyEdited = ref(false);
    const childValidationAttempted = ref(false);
    const creatingChildMetadata = computed(
      () => state.fieldEditorOpen.value && !fieldDraft.value.id && childNodeType.value === 'CHILD_METADATA',
    );
    watch(
      () => childMetadataDraft.value.title,
      (title) => {
        if (!childAliasManuallyEdited.value)
          childMetadataDraft.value.alias = generatedMetadataAlias(
            title,
            63 - (props.moduleAlias.split('.')[0]?.length ?? 0) - 1,
          );
      },
    );
    function updateChildAlias(alias: string) {
      childAliasManuallyEdited.value = true;
      childMetadataDraft.value.alias = alias;
    }
    const childAliasError = computed(() => {
      const alias = childMetadataDraft.value.alias.trim();
      if (!alias) return '请填写子元数据标识';
      if (!/^[a-z][a-z0-9_]{0,62}$/.test(alias))
        return '标识须以小写字母开头，仅含小写字母、数字或下划线，最多 63 个字符';
      return undefined;
    });

    type ModuleMetadataCapabilityFact = {
      capability: string;
      enabled: boolean;
      configurable: boolean;
      changeSetConfigurable?: boolean;
      reason: string;
      fieldContributions: string[];
      defaultKind: string;
      defaultDescription: string;
    };
    type ModuleMetadataRelationRecordCount = { relationId: string; recordCount: number };
    type ModuleMetadataCapabilitySnapshot = {
      capabilities: ModuleMetadataCapabilityFact[];
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
    const firstReleaseDeclaredCapabilities = new Set(['TREE', 'SORT', 'ENABLE', 'RECYCLE_BIN', 'APPROVAL']);
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
        configurable: fact.changeSetConfigurable === true,
        title: capabilityTitleOf(fact.capability),
        selected:
          fact.enabled ||
          Boolean(
            selectedRelationId.value &&
            editSession.relation(selectedRelationId.value)?.capabilitySelections[fact.capability],
          ),
      })),
    );
    const selectedField = computed(() => {
      const parsed = selectedTreeKey.value ? parseMetadataModelTreeKey(selectedTreeKey.value) : undefined;
      if (parsed?.kind !== 'FIELD' || parsed.relationId !== selectedRelationId.value) return undefined;
      return displayedFields.value.find((field) => (field.id ?? field.fieldName) === parsed.fieldId);
    });
    const ASSISTANT_METADATA_FIELD_LIMIT = 80;
    const ASSISTANT_FIELD_SPEC_LIMIT = 40;

    function assistantSummary() {
      const relation = state.selectedRelation.value;
      const fields = relation?.id
        ? visibleFields(editSession.fieldsForDisplay(relation.id, state.allFields.value))
        : [];
      const projectedFields = fields.slice(0, ASSISTANT_METADATA_FIELD_LIMIT).map((field) => ({
        fieldName: field.fieldName ?? '',
        columnName: field.columnName,
        title: field.title,
        fieldSpecAlias: field.fieldSpecAlias,
        required: field.required ?? false,
        uniqueField: field.uniqueField ?? false,
        indexed: field.indexed ?? false,
        sortableField: field.sortableField ?? false,
        titleField: field.titleField ?? false,
        enabled: field.enabled !== false,
        propertyKind: fieldPropertyOf(field).kind,
        reference: fieldPropertyOf(field).referenceConfig,
        defaultValue: fieldDefaultValue(field, fieldPropertyOf(field)),
        governance: metadataFieldGovernanceLabel(
          metadataFieldGovernanceKind(field, relation, capabilityFieldNames.value),
        ),
      }));
      return {
        moduleAlias: props.moduleAlias,
        moduleTitle: title.value,
        factsAvailable: !disposed && submissionStatus.value !== 'unknown' && !committedNeedsReload.value,
        submissionStatus: submissionStatus.value,
        committedNeedsReload: committedNeedsReload.value,
        relationCount: state.relations.value.length,
        selectedRelation:
          relation?.id == null
            ? undefined
            : {
                relationId: relation.id,
                title: state.selectedMetadata.value?.title,
                fieldCount: fields.length,
                fieldsSource: editSession.editing.value
                  ? ('UNSAVED_CANDIDATE' as const)
                  : ('SAVED_CONFIGURATION' as const),
                fields: projectedFields,
                capabilities: capabilityItems.value,
                truncated: projectedFields.length < fields.length,
              },
        mainCandidate: state.mainEditorOpen.value
          ? {
              ...state.mainMetadataDraft.value,
              storageDefaultsOnSave: (['schemaName', 'tableName'] as const).filter(
                (field) => !state.mainMetadataDraft.value[field].trim(),
              ),
              saved: false as const,
              nextStep: 'REVIEW_AND_SAVE_STRUCTURE_BEFORE_FIELDS' as const,
            }
          : undefined,
        childCandidate:
          creatingChildMetadata.value && relation?.id
            ? {
                alias: childMetadataDraft.value.alias,
                title: childMetadataDraft.value.title,
                parentRelationId: relation.id,
                parentTitle: state.selectedMetadata.value?.title,
                saved: false as const,
              }
            : undefined,
        draft: {
          active: editSession.editing.value || state.mode.value !== 'view',
          fieldPlanOpen: fieldPlanActive.value && !state.fieldEditorOpen.value,
          fieldPlanEditing: fieldPlanActive.value && state.fieldEditorOpen.value,
          dirty: editSession.isDirty.value || creatingChildMetadata.value || state.mainEditorOpen.value,
          editorOpen:
            state.mainEditorOpen.value ||
            state.fieldEditorOpen.value ||
            sorting.value ||
            fieldPlanActive.value,
        },
        fieldSpecs: state.fieldSpecs.value
          .filter((spec) => spec.enabled !== false)
          .slice(0, ASSISTANT_FIELD_SPEC_LIMIT)
          .flatMap((spec) => {
            const alias = spec.alias ?? spec.id;
            return alias ? [{ alias, title: spec.title }] : [];
          }),
      };
    }

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
              // Preview ordering in the tree; field creation drafts stay in the editor until applied.
              visibleFields(
                sorting.value && !state.fieldEditorOpen.value
                  ? editSession
                      .fieldsForDisplay(relation.id, fieldsByRelation.value[relation.id] ?? [])
                      .map((field, index) => ({ ...field, sortOrder: index }))
                  : (fieldsByRelation.value[relation.id] ?? []),
              ),
            ]),
        ),
        fieldLocked: (relation, field) => fieldProtectionReasonFor(relation, field) !== undefined,
      }),
    );
    const selectedRelationIsMain = computed(() => isMainRelation(state.selectedRelation.value?.relationRole));
    const fieldPropertyEditorKind = computed(() => state.fieldPropertyDraft.value.kind);
    const fixedDefaultEditable = computed(
      () =>
        !fieldDraft.value.id ||
        fieldProperties.value.find((item) => item.fieldId === fieldDraft.value.id)?.fixedDefault?.editable !==
          false,
    );
    const fixedDefaultReadValue = computed(() =>
      fieldDraft.value.id
        ? (fieldProperties.value.find((item) => item.fieldId === fieldDraft.value.id)?.fixedDefault?.value ??
          null)
        : null,
    );
    const selectedRelationHasBusinessRecords = computed(
      () => (recordCountsByRelation.value[selectedRelationId.value ?? ''] ?? 0) > 0,
    );
    const editableFieldSpecOptions = computed(() =>
      editorMode.value === 'SIMPLE' &&
      Boolean(fieldDraft.value.id) &&
      selectedRelationHasBusinessRecords.value
        ? dataSafeFieldSpecOptions(state.fieldSpecs.value, fieldDraft.value.fieldSpecAlias)
        : state.fieldSpecOptions.value,
    );
    const fieldCreationItems = [
      { key: 'BASIC', title: '普通字段' },
      { key: 'MODULE_REFERENCE', title: '模块引用' },
      { key: 'DICTIONARY', title: '数据字典' },
    ];
    const referenceSearch = ref('');
    const dictionarySearch = ref('');
    const targetModules = ref<Array<{ alias: string; title?: string }>>([]);
    const dictionaries = ref<DictionaryCategory[]>([]);
    const choicesLoading = ref(false);
    const choicesError = ref<string>();
    let choicesToken = 0;
    const referenceModuleOptions = computed(() => {
      const options: Option[] = targetModules.value.map((item) => ({
        value: item.alias,
        label: `${item.title || item.alias} · ${item.alias}`,
      }));
      const alias = fieldPropertyDraft.value.referenceConfig?.targetModuleAlias;
      if (alias && !options.some((item) => item.value === alias))
        options.push({ value: alias, label: `${alias}（当前绑定）` });
      return options.filter((item) => item.label.toLowerCase().includes(referenceSearch.value.toLowerCase()));
    });
    const dictionaryValue = computed(() => {
      const config = fieldPropertyDraft.value.dictionaryConfig;
      return config?.dictionaryApplicationAlias && config.dictionaryCategoryAlias
        ? `${config.dictionaryApplicationAlias}.${config.dictionaryCategoryAlias}`
        : undefined;
    });
    const dictionaryOptions = computed(() => {
      // Dictionary bindings use application/category aliases; tenant variants share that identity.
      const options: Option[] = [
        ...new Map(
          dictionaries.value.map((item) => {
            const value = `${item.applicationAlias}.${item.alias}`;
            return [value, { value, label: `${item.title || item.alias} · ${value}` }];
          }),
        ).values(),
      ];
      if (dictionaryValue.value && !options.some((item) => item.value === dictionaryValue.value))
        options.push({ value: dictionaryValue.value, label: `${dictionaryValue.value}（当前绑定）` });
      return options.filter((item) =>
        item.label.toLowerCase().includes(dictionarySearch.value.toLowerCase()),
      );
    });
    const fieldTitleManuallyEdited = ref(false);
    function updateFieldTitle(title: string) {
      fieldTitleManuallyEdited.value = Boolean(title.trim());
      fieldDraft.value.title = title;
    }
    function suggestFieldTitle(title: string) {
      if (!fieldTitleManuallyEdited.value) fieldDraft.value.title = title;
    }
    function updateDictionary(value: unknown) {
      const selected = dictionaries.value.find((item) => `${item.applicationAlias}.${item.alias}` === value);
      const config = fieldPropertyDraft.value.dictionaryConfig;
      if (!config) return;
      config.dictionaryApplicationAlias = selected?.applicationAlias;
      config.dictionaryCategoryAlias = selected?.alias;
      suggestFieldTitle(selected?.title || selected?.alias || '');
    }
    watch(
      () => [state.fieldEditorOpen.value, fieldPropertyEditorKind.value, selectedRelationId.value],
      async () => {
        const token = ++choicesToken;
        choicesError.value = undefined;
        choicesLoading.value = false;
        if (!state.fieldEditorOpen.value || fieldPropertyEditorKind.value === 'BASIC') return;
        choicesLoading.value = true;
        try {
          if (fieldPropertyEditorKind.value === 'MODULE_REFERENCE') {
            const records = await moduleContext.http.request<Array<{ alias: string; title?: string }>>({
              method: 'GET',
              path: relationPath(
                `/${encodeURIComponent(selectedRelationId.value!)}/reference-target-modules`,
              ),
            });
            if (token === choicesToken) targetModules.value = records;
          } else {
            const records = await loadAllRecords<DictionaryCategory>('/platform.dictionary_category/query');
            if (token === choicesToken)
              dictionaries.value = records.filter(
                (item) =>
                  item.categoryKind?.toUpperCase() === 'DICTIONARY' && item.applicationAlias && item.alias,
              );
          }
        } catch (cause) {
          if (token === choicesToken) {
            choicesError.value = '选项目录加载失败，请取消后重试。';
            presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
          }
        } finally {
          if (token === choicesToken) choicesLoading.value = false;
        }
      },
    );
    const fieldNameManuallyEdited = ref(false);
    const columnNameManuallyEdited = ref(false);
    function updateFieldName(name: string) {
      fieldNameManuallyEdited.value = Boolean(name.trim());
      fieldDraft.value.fieldName = name;
      if (!columnNameManuallyEdited.value) fieldDraft.value.columnName = physicalNameOf(name);
    }
    function updateColumnName(name: string) {
      columnNameManuallyEdited.value = Boolean(name.trim());
      fieldDraft.value.columnName = name;
    }
    watch(
      () => [fieldDraft.value.title, fieldDraft.value.titleField] as const,
      ([title, titleField]) => {
        if (fieldDraft.value.id) return;
        if (!fieldNameManuallyEdited.value)
          fieldDraft.value.fieldName = generatedBusinessFieldName(
            title,
            fieldPropertyEditorKind.value,
            titleField,
          );
        if (!columnNameManuallyEdited.value)
          fieldDraft.value.columnName = physicalNameOf(fieldDraft.value.fieldName);
      },
    );
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
    const referenceAffectMappings = computed(
      () => fieldPropertyDraft.value.referenceConfig?.affectMappings ?? [],
    );
    const referenceAffectSourceOptions = computed(() =>
      referenceFieldOptions(referenceTargetFieldCatalog.value?.labelFields ?? [], undefined),
    );
    const savedReferenceAffectTargetOptions = computed(() =>
      displayedFields.value
        .filter(
          (field) =>
            field.id &&
            field.fieldName &&
            fieldEditableInSession(field) &&
            fieldPropertyOf(field).kind === 'BASIC',
        )
        .map((field) => ({ value: field.fieldName!, label: field.title || field.fieldName! })),
    );
    const referenceAffectTargetOptions = computed(() =>
      savedReferenceAffectTargetOptions.value.filter((field) => field.value !== fieldDraft.value.fieldName),
    );
    function updateReferenceAffect(index: number, side: number, value: unknown) {
      const reference = fieldPropertyDraft.value.referenceConfig;
      if (!reference) return;
      const mappings = [...(reference.affectMappings ?? [])];
      const parts = (mappings[index] ?? ':').split(':');
      parts[side] = typeof value === 'string' ? value : '';
      mappings[index] = parts.join(':');
      reference.affectMappings = mappings;
    }
    function addReferenceAffect() {
      const reference = fieldPropertyDraft.value.referenceConfig;
      if (reference && (reference.affectMappings?.length ?? 0) < 8)
        reference.affectMappings = [...(reference.affectMappings ?? []), ':'];
    }
    function removeReferenceAffect(index: number) {
      const reference = fieldPropertyDraft.value.referenceConfig;
      if (reference)
        reference.affectMappings = (reference.affectMappings ?? []).filter(
          (_, position) => position !== index,
        );
    }
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
      const destinations = new Set<string>();
      for (const mapping of reference.affectMappings ?? []) {
        const [source, destination, extra] = mapping.split(':');
        if (
          extra !== undefined ||
          !source ||
          !destination ||
          !catalog.labelFields.some((field) => field.fieldName === source && field.selectable) ||
          !referenceAffectTargetOptions.value.some((field) => field.value === destination) ||
          destinations.has(destination)
        )
          return '请为每项选择回填指定可读取的来源字段和不同的当前普通字段。目标字段需先保存。';
        destinations.add(destination);
      }
      return undefined;
    });

    function updateReferenceTargetModuleAlias(value: unknown) {
      const targetModuleAlias = typeof value === 'string' ? value : '';
      const reference = fieldPropertyDraft.value.referenceConfig;
      if (!reference) return;
      if (reference.targetModuleAlias?.trim() !== targetModuleAlias.trim()) {
        referenceTargetFieldCatalogRequestToken += 1;
        reference.targetMetadataId = undefined;
        reference.affectMappings = [];
        reference.targetKeyField = 'id';
        reference.targetLabelField = undefined;
        referenceTargetFieldCatalog.value = undefined;
        referenceTargetFieldCatalogError.value = undefined;
        referenceTargetFieldCatalogLoading.value = false;
      }
      reference.targetModuleAlias = targetModuleAlias;
      suggestFieldTitle(targetModules.value.find((item) => item.alias === targetModuleAlias)?.title || '');
    }

    watch(
      () =>
        [fieldPropertyEditorKind.value, fieldPropertyDraft.value.dictionaryConfig?.selectionMode] as const,
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

    async function loadWorkspace(
      commit?: (accept: () => void) => void,
      recovering = false,
      signal?: AbortSignal,
    ) {
      requireValid();
      if (
        !recovering &&
        (submissionStatus.value === 'unknown' || (committedNeedsReload.value && !saving.value))
      )
        throw new OperationUsageError('原元数据提交结果未知，请先读取当前配置再重新审阅。');
      if (!recovering && (editSession.isDirty.value || state.mode.value !== 'view'))
        throw new OperationUsageError('请先保存或取消当前元数据候选，再刷新基线');
      const requestRevision = ++workspaceLoadRevision;
      const selectionBeforeRefresh = selectedTreeKey.value;
      if (!commit) {
        loading.value = true;
        workspaceReady.value = false;
        workspaceLoadFailed.value = false;
      }
      try {
        const moduleAlias = props.moduleAlias;
        const relations = await loadAllRecords<ModuleMetadataRelation>(relationPath('/query'));
        const metadata = await Promise.all(
          relations.map(async (relation) =>
            relation.metadataId ? metadataClient.view(relation.metadataId) : undefined,
          ),
        );
        const loaded = await Promise.all(
          relations.map(async (relation) => {
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
        if (requestRevision !== workspaceLoadRevision || !valid() || moduleAlias !== props.moduleAlias) {
          if (commit) throw new OperationUsageError('元数据读取已过期，请重新选择');
          return;
        }
        if (signal?.aborted) throw new DOMException('元数据读取已取消', 'AbortError');
        const rebase = recovering
          ? editSession.prepareRebase(
              relations.flatMap((relation) => {
                const item = loaded.find((entry) => entry?.relationId === relation.id);
                const model = metadata.find((entry) => entry?.id === relation.metadataId);
                if (!relation.id || !relation.metadataId || !item || !model) return [];
                return [
                  {
                    relationId: relation.id,
                    metadataId: relation.metadataId,
                    parentMetadataId: relation.parentMetadataId,
                    sortOrder: relation.sortOrder,
                    expectedMetadataVersion: model.version ?? 0,
                    fields: item.fields,
                    fieldProperties: item.properties,
                  },
                ];
              }),
              submittedCandidate!,
              Object.fromEntries(
                loaded
                  .filter((item) => item !== undefined)
                  .map((item) => [
                    item.relationId,
                    item.capabilities.capabilities
                      .filter((fact) => fact.enabled)
                      .map((fact) => fact.capability),
                  ]),
              ),
              submittedBaseline,
            )
          : undefined;
        const accept = () => {
          requireValid();
          if (requestRevision !== workspaceLoadRevision || signal?.aborted)
            throw new OperationUsageError('元数据读取已过期，请重新读取。');
          state.handleRelationsLoaded(relations);
          metadata.forEach((item) => {
            if (item) state.handleMetadataLoaded(item);
          });
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
          restoreTreeSelection(selectedTreeKey.value ?? selectionBeforeRefresh);
          if (!recovering) committedNeedsReload.value = false;
          if (!recovering && sorting.value && !state.fieldEditorOpen.value && !state.mainEditorOpen.value)
            startNodeEditSession();
          if (rebase) {
            rebase();
            state.cancelEditor();
            stagedNewFieldKey.value = undefined;
            sorting.value = false;
            fieldPlanActive.value = true;
            submissionStatus.value = submissionStatus.value === 'unknown' ? 'current-read' : 'idle';
            submittedCandidate = undefined;
            submittedBaseline = undefined;
          }
          committedNeedsReload.value = false;
          workspaceReady.value = true;
          workspaceLoadFailed.value = false;
        };
        if (commit) commit(accept);
        else accept();
      } catch (cause) {
        if (commit) throw cause;
        if (requestRevision !== workspaceLoadRevision || !valid()) return;
        workspaceLoadFailed.value = true;
        presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
      } finally {
        if (!commit && requestRevision === workspaceLoadRevision) loading.value = false;
      }
    }

    /** Keeps a field-level focus across a read-model refresh; only removed nodes fall back to their entity. */
    function restoreTreeSelection(previousKey: string | undefined) {
      const parsed = previousKey ? parseMetadataModelTreeKey(previousKey) : undefined;
      const relationId = parsed?.relationId ?? state.selectedRelation.value?.id;
      if (!relationId) return;
      const relation =
        state.relations.value.find((item) => item.id === relationId) ?? state.selectedRelation.value;
      if (!relation?.id) {
        selectedTreeKey.value = undefined;
        return;
      }
      if (state.selectRelation(relation)) hydrateSelectedRelation(relation.id);
      else hydrateSelectedRelation(relation.id);

      selectedTreeKey.value = reconcileSelectedKey(
        previousKey,
        metadataTreeKeys(metadataTreeNodes.value),
        metadataNodeKey(relation.id),
      );
      const relationKey = metadataNodeKey(relation.id);
      expandedTreeKeys.value = [...new Set([...expandedTreeKeys.value, relationKey])];
    }

    function metadataTreeKeys(nodes: UiTreeNode[]): string[] {
      return nodes.flatMap((node) => [node.key, ...metadataTreeKeys(node.children ?? [])]);
    }

    async function loadFieldSpecs() {
      try {
        state.handleFieldSpecsLoaded(await loadAllRecords('/platform.field_spec/query'));
        fieldSpecsReady.value = true;
      } catch (cause) {
        presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
      }
    }

    async function readCurrent(
      signal?: AbortSignal,
      commit: (accept: () => void) => void = (accept) => accept(),
    ) {
      requireValid();
      if (
        (submissionStatus.value !== 'unknown' && !committedNeedsReload.value) ||
        saving.value ||
        loading.value ||
        readingCurrent.value ||
        !submittedCandidate
      )
        throw new OperationUsageError('当前没有需要核实的元数据提交。');
      const revision = metadataCandidateRevision.value;
      const scopeIsCurrent = options.confirmationScope ?? (() => true);
      if (!scopeIsCurrent()) throw new OperationUsageError('编辑范围已变化，请重新选择模块。');
      readingCurrent.value = true;
      try {
        await loadWorkspace(
          (accept) =>
            commit(() => {
              if (metadataCandidateRevision.value !== revision || !scopeIsCurrent() || signal?.aborted)
                throw new OperationUsageError('候选或编辑范围已变化，原候选已保留；请重新读取。');
              accept();
            }),
          true,
          signal,
        );
      } finally {
        if (valid()) readingCurrent.value = false;
      }
    }

    function hydrateSelectedRelation(relationId: string) {
      state.handleFieldsLoaded(fieldsByRelation.value[relationId] ?? []);
      fieldProperties.value = fieldPropertiesByRelation.value[relationId] ?? [];
      capabilitySnapshot.value = capabilitiesByRelation.value[relationId];
    }

    async function selectMetadataTreeNode(node: UiTreeNode) {
      if (
        submissionStatus.value === 'unknown' ||
        committedNeedsReload.value ||
        state.fieldEditorOpen.value ||
        fieldPlanActive.value
      ) {
        presentPlatformMessage('请先保存或取消当前字段候选，再切换元数据。', {
          source: 'metadata-orchestration',
          phase: 'validation',
        });
        return;
      }
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
      requireKnownSubmission();
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

    function prepareCapabilityDraft(input: { capability: string; selected: boolean }) {
      requireValid();
      const relationId = selectedRelationId.value;
      const fact = capabilityItems.value.find((item) => item.capability === input.capability);
      if (
        !relationId ||
        !fact ||
        !fact.configurable ||
        fact.enabled ||
        saving.value ||
        loading.value ||
        state.fieldEditorOpen.value ||
        sorting.value
      )
        throw new OperationUsageError('请先完成当前编辑，并选择可配置的基础能力；已启用能力不能在此关闭。');
      return () => {
        requireValid();
        if (!editSession.editing.value) startNodeEditSession();
        editSession.stageCapability(relationId, input.capability, input.selected);
        fieldPlanActive.value = true;
        selectedTreeKey.value = metadataNodeKey(relationId);
        return { relationId, capability: input.capability, selected: input.selected, saved: false };
      };
    }

    function selectCapability(capability: string, selected: boolean) {
      try {
        prepareCapabilityDraft({ capability, selected })();
      } catch (cause) {
        presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'validation' });
      }
    }

    function startCreateField(kind: MetadataFieldPropertyDraft['kind'] = 'BASIC') {
      fieldTitleManuallyEdited.value = false;
      fieldNameManuallyEdited.value = false;
      columnNameManuallyEdited.value = false;
      editorMode.value = 'SIMPLE';
      referenceSearch.value = '';
      dictionarySearch.value = '';
      stagedNewFieldKey.value = undefined;
      if (!fieldPlanActive.value) startNodeEditSession();
      state.startCreateField(kind);
    }

    function prepareAssistantNewFieldDraft(input: AddMetadataFieldDraftInput) {
      const relationId = selectedRelationId.value;
      if (!relationId || !state.selectedMetadata.value?.id)
        throw new OperationUsageError('No metadata relation is selected');
      if (state.fieldEditorOpen.value || sorting.value)
        throw new OperationUsageError(
          'Finish or cancel the current metadata editor before adding another field',
        );
      requireEnabledFieldSpec(input.fieldSpecAlias);
      const fieldName =
        input.fieldName?.trim() || generatedBusinessFieldName(input.title, 'BASIC', input.titleField);
      validateAssistantNewFieldName(relationId, fieldName);
      const field: MetadataField = {
        fieldName,
        columnName: physicalNameOf(fieldName),
        title: input.title,
        fieldSpecAlias: input.fieldSpecAlias,
        fieldOwnership: 'BUSINESS',
        fieldForm: 'PHYSICAL',
        required: input.required ?? false,
        uniqueField: input.unique ?? false,
        indexed: input.indexed ?? false,
        sortableField: input.sortable ?? false,
        titleField: input.titleField ?? false,
        enabled: true,
      };
      requireRecordNameField(field);
      return () => {
        if (!editSession.editing.value) startNodeEditSession();
        const property: MetadataFieldPropertyDraft = {
          kind: 'BASIC',
          ...(input.defaultValue !== undefined ? { fixedDefault: { value: input.defaultValue } } : {}),
        };
        editSession.stageField(relationId, field, property);
        stagedNewFieldKey.value = fieldName;
        fieldTitleManuallyEdited.value = true;
        fieldNameManuallyEdited.value = Boolean(input.fieldName);
        columnNameManuallyEdited.value = false;
        editorMode.value = 'SIMPLE';
        state.startEditField(field, property);
        return {
          relationId,
          fieldName,
          columnName: field.columnName!,
          title: input.title,
          fieldSpecAlias: input.fieldSpecAlias,
        };
      };
    }

    function assistantCandidate(): MetadataFieldCandidate | undefined {
      const relationId = selectedRelationId.value;
      if (
        !relationId ||
        !state.fieldEditorOpen.value ||
        childNodeType.value === 'CHILD_METADATA' ||
        sorting.value ||
        saving.value
      )
        return undefined;
      const field = normalizeFieldDraft(state.fieldDraft.value);
      const property = normalizeFieldPropertyDraft(state.fieldPropertyDraft.value);
      const baseline = field.id ? state.allFields.value.find((item) => item.id === field.id) : undefined;
      const baselineProperty = baseline
        ? propertyDraftFromSummary(
            fieldProperties.value.find((item) => item.fieldId === baseline.id) ?? {
              fieldId: baseline.id,
              kind: 'BASIC',
            },
          )
        : undefined;
      const project = (
        value: MetadataField,
        binding?: MetadataFieldPropertyDraft,
      ): Record<string, string | boolean | undefined> => ({
        显示名称: value.title,
        字段名: value.fieldName,
        物理列名: value.columnName,
        字段规格: value.fieldSpecAlias,
        必填: value.required ?? false,
        唯一: value.uniqueField ?? false,
        索引: value.indexed ?? false,
        允许排序: value.sortableField ?? false,
        标题字段: value.titleField ?? false,
        启用: value.enabled !== false,
        引用目标: binding?.referenceConfig?.targetModuleAlias,
        选择回填: binding?.referenceConfig?.affectMappings?.join('；'),
        字典应用: binding?.dictionaryConfig?.dictionaryApplicationAlias,
        字典类别: binding?.dictionaryConfig?.dictionaryCategoryAlias,
        字典选择: binding?.dictionaryConfig?.selectionMode,
        启用限制: binding?.referenceConfig?.requireEnabled,
        固定默认值: binding?.fixedDefault?.value ?? undefined,
      });
      const before = baseline ? project(baseline, baselineProperty) : {};
      const after = project(field, property);
      return {
        fieldName: field.fieldName ?? '',
        kind: property.kind,
        operation: baseline ? 'UPDATE' : 'ADD',
        saved: false,
        editable:
          ['BASIC', 'MODULE_REFERENCE'].includes(property.kind) &&
          Boolean(field.fieldName && isPlatformFieldName(field.fieldName)) &&
          (!baseline || fieldEditableInSession(baseline)),
        expectedMetadataVersion: editSession.relation(relationId)?.expectedMetadataVersion ?? 0,
        changes: Object.entries(after)
          .filter(([key, value]) => value !== before[key])
          .map(([key, value]) => ({ property: key, before: before[key], after: value })),
      };
    }

    function assistantProposal(): MetadataModelChangeSetProposal | undefined {
      if (!state.fieldEditorOpen.value) return editSession.buildProposal();
      const relationId = selectedRelationId.value;
      if (!relationId || childNodeType.value === 'CHILD_METADATA') return undefined;
      const field = normalizeFieldDraft(state.fieldDraft.value);
      const property = normalizeFieldPropertyDraft(state.fieldPropertyDraft.value);
      if (!isValidFieldDraft(field) || !isValidFieldPropertyDraft(property) || planFieldConflicts(field))
        return undefined;
      return editSession.proposalWithField(relationId, field, property, stagedNewFieldKey.value);
    }

    function assistantEditableBasicFieldNames() {
      const relationId = selectedRelationId.value;
      if (!relationId) return [];
      if (state.fieldEditorOpen.value) {
        const candidate = assistantCandidate();
        return candidate?.editable && candidate.kind === 'BASIC' ? [candidate.fieldName] : [];
      }
      return editSession
        .fieldsForDisplay(relationId, state.allFields.value)
        .filter(
          (field) =>
            Boolean(field.fieldName) &&
            fieldEditableInSession(field) &&
            fieldPropertyOf(field).kind === 'BASIC',
        )
        .map((field) => field.fieldName!);
    }

    function prepareAssistantFieldUpdate(input: UpdateMetadataFieldDraftInput) {
      const relationId = selectedRelationId.value;
      if (!relationId) throw new OperationUsageError('No metadata relation is selected');
      const revising = state.fieldEditorOpen.value;
      const candidate = assistantCandidate();
      if (
        sorting.value ||
        saving.value ||
        (revising &&
          (!candidate?.editable || candidate.kind !== 'BASIC' || candidate.fieldName !== input.fieldName))
      )
        throw new OperationUsageError(
          'Only the current editable candidate can be revised',
          'PRECONDITION_FAILED',
        );
      const field = revising
        ? normalizeFieldDraft(state.fieldDraft.value)
        : editSession
            .fieldsForDisplay(relationId, state.allFields.value)
            .find((item) => item.fieldName === input.fieldName);
      if (
        !field ||
        (!revising && (!fieldEditableInSession(field) || fieldPropertyOf(field).kind !== 'BASIC'))
      )
        throw new OperationUsageError('The selected metadata field is unavailable for editing');
      if (input.fieldSpecAlias) {
        const options = selectedRelationHasBusinessRecords.value
          ? dataSafeFieldSpecOptions(state.fieldSpecs.value, field.fieldSpecAlias)
          : state.fieldSpecOptions.value;
        if (!options.some((option) => option.value === input.fieldSpecAlias))
          throw new OperationUsageError(
            'The selected field specification is unsafe for the current metadata data',
          );
      }
      const updated: MetadataField = {
        ...field,
        ...(input.title !== undefined ? { title: input.title } : {}),
        ...(input.fieldSpecAlias !== undefined ? { fieldSpecAlias: input.fieldSpecAlias } : {}),
        ...(input.required !== undefined ? { required: input.required } : {}),
        ...(input.unique !== undefined ? { uniqueField: input.unique } : {}),
        ...(input.indexed !== undefined ? { indexed: input.indexed } : {}),
        ...(input.sortable !== undefined ? { sortableField: input.sortable } : {}),
        ...(input.titleField !== undefined ? { titleField: input.titleField } : {}),
        ...(input.enabled !== undefined ? { enabled: input.enabled } : {}),
      };
      requireRecordNameField(updated);
      const property = revising
        ? normalizeFieldPropertyDraft(state.fieldPropertyDraft.value)
        : normalizeFieldPropertyDraft(fieldPropertyOf(field));
      const previousProperty = JSON.stringify(property);
      if (input.defaultValue !== undefined) {
        const fact = fieldProperties.value.find((item) => item.fieldId === field.id)?.fixedDefault;
        if (fact?.editable === false) throw new OperationUsageError('该字段的初值只能读取，不能在此修改。');
        property.fixedDefault = { ...property.fixedDefault, value: input.defaultValue };
      }
      if (JSON.stringify(updated) === JSON.stringify(field) && JSON.stringify(property) === previousProperty)
        throw new OperationUsageError(
          'The requested metadata field update does not change the current value',
        );
      return () => {
        if (fieldPlanActive.value && !revising) {
          editSession.stageField(relationId, updated, property, field.fieldName);
          return {
            relationId,
            fieldName: updated.fieldName!,
            title: updated.title,
            fieldSpecAlias: updated.fieldSpecAlias,
          };
        }
        if (!revising) startNodeEditSession();
        if (!fieldPlanActive.value)
          editSession.stageField(relationId, updated, property, stagedNewFieldKey.value);
        if (!fieldPlanActive.value) stagedNewFieldKey.value = updated.id ? undefined : updated.fieldName;
        fieldTitleManuallyEdited.value = Boolean(updated.title?.trim());
        fieldNameManuallyEdited.value = true;
        columnNameManuallyEdited.value = true;
        if (!revising) editorMode.value = 'SIMPLE';
        state.startEditField(updated, property);
        return {
          relationId,
          fieldName: updated.fieldName!,
          title: updated.title,
          fieldSpecAlias: updated.fieldSpecAlias,
        };
      };
    }

    async function findAssistantFieldTargets(input: FindMetadataFieldTargetsInput, signal: AbortSignal) {
      const relationId = selectedRelationId.value;
      if (!relationId) throw new Error('No metadata relation is selected');
      const keyword = input.keyword?.trim().toLowerCase() ?? '';
      const targets =
        input.kind === 'MODULE_REFERENCE'
          ? await loadAssistantReferenceTargets(relationId, signal)
          : await loadAssistantDictionaryTargets(signal);
      const matches = targets
        .filter((item) => !keyword || `${item.title ?? ''} ${item.target}`.toLowerCase().includes(keyword))
        .sort((left, right) => left.target.localeCompare(right.target));
      return { targets: matches.slice(0, 30), truncated: matches.length > 30 };
    }

    function assistantEditableReferenceFieldNames() {
      const relationId = selectedRelationId.value;
      if (!relationId || sorting.value || saving.value || loading.value) return [];
      if (state.fieldEditorOpen.value) {
        const candidate = assistantCandidate();
        return candidate?.editable && candidate.kind === 'MODULE_REFERENCE' ? [candidate.fieldName] : [];
      }
      return editSession
        .fieldsForDisplay(relationId, state.allFields.value)
        .filter(
          (field) =>
            field.fieldName &&
            fieldEditableInSession(field) &&
            fieldPropertyOf(field).kind === 'MODULE_REFERENCE',
        )
        .map((field) => field.fieldName!);
    }

    async function prepareAssistantReferenceUpdate(
      input: UpdateMetadataReferenceDraftInput,
      signal: AbortSignal,
    ) {
      requireValid();
      const relationId = selectedRelationId.value;
      if (!relationId || !assistantEditableReferenceFieldNames().includes(input.fieldName))
        throw new OperationUsageError('请选择当前可编辑的模块引用字段。');
      const revising = state.fieldEditorOpen.value;
      const currentField = () =>
        revising
          ? normalizeFieldDraft(state.fieldDraft.value)
          : editSession
              .fieldsForDisplay(relationId, state.allFields.value)
              .find((field) => field.fieldName === input.fieldName);
      const field = currentField()!;
      const currentProperty = () =>
        normalizeFieldPropertyDraft(
          revising ? state.fieldPropertyDraft.value : fieldPropertyOf(currentField()!),
        );
      const property = currentProperty();
      const before = JSON.stringify({ field, property });
      const reference = property.referenceConfig!;
      const updated = normalizeFieldPropertyDraft(property);
      if (input.affectMappings !== undefined) {
        const catalog = await requestReferenceTargetFieldCatalog(
          relationId,
          reference.targetModuleAlias!,
          reference.targetMetadataId,
          signal,
        );
        const destinations = new Set<string>();
        for (const mapping of input.affectMappings) {
          const [source, destination] = mapping.split(':');
          if (
            !catalog.labelFields.some((item) => item.fieldName === source && item.selectable) ||
            destination === field.fieldName ||
            !savedReferenceAffectTargetOptions.value.some((item) => item.value === destination) ||
            destinations.has(destination!)
          )
            throw new OperationUsageError('请选择目录中的来源字段和不同的已保存普通字段。');
          destinations.add(destination!);
        }
        updated.referenceConfig!.affectMappings = [...input.affectMappings];
      }
      if (input.requireEnabled !== undefined) updated.referenceConfig!.requireEnabled = input.requireEnabled;
      if (JSON.stringify(updated) === JSON.stringify(property))
        throw new OperationUsageError('引用设置与当前内容一致，无需修改。');
      return () => {
        requireValid();
        if (
          selectedRelationId.value !== relationId ||
          state.fieldEditorOpen.value !== revising ||
          !assistantEditableReferenceFieldNames().includes(input.fieldName) ||
          JSON.stringify({ field: currentField(), property: currentProperty() }) !== before
        )
          throw new OperationUsageError('引用草稿已变化，请重新审阅后修改。');
        if (fieldPlanActive.value && !revising) {
          editSession.stageField(relationId, field, updated, field.fieldName);
          return { relationId, fieldName: input.fieldName, reference: updated.referenceConfig, saved: false };
        }
        if (!revising) startNodeEditSession();
        if (!fieldPlanActive.value) {
          editSession.stageField(relationId, field, updated, stagedNewFieldKey.value);
          stagedNewFieldKey.value = field.id ? undefined : field.fieldName;
        }
        fieldTitleManuallyEdited.value = Boolean(field.title?.trim());
        fieldNameManuallyEdited.value = true;
        columnNameManuallyEdited.value = true;
        if (!revising) editorMode.value = 'SIMPLE';
        state.startEditField(field, updated);
        return { relationId, fieldName: input.fieldName, reference: updated.referenceConfig, saved: false };
      };
    }

    async function loadAssistantReferenceTargets(relationId: string, signal: AbortSignal) {
      const targets = await moduleContext.http.request<Array<{ alias: string; title?: string }>>({
        method: 'GET',
        path: relationPath(`/${encodeURIComponent(relationId)}/reference-target-modules`),
        signal,
      });
      return targets.map((item) => ({ target: item.alias, title: item.title }));
    }

    async function loadAssistantDictionaryTargets(signal: AbortSignal) {
      const categories = await loadAllRecords<DictionaryCategory>(
        '/platform.dictionary_category/query',
        signal,
      );
      return [
        ...new Map(
          categories
            .filter(
              (item) =>
                item.categoryKind?.toUpperCase() === 'DICTIONARY' && item.applicationAlias && item.alias,
            )
            .map((item) => {
              const target = `${item.applicationAlias}.${item.alias}`;
              return [target, { target, title: item.title }] as const;
            }),
        ).values(),
      ];
    }

    async function referenceAffectDirectory(target: string, signal: AbortSignal) {
      const relationId = selectedRelationId.value;
      if (!relationId) throw new OperationUsageError('请先选择元数据节点。');
      const targets = await loadAssistantReferenceTargets(relationId, signal);
      if (!targets.some((item) => item.target === target)) throw new OperationUsageError('引用目标不可用。');
      const catalog = await requestReferenceTargetFieldCatalog(relationId, target, undefined, signal);
      return {
        sources: catalog.labelFields
          .filter((field) => field.selectable)
          .map(({ fieldName, title }) => ({ fieldName, title })),
        destinations: savedReferenceAffectTargetOptions.value.map(({ value, label }) => ({
          fieldName: value,
          title: label,
        })),
      };
    }

    async function prepareAssistantPropertyFieldDraft(
      input: AddMetadataPropertyFieldDraftInput,
      signal: AbortSignal,
    ): Promise<PreparedMetadataPropertyFieldDraft> {
      const relationId = selectedRelationId.value;
      if (!relationId) throw new Error('No metadata relation is selected');
      if (state.fieldEditorOpen.value || sorting.value)
        throw new Error('Finish or cancel the current metadata editor before adding another field');
      const fieldName = input.fieldName?.trim() || generatedBusinessFieldName(input.title, input.kind);
      validateAssistantNewFieldName(relationId, fieldName);
      if (input.kind === 'MODULE_REFERENCE') {
        const targets = await loadAssistantReferenceTargets(relationId, signal);
        if (!targets.some((item) => item.target === input.target))
          throw new Error('The requested reference target is unavailable');
        const catalog = await requestReferenceTargetFieldCatalog(relationId, input.target, undefined, signal);
        const targetKeyField = defaultCandidateField(catalog.keyFields);
        const targetLabelField = defaultCandidateField(catalog.labelFields);
        if (!targetKeyField || !targetLabelField)
          throw new Error('The reference target does not expose selectable key and label fields');
        const destinations = new Set<string>();
        for (const mapping of input.affectMappings ?? []) {
          const [source, destination] = mapping.split(':');
          if (
            !catalog.labelFields.some((field) => field.fieldName === source && field.selectable) ||
            destination === fieldName ||
            !savedReferenceAffectTargetOptions.value.some((field) => field.value === destination) ||
            destinations.has(destination!)
          )
            throw new OperationUsageError('请选择目录中的来源字段和不同的已保存普通字段。');
          destinations.add(destination!);
        }
        requireEnabledFieldSpec('string');
        return {
          relationId,
          kind: input.kind,
          title: input.title,
          fieldName,
          columnName: physicalNameOf(fieldName),
          fieldSpecAlias: 'string',
          required: input.required ?? false,
          reference: {
            targetModuleAlias: input.target,
            targetMetadataId: catalog.targetMetadataId ?? undefined,
            targetKeyField,
            targetLabelField,
            affectMappings: [...(input.affectMappings ?? [])],
          },
        };
      }
      const dictionaries = await loadAssistantDictionaryTargets(signal);
      if (!dictionaries.some((item) => item.target === input.target))
        throw new Error('The requested dictionary target is unavailable');
      const separator = input.target.indexOf('.');
      if (separator <= 0 || separator === input.target.length - 1)
        throw new Error('The requested dictionary target is invalid');
      const selectionMode = input.selectionMode ?? 'SINGLE';
      const fieldSpecAlias = storageFieldSpecAliasOf(input.kind, selectionMode)!;
      requireEnabledFieldSpec(fieldSpecAlias);
      return {
        relationId,
        kind: input.kind,
        title: input.title,
        fieldName,
        columnName: physicalNameOf(fieldName),
        fieldSpecAlias,
        required: input.required ?? false,
        ...(input.defaultValue !== undefined ? { defaultValue: input.defaultValue } : {}),
        dictionary: {
          applicationAlias: input.target.slice(0, separator),
          categoryAlias: input.target.slice(separator + 1),
          selectionMode,
        },
      };
    }

    function propertyFieldEntry(prepared: PreparedMetadataPropertyFieldDraft) {
      const field: MetadataField = {
        fieldName: prepared.fieldName,
        columnName: prepared.columnName,
        title: prepared.title,
        fieldSpecAlias: prepared.fieldSpecAlias,
        fieldOwnership: 'BUSINESS',
        fieldForm: 'PHYSICAL',
        required: prepared.required,
        uniqueField: false,
        indexed: false,
        sortableField: false,
        titleField: false,
        enabled: true,
      };
      const property: MetadataFieldPropertyDraft =
        prepared.kind === 'MODULE_REFERENCE'
          ? {
              kind: prepared.kind,
              referenceConfig: {
                ...prepared.reference!,
                cardinality: 'ONE',
                targetUnavailablePolicy: 'PRESERVE_HISTORY',
                requireEnabled: false,
                projectionMappings: [],
              },
            }
          : {
              kind: prepared.kind,
              dictionaryConfig: {
                dictionaryApplicationAlias: prepared.dictionary!.applicationAlias,
                dictionaryCategoryAlias: prepared.dictionary!.categoryAlias,
                selectionMode: prepared.dictionary!.selectionMode,
              },
            };
      if (prepared.defaultValue !== undefined) property.fixedDefault = { value: prepared.defaultValue };
      return { field, property };
    }

    async function prepareAssistantFieldPlan(inputs: MetadataFieldPlanInput, signal: AbortSignal) {
      if (editSession.editing.value || state.fieldEditorOpen.value || sorting.value || saving.value)
        throw new Error('Finish the current candidate before preparing a field plan');
      const relationId = selectedRelationId.value;
      if (!relationId) throw new Error('No metadata relation is selected');
      const entries: Array<{ field: MetadataField; property: MetadataFieldPropertyDraft }> = [];
      for (const input of inputs) {
        if (input.kind !== 'BASIC') {
          entries.push(propertyFieldEntry(await prepareAssistantPropertyFieldDraft(input, signal)));
        } else {
          requireEnabledFieldSpec(input.fieldSpecAlias);
          const fieldName =
            input.fieldName || generatedBusinessFieldName(input.title, 'BASIC', input.titleField);
          validateAssistantNewFieldName(relationId, fieldName);
          entries.push({
            field: {
              fieldName,
              columnName: physicalNameOf(fieldName),
              title: input.title,
              fieldSpecAlias: input.fieldSpecAlias,
              fieldOwnership: 'BUSINESS',
              fieldForm: 'PHYSICAL',
              required: input.required ?? false,
              uniqueField: input.unique ?? false,
              indexed: input.indexed ?? false,
              sortableField: input.sortable ?? false,
              titleField: input.titleField ?? false,
              enabled: true,
            },
            property: {
              kind: 'BASIC',
              ...(input.defaultValue !== undefined ? { fixedDefault: { value: input.defaultValue } } : {}),
            },
          });
        }
      }
      const names = entries.map(({ field }) => field.fieldName!.toLowerCase());
      entries.forEach(({ field }) => requireRecordNameField(field));
      const columns = entries.map(({ field }) => field.columnName!.toLowerCase());
      if (new Set(names).size !== names.length || new Set(columns).size !== columns.length)
        throw new Error('方案包含重复字段名或物理列名，请调整后重新生成。');
      return () => {
        if (
          signal.aborted ||
          relationId !== selectedRelationId.value ||
          fieldPlanActive.value ||
          state.fieldEditorOpen.value ||
          sorting.value ||
          saving.value
        )
          throw new Error('Field plan is no longer current');
        for (const { field } of entries) validateAssistantNewFieldName(relationId, field.fieldName!);
        startNodeEditSession();
        editSession.stageFields(relationId, entries);
        fieldPlanActive.value = true;
        return { saved: false, relationId, fields: entries };
      };
    }

    function editPlanField(field: MetadataField) {
      if (saving.value) return;
      const property = fieldPropertyOf(field);
      stagedNewFieldKey.value = field.fieldName;
      childNodeType.value = 'FIELD';
      fieldTitleManuallyEdited.value = true;
      fieldNameManuallyEdited.value = true;
      columnNameManuallyEdited.value = true;
      editorMode.value = 'ADVANCED';
      state.startEditField(field, property);
    }

    function startFieldPlan() {
      if (saving.value || loading.value || dirty.value || sorting.value || !selectedRelationId.value) return;
      startNodeEditSession();
      fieldPlanActive.value = true;
    }

    function removePlanField(field: MetadataField) {
      try {
        prepareRemoveNewFieldDraft({ fieldName: field.fieldName ?? '' })();
      } catch (cause) {
        presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'validation' });
      }
    }

    function removableNewFieldNames() {
      if (
        !fieldPlanActive.value ||
        state.fieldEditorOpen.value ||
        saving.value ||
        loading.value ||
        sorting.value
      )
        return [];
      return fieldPlanEntries.value.flatMap((field) => (field.fieldName ? [field.fieldName] : []));
    }

    function prepareRemoveNewFieldDraft(input: { fieldName: string }) {
      requireValid();
      const relationId = selectedRelationId.value;
      if (!relationId || !removableNewFieldNames().includes(input.fieldName))
        throw new OperationUsageError('只能移除本次方案中未保存的新增字段，请先完成当前字段编辑。');
      return () => {
        requireValid();
        if (relationId !== selectedRelationId.value || !removableNewFieldNames().includes(input.fieldName))
          throw new OperationUsageError('字段方案已经变化，请核对当前草稿。');
        editSession.discardNewField(relationId, input.fieldName);
        return { relationId, fieldName: input.fieldName, removed: true, saved: false };
      };
    }

    function cancelFieldPlan() {
      requireKnownSubmission();
      if (saving.value) return;
      fieldPlanActive.value = false;
      cancelNodeEditor();
    }

    function prepareAssistantPropertyFieldCommit(prepared: PreparedMetadataPropertyFieldDraft) {
      const relationId = selectedRelationId.value;
      if (!relationId || relationId !== prepared.relationId)
        throw new OperationUsageError(
          'The selected metadata relation changed before the candidate could be opened',
        );
      if (state.fieldEditorOpen.value || sorting.value)
        throw new OperationUsageError(
          'Finish or cancel the current metadata editor before adding another field',
        );
      validateAssistantNewFieldName(relationId, prepared.fieldName);
      requireEnabledFieldSpec(prepared.fieldSpecAlias);
      const { field, property } = propertyFieldEntry(prepared);
      return () => {
        if (!editSession.editing.value) startNodeEditSession();
        editSession.stageField(relationId, field, property);
        stagedNewFieldKey.value = prepared.fieldName;
        fieldTitleManuallyEdited.value = true;
        fieldNameManuallyEdited.value = Boolean(prepared.fieldName);
        columnNameManuallyEdited.value = false;
        editorMode.value = 'ADVANCED';
        state.startEditField(field, property);
        return {
          relationId,
          kind: prepared.kind,
          fieldName: prepared.fieldName,
          columnName: prepared.columnName,
          title: prepared.title,
          fieldSpecAlias: prepared.fieldSpecAlias,
          target:
            prepared.kind === 'MODULE_REFERENCE'
              ? prepared.reference!.targetModuleAlias
              : `${prepared.dictionary!.applicationAlias}.${prepared.dictionary!.categoryAlias}`,
        };
      };
    }

    function validateAssistantNewFieldName(relationId: string, fieldName: string) {
      if (!isPlatformFieldName(fieldName))
        throw new OperationUsageError('The metadata field name is invalid');
      if ([...capabilityFieldNames.value].some((name) => name.toLowerCase() === fieldName.toLowerCase()))
        throw new OperationUsageError('该字段由基础能力管理，请选择对应能力，不要创建同名业务字段。');
      if (isDynamicRecordReservedFieldName(fieldName))
        throw new OperationUsageError('The metadata field name is reserved by the dynamic record protocol');
      if (
        editSession
          .fieldsForDisplay(relationId, state.allFields.value)
          .some((field) => field.fieldName?.toLowerCase() === fieldName.toLowerCase())
      )
        throw new OperationUsageError(
          `Metadata field “${fieldName}” already exists in the selected relation`,
        );
    }

    function requireRecordNameField(field: MetadataField) {
      const problem = recordNameFieldProblem(field);
      if (problem) throw new OperationUsageError(problem);
    }

    function requireEnabledFieldSpec(alias: string) {
      if (!state.fieldSpecs.value.some((spec) => spec.enabled !== false && (spec.alias ?? spec.id) === alias))
        throw new OperationUsageError(`The required metadata field specification is unavailable: ${alias}`);
    }

    function startCreateMainMetadata() {
      editorMode.value = 'SIMPLE';
      state.startCreateMain();
      mainMetadataDraft.value.alias = props.moduleAlias.split('.').at(-1) ?? props.moduleAlias;
      mainMetadataDraft.value.title = props.moduleTitle?.trim() || props.title?.trim() || props.moduleAlias;
    }

    function startCreateChildNode(kind = 'BASIC') {
      if (saving.value || loading.value || !fieldCreationItems.some((item) => item.key === kind)) return;
      childNodeType.value = 'FIELD';
      childAliasManuallyEdited.value = false;
      childValidationAttempted.value = false;
      childMetadataDraft.value = { alias: '', title: '' };
      startCreateField(kind as MetadataFieldPropertyDraft['kind']);
    }

    function startCreateChildMetadataNode() {
      childNodeType.value = 'CHILD_METADATA';
      childAliasManuallyEdited.value = false;
      childValidationAttempted.value = false;
      childMetadataDraft.value = { alias: '', title: '' };
      startCreateField();
    }

    function startEditField(field: MetadataField, property: MetadataFieldPropertyDraft) {
      fieldTitleManuallyEdited.value = Boolean(field.title?.trim());
      fieldNameManuallyEdited.value = true;
      columnNameManuallyEdited.value = true;
      editorMode.value = 'SIMPLE';
      referenceSearch.value = '';
      dictionarySearch.value = '';
      stagedNewFieldKey.value = undefined;
      startNodeEditSession();
      state.startEditField(field, property);
    }

    function cancelNodeEditor() {
      requireKnownSubmission();
      childNodeType.value = 'FIELD';
      stagedNewFieldKey.value = undefined;
      state.cancelEditor();
      if (fieldPlanActive.value) return;
      editSession.cancel();
      if (sorting.value) startNodeEditSession();
    }

    function toggleSorting() {
      requireKnownSubmission();
      if (saving.value || fieldPlanActive.value) return;
      if (sorting.value) {
        editSession.cancel();
        sorting.value = false;
      } else {
        startNodeEditSession();
        sorting.value = true;
      }
    }

    async function previewAndApply(
      operationName = '保存元数据',
      mode: 'confirm' | 'immediate-order' = 'confirm',
    ) {
      if (saving.value) return;
      try {
        requireKnownSubmission();
      } catch (cause) {
        presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
        return;
      }
      const moduleAlias = props.moduleAlias;
      const proposal = editSession.buildProposal();
      if (!proposal) {
        presentPlatformMessage('当前草稿包含首批不支持的删除操作；请取消编辑后重新调整。', {
          source: 'metadata-orchestration',
          phase: 'validation',
        });
        return;
      }
      const requireUnchangedCandidate = captureMetadataCandidate();
      let committed = false;
      saving.value = true;
      try {
        const submission = await prepareMetadataChangeSetSubmission(
          moduleContext.http,
          moduleAlias,
          proposal,
          requireUnchangedCandidate,
          undefined,
          Object.fromEntries(
            state.fieldSpecs.value.map((spec) => [spec.alias ?? '', spec.title || spec.alias || '']),
          ),
          Object.fromEntries(capabilityItems.value.map((fact) => [fact.capability, fact.title])),
        );
        if (
          mode === 'confirm' &&
          !(await confirmAction({
            title: `确认${operationName}`,
            content: submission.lines.join('\n') || '将保存当前配置。',
            details: { title: '查看详细配置和影响', lines: submission.details },
            okText: '保存',
          }))
        )
          return;
        await applyMetadataSubmission(submission, proposal);
        committed = true;
        await completeMetadataChange(proposal, moduleAlias, operationName, mode, requireUnchangedCandidate);
      } catch (cause) {
        if (cause instanceof MetadataChangeSetPrecheckError) {
          presentPlatformMessage(cause.message, { source: 'metadata-orchestration', phase: 'validation' });
          return;
        }
        presentPlatformError(cause, {
          source: 'metadata-orchestration',
          phase: committed ? 'load' : 'action',
        });
        if (committed)
          presentPlatformMessage(
            `${operationName}已保存，但生效状态或页面同步失败；请刷新核实，不要重复保存。`,
            {
              source: 'metadata-orchestration',
              phase: 'load',
            },
          );
      } finally {
        if (mode === 'immediate-order' && submissionStatus.value !== 'unknown' && !committedNeedsReload.value)
          startNodeEditSession();
        saving.value = false;
      }
    }

    async function completeMetadataChange(
      proposal: MetadataModelChangeSetProposal,
      moduleAlias: string,
      operationName: string,
      mode: 'confirm' | 'immediate-order',
      requireUnchangedCandidate: () => boolean,
    ) {
      if (valid()) options.onCommitted?.(moduleAlias);
      if (!requireUnchangedCandidate()) throw new Error('配置候选或基线已变化，请刷新核实。');
      if (mode === 'immediate-order') retainCommittedOrder(proposal);
      else {
        fieldPlanActive.value = false;
        editSession.cancel();
        state.cancelEditor();
      }
      const activationFeedback = await refreshActivation?.(moduleAlias);
      if (mode === 'immediate-order') {
        // The write is committed. Keep its order even if the subsequent read fails.
        try {
          await synchronizeOrder(proposal);
        } catch (cause) {
          presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
          presentPlatformMessage('排序已保存，但最新数据同步失败；请使用元数据的刷新操作重试。', {
            source: 'metadata-orchestration',
            phase: 'load',
          });
          return;
        }
      } else {
        await loadWorkspace();
        if (workspaceLoadFailed.value) throw new Error('最新元数据读取失败');
      }
      await handlePlatformActionSuccess(
        activationFeedback ?? { message: { text: `${operationName}已保存，生效状态待确认`, type: 'INFO' } },
        { source: 'metadata-orchestration' },
      );
      committedNeedsReload.value = false;
      submittedCandidate = undefined;
      submittedBaseline = undefined;
      return activationFeedback;
    }

    const metadataCandidateRevision = ref(0);
    watch(
      () => ({
        proposal: assistantProposal(),
        relationId: selectedRelationId.value,
        mode: state.mode.value,
        field: state.fieldDraft.value,
        property: state.fieldPropertyDraft.value,
        plan: fieldPlanActive.value,
        sorting: sorting.value,
        childNodeType: childNodeType.value,
        child: childMetadataDraft.value,
        submissionStatus: submissionStatus.value,
      }),
      () => metadataCandidateRevision.value++,
      { deep: true, flush: 'sync' },
    );

    function captureMetadataCandidate() {
      const revision = metadataCandidateRevision.value;
      const loadRevision = workspaceLoadRevision;
      const moduleAlias = props.moduleAlias;
      return () =>
        valid() &&
        props.moduleAlias === moduleAlias &&
        metadataCandidateRevision.value === revision &&
        workspaceLoadRevision === loadRevision &&
        workspaceReady.value &&
        !workspaceLoadFailed.value;
    }

    async function prepareAssistantMetadataConfirmation(signal: AbortSignal): Promise<OperationProposal> {
      requireKnownSubmission();
      if (saving.value || loading.value) throw new OperationUsageError('请等待元数据加载或保存完成');
      if (creatingChildMetadata.value) return prepareChildConfirmation(options.confirmationScope);
      const proposal = assistantProposal();
      if (!proposal) throw new OperationUsageError('请先完成有效的字段候选');
      const moduleAlias = props.moduleAlias;
      const captured = captureMetadataCandidate();
      const current = () => captured() && (options.confirmationScope?.() ?? true);
      const submission = await prepareMetadataChangeSetSubmission(
        moduleContext.http,
        moduleAlias,
        proposal,
        current,
        signal,
        Object.fromEntries(
          state.fieldSpecs.value.map((spec) => [spec.alias ?? '', spec.title || spec.alias || '']),
        ),
        Object.fromEntries(capabilityItems.value.map((fact) => [fact.capability, fact.title])),
      ).catch((cause: unknown) => {
        if (cause instanceof MetadataChangeSetPrecheckError) throw new OperationUsageError(cause.message);
        throw cause;
      });
      return {
        presentation: {
          title: '确认保存配置',
          lines: [
            title.value,
            ...submission.lines,
            '保存以上整组更改（含页面中修改的内容），不会创建业务记录。保存后会核实是否生效。',
          ],
          details: { title: '查看详细配置和影响', lines: submission.details },
        },
        confirmLabel: '确认保存配置',
        expiresAt: Date.now() + 10 * 60_000,
        isCurrent: () => current() && !saving.value,
        async execute() {
          if (!current() || saving.value) throw new OperationRejectedError('配置候选已变化，请重新预检。');
          saving.value = true;
          try {
            await applyMetadataSubmission(submission, proposal);
            try {
              const feedback = await completeMetadataChange(
                proposal,
                moduleAlias,
                '元数据',
                'confirm',
                current,
              );
              return {
                title: '元数据已保存',
                lines: [
                  feedback?.message.text || '配置已保存，运行态生效状态待核实。',
                  '本次保存不发布页面布局；如需展示或录入新增字段，请继续到页面配置中编排并保存生效。',
                ],
              };
            } catch (cause) {
              presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'load' });
              return {
                title: '元数据已保存，状态待核实',
                lines: ['保存已完成，但生效状态或页面同步失败；请刷新核实，不要重复保存。'],
              };
            }
          } finally {
            saving.value = false;
          }
        },
        async lookup() {
          if (submissionStatus.value === 'unknown') await readCurrent();
          return undefined;
        },
      };
    }

    /** Retain only the committed order; authoritative versions still come from the read API. */
    function retainCommittedOrder(proposal: MetadataModelChangeSetProposal) {
      for (const { relationIds } of proposal.relationOrders) {
        state.relations.value = state.relations.value.map((relation) => {
          const index = relationIds.indexOf(relation.id!);
          return index < 0 ? relation : { ...relation, sortOrder: index };
        });
      }
      const fields = { ...fieldsByRelation.value };
      for (const { relationId, fieldIds } of proposal.fieldOrders) {
        const byId = new Map((fields[relationId] ?? []).map((field) => [field.id, field]));
        fields[relationId] = fieldIds.flatMap((id, sortOrder) => {
          const field = byId.get(id);
          return field ? [{ ...field, sortOrder }] : [];
        });
      }
      fieldsByRelation.value = fields;
      if (selectedRelationId.value) hydrateSelectedRelation(selectedRelationId.value);
    }

    /** Refresh only records touched by ordering, keeping the tree and navigation state mounted. */
    async function synchronizeOrder(proposal: MetadataModelChangeSetProposal) {
      const [relations, fields] = await Promise.all([
        proposal.relationOrders.length > 0
          ? loadAllRecords<ModuleMetadataRelation>(relationPath('/query'))
          : Promise.resolve(undefined),
        Promise.all(
          proposal.fieldOrders.map(async ({ relationId }) => {
            const relation = state.relations.value.find((item) => item.id === relationId);
            if (!relation?.metadataId) throw new Error('排序节点已失效，请刷新元数据。');
            const records = await loadAllRecords<MetadataField>(
              `/platform.metadata/${encodeURIComponent(relation.metadataId)}/fields/query`,
            );
            return [relationId, records] as const;
          }),
        ),
      ]);
      if (relations) state.relations.value = relations;
      fieldsByRelation.value = { ...fieldsByRelation.value, ...Object.fromEntries(fields) };
      const selectedFields = fields.find(([relationId]) => relationId === selectedRelationId.value)?.[1];
      if (selectedFields) state.handleFieldsLoaded(selectedFields);
    }

    function planFieldConflicts(draft: MetadataField) {
      return (
        fieldPlanActive.value &&
        sessionFields.value.some(
          (field) =>
            field.fieldName !== stagedNewFieldKey.value &&
            (field.fieldName?.toLowerCase() === draft.fieldName?.toLowerCase() ||
              field.columnName?.toLowerCase() === draft.columnName?.toLowerCase()),
        )
      );
    }

    function stageFieldDraft() {
      if (!fieldDraft.value.id && childNodeType.value === 'CHILD_METADATA') {
        void createChildMetadata();
        return;
      }
      try {
        prepareFieldDraftCommit()();
      } catch (cause) {
        presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'validation' });
      }
    }

    function prepareRetainFieldDraft() {
      if (!fieldPlanActive.value || sorting.value)
        throw new OperationUsageError('请在共享字段方案中保留修改；单个字段保存仍需正式确认。');
      return prepareFieldDraftCommit();
    }

    function prepareFieldDraftCommit() {
      requireKnownSubmission();
      if (
        !state.fieldEditorOpen.value ||
        childNodeType.value === 'CHILD_METADATA' ||
        saving.value ||
        loading.value
      )
        throw new OperationUsageError('当前字段不可编辑，请核对当前页面状态。');
      if (!fieldDraft.value.title?.trim() && !fieldDraft.value.fieldName?.trim()) {
        throw new OperationUsageError('请填写显示名称');
      }
      const fieldName =
        state.fieldDraft.value.fieldName?.trim() ||
        generatedBusinessFieldName(
          state.fieldDraft.value.title,
          fieldPropertyEditorKind.value,
          state.fieldDraft.value.titleField,
        );
      const draft = normalizeFieldDraft({
        ...state.fieldDraft.value,
        fieldName,
        columnName: state.fieldDraft.value.columnName?.trim() || physicalNameOf(fieldName),
      });
      const property = normalizeFieldPropertyDraft(state.fieldPropertyDraft.value);
      requireRecordNameField(draft);
      if (draft.fieldName && isDynamicRecordReservedFieldName(draft.fieldName)) {
        throw new OperationUsageError('字段名称与动态记录协议保留字段冲突，请调整。');
      }
      if (!isValidFieldDraft(draft)) {
        throw new OperationUsageError('请填写字段名、物理列名和字段规格');
      }
      if (!isValidFieldPropertyDraft(property)) {
        throw new OperationUsageError(
          property.kind === 'MODULE_REFERENCE' ? '请配置目标模块。' : '请配置字典应用和类别。',
        );
      }
      if (property.kind === 'MODULE_REFERENCE' && referenceTargetFieldCatalogProblem.value) {
        throw new OperationUsageError(referenceTargetFieldCatalogProblem.value);
      }
      const relationId = selectedRelationId.value;
      if (!relationId) throw new OperationUsageError('请先选择元数据节点。');
      if (planFieldConflicts(draft)) {
        throw new OperationUsageError('字段名称或物理列与方案中的其他字段冲突。');
      }
      const snapshot = JSON.stringify([
        state.fieldDraft.value,
        state.fieldPropertyDraft.value,
        stagedNewFieldKey.value,
      ]);
      return () => {
        requireValid();
        if (
          saving.value ||
          loading.value ||
          !state.fieldEditorOpen.value ||
          relationId !== selectedRelationId.value ||
          snapshot !==
            JSON.stringify([state.fieldDraft.value, state.fieldPropertyDraft.value, stagedNewFieldKey.value])
        )
          throw new OperationUsageError('字段编辑已经变化，请核对当前草稿。');
        editSession.stageField(
          relationId,
          { ...draft, fieldOwnership: 'BUSINESS', fieldForm: 'PHYSICAL' },
          property,
          stagedNewFieldKey.value,
        );
        if (!draft.id) stagedNewFieldKey.value = draft.fieldName;
        if (fieldPlanActive.value) {
          state.cancelEditor();
          stagedNewFieldKey.value = undefined;
        } else {
          void previewAndApply('保存字段');
        }
        return { relationId, fieldName: draft.fieldName, retained: true, saved: false };
      };
    }

    function prepareAssistantMainDraft(input: { title: string }) {
      requireValid();
      if (saving.value || loading.value || !workspaceReady.value)
        throw new OperationUsageError('请先读取模块元数据');
      if (state.relations.value.length > 0)
        throw new OperationUsageError('模块已有元数据，请选择现有节点继续配置');
      if (dirty.value && !state.mainEditorOpen.value) throw new OperationUsageError('请先保存或放弃当前候选');
      return () => {
        if (!state.mainEditorOpen.value) startCreateMainMetadata();
        state.mainMetadataDraft.value.title = input.title;
        return assistantSummary().mainCandidate;
      };
    }

    function prepareAssistantChildDraft(input: { alias: string; title: string }) {
      requireValid();
      if (saving.value || loading.value || !workspaceReady.value || !selectedRelationId.value)
        throw new OperationUsageError('请先选择并读取父元数据');
      if (dirty.value && !creatingChildMetadata.value)
        throw new OperationUsageError('请先保存或放弃当前字段候选，再建设明细');
      if (state.relations.value.some((relation) => relation.relationAlias === input.alias))
        throw new OperationUsageError('此明细标识已存在，请选择现有明细继续配置');
      return () => {
        if (!creatingChildMetadata.value) startCreateChildMetadataNode();
        childAliasManuallyEdited.value = true;
        childMetadataDraft.value = { ...childMetadataDraft.value, ...input };
        return assistantSummary().childCandidate;
      };
    }

    function prepareChildConfirmation(scopeIsCurrent: () => boolean = () => true): OperationProposal {
      requireValid();
      const relationId = selectedRelationId.value;
      const draft = {
        alias: childMetadataDraft.value.alias.trim(),
        title: childMetadataDraft.value.title.trim(),
        schemaName: childMetadataDraft.value.schemaName?.trim() || undefined,
        tableName: childMetadataDraft.value.tableName?.trim() || undefined,
      };
      childValidationAttempted.value = true;
      if (
        !creatingChildMetadata.value ||
        !relationId ||
        childAliasError.value ||
        !draft.title ||
        draft.title.length > 120
      )
        throw new OperationUsageError(childAliasError.value ?? '请填写不超过 120 字的明细名称');
      if (state.relations.value.some((relation) => relation.relationAlias === draft.alias))
        throw new OperationUsageError('此明细标识已存在，请选择现有明细继续配置');
      const requestId = createUuid();
      const parentTreeKey = metadataNodeKey(relationId);
      const captured = captureMetadataCandidate();
      const current = () => captured() && scopeIsCurrent();
      async function completeCreation(createdRelationId: string | undefined) {
        if (valid()) options.onCommitted?.(props.moduleAlias);
        try {
          if (!current()) throw new Error('编辑上下文已变化');
          state.cancelEditor();
          childNodeType.value = 'FIELD';
          editSession.cancel();
          await loadWorkspace();
          if (!valid() || workspaceLoadFailed.value) throw new Error('最新元数据读取失败或上下文已失效');
          if (!scopeIsCurrent()) throw new Error('编辑上下文已变化');
          state.focusRelation(createdRelationId);
          if (createdRelationId) {
            hydrateSelectedRelation(createdRelationId);
            selectedTreeKey.value = metadataNodeKey(createdRelationId);
            expandedTreeKeys.value = [...new Set([...expandedTreeKeys.value, parentTreeKey])];
          }
          const feedback = await refreshActivation?.(props.moduleAlias);
          return {
            title: '明细已建立',
            lines: [
              feedback?.message.text ?? '配置已保存，运行态生效状态待核实。',
              '接下来配置明细字段、引用、计算规则和页面。',
            ],
          };
        } catch {
          return {
            title: '明细已建立，状态待核实',
            lines: ['保存已完成，但页面或生效状态未同步；请重新读取元数据，不要重复创建。'],
          };
        }
      }
      return {
        receiptReference: { kind: 'child-metadata', moduleAlias: props.moduleAlias, relationId, requestId },
        presentation: {
          title: `确认建立明细：${draft.title}`,
          lines: [
            `所属登记表：${state.selectedMetadata.value?.title ?? title.value}`,
            '一张记录可以填写多行明细，明细随所属记录一起保存。',
            '本次只建立空明细及其关联；要填的内容、关联对象、计算规则和页面仍需继续配置。',
          ],
          details: {
            title: '查看配置影响',
            lines: [
              `模块：${props.moduleAlias}；明细标识：${draft.alias}`,
              '通过标准元数据入口创建物理表和父关联字段，不录入业务数据。',
              ...(draft.schemaName ? [`存储 schema：${draft.schemaName}`] : []),
              ...(draft.tableName ? [`存储表：${draft.tableName}`] : []),
            ],
          },
        },
        confirmLabel: '确认建立明细',
        expiresAt: Date.now() + 10 * 60_000,
        isCurrent: () => current() && !saving.value && !loading.value,
        async execute() {
          if (!current() || saving.value || loading.value)
            throw new OperationRejectedError('明细候选或编辑上下文已变化，请重新确认');
          saving.value = true;
          try {
            const result = await moduleContext.http
              .request<CreationResult>({
                method: 'POST',
                path: relationPath(`/${encodeURIComponent(relationId)}/create-child-metadata`),
                body: { ...draft, requestId },
              })
              .catch((cause: unknown) => {
                if (cause instanceof AppError && [400, 401, 403, 404, 409, 422].includes(cause.status ?? 0))
                  throw new OperationRejectedError(cause.message);
                throw cause;
              });
            return await completeCreation(result.relation.id);
          } finally {
            saving.value = false;
          }
        },
        async lookup() {
          requireValid();
          if (saving.value || loading.value) throw new Error('请等待当前元数据操作完成再查询结果');
          saving.value = true;
          try {
            const receipt = await moduleContext.http.request<
              { metadataId: string; relationId: string } | undefined
            >({
              method: 'GET',
              path: relationPath(
                `/${encodeURIComponent(relationId)}/child-metadata-creations/${encodeURIComponent(requestId)}`,
              ),
            });
            if (!receipt) return undefined;
            return await completeCreation(receipt.relationId);
          } finally {
            saving.value = false;
          }
        },
      };
    }

    let manualChildCreation: OperationConfirmation | undefined;
    async function createChildMetadata() {
      if (saving.value || loading.value) return;
      try {
        requireValid();
        if (manualChildCreation?.state === 'unknown') {
          await manualChildCreation.check();
        } else {
          manualChildCreation = createOperationConfirmation(prepareChildConfirmation(), valid);
          await manualChildCreation.confirm();
        }
        if (!valid()) return;
        if (manualChildCreation.state !== 'succeeded') {
          presentPlatformMessage(
            manualChildCreation.state === 'unknown'
              ? '明细创建结果尚未确定。再次点击保存只查询本次结果，不会重复创建；请勿重新提交同一明细。'
              : manualChildCreation.result?.lines.join('；') || '明细候选已变化，请重新检查后保存。',
            { source: 'metadata-orchestration', phase: 'action' },
          );
          return;
        }
        const result = manualChildCreation.result!;
        manualChildCreation = undefined;
        await handlePlatformActionSuccess(
          { success: true, message: { text: [result.title, ...result.lines].join('；'), type: 'INFO' } },
          { source: 'metadata-orchestration' },
        );
      } catch (cause) {
        presentPlatformError(cause, { source: 'metadata-orchestration', phase: 'action' });
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
          const parentMetadataId = state.selectedRelation.value?.parentMetadataId;
          state.handleRelationsLoaded(state.relations.value.filter((relation) => relation.id !== relationId));
          const parent = state.relations.value.find((relation) => relation.metadataId === parentMetadataId);
          if (parent?.id) state.focusRelation(parent.id);
          for (const map of [
            fieldsByRelation,
            fieldPropertiesByRelation,
            capabilitiesByRelation,
            recordCountsByRelation,
          ]) {
            const remaining = { ...map.value };
            delete remaining[relationId];
            map.value = remaining as typeof map.value;
          }
          editSession.cancel();
          state.cancelEditor();
          const remainingRelation = state.selectedRelation.value;
          selectedTreeKey.value = remainingRelation?.id ? metadataNodeKey(remainingRelation.id) : undefined;
          if (remainingRelation?.id) hydrateSelectedRelation(remainingRelation.id);
          if (sorting.value) startNodeEditSession();
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
        const catalog = await requestReferenceTargetFieldCatalog(
          relationId,
          requestedTarget,
          targetMetadataId,
        );
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
        reference.targetMetadataId = catalog.targetMetadataId ?? undefined;
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

    function requestReferenceTargetFieldCatalog(
      relationId: string,
      targetModuleAlias: string,
      targetMetadataId?: string,
      signal?: AbortSignal,
    ) {
      const query = new URLSearchParams({ targetModuleAlias: targetModuleAlias.trim() });
      if (targetMetadataId?.trim()) query.set('targetMetadataId', targetMetadataId.trim());
      return moduleContext.http.request<ReferenceTargetFieldCatalog>({
        method: 'GET',
        path: relationPath(
          `/${encodeURIComponent(relationId)}/reference-target-field-catalog?${query.toString()}`,
        ),
        signal,
      });
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
      return candidates.find((candidate) => candidate.defaultField && candidate.selectable !== false)
        ?.fieldName;
    }

    function candidateLabel(candidate: ReferenceTargetFieldCandidate): string {
      return candidate.title ? `${candidate.title}（${candidate.fieldName}）` : candidate.fieldName;
    }

    async function createMainMetadata() {
      const draft = normalizeMainMetadataDraft(state.mainMetadataDraft.value);
      if (!isValidMainMetadataDraft(draft)) {
        presentPlatformMessage('请填写元数据标识（alias）和名称', {
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
          { success: true, message: '主元数据已创建' },
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

    async function loadAllRecords<T>(path: string, signal?: AbortSignal): Promise<T[]> {
      const records: T[] = [];
      for (let pageNum = 1; ; pageNum += 1) {
        const response = await moduleContext.http.request<WebPageResponse<T>>({
          method: 'POST',
          path,
          body: { page: { pageNum, pageSize: ORCHESTRATION_QUERY_PAGE_SIZE } },
          signal,
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

    function fieldDefaultValue(field: MetadataField, property: MetadataFieldPropertyDraft): string | null {
      if (property.fixedDefault) return property.fixedDefault.value;
      return (
        fieldProperties.value.find((item) => item.fieldId === field.id || item.fieldName === field.fieldName)
          ?.fixedDefault?.value ?? null
      );
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
          PLATFORM_SYSTEM: '平台系统字段，不能在元数据会话中修改。',
          RELATION_FOREIGN_KEY: '由元数据关系维护，不能作为独立字段修改。',
        }[kind] ?? undefined
      );
    }

    function fieldSortableInTree(_relation: ModuleMetadataRelation, field: MetadataField): boolean {
      return Boolean(field.id);
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
      return (
        sorting.value &&
        submissionStatus.value !== 'unknown' &&
        !committedNeedsReload.value &&
        !saving.value &&
        !loading.value &&
        !state.fieldEditorOpen.value &&
        (node as MetadataModelTreeNode).draggable === true
      );
    }

    function allowMetadataModelDrop(event: UiTreeDropEvent) {
      return (
        sorting.value && !saving.value && !state.fieldEditorOpen.value && canReorderMetadataModelTree(event)
      );
    }

    function handleMetadataModelDrop(event: UiTreeDropEvent) {
      if (submissionStatus.value === 'unknown' || committedNeedsReload.value) return;
      if (event.target.kind !== 'node') return;
      if (!allowMetadataModelDrop(event)) return;
      const drag = event.source.node as MetadataModelTreeNode;
      const drop = event.target.node as MetadataModelTreeNode;
      if (!drag.relationId || !drop.relationId || event.target.position === 'inside') return;
      if (drag.modelKind === 'FIELD') {
        const relationId = drag.relationId;
        const relation = state.relations.value.find((item) => item.id === relationId);
        if (!relation) return;
        const fields = editSession
          .fieldsForDisplay(relationId, fieldsByRelation.value[relationId] ?? [])
          .filter((field) => fieldSortableInTree(relation, field));
        const order = reorderedIds(
          visibleFields(fields),
          drag.fieldId!,
          drop.fieldId!,
          event.target.position === 'before' ? -1 : 1,
        );
        const visibleIds = new Set(order);
        let position = 0;
        editSession.stageFieldOrder(
          relationId,
          fields.map((field) => (visibleIds.has(field.id!) ? order[position++]! : field.id!)),
        );
        if (editSession.isDirty.value) void previewAndApply('排序', 'immediate-order');
        return;
      }
      const relation = state.relations.value.find((item) => item.id === drag.relationId);
      const siblings = state.relations.value
        .filter((item) => item.parentMetadataId === relation?.parentMetadataId)
        .sort((left, right) => {
          const staged = editSession.relationOrder.value[relation?.parentMetadataId ?? ''] ?? [];
          return staged.indexOf(left.id!) - staged.indexOf(right.id!);
        });
      const order = reorderedIds(
        siblings,
        drag.relationId,
        drop.relationId,
        event.target.position === 'before' ? -1 : 1,
      );
      editSession.stageRelationOrder(relation?.parentMetadataId, order);
      if (editSession.isDirty.value) void previewAndApply('排序', 'immediate-order');
    }

    function capabilityTitleOf(capability: string): string {
      return (
        {
          TREE: '树结构',
          SORT: '排序',
          REFERENCE: '引用标题',
          ENABLE: '启停',
          RECYCLE_BIN: '回收站',
          DATA_SCOPE: '数据权限',
          APPROVAL: '审批',
        }[capability] ?? capability
      );
    }

    const contextRevision = ref(0);
    watch(
      () => ({
        summary: assistantSummary(),
        proposal: assistantProposal(),
        candidate: assistantCandidate(),
        field: state.fieldDraft.value,
        property: state.fieldPropertyDraft.value,
      }),
      () => contextRevision.value++,
      { deep: true, flush: 'sync' },
    );
    const adapter: MetadataGovernanceEditor = {
      prepareMainDraft: prepareAssistantMainDraft,
      prepareChildDraft: prepareAssistantChildDraft,
      discardCandidate() {
        requireKnownSubmission();
        if (saving.value) throw new OperationUsageError('配置正在保存，请等待完成');
        sorting.value = false;
        cancelFieldPlan();
      },
      summary: assistantSummary,
      candidate: assistantCandidate,
      prepareFieldPlan: prepareAssistantFieldPlan,
      prepareCapabilityDraft,
      prepareRetainFieldDraft,
      removableNewFieldNames,
      prepareRemoveNewFieldDraft,
      plan: () =>
        fieldPlanActive.value
          ? assistantProposal()?.relationDrafts.flatMap((relation) =>
              relation.fieldDrafts.map((draft) => ({ ...draft, saved: false })),
            )
          : undefined,
      proposal: assistantProposal,
      prepareConfirmation: prepareAssistantMetadataConfirmation,
      readCurrent,
      preview: (proposal, signal) =>
        previewMetadataModelChangeSet(moduleContext.http, props.moduleAlias, proposal, signal),
      fieldSpecAliases: () =>
        state.fieldSpecs.value
          .filter((spec) => spec.enabled !== false)
          .map((spec) => spec.alias ?? spec.id ?? '')
          .filter(Boolean),
      editableBasicFieldNames: assistantEditableBasicFieldNames,
      editableReferenceFieldNames: assistantEditableReferenceFieldNames,
      prepareReferenceUpdate: prepareAssistantReferenceUpdate,
      prepareNewFieldDraft: prepareAssistantNewFieldDraft,
      prepareFieldUpdate: prepareAssistantFieldUpdate,
      findFieldTargets: findAssistantFieldTargets,
      referenceAffectDirectory,
      preparePropertyFieldDraft: prepareAssistantPropertyFieldDraft,
      preparePropertyFieldCommit: prepareAssistantPropertyFieldCommit,
    };
    let pendingLoad: Promise<void> | undefined;
    async function ensureLoaded(refresh = false, commit?: (accept: () => void) => void) {
      requireValid();
      if (commit && (loading.value || saving.value))
        throw new OperationUsageError('元数据正在读取或保存，请稍后再选择');
      if (submissionStatus.value === 'unknown' || committedNeedsReload.value) {
        commit?.(() => {});
        return;
      }
      if (workspaceReady.value && fieldSpecsReady.value && (!refresh || dirty.value)) {
        commit?.(() => {});
        return;
      }
      if (commit) {
        if (!fieldSpecsReady.value) await loadFieldSpecs();
        if (!fieldSpecsReady.value) throw new OperationUsageError('字段规格加载未完成，请重新选择模块');
        await loadWorkspace(commit);
        return;
      }
      if (!pendingLoad)
        pendingLoad = Promise.all([loadWorkspace(), loadFieldSpecs()])
          .then(() => {
            requireValid();
            if (!workspaceReady.value || workspaceLoadFailed.value || !fieldSpecsReady.value)
              throw new OperationUsageError('元数据加载未完成，请重新选择模块');
          })
          .finally(() => {
            pendingLoad = undefined;
          });
      return pendingLoad;
    }
    function selectRelation(relationId: string) {
      requireValid();
      if (selectedRelationId.value === relationId) return;
      if (state.mode.value !== 'view' || editSession.isDirty.value || fieldPlanActive.value)
        throw new OperationUsageError('请先保存或取消当前字段候选，再切换元数据');
      const relation = state.relations.value.find((item) => item.id === relationId);
      if (!relation) throw new OperationUsageError('元数据节点不存在，请读取实际节点目录');
      state.selectRelation(relation);
      hydrateSelectedRelation(relationId);
      selectedTreeKey.value = metadataNodeKey(relationId);
    }
    const dirty = computed(() => editSession.isDirty.value || state.mode.value !== 'view');
    return {
      moduleAlias: props.moduleAlias,
      title,
      adapter,
      ensureLoaded,
      selectRelation,
      dirty,
      submissionStatus,
      committedNeedsReload,
      readCurrent,
      readingCurrent,
      saving,
      loading,
      workspaceReady,
      revision: metadataCandidateRevision,
      contextRevision,
      invalidateConfirmations: () => {
        metadataCandidateRevision.value++;
        contextRevision.value++;
      },
      relations: state.relations,
      dispose: () => {
        disposed = true;
        scope.stop();
      },
      view: {
        state,
        capabilityItems,
        selectCapability,
        fieldPlanActive,
        fieldPlanEntries,
        sorting,
        mainMetadataDraft,
        fieldDraft,
        fieldPropertyDraft,
        loading,
        saving,
        showSystemFields,
        selectedTreeKey,
        expandedTreeKeys,
        childNodeType,
        editorMode,
        editorModeOptions,
        childMetadataDraft,
        childValidationAttempted,
        creatingChildMetadata,
        updateChildAlias,
        childAliasError,
        referenceTargetFieldCatalogLoading,
        referenceTargetFieldCatalogError,
        selectedField,
        selectedNodeIsField,
        metadataTreeNodes,
        selectedRelationIsMain,
        fieldPropertyEditorKind,
        fixedDefaultEditable,
        fixedDefaultReadValue,
        editableFieldSpecOptions,
        fieldCreationItems,
        referenceSearch,
        dictionarySearch,
        choicesLoading,
        choicesError,
        referenceModuleOptions,
        dictionaryValue,
        dictionaryOptions,
        updateFieldTitle,
        updateDictionary,
        updateFieldName,
        updateColumnName,
        projectionMappingsText,
        referenceAffectMappings,
        referenceAffectSourceOptions,
        referenceAffectTargetOptions,
        updateReferenceAffect,
        addReferenceAffect,
        removeReferenceAffect,
        referenceKeyFieldOptions,
        referenceLabelFieldOptions,
        referenceTargetFieldCatalogProblem,
        updateReferenceTargetModuleAlias,
        loadWorkspace,
        selectMetadataTreeNode,
        editPlanField,
        removePlanField,
        cancelFieldPlan,
        startFieldPlan,
        startCreateMainMetadata,
        startCreateChildNode,
        startCreateChildMetadataNode,
        startEditField,
        cancelNodeEditor,
        toggleSorting,
        previewAndApply,
        stageFieldDraft,
        deleteSelectedNode,
        createMainMetadata,
        fieldPropertyOf,
        fieldSourceOf,
        fieldEditableInSession,
        fieldProtectionReason,
        canDragMetadataNode,
        allowMetadataModelDrop,
        handleMetadataModelDrop,
        metadataFieldPropertyLabel,
        fieldSpecDisplayLabel,
      },
    };
  })!;
}
export type MetadataEditorSession = ReturnType<typeof createMetadataEditorSession>;
