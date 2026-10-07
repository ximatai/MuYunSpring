import { computed, ref } from 'vue';
import type { MetadataField, ModuleMetadataRelation } from '@muyun/web-contracts';
import {
  copyFieldPropertyDraft,
  emptyFieldPropertyDraft,
  propertyDraftFromSummary,
  type MetadataFieldPropertyDraft,
  type MetadataFieldPropertySummary,
} from './metadataOrchestrationState';

export type MetadataFieldGovernanceKind =
  | 'BUSINESS'
  | 'CAPABILITY_DERIVED'
  | 'PLATFORM_SYSTEM'
  | 'RELATION_FOREIGN_KEY';

export interface MetadataFieldChangeSetDraft {
  operation: 'ADD' | 'UPDATE' | 'DELETE';
  fieldId?: string;
  expectedFieldVersion?: number;
  field?: MetadataField;
  /** JSON-facing command contract; projection mappings remain an ordered string array. */
  property?: MetadataFieldPropertyChangeSetPayload;
}

/**
 * The editor uses stable upper-case cardinality labels, while the public HTTP contract for
 * CodeTitleEnum writes their lower-case codes. Keep that translation at the change-set boundary
 * rather than leaking transport values into the editor and form renderer.
 */
export type MetadataFieldPropertyChangeSetPayload = Omit<MetadataFieldPropertyDraft, 'dictionaryConfig'> & {
  dictionaryConfig?: Omit<NonNullable<MetadataFieldPropertyDraft['dictionaryConfig']>, 'selectionMode'> & {
    selectionMode?: 'single' | 'multiple';
  };
};

export function toFieldPropertyChangeSetPayload(
  property: MetadataFieldPropertyDraft,
): MetadataFieldPropertyChangeSetPayload {
  const copy = copyFieldPropertyDraft(property);
  if (copy.kind !== 'DICTIONARY' || !copy.dictionaryConfig) {
    const { dictionaryConfig, ...payload } = copy;
    void dictionaryConfig;
    return payload;
  }
  const { selectionMode, ...dictionaryConfig } = copy.dictionaryConfig;
  return {
    ...copy,
    dictionaryConfig: {
      ...dictionaryConfig,
      ...(selectionMode ? { selectionMode: selectionMode.toLowerCase() as 'single' | 'multiple' } : {}),
    },
  };
}

export interface MetadataRelationChangeSetProposal {
  expectedMetadataVersion: number;
  fieldDrafts: MetadataFieldChangeSetDraft[];
  capabilitySelections?: Record<string, boolean>;
}

/** Input facts are loaded for every node before a module-wide edit session begins. */
export interface MetadataModelRelationDraftSource {
  relationId: string;
  metadataId: string;
  parentMetadataId?: string;
  sortOrder?: number;
  expectedMetadataVersion: number;
  fields: MetadataField[];
  /** The server's sortable set; definition protection does not restrict display ordering. */
  sortableFieldIds?: string[];
  fieldProperties?: MetadataFieldPropertySummary[];
}

export interface MetadataModelRelationDraft {
  metadataId: string;
  relationId: string;
  expectedMetadataVersion: number;
  capabilitySelections: Record<string, boolean>;
  fields: Record<string, MetadataField>;
  fieldProperties: Record<string, MetadataFieldPropertyDraft>;
  parentMetadataId?: string;
  fieldOrder: string[];
  sortableFieldIds: string[];
}

export interface MetadataModelChangeSetProposal {
  relationDrafts: Array<MetadataRelationChangeSetProposal & { relationId: string }>;
  relationOrders: Array<{ parentMetadataId?: string; relationIds: string[] }>;
  fieldOrders: Array<{ relationId: string; fieldIds: string[] }>;
}

/**
 * A single local session for the entire visible metadata model.  It deliberately reuses the
 * relation-level field proposal shape: the module façade owns atomic validation/persistence while
 * this class keeps every tree action local until the user chooses one publish operation.
 */
