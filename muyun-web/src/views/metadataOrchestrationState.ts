import { computed, ref } from 'vue';
import type { FieldSpec, Metadata, MetadataField, ModuleMetadataRelation } from '@muyun/web-contracts';
import type { RecordExplorerItemDescriptor } from '../platform-components/recordExplorerItemModel';

/** Editor lifecycle of the metadata orchestration workspace. */
export type MetadataOrchestrationMode =
  | 'view'
  | 'create-main'
  | 'create-field'
  | 'create-reference-field'
  | 'create-dictionary-field'
  | 'edit-field';

export interface MainMetadataDraft {
  alias: string;
  title: string;
  schemaName: string;
  tableName: string;
  dataScopeEnabled: boolean;
}

export type MetadataFieldDraft = Partial<MetadataField>;

/**
 * A field's business property is distinct from its physical field specification.  For example,
 * a reference can still use a short-text column to store a target key.
 */
export type MetadataFieldPropertyKind = 'BASIC' | 'MODULE_REFERENCE' | 'DICTIONARY' | 'LEGACY_LOCKED';

export interface MetadataFieldReferencePropertyConfig {
  targetModuleAlias?: string;
  targetMetadataId?: string;
  targetKeyField?: string;
  targetLabelField?: string;
  cardinality?: 'ONE' | 'MANY';
  targetUnavailablePolicy?: 'PRESERVE_HISTORY' | 'RESTRICT' | 'CASCADE_DELETE';
  projectionMappings?: string[];
}

export interface MetadataFieldDictionaryPropertyConfig {
  dictionaryApplicationAlias?: string;
  dictionaryCategoryAlias?: string;
  selectionMode?: 'SINGLE' | 'MULTIPLE';
}

/**
 * The platform field-spec catalog owns these stable storage shapes.  A property editor must
 * select a legal shape rather than exposing every scalar type as a possible reference/dictionary
 * backing store.
 */
export function storageFieldSpecAliasOf(
  kind: MetadataFieldPropertyKind,
  selectionMode: MetadataFieldDictionaryPropertyConfig['selectionMode'] = 'SINGLE',
): string | undefined {
  if (kind === 'MODULE_REFERENCE') return 'string';
  if (kind === 'DICTIONARY') return selectionMode === 'MULTIPLE' ? 'json_set' : 'string';
  return undefined;
}

export interface MetadataFieldPropertyDraft {
  kind: MetadataFieldPropertyKind;
  expectedBindingVersion?: number;
  referenceConfig?: MetadataFieldReferencePropertyConfig;
  dictionaryConfig?: MetadataFieldDictionaryPropertyConfig;
}

/** Relation-scoped property facts supplied by the module-governance read model. */
export interface MetadataFieldPropertySummary extends MetadataFieldPropertyDraft {
  fieldId?: string;
  fieldName?: string;
  fieldSpecAlias?: string;
  bindingVersion?: number;
  legacyReason?: string;
  /** Transport shape of the relation-scoped property summary. */
  reference?: MetadataFieldReferencePropertyConfig;
  dictionary?: {
    applicationAlias?: string;
    categoryAlias?: string;
    selectionMode?: 'SINGLE' | 'MULTIPLE';
  };
}

export interface FieldSpecOption {
  value: string;
  label: string;
}

