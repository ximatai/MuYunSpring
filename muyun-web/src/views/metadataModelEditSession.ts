import { computed, ref } from 'vue';
import type { MetadataField, ModuleMetadataRelation } from '@muyun/web-contracts';

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
  ) {
    initialFields.value = fieldMap(fields);
    initialCapabilities.value = capabilityMap(capabilities);
    draft.value = {
      metadataId,
      relationId,
      expectedMetadataVersion,
      fields: copyFieldMap(initialFields.value),
      capabilitySelections: { ...initialCapabilities.value },
    };
  }

  function cancel() {
    draft.value = undefined;
    initialFields.value = {};
    initialCapabilities.value = {};
  }

  function stageField(field: MetadataField) {
    const current = draft.value;
    const key = field.id ?? (field.fieldName ? `new:${field.fieldName}` : undefined);
    if (!current || !key) return;
    current.fields = { ...current.fields, [key]: { ...field } };
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
      if (!initial) {
        fieldDrafts.push({ operation: 'ADD', field: { ...field } });
      } else if (JSON.stringify(field) !== JSON.stringify(initial)) {
        fieldDrafts.push({
          operation: 'UPDATE',
          fieldId: id,
          expectedFieldVersion: initial.version,
          field: { ...field },
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
      BUSINESS: '业务字段',
      CAPABILITY_DERIVED: '能力派生',
      PLATFORM_SYSTEM: '平台系统',
      RELATION_FOREIGN_KEY: '关系外键',
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

function capabilityMap(capabilities: MetadataCapabilityDraftSource[]): Record<string, boolean> {
  return Object.fromEntries(capabilities.map((capability) => [capability.capability, capability.enabled]));
}