export function createMetadataModelWorkspaceEditSession() {
  const draft = ref<Record<string, MetadataModelRelationDraft>>();
  const initial = ref<Record<string, MetadataModelRelationDraft>>({});
  const relationOrder = ref<Record<string, string[]>>({});
  const initialRelationOrder = ref<Record<string, string[]>>({});

  const editing = computed(() => draft.value !== undefined);
  const isDirty = computed(() => {
    if (!draft.value) return false;
    if (JSON.stringify(relationOrder.value) !== JSON.stringify(initialRelationOrder.value)) return true;
    return Object.entries(draft.value).some(([relationId, current]) => {
      const original = initial.value[relationId];
      return !original || JSON.stringify(current) !== JSON.stringify(original);
    });
  });

  function begin(sources: MetadataModelRelationDraftSource[]) {
    const drafts = Object.fromEntries(sources.map((source) => [source.relationId, relationDraft(source)]));
    initial.value = copyRelationDrafts(drafts);
    draft.value = copyRelationDrafts(drafts);
    const grouped = new Map<string, MetadataModelRelationDraftSource[]>();
    for (const source of sources) {
      const key = source.parentMetadataId ?? '';
      grouped.set(key, [...(grouped.get(key) ?? []), source]);
    }
    const orders = Object.fromEntries(
      [...grouped.entries()].map(([parentMetadataId, siblings]) => [
        parentMetadataId,
        siblings
          .slice()
          .sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0))
          .map((source) => source.relationId),
      ]),
    );
    relationOrder.value = copyStringMap(orders);
    initialRelationOrder.value = copyStringMap(orders);
  }

  /** Prepare recovery on a newly read baseline without changing the retained candidate on failure. */
  function prepareRebase(
    sources: MetadataModelRelationDraftSource[],
    proposal: MetadataModelChangeSetProposal,
    enabledCapabilities: Readonly<Record<string, string[]>> = {},
    baseline: Readonly<Record<string, MetadataModelRelationDraft>> = initial.value,
  ): () => void {
    const recovered = createMetadataModelWorkspaceEditSession();
    recovered.begin(sources);
    const targets = new Set([
      ...proposal.relationDrafts.map((item) => item.relationId),
      ...proposal.fieldOrders.map((item) => item.relationId),
      ...proposal.relationOrders.flatMap((item) => item.relationIds),
    ]);
    for (const relationId of targets) {
      if (
        !baseline[relationId] ||
        baseline[relationId].metadataId !== recovered.relation(relationId)?.metadataId ||
        baseline[relationId].parentMetadataId !== recovered.relation(relationId)?.parentMetadataId
      )
        throw new Error('候选所属元数据实体已变化；不会迁移到其他实体，原候选已保留。');
    }
    for (const candidate of proposal.relationDrafts) {
      const current = recovered.relation(candidate.relationId);
      if (!current) throw new Error('候选所属元数据已不存在；原候选已保留，请核对配置。');
      for (const change of candidate.fieldDrafts) {
        if (!change.field || change.operation === 'DELETE')
          throw new Error('无法恢复此字段候选；原候选已保留，请核对配置。');
        const existing =
          change.operation === 'UPDATE'
            ? current.fields[change.fieldId ?? '']
            : Object.values(current.fields).find((field) => field.fieldName === change.field!.fieldName);
        if (change.operation === 'UPDATE' && !existing)
          throw new Error('候选中的原字段已不存在；不会按同名迁移，原候选已保留。');
        const original =
          change.operation === 'UPDATE'
            ? baseline[candidate.relationId]?.fields[change.fieldId ?? '']
            : undefined;
        const changedField = original
          ? Object.fromEntries(
              Object.entries(change.field).filter(
                ([key, value]) =>
                  JSON.stringify(value) !== JSON.stringify(original[key as keyof MetadataField]),
              ),
            )
          : change.field;
        const field = existing
          ? {
              ...existing,
              ...changedField,
              id: existing.id,
              version: existing.version,
              metadataId: existing.metadataId,
            }
          : { ...change.field };
        const baselineProperty = existing
          ? recovered.propertyForField(candidate.relationId, existing)
          : emptyFieldPropertyDraft('BASIC');
        let property: MetadataFieldPropertyDraft = change.property
          ? {
              ...change.property,
              expectedBindingVersion: baselineProperty.expectedBindingVersion,
              dictionaryConfig: change.property.dictionaryConfig
                ? {
                    ...change.property.dictionaryConfig,
                    selectionMode: change.property.dictionaryConfig.selectionMode?.toUpperCase() as
                      | 'SINGLE'
                      | 'MULTIPLE'
                      | undefined,
                  }
                : undefined,
              ...(change.property.fixedDefault
                ? {
                    fixedDefault: {
                      value: change.property.fixedDefault.value,
                      expectedConfigVersion: baselineProperty.fixedDefault?.expectedConfigVersion,
                    },
                  }
                : {}),
            }
          : baselineProperty;
        const originalProperty = original
          ? baseline[candidate.relationId]?.fieldProperties[change.fieldId ?? '']
          : undefined;
        if (change.property && originalProperty?.kind === property.kind) {
          const intended = property;
          property = copyFieldPropertyDraft(baselineProperty);
          for (const key of ['referenceConfig', 'dictionaryConfig'] as const) {
            const before = originalProperty[key];
            const desired = intended[key];
            if (!desired) continue;
            const changes = Object.fromEntries(
              Object.entries(desired).filter(
                ([name, value]) =>
                  JSON.stringify(value) !== JSON.stringify(before?.[name as keyof typeof before]),
              ),
            );
            if (Object.keys(changes).length)
              property = { ...property, [key]: { ...property[key], ...changes } };
          }
          if (intended.fixedDefault?.value !== originalProperty.fixedDefault?.value)
            property.fixedDefault = intended.fixedDefault;
        }
        recovered.stageField(candidate.relationId, field, property);
      }
      for (const [capability, selected] of Object.entries(candidate.capabilitySelections ?? {}))
        if (selected && !enabledCapabilities[candidate.relationId]?.includes(capability))
          recovered.stageCapability(candidate.relationId, capability, true);
    }
    for (const order of proposal.fieldOrders) {
      if (!sameMembers(recovered.relation(order.relationId)?.sortableFieldIds ?? [], order.fieldIds))
        throw new Error('字段排序范围已变化；原候选已保留，请重新核对。');
      recovered.stageFieldOrder(order.relationId, order.fieldIds);
    }
    for (const order of proposal.relationOrders) {
      if (!sameMembers(recovered.relationOrder.value[order.parentMetadataId ?? ''] ?? [], order.relationIds))
        throw new Error('元数据排序范围已变化；原候选已保留，请重新核对。');
      recovered.stageRelationOrder(order.parentMetadataId, order.relationIds);
    }
    const rebased = recovered.buildProposal()!;
    return () => {
      begin(sources);
      for (const candidate of rebased.relationDrafts) {
        const source = recovered.relation(candidate.relationId)!;
        for (const change of candidate.fieldDrafts)
          stageField(candidate.relationId, change.field!, source.fieldProperties[fieldKey(change.field!)!]);
        for (const [capability, selected] of Object.entries(candidate.capabilitySelections ?? {}))
          stageCapability(candidate.relationId, capability, selected);
      }
      for (const order of rebased.fieldOrders) stageFieldOrder(order.relationId, order.fieldIds);
      for (const order of rebased.relationOrders)
        stageRelationOrder(order.parentMetadataId, order.relationIds);
    };
  }

  function cancel() {
    draft.value = undefined;
    initial.value = {};
    relationOrder.value = {};
    initialRelationOrder.value = {};
  }

  function relation(relationId: string) {
    return draft.value?.[relationId];
  }

  function fieldsForDisplay(relationId: string, fallback: MetadataField[]) {
    const current = relation(relationId);
    if (!current) return fallback;
    const byKey = current.fields;
    return current.fieldOrder
      .map((key) => byKey[key])
      .filter((field): field is MetadataField => Boolean(field));
  }

  function propertyForField(relationId: string, field: MetadataField) {
    const current = relation(relationId);
    const key = fieldKey(field);
    return (key && current?.fieldProperties[key]) || emptyFieldPropertyDraft('BASIC');
  }

  function stageField(
    relationId: string,
    field: MetadataField,
    property?: MetadataFieldPropertyDraft,
    replaceNewFieldKey?: string,
  ) {
    const current = relation(relationId);
    stageRelationField(current, field, property, replaceNewFieldKey);
  }

  /** Validate the whole batch before replacing the session, so failures cannot leave partial candidates. */
  function stageFields(
    relationId: string,
    entries: Array<{ field: MetadataField; property: MetadataFieldPropertyDraft }>,
  ) {
    if (!draft.value?.[relationId]) throw new Error('No metadata edit session');
    const projected = copyRelationDrafts(draft.value);
    const current = projected[relationId];
    const names = new Set(Object.values(current.fields).map((field) => field.fieldName?.toLowerCase()));
    for (const { field, property } of entries) {
      const name = field.fieldName?.toLowerCase();
      if (!name || field.id || names.has(name)) throw new Error('Duplicate or invalid new metadata field');
      names.add(name);
      stageRelationField(current, field, property);
    }
    draft.value = projected;
  }

  function discardNewField(relationId: string, fieldName: string) {
    const current = relation(relationId);
    if (!current || current.fields[fieldName]?.id || initial.value[relationId]?.fields[fieldName]) return;
    delete current.fields[fieldName];
    delete current.fieldProperties[fieldName];
    current.fieldOrder = current.fieldOrder.filter((key) => key !== fieldName);
  }

  /** Project the visible editor without committing it to the tree/session. */
  function proposalWithField(
    relationId: string,
    field: MetadataField,
    property: MetadataFieldPropertyDraft,
    replaceNewFieldKey?: string,
  ) {
    if (!draft.value || !draft.value[relationId]) return undefined;
    const projected = copyRelationDrafts(draft.value);
    stageRelationField(projected[relationId], field, property, replaceNewFieldKey);
    return buildProposal(projected);
  }

  function stageCapability(relationId: string, capability: string, selected: boolean) {
    const current = relation(relationId);
    if (!current) throw new Error('No metadata edit session');
    const selections = { ...current.capabilitySelections };
    if (selected) selections[capability] = true;
    else delete selections[capability];
    current.capabilitySelections = selections;
  }

  function stageFieldOrder(relationId: string, fieldIds: string[]) {
    const current = relation(relationId);
    if (!current || !sameMembers(current.sortableFieldIds, fieldIds)) return;
    const remaining = [...fieldIds];
    current.fieldOrder = current.fieldOrder.map((fieldId) =>
      current.sortableFieldIds.includes(fieldId) ? remaining.shift()! : fieldId,
    );
  }

  function stageRelationOrder(parentMetadataId: string | undefined, relationIds: string[]) {
    const key = parentMetadataId ?? '';
    const current = relationOrder.value[key];
    if (!current || !sameMembers(current, relationIds)) return;
    relationOrder.value = { ...relationOrder.value, [key]: [...relationIds] };
  }

  function buildProposal(currentDraft = draft.value): MetadataModelChangeSetProposal | undefined {
    if (!currentDraft) return undefined;
    const relationDrafts = Object.entries(currentDraft).flatMap(([relationId, current]) => {
      const original = initial.value[relationId];
      if (!original) return [];
      const relationProposal = relationProposalOf(current, original);
      return relationProposal ? [{ relationId, ...relationProposal }] : [];
    });
    const relationOrders = Object.entries(relationOrder.value).flatMap(([parentMetadataId, order]) =>
      JSON.stringify(order) === JSON.stringify(initialRelationOrder.value[parentMetadataId])
        ? []
        : [{ parentMetadataId: parentMetadataId || undefined, relationIds: [...order] }],
    );
    const fieldOrders = Object.entries(currentDraft).flatMap(([relationId, current]) =>
      JSON.stringify(sortableFieldOrder(current)) ===
      JSON.stringify(sortableFieldOrder(initial.value[relationId]))
        ? []
        : [{ relationId, fieldIds: sortableFieldOrder(current) }],
    );
    return { relationDrafts, relationOrders, fieldOrders };
  }

  return {
    draft,
    relationOrder,
    editing,
    isDirty,
    begin,
    prepareRebase,
    captureBaseline: () => copyRelationDrafts(initial.value),
    cancel,
    relation,
    fieldsForDisplay,
    propertyForField,
    stageField,
    stageFields,
    discardNewField,
    stageCapability,
    stageFieldOrder,
    stageRelationOrder,
    buildProposal,
    proposalWithField,
  };
}

