import { computed, ref } from 'vue';
import type { MetadataField, ModuleMetadataRelation } from '@muyun/web-contracts';
import {
  copyFieldPropertyDraft,
  emptyFieldPropertyDraft,
  propertyDraftFromSummary,
  type MetadataFieldPropertyDraft,
  type MetadataFieldPropertySummary,
} from './metadataOrchestrationState';

/**
 * A local edit session for one metadata relation.
 *
 * It intentionally owns no transport: change-set preview/apply APIs are
 * the only place that may persist this draft. This prevents field CRUD and
 * capability choices from escaping as unrelated immediate mutations.
 */
export interface MetadataCapabilityDraftSource {
  capability: string;
  enabled: boolean;
  selectable: boolean;
  reason?: string;
}

export interface MetadataModelEditDraft {
  metadataId: string;
  relationId: string;
  expectedMetadataVersion: number;
  fields: Record<string, MetadataField>;
  fieldProperties: Record<string, MetadataFieldPropertyDraft>;
  capabilitySelections: Record<string, boolean>;
}

export type MetadataFieldGovernanceKind =
  | 'BUSINESS'
  | 'CAPABILITY_DERIVED'
  | 'PLATFORM_SYSTEM'
  | 'RELATION_FOREIGN_KEY';

export function createMetadataModelEditSession() {
  const draft = ref<MetadataModelEditDraft>();
  const initialFields = ref<Record<string, MetadataField>>({});
  const initialFieldProperties = ref<Record<string, MetadataFieldPropertyDraft>>({});
  const initialCapabilities = ref<Record<string, boolean>>({});

  const editing = computed(() => draft.value !== undefined);
  const isDirty = computed(() => {
    const current = draft.value;
    if (!current) return false;
    if (Object.keys(current.fields).length !== Object.keys(initialFields.value).length) return true;
    if (
      Object.entries(current.fields).some(
        ([id, field]) => JSON.stringify(field) !== JSON.stringify(initialFields.value[id]),
      )
    ) {
      return true;
    }
    if (
      Object.keys(current.fieldProperties).some(
        (id) =>
          JSON.stringify(current.fieldProperties[id]) !== JSON.stringify(initialFieldProperties.value[id]),
      ) ||
      Object.keys(initialFieldProperties.value).some((id) => !(id in current.fieldProperties))
    ) {
      return true;
    }
    return Object.entries(current.capabilitySelections).some(
      ([capability, enabled]) => enabled !== initialCapabilities.value[capability],
    );
  });

  function begin(
    metadataId: string,
    relationId: string,
    expectedMetadataVersion: number,
    fields: MetadataField[],
    capabilities: MetadataCapabilityDraftSource[],
    fieldProperties: MetadataFieldPropertySummary[] = [],
  ) {
    initialFields.value = fieldMap(fields);
    initialFieldProperties.value = propertyMap(initialFields.value, fieldProperties);
    initialCapabilities.value = capabilityMap(capabilities);
    draft.value = {
      metadataId,
      relationId,
      expectedMetadataVersion,
      fields: copyFieldMap(initialFields.value),
      fieldProperties: copyFieldPropertyMap(initialFieldProperties.value),
      capabilitySelections: { ...initialCapabilities.value },
    };
  }

  function cancel() {
    draft.value = undefined;
    initialFields.value = {};
    initialFieldProperties.value = {};
    initialCapabilities.value = {};
  }

  function stageField(field: MetadataField, property?: MetadataFieldPropertyDraft) {
    const current = draft.value;
    const key = field.id ?? (field.fieldName ? `new:${field.fieldName}` : undefined);
    if (!current || !key) return;
    current.fields = { ...current.fields, [key]: { ...field } };
    if (property) {
      current.fieldProperties = { ...current.fieldProperties, [key]: copyFieldPropertyDraft(property) };
    }
  }

  function propertyForField(field: MetadataField): MetadataFieldPropertyDraft {
    const current = draft.value;
    const key = field.id ?? (field.fieldName ? `new:${field.fieldName}` : undefined);
    return key && current?.fieldProperties[key]
      ? current.fieldProperties[key]
      : emptyFieldPropertyDraft('BASIC');
  }

  function stageCapability(capability: string, enabled: boolean, selectable: boolean) {
    const current = draft.value;
    if (!current || !selectable) return;
    current.capabilitySelections = { ...current.capabilitySelections, [capability]: enabled };
  }

  function fieldsForDisplay(fallback: MetadataField[]): MetadataField[] {
    return draft.value ? Object.values(draft.value.fields) : fallback;
  }

  function buildProposal(): MetadataRelationChangeSetProposal | undefined {
    const current = draft.value;
    if (!current) return undefined;
    const fieldDrafts: MetadataFieldChangeSetDraft[] = [];
    for (const [id, field] of Object.entries(current.fields)) {
      const initial = initialFields.value[id];
      const property = current.fieldProperties[id] ?? emptyFieldPropertyDraft('BASIC');
      const initialProperty = initialFieldProperties.value[id];
      const propertyChanged = JSON.stringify(property) !== JSON.stringify(initialProperty);
      if (!initial) {
        fieldDrafts.push({
          operation: 'ADD',
          field: { ...field },
          property: property?.kind === 'BASIC' ? undefined : toFieldPropertyChangeSetPayload(property),
        });
      } else if (JSON.stringify(field) !== JSON.stringify(initial) || propertyChanged) {
        fieldDrafts.push({
          operation: 'UPDATE',
          fieldId: id,
          expectedFieldVersion: initial.version,
          field: { ...field },
          property: propertyChanged ? toFieldPropertyChangeSetPayload(property) : undefined,
        });
      }
    }
    const capabilitySelections = Object.fromEntries(
      Object.entries(current.capabilitySelections).filter(
        ([capability, enabled]) => enabled !== initialCapabilities.value[capability],
      ),
    );
    return { expectedMetadataVersion: current.expectedMetadataVersion, capabilitySelections, fieldDrafts };
  }

  return {
    draft,
    editing,
    isDirty,
    begin,
    cancel,
    stageField,
    propertyForField,
    stageCapability,
    fieldsForDisplay,
    buildProposal,
  };
}

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
  capabilitySelections: Record<string, boolean>;
  fieldDrafts: MetadataFieldChangeSetDraft[];
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

function capabilityMap(capabilities: MetadataCapabilityDraftSource[]): Record<string, boolean> {
  return Object.fromEntries(capabilities.map((capability) => [capability.capability, capability.enabled]));
}
