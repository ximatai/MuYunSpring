import { computed, ref } from 'vue';
import type { FieldSpec, Metadata, MetadataField, ModuleMetadataRelation } from '@muyun/web-contracts';
import type { RecordExplorerItemDescriptor } from '../platform-components/recordExplorerItemModel';

/** Editor lifecycle of the metadata orchestration workspace. */
export type MetadataOrchestrationMode = 'view' | 'create-main' | 'create-field' | 'edit-field';

export interface MainMetadataDraft {
  alias: string;
  title: string;
  schemaName: string;
  tableName: string;
  dataScopeEnabled: boolean;
}

export type MetadataFieldDraft = Partial<MetadataField>;

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

  const selectedRelation = computed(() =>
    relations.value.find((item) => item.id === selectedRelationId.value),
  );
  const selectedMetadata = computed(() =>
    selectedRelation.value?.metadataId ? metadataById.value[selectedRelation.value.metadataId] : undefined,
  );
  const hasMainMetadata = computed(() => relations.value.some((item) => item.relationRole === 'MAIN'));
  const mainEditorOpen = computed(() => mode.value === 'create-main');
  const fieldEditorOpen = computed(() => mode.value === 'create-field' || mode.value === 'edit-field');
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

  function startCreateField() {
    if (!selectedMetadata.value?.id) return;
    fieldDraft.value = emptyFieldDraft();
    mode.value = 'create-field';
  }

  function startEditField(field: MetadataField) {
    if (!selectedMetadata.value?.id) return;
    fieldDraft.value = copyMetadataField(field);
    mode.value = 'edit-field';
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

/** @deprecated Kept for hot-reload compatibility while field sources moved into the unified table. */
export function metadataFieldGroupsOf(fields: MetadataField[]) {
  return {
    business: orchestratableFields(fields),
    platform: fields.filter(
      (field) =>
        field.fieldForm === 'PHYSICAL' &&
        (field.fieldOwnership !== 'BUSINESS' || field.systemManaged === true),
    ),
    derived: fields.filter((field) => field.fieldForm && field.fieldForm !== 'PHYSICAL'),
  };
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

export function entityTitleOf(relation: ModuleMetadataRelation, metadata: Metadata | undefined): string {
  return metadata?.title ?? relation.relationAlias ?? '未命名实体';
}

export function relationRoleTag(role: ModuleMetadataRelation['relationRole']): string | undefined {
  if (role === 'MAIN') return '主实体';
  if (role === 'CHILD') return '子实体';
  return undefined;
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
