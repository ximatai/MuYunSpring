import type {
  ResolvedDetailRelationDescriptor,
  ResolvedModuleUiDescriptor,
  OptionItemDescriptor,
} from '@muyun/web-contracts';
import {
  evaluateUiFormula,
  referencePickerDisplayTitle,
  resolveRecordFormFields,
  resolveRecordFormFieldState,
  resolveRecordDetailDisplayValue,
  type RecordFormFieldState,
  type RecordFormRecord,
} from '@muyun/platform-components';

/** Read the same authorized display facts as the form; never expose a reference ID as its label. */
export function recordFieldDisplay(
  field: RecordFormFieldState,
  record: RecordFormRecord,
  optionItems?: OptionItemDescriptor[],
  draft = false,
): string {
  if (field.calculationPending || (draft && field.calculationTiming === 'IMMEDIATE'))
    return resolveRecordDetailDisplayValue(field, record, { emptyText: '空', optionItems, draft });
  if (field.reference) {
    const value = record[field.fieldName];
    if (value == null || value === '' || (Array.isArray(value) && !value.length)) return '未选择';
    if (!field.referenceTitleField || record[field.referenceTitleField] == null)
      return '已选择（名称暂不可用）';
    const display = resolveRecordDetailDisplayValue(field, record, { emptyText: '未选择' });
    return display === String(value) ? '已选择（名称暂不可用）' : display;
  }
  return resolveRecordDetailDisplayValue(field, record, { emptyText: '空', optionItems, draft });
}

/** Resolve display labels through the same authorized provider used by the picker. */
export async function resolvedRecordFieldDisplay(
  field: RecordFormFieldState,
  record: RecordFormRecord,
  optionItems?: OptionItemDescriptor[],
) {
  if (field.calculationPending) return recordFieldDisplay(field, record);
  const value = record[field.fieldName];
  if (!field.reference || !field.pickerConfig?.provider || value == null || value === '')
    return recordFieldDisplay(field, record, optionItems, true);
  const ids = (Array.isArray(value) ? value : [value]).map(String);
  if (!ids.length) return '未选择';
  const candidates = await field.pickerConfig.provider.resolve(ids);
  return ids
    .map((id) => {
      const candidate = candidates.find((item) => item.id === id);
      if (!candidate || candidate.identifierFallback || !candidate.title || candidate.title === id)
        return '已选择（名称暂不可用）';
      const title = referencePickerDisplayTitle(candidate);
      return candidate.unavailable ? `${title}（不可用）` : title;
    })
    .join('、');
}

export function assistantReadableField(field: RecordFormFieldState) {
  return (
    field.visible &&
    field.assistantPolicy !== 'HIDDEN' &&
    field.assistantPolicy !== 'DESCRIBE' &&
    field.fieldControl?.alias !== 'password' &&
    !field.fileReference
  );
}

/** Embedded aggregate facts only; no additional queries or inferred independent-relation rows. */
export function recordRelationProjection(
  descriptor: ResolvedModuleUiDescriptor | undefined,
  relations: ResolvedDetailRelationDescriptor[],
  record: RecordFormRecord,
  {
    baseline = {},
    purpose = 'context',
    draft = purpose === 'confirmation',
    relationOptions = {},
    editableRelations = new Set<string>(),
    includeDescribedValues = false,
    includeBaseline = false,
  }: {
    draft?: boolean;
    editableRelations?: ReadonlySet<string>;
    includeDescribedValues?: boolean;
    includeBaseline?: boolean;
    baseline?: RecordFormRecord;
    purpose?: 'context' | 'confirmation' | 'observation';
    relationOptions?: Record<string, Record<string, OptionItemDescriptor[]>>;
  } = {},
) {
  const confirmation = purpose === 'confirmation';
  const rowLimit = confirmation ? undefined : 20;
  return relations.flatMap((relation) => {
    if (!relation.embeddedField) return [];
    const hidden =
      relation.visible?.constant === false ||
      Boolean(relation.visible?.formula && !evaluateUiFormula(relation.visible.formula, record));
    if (hidden && !confirmation) return [];
    const key = relation.embeddedField;
    const fields = resolveRecordFormFields(descriptor, relation.targetEntityAlias);
    const loaded = Array.isArray(record[key]);
    const rows = loaded ? (record[key] as RecordFormRecord[]) : [];
    const before = Array.isArray(baseline[key]) ? (baseline[key] as RecordFormRecord[]) : [];
    const removed = loaded
      ? before.filter((row) => row.id != null && !rows.some((next) => next.id === row.id))
      : [];
    const project = (row: RecordFormRecord, index: number, pending = draft) => ({
      row: index + 1,
      values: [...fields.keys()].flatMap((name) => {
        if (purpose === 'observation' && (name === 'id' || name === 'version')) return [];
        const field = resolveRecordFormFieldState(name, {
          fields,
          record: row,
          mode: pending ? 'edit' : 'view',
        });
        return !hidden &&
          (assistantReadableField(field) ||
            (includeDescribedValues &&
              field.visible &&
              field.assistantPolicy === 'DESCRIBE' &&
              field.fieldControl?.alias !== 'password' &&
              !field.fileReference))
          ? [
              {
                label:
                  relation.queryContract?.listProjection?.fields.find((column) => column.fieldName === name)
                    ?.title ??
                  field.label ??
                  name,
                ...(field.assistantPolicy ? { assistantPolicy: field.assistantPolicy } : {}),
                value: recordFieldDisplay(field, row, relationOptions[key]?.[name], pending).slice(
                  0,
                  confirmation ? undefined : 2000,
                ),
              },
            ]
          : [];
      }),
    });
    return [
      {
        relationCode: relation.code,
        title: relation.title ?? relation.code,
        editable: editableRelations.has(relation.code),
        source: draft ? 'unsaved-draft' : 'saved-record',
        loaded,
        count: loaded ? rows.length : null,
        removedCount: removed.length,
        truncated:
          !confirmation &&
          (rows.length > 20 || removed.length > 20 || (includeBaseline && before.length > 20)),
        rows: rows.slice(0, rowLimit).map((row, index) => project(row, index)),
        removedRows: removed.slice(0, rowLimit).map((row, index) => project(row, index, false)),
        ...(includeBaseline
          ? {
              savedCount: Array.isArray(baseline[key]) ? before.length : null,
              savedRows: before.slice(0, rowLimit).map((row, index) => project(row, index, false)),
            }
          : {}),
      },
    ];
  });
}
