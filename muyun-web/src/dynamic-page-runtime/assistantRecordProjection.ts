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
export function assistantFieldDisplay(
  field: RecordFormFieldState,
  record: RecordFormRecord,
  optionItems?: OptionItemDescriptor[],
  draft = false,
): string {
  if (field.calculationPending) return '保存后计算';
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
export async function assistantResolvedFieldDisplay(field: RecordFormFieldState, record: RecordFormRecord) {
  if (field.calculationPending) return assistantFieldDisplay(field, record);
  const value = record[field.fieldName];
  if (!field.reference || !field.pickerConfig?.provider || value == null || value === '')
    return assistantFieldDisplay(field, record, undefined, true);
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
export function assistantRelationProjection(
  descriptor: ResolvedModuleUiDescriptor | undefined,
  relations: ResolvedDetailRelationDescriptor[],
  record: RecordFormRecord,
  {
    baseline = {},
    purpose = 'context',
    draft = purpose === 'confirmation',
    relationOptions = {},
    editableRelations = new Set<string>(),
  }: {
    draft?: boolean;
    editableRelations?: ReadonlySet<string>;
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
        return !hidden && assistantReadableField(field)
          ? [
              {
                label:
                  relation.queryContract?.listProjection?.fields.find((column) => column.fieldName === name)
                    ?.title ??
                  field.label ??
                  name,
                value: assistantFieldDisplay(field, row, relationOptions[key]?.[name], pending).slice(
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
        assistantWritable: editableRelations.has(relation.code),
        operationBoundary: editableRelations.has(relation.code)
          ? '可通过 relation.describe 读取和编辑明细草稿；最后审阅并保存整单。'
          : !loaded
            ? '已声明明细，但当前没有完整行数据，不能据此判断为空。'
            : !draft
              ? '当前展示只读明细；若用户要求修改且 record.start-edit 可用，先打开主记录编辑草稿，再核实当前明细编辑能力。'
              : '当前草稿未开放助手明细写入；请在页面增改明细后回到对话确认保存整单。',
        loaded,
        count: loaded ? rows.length : null,
        removedCount: removed.length,
        truncated: !confirmation && (rows.length > 20 || removed.length > 20),
        rows: rows.slice(0, rowLimit).map((row, index) => project(row, index)),
        removedRows: removed.slice(0, rowLimit).map((row, index) => project(row, index, false)),
      },
    ];
  });
}