export function createMetadataOrchestrationState() {
  const relations = ref<ModuleMetadataRelation[]>([]);
  const metadataById = ref<Record<string, Metadata>>({});
  const selectedRelationId = ref<string>();
  const fields = ref<MetadataField[]>([]);
  const allFields = ref<MetadataField[]>([]);
  const fieldSpecs = ref<FieldSpec[]>([]);
  const mode = ref<MetadataOrchestrationMode>('view');
  const mainMetadataDraft = ref<MainMetadataDraft>(emptyMainMetadataDraft());
  const fieldDraft = ref<MetadataFieldDraft>(emptyFieldDraft());
  const fieldPropertyDraft = ref<MetadataFieldPropertyDraft>(emptyFieldPropertyDraft('BASIC'));

  const selectedRelation = computed(() =>
    relations.value.find((item) => item.id === selectedRelationId.value),
  );
  const selectedMetadata = computed(() =>
    selectedRelation.value?.metadataId ? metadataById.value[selectedRelation.value.metadataId] : undefined,
  );
  const hasMainMetadata = computed(() => relations.value.some((item) => isMainRelation(item.relationRole)));
  const mainEditorOpen = computed(() => mode.value === 'create-main');
  const fieldEditorOpen = computed(
    () =>
      mode.value === 'create-field' ||
      mode.value === 'create-reference-field' ||
      mode.value === 'create-dictionary-field' ||
      mode.value === 'edit-field',
  );
  const fieldSpecOptions = computed(() => fieldSpecOptionListOf(fieldSpecs.value));

  function handleRelationsLoaded(loaded: ModuleMetadataRelation[]) {
    relations.value = loaded;
    const availableIds = new Set(loaded.map((item) => item.id).filter(Boolean) as string[]);
    if (!selectedRelationId.value || !availableIds.has(selectedRelationId.value)) {
      selectedRelationId.value = loaded[0]?.id;
    }
  }

  function handleMetadataLoaded(metadata: Metadata) {
    if (!metadata.id) return;
    metadataById.value = { ...metadataById.value, [metadata.id]: metadata };
  }

  function handleFieldsLoaded(loaded: MetadataField[]) {
    allFields.value = loaded;
    fields.value = orchestratableFields(loaded);
  }

  function handleFieldSpecsLoaded(loaded: FieldSpec[]) {
    fieldSpecs.value = loaded;
  }

  /** Returns true when the selection actually changed and details must be loaded. */
  function selectRelation(relation: ModuleMetadataRelation): boolean {
    mode.value = 'view';
    if (!relation.id || relation.id === selectedRelationId.value) return false;
    selectedRelationId.value = relation.id;
    fields.value = [];
    return true;
  }

  function focusRelation(relationId: string | undefined) {
    if (!relationId) return;
    selectedRelationId.value = relationId;
    mode.value = 'view';
    fields.value = [];
  }

  function startCreateMain() {
    mainMetadataDraft.value = emptyMainMetadataDraft();
    mode.value = 'create-main';
  }

  function startCreateField(kind: MetadataFieldPropertyKind = 'BASIC') {
    if (!selectedMetadata.value?.id) return;
    fieldPropertyDraft.value = emptyFieldPropertyDraft(kind);
    fieldDraft.value = {
      ...emptyFieldDraft(),
      fieldSpecAlias: storageFieldSpecAliasOf(kind, fieldPropertyDraft.value.dictionaryConfig?.selectionMode),
    };
    mode.value =
      kind === 'MODULE_REFERENCE'
        ? 'create-reference-field'
        : kind === 'DICTIONARY'
          ? 'create-dictionary-field'
          : 'create-field';
  }

  function startEditField(
    field: MetadataField,
    property: MetadataFieldPropertyDraft = emptyFieldPropertyDraft('BASIC'),
  ) {
    if (!selectedMetadata.value?.id) return;
    fieldDraft.value = copyMetadataField(field);
    fieldPropertyDraft.value = copyFieldPropertyDraft(property);
    mode.value = 'edit-field';
  }

  /** Existing physical fields can enter an explicit property-configuration workflow. */
  function startConfigureFieldProperty(
    field: MetadataField,
    kind: Exclude<MetadataFieldPropertyKind, 'BASIC' | 'LEGACY_LOCKED'>,
  ) {
    startEditField(field, emptyFieldPropertyDraft(kind));
  }

  /** Cancelling any editor returns to the currently selected entity. */
  function cancelEditor() {
    mode.value = 'view';
  }

  return {
    relations,
    metadataById,
    selectedRelationId,
    fields,
    allFields,
    fieldSpecs,
    mode,
    mainMetadataDraft,
    fieldDraft,
    fieldPropertyDraft,
    selectedRelation,
    selectedMetadata,
    hasMainMetadata,
    mainEditorOpen,
    fieldEditorOpen,
    fieldSpecOptions,
    handleRelationsLoaded,
    handleMetadataLoaded,
    handleFieldsLoaded,
    handleFieldSpecsLoaded,
    selectRelation,
    focusRelation,
    startCreateMain,
    startCreateField,
    startEditField,
    startConfigureFieldProperty,
    cancelEditor,
  };
}

