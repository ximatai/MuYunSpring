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

export type MetadataFieldPropertyChangeSetPayload = MetadataFieldPropertyDraft;

export function toFieldPropertyChangeSetPayload(
  property: MetadataFieldPropertyDraft,
): MetadataFieldPropertyChangeSetPayload {
  return copyFieldPropertyDraft(property);
}

export interface MetadataRelationChangeSetProposal {
  expectedMetadataVersion: number;
  fieldDrafts: MetadataFieldChangeSetDraft[];
}

/** Input facts are loaded for every node before a module-wide edit session begins. */
export interface MetadataModelRelationDraftSource {
  relationId: string;
  metadataId: string;
  parentMetadataId?: string;
  sortOrder?: number;
  expectedMetadataVersion: number;
  fields: MetadataField[];
  /** The server's sortable subset; system, capability and relation-owned fields never participate. */
  sortableFieldIds?: string[];
  fieldProperties?: MetadataFieldPropertySummary[];
}

export interface MetadataModelRelationDraft {
  metadataId: string;
  relationId: string;
  expectedMetadataVersion: number;
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

  function stageField(relationId: string, field: MetadataField, property?: MetadataFieldPropertyDraft) {
    const current = relation(relationId);
    const key = fieldKey(field);
    if (!current || !key) return;
    current.fields = { ...current.fields, [key]: { ...field } };
    if (!current.fieldOrder.includes(key)) current.fieldOrder = [...current.fieldOrder, key];
    if (property)
      current.fieldProperties = { ...current.fieldProperties, [key]: copyFieldPropertyDraft(property) };
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

  function buildProposal(): MetadataModelChangeSetProposal | undefined {
    if (!draft.value) return undefined;
    const relationDrafts = Object.entries(draft.value).flatMap(([relationId, current]) => {
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
    const fieldOrders = Object.entries(draft.value).flatMap(([relationId, current]) =>
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
    cancel,
    relation,
    fieldsForDisplay,
    propertyForField,
    stageField,
    stageFieldOrder,
    stageRelationOrder,
    buildProposal,
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
        property: property.kind === 'BASIC' ? undefined : toFieldPropertyChangeSetPayload(property),
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
  return fieldDrafts.length
    ? { expectedMetadataVersion: current.expectedMetadataVersion, fieldDrafts }
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
