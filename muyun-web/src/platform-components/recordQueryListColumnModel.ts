import type {
  OptionItemDescriptor,
  QuerySchemaField,
  ResolvedViewDescriptor,
  ResolvedViewFieldDescriptor,
} from '@muyun/web-contracts';
import type { Component } from 'vue';

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
  type?: 'text' | 'enabledStatus' | 'booleanStatus' | 'tagList' | 'datetime' | 'fileSize' | 'colorPicker';
  booleanStatus?: ResolvedViewFieldDescriptor['booleanStatus'];
  width?: string;
  align?: 'left' | 'center' | 'right';
  titleField?: string;
  /** Only descriptor-declared option fields load a runtime option catalog. */
  optionBinding?: boolean;
  /** Dynamic child entity whose declared option binding owns this field. */
  optionEntityAlias?: string;
  /** Runtime dictionary/reference options used when the response has no title companion. */
  optionItems?: OptionItemDescriptor[];
  /** Maximum visible lines for text cells. Defaults to one line. */
  maxDisplayLines?: number;
  render?: (record: QueryListRecord) => string;
}

export interface RecordQueryListCellComponent {
  key: string;
  component: Component;
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
          field.option?.titleField ??
          queryField?.optionTitleField ??
          (field.reference ? `${field.fieldRef.fieldName}Title` : undefined),
        optionBinding: field.option ? true : undefined,
        booleanStatus: field.booleanStatus,
        maxDisplayLines: field.maxDisplayLines,
      };
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
