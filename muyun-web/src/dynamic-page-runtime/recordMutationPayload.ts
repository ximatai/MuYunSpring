import type { RecordFormRecord } from '@muyun/platform-components';
import type { ResolvedViewFieldDescriptor } from '@muyun/web-contracts';

/** Read projections and constant read-only fields never become business writes. */
export function recordMutationPayload(
  record: RecordFormRecord,
  fields: Iterable<
    Pick<ResolvedViewFieldDescriptor, 'fieldRef' | 'reference' | 'referenceSummary' | 'readOnly'>
  > = [],
): RecordFormRecord {
  const outputs = new Set<string>();
  for (const field of fields) {
    for (const projection of field.reference?.displayProjections ?? []) outputs.add(projection.outputField);
    if (field.referenceSummary) outputs.add(field.fieldRef.fieldName);
    if (field.readOnly?.constant === true) outputs.add(field.fieldRef.fieldName);
  }
  // Identity and optimistic concurrency belong to the mutation envelope, even when displayed read-only.
  outputs.delete('id');
  outputs.delete('version');
  return Object.fromEntries(
    Object.entries(record).filter(([fieldName]) => !fieldName.includes('.') && !outputs.has(fieldName)),
  );
}