function relationDraft(source: MetadataModelRelationDraftSource): MetadataModelRelationDraft {
  const fields = fieldMap(
    [...source.fields].sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0)),
  );
  return {
    metadataId: source.metadataId,
    relationId: source.relationId,
    parentMetadataId: source.parentMetadataId,
    expectedMetadataVersion: source.expectedMetadataVersion,
    fields,
    capabilitySelections: {},
    fieldProperties: propertyMap(fields, source.fieldProperties ?? []),
    fieldOrder: Object.keys(fields),
    sortableFieldIds: source.sortableFieldIds ?? Object.keys(fields),
  };
}

function relationProposalOf(
  current: MetadataModelRelationDraft,
  original: MetadataModelRelationDraft,
): MetadataRelationChangeSetProposal | undefined {
  const fieldDrafts: MetadataFieldChangeSetDraft[] = [];
  for (const [id, field] of Object.entries(current.fields)) {
    const initialField = original.fields[id];
    const property = current.fieldProperties[id] ?? emptyFieldPropertyDraft('BASIC');
    const initialProperty = original.fieldProperties[id];
    const propertyChanged = JSON.stringify(property) !== JSON.stringify(initialProperty);
    if (!initialField) {
      fieldDrafts.push({
        operation: 'ADD',
        field: { ...field },
        property:
          property.kind === 'BASIC' && !property.fixedDefault
            ? undefined
            : toFieldPropertyChangeSetPayload(property),
      });
    } else if (JSON.stringify(field) !== JSON.stringify(initialField) || propertyChanged) {
      fieldDrafts.push({
        operation: 'UPDATE',
        fieldId: id,
        expectedFieldVersion: initialField.version,
        field: { ...field },
        property: propertyChanged ? toFieldPropertyChangeSetPayload(property) : undefined,
      });
    }
  }
  const capabilitySelections = current.capabilitySelections;
  return fieldDrafts.length || Object.keys(capabilitySelections).length
    ? {
        expectedMetadataVersion: current.expectedMetadataVersion,
        fieldDrafts,
        ...(Object.keys(capabilitySelections).length
          ? { capabilitySelections: { ...capabilitySelections } }
          : {}),
      }
    : undefined;
}

