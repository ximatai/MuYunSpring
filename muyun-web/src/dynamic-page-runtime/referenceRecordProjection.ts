import type {
  RecordFormFieldDescriptor,
  RecordFormRecord,
  ReferenceRecordDetailMutation,
} from '@muyun/platform-components';

/**
 * Applies published ONE-reference display projections after the referenced
 * record is saved, without changing the source record's reference value.
 */
export function applyReferenceRecordProjection(
  draft: RecordFormRecord,
  fields: ReadonlyMap<string, RecordFormFieldDescriptor>,
  mutation: ReferenceRecordDetailMutation,
): RecordFormRecord {
  if (mutation.type !== 'saved' || !mutation.record) return draft;

  const projections: Record<string, unknown> = {};
  for (const field of fields.values()) {
    const reference = field.reference;
    if (
      reference?.cardinality !== 'ONE' ||
      reference.targetModuleAlias !== mutation.targetModuleAlias ||
      String(draft[field.fieldRef.fieldName] ?? '') !== mutation.recordId
    )
      continue;
    for (const projection of reference.displayProjections ?? []) {
      if (mutation.record[projection.targetField] !== undefined)
        projections[projection.outputField] = mutation.record[projection.targetField];
    }
  }
  return Object.keys(projections).length > 0 ? { ...draft, ...projections } : draft;
}
