import type {
  ResolvedDetailRelationDescriptor,
  ResolvedModuleUiDescriptor,
  OptionItemDescriptor,
} from '@muyun/web-contracts';
import {
  evaluateUiFormula,
  resolveRecordFormFields,
  resolveRecordFormFieldState,
  resolveRecordDetailDisplayValue,
  type RecordFormFieldState,
  type RecordFormRecord,
} from '@muyun/platform-components';

/** Read the same authorized display facts as the form; never expose a reference ID as its label. */
export function assistantFieldDisplay(
  field: RecordFormFieldState,
  record: RecordFormRecord,
  optionItems?: OptionItemDescriptor[],
): string {
  if (field.reference) {
    const value = record[field.fieldName];
    if (value == null || value === '' || (Array.isArray(value) && !value.length)) return '未选择';
    if (!field.referenceTitleField || record[field.referenceTitleField] == null)
      return '已选择（名称暂不可用）';
    const display = resolveRecordDetailDisplayValue(field, record, { emptyText: '未选择' });
    return display === String(value) ? '已选择（名称暂不可用）' : display;
  }
  return resolveRecordDetailDisplayValue(field, record, { emptyText: '空', optionItems });
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
export function assistantRelationProjection(
  descriptor: ResolvedModuleUiDescriptor | undefined,
  relations: ResolvedDetailRelationDescriptor[],
  record: RecordFormRecord,
  {
    baseline = {},
    purpose = 'context',
    relationOptions = {},
  }: {
    baseline?: RecordFormRecord;
    purpose?: 'context' | 'confirmation';
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
    const project = (row: RecordFormRecord, index: number) => ({
      row: index + 1,
      values: [...fields.keys()].flatMap((name) => {
        const field = resolveRecordFormFieldState(name, { fields, record: row });
        return !hidden && assistantReadableField(field)
          ? [
              {
                label:
                  relation.queryContract?.listProjection?.fields.find((column) => column.fieldName === name)
                    ?.title ??
                  field.label ??
                  name,
                value: assistantFieldDisplay(field, row, relationOptions[key]?.[name]).slice(
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
        assistantWritable: false,
        operationBoundary: loaded
          ? '明细存在且随整单保存；当前助手仅能读取，请在页面增改明细后回到对话确认保存。'
          : '已声明明细，但当前没有完整行数据，不能据此判断为空。',
        loaded,
        count: loaded ? rows.length : null,
        removedCount: removed.length,
        truncated: !confirmation && (rows.length > 20 || removed.length > 20),
        rows: rows.slice(0, rowLimit).map(project),
        removedRows: removed.slice(0, rowLimit).map(project),
      },
    ];
  });
}
