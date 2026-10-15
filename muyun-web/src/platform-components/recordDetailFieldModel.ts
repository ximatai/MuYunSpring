import { selectionCodesOf } from './optionFieldOptions';
import { formatPlatformDateTime } from './platformDateTime';
import type { OptionItemDescriptor } from '@muyun/web-contracts';
import type { RecordPickerRecord } from './recordPickerConstraints';
import type { RecordFormFieldState, RecordFormRecord } from './recordFormFieldModel';
import { resolveRecordEnabledStatusValue } from './recordFormFieldModel';
import { readonlyReferenceDisplay } from './readonlyReferenceDisplay';
import {
  recordPresentationLabel,
  recordPresentationText,
  presentationLabel,
  platformLanguageContext,
  type LanguageContext,
} from '@muyun/web-core';

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
    optionItems?: OptionItemDescriptor[];
    /** Editable display defaults are not facts about an absent saved value. */
    draft?: boolean;
    language?: LanguageContext;
  } = {},
) {
  const language = options.language ?? platformLanguageContext;
  if (field.calculationPending) return language.t('platform.form.calculation-pending', '保存后计算');
  const emptyText = options.emptyText ?? '-';
  const value = record[field.fieldName];
  const customValue = options.displayOf?.(field.fieldName, value, record, field);
  if (isPresent(customValue)) {
    return String(customValue);
  }
  if (!options.draft && recordPresentationText(record, field.fieldName))
    return recordPresentationLabel(record, field.fieldName, language) ?? String(value);
  const scale = field.inputRequirements?.scale;
  if (
    field.valueType === 'DECIMAL' &&
    typeof value === 'number' &&
    Number.isFinite(value) &&
    typeof scale === 'number' &&
    Number.isInteger(scale) &&
    scale >= 0 &&
    scale <= 20
  ) {
    return new Intl.NumberFormat('en-US', {
      useGrouping: false,
      minimumFractionDigits: scale,
      maximumFractionDigits: scale,
    }).format(value);
  }
  if (options.draft && field.controlType === 'enabledStatus') {
    return resolveRecordEnabledStatusValue(value)
      ? language.t('platform.value.enabled', '启用')
      : language.t('platform.value.disabled', '停用');
  }
  // Saved name projections have no identity binding to a newly computed draft code.
  const computedDraft = options.draft && field.calculationTiming === 'IMMEDIATE';
  const items = field.optionItems ?? options.optionItems ?? [];
  const codes = selectionCodesOf(value);
  if (items.some((item) => item.titleText || item.titleSource)) {
    if (!codes.length) return emptyText;
    return codes
      .map((code) => {
        const item = items.find((candidate) => candidate.code === code);
        return item
          ? presentationLabel(item.title, item.titleText, options.language ?? platformLanguageContext)
          : code;
      })
      .join(language.t('platform.list.separator', '、'));
  }
  const optionTitle = !computedDraft && field.optionTitleField ? record[field.optionTitleField] : undefined;
  if (isPresent(optionTitle)) {
    return String(optionTitle);
  }
  if (field.controlType === 'select' && field.options) {
    const option = field.options.find((item) => item.value === value);
    if (option?.label) {
      return option.label;
    }
  }
  if (field.hasOption && (field.optionItems || options.optionItems)) {
    const option = (field.optionItems ?? options.optionItems ?? []).find(
      (item) => item.code === String(value),
    );
    if (option) {
      return presentationLabel(option.title, option.titleText, options.language ?? platformLanguageContext);
    }
  }
  // `treeRootTitle` names the virtual root inside an editor picker. In a read-only detail,
  // it is an absent parent rather than record data, so preserve the platform empty-value display.
  if (field.treeRootTitle && value === 'root') {
    return emptyText;
  }
  if (computedDraft && field.reference) {
    return isPresent(value)
      ? language.t('platform.reference.selected-name-unavailable', '已选择（名称暂不可用）')
      : emptyText;
  }
  const referenceTitle = field.referenceTitleField ? record[field.referenceTitleField] : undefined;
  if (field.reference) {
    const display = readonlyReferenceDisplay(field.reference, value, referenceTitle, language);
    if (display !== undefined) return display || emptyText;
  }
  if (field.controlType === 'recordPicker' && isReferenceSummary(referenceTitle)) {
    return referenceSummaryLabel(referenceTitle, language) ?? String(value ?? emptyText);
  }
  if (field.controlType === 'recordMultiPicker' && Array.isArray(referenceTitle)) {
    const summaryLabels = referenceTitle
      .filter(isReferenceSummary)
      .map((summary) => referenceSummaryLabel(summary, language))
      .filter((label): label is string => Boolean(label));
    if (summaryLabels.length > 0) {
      return summaryLabels.join(language.t('platform.list.separator', '、'));
    }
    const titles = referenceTitle.filter(
      (title): title is string | number | boolean =>
        typeof title === 'string' || typeof title === 'number' || typeof title === 'boolean',
    );
    if (titles.length > 0) {
      return titles.join(language.t('platform.list.separator', '、'));
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
  if (field.controlType === 'dateTimeInput' && (typeof value === 'string' || typeof value === 'number')) {
    return formatPlatformDateTime(value, { language }).text;
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

function referenceSummaryLabel(summary: Record<string, unknown>, language: LanguageContext) {
  const title = recordPresentationLabel(summary, 'title', language);
  const alias = summary.alias;
  if (isPresent(title) && isPresent(alias) && String(title) !== String(alias)) {
    return `${title} (${alias})`;
  }
  return isPresent(title) ? String(title) : isPresent(alias) ? String(alias) : undefined;
}