function fieldKey(field: MetadataField) {
  return field.id ?? field.fieldName;
}

function copyRelationDrafts(source: Record<string, MetadataModelRelationDraft>) {
  return Object.fromEntries(
    Object.entries(source).map(([relationId, value]) => [
      relationId,
      {
        ...value,
        capabilitySelections: { ...value.capabilitySelections },
        fields: copyFieldMap(value.fields),
        fieldProperties: copyFieldPropertyMap(value.fieldProperties),
        fieldOrder: [...value.fieldOrder],
        sortableFieldIds: [...value.sortableFieldIds],
      },
    ]),
  );
}

function copyStringMap(source: Record<string, string[]>) {
  return Object.fromEntries(Object.entries(source).map(([key, value]) => [key, [...value]]));
}

function sameMembers(left: string[], right: string[]) {
  return left.length === right.length && left.every((item) => right.includes(item));
}

function sortableFieldOrder(draft: MetadataModelRelationDraft | undefined) {
  return draft ? draft.fieldOrder.filter((fieldId) => draft.sortableFieldIds.includes(fieldId)) : [];
}

export function metadataFieldGovernanceKind(
  field: MetadataField,
  relation: ModuleMetadataRelation | undefined,
  capabilityFieldNames: ReadonlySet<string>,
): MetadataFieldGovernanceKind {
  if (
    relation?.foreignKey &&
    (field.fieldName === relation.foreignKey || field.columnName === relation.foreignKey)
  ) {
    return 'RELATION_FOREIGN_KEY';
  }
  if (capabilityFieldNames.has(field.fieldName ?? '')) return 'CAPABILITY_DERIVED';
  if (field.id?.startsWith('system:') || field.systemManaged || field.fieldOwnership !== 'BUSINESS') {
    return 'PLATFORM_SYSTEM';
  }
  return 'BUSINESS';
}

