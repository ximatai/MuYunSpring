import { selectionCodesOf } from './optionFieldOptions';
import type {
  OptionItemDescriptor,
  QuerySchemaField,
  ResolvedViewDescriptor,
  ResolvedViewFieldDescriptor,
  ResolvedReferenceFieldDescriptor,
} from '@muyun/web-contracts';
import type { Component } from 'vue';
import { readonlyReferenceDisplay, readonlyReferenceSummaryOf } from './readonlyReferenceDisplay';
import { resolveRecordBooleanStatusValue } from './recordFormFieldModel';
import { formatPlatformDateTime } from './platformDateTime';
import { formatPlatformFileSize } from './platformFileSize';
import {
  presentationLabel,
  recordPresentationLabel,
  platformLanguageContext,
  type LanguageContext,
} from '@muyun/web-core';

export type QueryListRecord = Record<string, unknown> & { id?: string; enabled?: boolean };
export type RecordQueryListMode = 'normal' | 'recycleBin';
export type StandardCrudRowActionKey = 'view' | 'edit' | 'delete';

/**
 * Source-neutral list-column projection consumed by the standard table shell.
 * It deliberately carries presentation facts only; cell rendering remains the table's responsibility.
 */
export interface RecordQueryListColumn {
  key: string;
  title: string;
  /** Declared CODE resource for a contributed column heading. */
  titleKey?: string;
  type?: 'text' | 'enabledStatus' | 'booleanStatus' | 'tagList' | 'datetime' | 'fileSize' | 'colorPicker';
  booleanStatus?: ResolvedViewFieldDescriptor['booleanStatus'];
  width?: string;
  align?: 'left' | 'center' | 'right';
  titleField?: string;
  /** Compiled reference facts for pure read-side title/summary rendering. */
  reference?: ResolvedReferenceFieldDescriptor;
  /** Only descriptor-declared option fields load a runtime option catalog. */
  optionBinding?: boolean;
  /** Dynamic child entity whose declared option binding owns this field. */
  optionEntityAlias?: string;
  /** Runtime dictionary/reference options used when the response has no title companion. */
  optionItems?: OptionItemDescriptor[];
  /** Maximum visible lines for text cells. Defaults to one line. */
  maxDisplayLines?: number;
  render?: (record: QueryListRecord) => string;
  /** Explicit list-presentation policy for excluding a visible column from assistant projection. */
  assistantReadable?: boolean;
  assistantPolicy?: ResolvedViewFieldDescriptor['assistantPolicy'];
}

export interface RecordQueryListCellComponent {
  key: string;
  component: Component;
}

export interface RecordQueryListDisplayContext {
  timeZone?: string;
  language?: LanguageContext;
}

/**
 * Compiles the source-neutral resolved list descriptor into the standard table's presentation model.
 *
 * `queryFields` is optional because descriptors normally carry their own value and option facts. It
 * preserves the runtime list's compatibility fallback for older descriptors that still rely on the
 * query schema for timestamp and option-title information.
 */
export function resolveRecordQueryListColumns(
  view: ResolvedViewDescriptor | undefined,
  queryFields: readonly QuerySchemaField[] = [],
): RecordQueryListColumn[] {
  if (!view) {
    return [];
  }
  const queryFieldByName = new Map(queryFields.map((field) => [field.name, field]));
  return view.fields
    .filter((field) => field.visible?.constant !== false)
    .map((field) => {
      const queryField = queryFieldByName.get(field.fieldRef.fieldName);
      return {
        key: field.fieldRef.fieldName,
        title: field.label ?? field.fieldRef.fieldName,
        type: columnType(field, queryField),
        width: field.width,
        align: normalizeColumnAlign(field.align),
        titleField:
          field.reference?.titleField ??
          field.option?.titleField ??
          queryField?.optionTitleField ??
          (field.reference ? `${field.fieldRef.fieldName}Title` : undefined),
        ...(field.reference ? { reference: field.reference } : {}),
        ...(field.option ? { optionBinding: true } : {}),
        booleanStatus: field.booleanStatus,
        maxDisplayLines: field.maxDisplayLines,
        ...(field.assistantPolicy ? { assistantPolicy: field.assistantPolicy } : {}),
        ...(field.fieldControl?.alias === 'password' ? { assistantReadable: false } : {}),
      };
    });
}

