import type { RecordFormRecord } from '@muyun/platform-components';
import type { ResolvedViewFieldDescriptor } from '@muyun/web-contracts';

/** Read projections never become business writes, including explicitly named flat outputs. */
export function recordMutationPayload(
  record: RecordFormRecord,
  fields: Iterable<Pick<ResolvedViewFieldDescriptor, 'fieldRef' | 'reference' | 'referenceSummary'>> = [],
): RecordFormRecord {
  const outputs = new Set<string>();
  for (const field of fields) {
    for (const projection of field.reference?.displayProjections ?? []) outputs.add(projection.outputField);
    if (field.referenceSummary) outputs.add(field.fieldRef.fieldName);
  }
  return Object.fromEntries(
    Object.entries(record).filter(([fieldName]) => !fieldName.includes('.') && !outputs.has(fieldName)),
  );
}
