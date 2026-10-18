import type { RecordFormRecord } from '@muyun/platform-components';
import type { ResolvedViewFieldDescriptor } from '@muyun/web-contracts';

/** Read projections and constant read-only fields never become business writes. */
export function recordMutationPayload(
  record: RecordFormRecord,
  fields: Iterable<
    Pick<ResolvedViewFieldDescriptor, 'fieldRef' | 'reference' | 'referenceSummary' | 'readOnly'>
  > = [],
  capabilities: Iterable<string> = [],
): RecordFormRecord {
  // Platform storage facts remain read-only when the page does not compose them.
  const outputs = new Set<string>([
    'tenantId',
    'deleted',
    'deletedAt',
    'deletedBy',
    'createdBy',
    'createdAt',
    'updatedBy',
    'updatedAt',
    'authUserId',
    'authAssigneeIds',
    'authMemberIds',
    'authOrganizationId',
    'authDepartmentId',
    'authModuleAlias',
  ]);
  if (new Set(capabilities).has('APPROVAL')) {
    for (const field of [
      'approvalInstanceId',
      'approvalStatus',
      'approvalSubmittedBy',
      'approvalSubmittedAt',
      'approvalCompletedAt',
    ])
      outputs.add(field);
  }
  for (const field of fields) {
    for (const projection of field.reference?.displayProjections ?? []) outputs.add(projection.outputField);
    if (field.referenceSummary) outputs.add(field.fieldRef.fieldName);
    if (field.readOnly?.constant === true) outputs.add(field.fieldRef.fieldName);
  }
  // Identity and optimistic concurrency belong to the mutation envelope, even when displayed read-only.
  outputs.delete('id');
  outputs.delete('version');
  return Object.fromEntries(
    Object.entries(record).filter(
      ([fieldName]) =>
        fieldName !== '$texts' &&
        fieldName !== '$contentTexts' &&
        !fieldName.includes('.') &&
        !outputs.has(fieldName),
    ),
  );
}