/** Pure standard-cell projection shared by the rendered list and assistant-visible result summary. */
export function resolveRecordQueryListDisplayValue(
  record: QueryListRecord,
  column: RecordQueryListColumn,
  cellRenderers: Record<string, (record: QueryListRecord) => string> = {},
  context: RecordQueryListDisplayContext = {},
) {
  const language = context.language ?? platformLanguageContext;
  const rendered = column.render?.(record) ?? cellRenderers[column.key]?.(record);
  const rawValue = record[column.key];
  const presentationValue = rendered ?? rawValue;
  if (column.type === 'enabledStatus' || column.type === 'booleanStatus') {
    const enabled =
      column.type === 'booleanStatus'
        ? resolveRecordBooleanStatusValue(record[column.key])
        : record[column.key] !== false;
    if (enabled === true)
      return column.booleanStatus?.trueLabel ?? language.t('platform.value.enabled', '启用');
    if (enabled === false)
      return column.booleanStatus?.falseLabel ?? language.t('platform.value.disabled', '停用');
    return '-';
  }
  if (column.type === 'tagList') {
    return (Array.isArray(rawValue) ? rawValue : [])
      .filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null)
      .map((item) => String(item.title ?? '').trim())
      .filter(Boolean)
      .join('、');
  }
  if (
    column.type === 'datetime' &&
    (typeof presentationValue === 'string' ||
      typeof presentationValue === 'number' ||
      rawValue instanceof Date)
  ) {
    return formatPlatformDateTime(presentationValue, { language, timeZone: context.timeZone }).text;
  }
  if (
    column.type === 'fileSize' &&
    (typeof presentationValue === 'string' ||
      typeof presentationValue === 'number' ||
      typeof presentationValue === 'bigint')
  ) {
    return formatPlatformFileSize(presentationValue).text;
  }
  if (rendered !== undefined) return rendered;
  if (column.reference) {
    const display = readonlyReferenceDisplay(
      column.reference,
      record[column.key],
      readonlyReferenceSummaryOf(record, column.titleField ?? `${column.key}Title`, language),
      language,
    );
    if (display !== undefined) return display;
  }
  const sourcedOptions = column.optionItems?.filter((item) => item.titleText || item.titleSource);
  if (sourcedOptions?.length) {
    const codes = selectionCodesOf(rawValue);
    if (codes.length && codes.every((code) => sourcedOptions.some((item) => item.code === code)))
      return optionTitlesOf(rawValue, sourcedOptions, context.language).join('、');
  }
  const titleFields = [column.titleField, `${column.key}Title`].filter(
    (value, index, fields): value is string => Boolean(value) && fields.indexOf(value) === index,
  );
  for (const candidate of titleFields) {
    const titleValue = record[candidate];
    if (typeof titleValue === 'string' && titleValue.trim())
      return recordPresentationLabel(record, candidate, language);
  }
  const value = rawValue;
  const optionTitles = optionTitlesOf(value, column.optionItems, context.language);
  if (optionTitles.length > 0) return optionTitles.join('、');
  if (typeof value === 'boolean')
    return value ? language.t('platform.value.yes', '是') : language.t('platform.value.no', '否');
  return typeof value === 'string'
    ? recordPresentationLabel(record, column.key, language)
    : String(value ?? '');
}

function optionTitlesOf(
  value: unknown,
  optionItems: RecordQueryListColumn['optionItems'],
  language: LanguageContext = platformLanguageContext,
): string[] {
  if (!optionItems?.length) return [];
  return selectionCodesOf(value).map((code) => {
    const item = optionItems.find((item) => item.code === code);
    return item ? presentationLabel(item.title, item.titleText, language) : code;
  });
}

function columnType(
  field: ResolvedViewFieldDescriptor,
  queryField: QuerySchemaField | undefined,
): RecordQueryListColumn['type'] {
  if (field.uiType === 'enabledStatus') return 'enabledStatus';
  if (field.uiType === 'booleanStatus' && field.booleanStatus) return 'booleanStatus';
  if (field.uiType === 'tagList') return 'tagList';
  if (field.fieldControl?.rendererType === 'COLOR_PICKER' || field.uiType === 'colorPicker') {
    return 'colorPicker';
  }
  if (field.valuePresentation === 'FILE_SIZE') return 'fileSize';
  if (isDateTimeValueType(field.valueType ?? queryField?.valueType)) return 'datetime';
  return 'text';
}

function isDateTimeValueType(valueType: string | undefined) {
  return valueType === 'TIMESTAMP' || valueType === 'ZONED_TIMESTAMP' || valueType === 'INSTANT';
}

function normalizeColumnAlign(align: string | undefined): RecordQueryListColumn['align'] {
  return align === 'center' || align === 'right' ? align : 'left';
}