export type MetadataOrchestrationState = ReturnType<typeof createMetadataOrchestrationState>;

export function emptyMainMetadataDraft(): MainMetadataDraft {
  return { alias: '', title: '', schemaName: '', tableName: '', dataScopeEnabled: false };
}

export function normalizeMainMetadataDraft(draft: MainMetadataDraft): MainMetadataDraft {
  return {
    ...draft,
    alias: draft.alias.trim(),
    title: draft.title.trim(),
    schemaName: draft.schemaName.trim(),
    tableName: draft.tableName.trim(),
  };
}

export function isValidMainMetadataDraft(draft: MainMetadataDraft): boolean {
  return Boolean(draft.alias.trim() && draft.title.trim());
}

export function emptyFieldDraft(): MetadataFieldDraft {
  return {
    fieldName: '',
    columnName: '',
    title: '',
    fieldSpecAlias: undefined,
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
    required: false,
    uniqueField: false,
    indexed: false,
    sortableField: false,
    titleField: false,
    enabled: true,
  };
}

export function emptyFieldPropertyDraft(kind: MetadataFieldPropertyKind): MetadataFieldPropertyDraft {
  if (kind === 'MODULE_REFERENCE') {
    return {
      kind,
      referenceConfig: {
        targetKeyField: 'id',
        targetLabelField: 'title',
        cardinality: 'ONE',
        targetUnavailablePolicy: 'PRESERVE_HISTORY',
        projectionMappings: [],
      },
    };
  }
  if (kind === 'DICTIONARY') {
    return {
      kind,
      dictionaryConfig: { selectionMode: 'SINGLE' },
    };
  }
  if (kind === 'LEGACY_LOCKED') return { kind };
  return { kind: 'BASIC' };
}

export function copyFieldPropertyDraft(property: MetadataFieldPropertyDraft): MetadataFieldPropertyDraft {
  const draft = property;
  return {
    ...draft,
    ...(draft.expectedBindingVersion !== undefined
      ? { expectedBindingVersion: draft.expectedBindingVersion }
      : {}),
    ...(draft.referenceConfig
      ? {
          referenceConfig: {
            ...draft.referenceConfig,
            projectionMappings: [...(draft.referenceConfig.projectionMappings ?? [])],
          },
        }
      : {}),
    ...(draft.dictionaryConfig ? { dictionaryConfig: { ...draft.dictionaryConfig } } : {}),
  };
}

/** Converts the read-model's concise bindings into the draft contract accepted by change sets. */
export function propertyDraftFromSummary(summary: MetadataFieldPropertySummary): MetadataFieldPropertyDraft {
  if (summary.kind === 'LEGACY_LOCKED') return { kind: summary.kind };
  if (summary.kind === 'MODULE_REFERENCE') {
    return copyFieldPropertyDraft({
      kind: summary.kind,
      expectedBindingVersion: summary.expectedBindingVersion ?? summary.bindingVersion,
      referenceConfig: summary.referenceConfig ?? summary.reference,
    });
  }
  if (summary.kind === 'DICTIONARY') {
    return copyFieldPropertyDraft({
      kind: summary.kind,
      expectedBindingVersion: summary.expectedBindingVersion ?? summary.bindingVersion,
      dictionaryConfig:
        summary.dictionaryConfig ??
        (summary.dictionary
          ? {
              dictionaryApplicationAlias: summary.dictionary.applicationAlias,
              dictionaryCategoryAlias: summary.dictionary.categoryAlias,
              selectionMode: summary.dictionary.selectionMode,
            }
          : undefined),
    });
  }
  return { kind: 'BASIC', expectedBindingVersion: summary.expectedBindingVersion ?? summary.bindingVersion };
}