export function metadataFieldGovernanceLabel(kind: MetadataFieldGovernanceKind): string {
  return (
    {
      BUSINESS: '业务',
      CAPABILITY_DERIVED: '能力',
      PLATFORM_SYSTEM: '平台',
      RELATION_FOREIGN_KEY: '关系',
    }[kind] ?? kind
  );
}

export function isSessionEditableMetadataField(
  field: MetadataField,
  relation: ModuleMetadataRelation | undefined,
  capabilityFieldNames: ReadonlySet<string>,
): boolean {
  return metadataFieldGovernanceKind(field, relation, capabilityFieldNames) === 'BUSINESS';
}

function fieldMap(fields: MetadataField[]): Record<string, MetadataField> {
  return Object.fromEntries(
    fields
      .filter((field): field is MetadataField & { id: string } => Boolean(field.id))
      .map((field) => [field.id, { ...field }]),
  );
}

function copyFieldMap(fields: Record<string, MetadataField>): Record<string, MetadataField> {
  return Object.fromEntries(Object.entries(fields).map(([id, field]) => [id, { ...field }]));
}

function propertyMap(
  fields: Record<string, MetadataField>,
  summaries: MetadataFieldPropertySummary[],
): Record<string, MetadataFieldPropertyDraft> {
  const summariesByFieldId = new Map(
    summaries.filter((summary) => summary.fieldId).map((summary) => [summary.fieldId!, summary]),
  );
  return Object.fromEntries(
    Object.entries(fields).map(([id]) => [
      id,
      summariesByFieldId.has(id)
        ? propertyDraftFromSummary(summariesByFieldId.get(id)!)
        : emptyFieldPropertyDraft('BASIC'),
    ]),
  );
}

