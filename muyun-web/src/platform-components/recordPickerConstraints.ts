export interface RecordPickerRecord {
  id?: string;
  parentId?: string;
  title?: string;
  code?: string;
  enabled?: boolean;
  /** Server-authorized values applied after choosing this candidate. */
  affectPatch?: Record<string, unknown>;
  /** Server-authorized target projections used only by the owning form's transient UI context. */
  projections?: Record<string, unknown>;
}

export interface PickerConstraintContext<TRecord extends RecordPickerRecord> {
  records: TRecord[];
}

export interface PickerConstraint<TRecord extends RecordPickerRecord> {
  code: string;
  message: string;
  test(record: TRecord, context: PickerConstraintContext<TRecord>): boolean;
}

export function notRecordIds<TRecord extends RecordPickerRecord>(
  ids: Array<string | undefined>,
  message = '不能选择当前记录',
): PickerConstraint<TRecord> {
  const excluded = new Set(ids.filter((id): id is string => Boolean(id)));
  return {
    code: 'not-record-ids',
    message,
    test: (record) => !record.id || !excluded.has(record.id),
  };
}

export function notDescendantOf<TRecord extends RecordPickerRecord>(
  ancestorId?: string,
  message = '不能选择当前记录的下级',
): PickerConstraint<TRecord> {
  return {
    code: 'not-descendant',
    message,
    test: (record, context) =>
      !record.id || !ancestorId || !isDescendant(record.id, ancestorId, context.records),
  };
}

export function enabledOnly<TRecord extends RecordPickerRecord>(
  message = '停用记录不可选',
): PickerConstraint<TRecord> {
  return {
    code: 'enabled-only',
    message,
    test: (record) => record.enabled !== false,
  };
}

export function parentRecordConstraints<TRecord extends RecordPickerRecord>(
  currentId?: string,
): PickerConstraint<TRecord>[] {
  return [notRecordIds<TRecord>([currentId]), notDescendantOf<TRecord>(currentId)];
}

export function firstConstraintMessage<TRecord extends RecordPickerRecord>(
  record: TRecord,
  context: PickerConstraintContext<TRecord>,
  constraints: PickerConstraint<TRecord>[],
) {
  return constraints.find((constraint) => !constraint.test(record, context))?.message;
}

function isDescendant<TRecord extends RecordPickerRecord>(
  recordId: string,
  ancestorId: string,
  records: TRecord[],
) {
  const byId = new Map(records.filter((record) => record.id).map((record) => [record.id, record]));
  let cursor = byId.get(recordId)?.parentId;
  while (cursor) {
    if (cursor === ancestorId) {
      return true;
    }
    cursor = byId.get(cursor)?.parentId;
  }
  return false;
}
