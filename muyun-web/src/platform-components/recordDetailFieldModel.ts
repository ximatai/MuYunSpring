import type { RecordPickerRecord } from './recordPickerConstraints';
import type { RecordFormFieldState, RecordFormRecord } from './recordFormFieldModel';

export type RecordDetailDisplayValue = string | number | boolean | undefined | null;

export type RecordDetailDisplayResolver = (
  fieldName: string,
  value: unknown,
  record: RecordFormRecord,
  field: RecordFormFieldState,
) => RecordDetailDisplayValue;

export function resolveRecordDetailDisplayValue(
  field: RecordFormFieldState,
  record: RecordFormRecord,
  options: {
    displayOf?: RecordDetailDisplayResolver;
    emptyText?: string;
  } = {},
) {
  const emptyText = options.emptyText ?? '-';
  const value = record[field.fieldName];
  const customValue = options.displayOf?.(field.fieldName, value, record, field);
  if (isPresent(customValue)) {
    return String(customValue);
  }
  const optionTitle = field.optionTitleField ? record[field.optionTitleField] : undefined;
  if (isPresent(optionTitle)) {
    return String(optionTitle);
  }
  if (field.controlType === 'select' && field.options) {
    const option = field.options.find((item) => item.value === value);
    if (option?.label) {
      return option.label;
    }
  }
  const referenceTitle = field.referenceTitleField ? record[field.referenceTitleField] : undefined;
  if (field.controlType === 'recordPicker' && isReferenceSummary(referenceTitle)) {
    return referenceSummaryLabel(referenceTitle) ?? String(value ?? emptyText);
  }
  if (field.controlType === 'recordMultiPicker' && Array.isArray(referenceTitle)) {
    const summaryLabels = referenceTitle
      .filter(isReferenceSummary)
      .map(referenceSummaryLabel)
      .filter((label): label is string => Boolean(label));
    if (summaryLabels.length > 0) {
      return summaryLabels.join('、');
    }
    const titles = referenceTitle.filter(
      (title): title is string | number | boolean =>
        typeof title === 'string' || typeof title === 'number' || typeof title === 'boolean',
    );
    if (titles.length > 0) {
      return titles.join('、');
    }
  }
  if (isPresent(referenceTitle)) {
    return String(referenceTitle);
  }
  if (field.controlType === 'recordPicker' && isRecordPickerRecord(value)) {
    return field.pickerConfig?.titleOf?.(value) ?? value.title ?? value.code ?? value.id ?? emptyText;
  }
  if (!isPresent(value)) {
    return emptyText;
  }
  return String(value);
}

function isPresent(value: unknown): value is string | number | boolean {
  return value !== undefined && value !== null && value !== '';
}

function isRecordPickerRecord(value: unknown): value is RecordPickerRecord {
  return typeof value === 'object' && value !== null;
}

function isReferenceSummary(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function referenceSummaryLabel(summary: Record<string, unknown>) {
  const title = summary.title;
  const alias = summary.alias;
  if (isPresent(title) && isPresent(alias) && String(title) !== String(alias)) {
    return `${title} (${alias})`;
  }
  return isPresent(title) ? String(title) : isPresent(alias) ? String(alias) : undefined;
}