export function copyMetadataField(field: MetadataField): MetadataFieldDraft {
  return { ...field };
}

export function isValidFieldDraft(draft: MetadataFieldDraft): boolean {
  return Boolean(draft.fieldName?.trim() && draft.columnName?.trim() && draft.fieldSpecAlias);
}

export function normalizeFieldDraft(draft: MetadataFieldDraft): MetadataFieldDraft {
  return {
    ...draft,
    fieldName: draft.fieldName?.trim(),
    columnName: draft.columnName?.trim(),
    title: draft.title?.trim(),
  };
}

export function normalizeFieldPropertyDraft(
  property: MetadataFieldPropertyDraft,
): MetadataFieldPropertyDraft {
  if (property.kind === 'MODULE_REFERENCE') {
    const reference = property.referenceConfig ?? {};
    return {
      kind: property.kind,
      ...(property.expectedBindingVersion !== undefined
        ? { expectedBindingVersion: property.expectedBindingVersion }
        : {}),
      referenceConfig: {
        ...reference,
        ...(reference.targetModuleAlias?.trim()
          ? { targetModuleAlias: reference.targetModuleAlias.trim() }
          : {}),
        ...(reference.targetMetadataId?.trim()
          ? { targetMetadataId: reference.targetMetadataId.trim() }
          : {}),
        targetKeyField: reference.targetKeyField?.trim() || 'id',
        targetLabelField: reference.targetLabelField?.trim() || 'title',
        projectionMappings: (reference.projectionMappings ?? []).map((value) => value.trim()).filter(Boolean),
      },
    };
  }
  if (property.kind === 'DICTIONARY') {
    const dictionary = property.dictionaryConfig ?? {};
    return {
      kind: property.kind,
      ...(property.expectedBindingVersion !== undefined
        ? { expectedBindingVersion: property.expectedBindingVersion }
        : {}),
      dictionaryConfig: {
        ...dictionary,
        ...(dictionary.dictionaryApplicationAlias?.trim()
          ? { dictionaryApplicationAlias: dictionary.dictionaryApplicationAlias.trim() }
          : {}),
        ...(dictionary.dictionaryCategoryAlias?.trim()
          ? { dictionaryCategoryAlias: dictionary.dictionaryCategoryAlias.trim() }
          : {}),
      },
    };
  }
  if (property.kind === 'LEGACY_LOCKED') return { kind: property.kind };
  return property.expectedBindingVersion !== undefined
    ? { kind: 'BASIC', expectedBindingVersion: property.expectedBindingVersion }
    : { kind: 'BASIC' };
}

export function isValidFieldPropertyDraft(property: MetadataFieldPropertyDraft): boolean {
  if (property.kind === 'LEGACY_LOCKED') return false;
  if (property.kind === 'MODULE_REFERENCE') {
    return Boolean(property.referenceConfig?.targetModuleAlias?.trim());
  }
  if (property.kind === 'DICTIONARY') {
    return Boolean(
      property.dictionaryConfig?.dictionaryApplicationAlias?.trim() &&
      property.dictionaryConfig?.dictionaryCategoryAlias?.trim(),
    );
  }
  return true;
}

export function metadataFieldPropertyLabel(kind: MetadataFieldPropertyKind): string {
  return {
    BASIC: '普通字段',
    MODULE_REFERENCE: '模块引用',
    DICTIONARY: '数据字典',
    LEGACY_LOCKED: '旧配置锁定',
  }[kind];
}