function copyFieldPropertyMap(
  properties: Record<string, MetadataFieldPropertyDraft>,
): Record<string, MetadataFieldPropertyDraft> {
  return Object.fromEntries(
    Object.entries(properties).map(([id, property]) => [id, copyFieldPropertyDraft(property)]),
  );
}

function stageRelationField(
  current: MetadataModelRelationDraft | undefined,
  field: MetadataField,
  property?: MetadataFieldPropertyDraft,
  replaceNewFieldKey?: string,
) {
  const key = fieldKey(field);
  if (!current || !key) return;
  // A failed preview keeps the local session open. If the author then corrects an unsaved
  // field's technical name, replace that provisional ADD instead of emitting two columns.
  if (replaceNewFieldKey && replaceNewFieldKey !== key && !current.fields[replaceNewFieldKey]?.id) {
    const fields = { ...current.fields };
    delete fields[replaceNewFieldKey];
    current.fields = fields;
    const properties = { ...current.fieldProperties };
    delete properties[replaceNewFieldKey];
    current.fieldProperties = properties;
    current.fieldOrder = current.fieldOrder.map((fieldId) =>
      fieldId === replaceNewFieldKey ? key : fieldId,
    );
    current.sortableFieldIds = current.sortableFieldIds.map((fieldId) =>
      fieldId === replaceNewFieldKey ? key : fieldId,
    );
  }
  current.fields = { ...current.fields, [key]: { ...field } };
  if (!current.fieldOrder.includes(key)) current.fieldOrder = [...current.fieldOrder, key];
  if (property)
    current.fieldProperties = { ...current.fieldProperties, [key]: copyFieldPropertyDraft(property) };
}
