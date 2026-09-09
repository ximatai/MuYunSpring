import type { RecordFormRecord } from '@muyun/platform-components';

/** Platform write fields are flat; dot keys belong to read-model reference projections. */
export function recordMutationPayload(record: RecordFormRecord): RecordFormRecord {
  return Object.fromEntries(Object.entries(record).filter(([fieldName]) => !fieldName.includes('.')));
}