export function metadataFieldPropertySummary(property: MetadataFieldPropertyDraft): string {
  if (property.kind === 'MODULE_REFERENCE') {
    const reference = property.referenceConfig;
    if (!reference?.targetModuleAlias) return '待配置目标模块';
    const key = reference.targetKeyField || 'id';
    const label = reference.targetLabelField || 'title';
    return `${reference.targetModuleAlias} · ${key} → ${label}`;
  }
  if (property.kind === 'DICTIONARY') {
    const dictionary = property.dictionaryConfig;
    if (!dictionary?.dictionaryCategoryAlias) return '待配置字典类别';
    return `${dictionary.dictionaryApplicationAlias ?? '当前应用'} · ${dictionary.dictionaryCategoryAlias}`;
  }
  if (property.kind === 'LEGACY_LOCKED') return '旧链路配置，需在迁移流程中处理';
  return '无附加业务绑定';
}

/** Only business-owned physical fields are orchestrated; platform-managed fields stay hidden. */
export function orchestratableFields(fields: MetadataField[]): MetadataField[] {
  return fields.filter(isOrchestratableField);
}

/** Only these field facts may be changed through the metadata field editor. */
export function isOrchestratableField(field: MetadataField): boolean {
  return (
    field.fieldOwnership === 'BUSINESS' && field.fieldForm === 'PHYSICAL' && field.systemManaged !== true
  );
}

export function fieldSpecOptionListOf(specs: FieldSpec[]): FieldSpecOption[] {
  return specs
    .filter((item) => item.enabled !== false)
    .map((item) => ({
      value: item.alias ?? item.id ?? '',
      label: item.title ?? item.alias ?? item.id ?? '未命名字段规格',
    }))
    .filter((option) => option.value);
}

/** Filters a populated entity to the source specification's server-declared safe targets. */
export function dataSafeFieldSpecOptions(specs: FieldSpec[], currentAlias?: string): FieldSpecOption[] {
  const source = specs.find((spec) => (spec.alias ?? spec.id) === currentAlias);
  const permittedAliases = new Set(
    [currentAlias, ...(source?.safeTargetFieldSpecAliases ?? [])].filter(Boolean),
  );
  return fieldSpecOptionListOf(specs).filter((option) => permittedAliases.has(option.value));
}

/** Use the catalog title for human-facing metadata surfaces; aliases remain stable machine identities. */
export function fieldSpecDisplayLabel(fieldSpecAlias: string | undefined, specs: FieldSpec[]): string {
  if (!fieldSpecAlias) return '';
  return (
    specs.find((spec) => spec.alias === fieldSpecAlias || spec.id === fieldSpecAlias)?.title ?? fieldSpecAlias
  );
}

export function entityTitleOf(relation: ModuleMetadataRelation, metadata: Metadata | undefined): string {
  return metadata?.title ?? relation.relationAlias ?? '未命名实体';
}

export function relationRoleTag(role: ModuleMetadataRelation['relationRole']): string | undefined {
  if (isMainRelation(role)) return '主实体';
  if (role === 'CHILD' || role === 'child') return '子实体';
  return undefined;
}

/** Java code enums are serialized by their stable lower-case code in runtime responses. */
export function isMainRelation(role: ModuleMetadataRelation['relationRole'] | undefined): boolean {
  return role === 'MAIN' || role === 'main';
}

export function entityExplorerItem(
  relation: ModuleMetadataRelation,
  metadata: Metadata | undefined,
): RecordExplorerItemDescriptor {
  const title = entityTitleOf(relation, metadata);
  const secondary = metadata?.alias ?? relation.relationAlias;
  return {
    title,
    secondary: secondary && secondary !== title ? secondary : undefined,
    tag: relationRoleTag(relation.relationRole),
    muted: metadata?.enabled === false ? true : undefined,
  };
}

export function metadataSubtitleOf(metadata: Metadata | undefined): string | undefined {
  if (!metadata) return undefined;
  const table = metadata.tableName ? `${metadata.schemaName ?? 'public'}.${metadata.tableName}` : undefined;
  return [metadata.alias, table].filter(Boolean).join(' · ') || undefined;
}
