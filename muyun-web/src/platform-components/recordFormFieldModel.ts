import type {
  Option,
  OptionValueList,
  ResolvedReferenceFieldDescriptor,
  BooleanStatusPresentation,
  ResolvedOptionFieldDescriptor,
  ResolvedFileReferenceFieldDescriptor,
  ResolvedModuleUiDescriptor,
  ResolvedViewFieldDescriptor,
  FieldValuePresentation,
  FormGroupDescriptor,
  UiFormula,
  UiRule,
  ViewFieldDefinition,
} from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import type { PickerConstraint, RecordPickerRecord } from './recordPickerConstraints';
import { FormulaRuntime } from '../formula/FormulaRuntime';

export type RecordFormFieldDescriptor = (ViewFieldDefinition | ResolvedViewFieldDescriptor) & {
  option?: ResolvedOptionFieldDescriptor;
  reference?: ResolvedReferenceFieldDescriptor;
  fileReference?: ResolvedFileReferenceFieldDescriptor;
  formGroup?: FormGroupDescriptor;
};
export type RecordFormRecord = Record<string, unknown>;
export type RecordFormFieldValue = string | number | boolean | OptionValueList | string[] | undefined;
/** A business boolean does not inherit the lifecycle field's implicit enabled default. */
export type RecordBooleanStatusValue = boolean | undefined;
export type RecordFormFieldControlType =
  | 'input'
  | 'numberInput'
  | 'textarea'
  | 'colorPicker'
  | 'select'
  | 'enabledStatus'
  | 'booleanStatus'
  | 'switch'
  | 'recordPicker'
  | 'recordMultiPicker'
  | 'fileTransfer'
  | 'imageFileTransfer';

export interface RecordFormFieldFallback {
  label: string;
  required?: boolean;
  readOnly?: boolean;
  visible?: boolean;
  controlType?: RecordFormFieldControlType;
  placeholder?: string;
  disabledHint?: string;
  options?: Option[];
  optionSelectionMode?: 'SINGLE' | 'MULTIPLE';
}

export interface RecordFormFieldPickerConfig {
  context: ModuleContext<RecordPickerRecord>;
  reloadKey?: number;
  mode?: 'list' | 'tree';
  placeholder?: string;
  allowClear?: boolean;
  constraints?: PickerConstraint<RecordPickerRecord>[];
  titleOf?: (record: RecordPickerRecord) => string;
  descriptionOf?: (record: RecordPickerRecord) => string | undefined;
  filterOption?: (record: RecordPickerRecord, keyword: string) => boolean;
}

export interface RecordFormFieldState {
  fieldName: string;
  label: string;
  required: boolean;
  readOnly: boolean;
  visible: boolean;
  controlType: RecordFormFieldControlType;
  columnSpan: number;
  hasOption: boolean;
  optionSelectionMode?: 'SINGLE' | 'MULTIPLE';
  optionTitleField?: string;
  optionItems?: import('@muyun/web-contracts').OptionItemDescriptor[];
  referenceTitleField?: string;
  /** Title for the standard TreeAbility root sentinel when this field is a tree parent reference. */
  treeRootTitle?: string;
  fileReference?: ResolvedFileReferenceFieldDescriptor;
  pickerConfig?: RecordFormFieldPickerConfig;
  booleanStatus?: BooleanStatusPresentation;
  valuePresentation?: FieldValuePresentation;
  disabledHint?: string;
  placeholder?: string;
  options?: Option[];
  formGroup?: FormGroupDescriptor;
}

export interface ResolveRecordFormFieldNamesOptions {
  explicitOrder?: string[];
  exclude?: Iterable<string>;
}

export function resolveRecordFormFieldNames(
  fields?: Map<string, RecordFormFieldDescriptor>,
  fallback: Record<string, RecordFormFieldFallback> = {},
  options: ResolveRecordFormFieldNamesOptions = {},
): string[] {
  const excluded = new Set(options.exclude ?? []);
  const names: string[] = [];
  const seen = new Set<string>();
  const append = (fieldName: string) => {
    if (!fieldName || excluded.has(fieldName) || seen.has(fieldName)) {
      return;
    }
    seen.add(fieldName);
    names.push(fieldName);
  };

  options.explicitOrder?.forEach(append);
  fields?.forEach((_, fieldName) => append(fieldName));
  Object.keys(fallback).forEach(append);
  return names;
}

export function resolveRecordFormFields(
  uiDescriptor: ResolvedModuleUiDescriptor | undefined,
  resource?: string,
  editorSurface?: string,
): Map<string, RecordFormFieldDescriptor> {
  const formView = resource
    ? uiDescriptor?.editorContributions?.find((contribution) => contribution.resource === resource)?.editor
    : editorSurface
      ? uiDescriptor?.editorSurfaces?.find((surface) => surface.key === editorSurface)?.editor
      : (uiDescriptor?.page?.detail.editor ??
        uiDescriptor?.page?.detail.display ??
        uiDescriptor?.defaultEditor);
  const references = new Map(
    (uiDescriptor?.fileReferences ?? []).map((reference) => [fieldRefKey(reference.fieldRef), reference]),
  );
  const formGroupsByFieldName = new Map<string, FormGroupDescriptor>();
  formView?.formGroups?.forEach((group) => {
    group.fields.forEach((reference) => formGroupsByFieldName.set(reference.fieldName, group));
  });
  return new Map(
    formView?.fields.map((field) => [
      field.fieldRef.fieldName,
      {
        ...field,
        ...(references.has(fieldRefKey(field.fieldRef))
          ? { fileReference: references.get(fieldRefKey(field.fieldRef)) }
          : {}),
        ...(formGroupsByFieldName.has(field.fieldRef.fieldName)
          ? { formGroup: formGroupsByFieldName.get(field.fieldRef.fieldName) }
          : {}),
      },
    ]) ?? [],
  );
}

/**
 * Resolves the read-only page-detail projection.  A detail display is deliberately a separate
 * view from the editor: it may expose contextual fields which are write-hidden (for example a
 * navigator-provided scope), while avoiding editor-only controls in the standard detail grid.
 * Pages without an explicit display retain the existing editor-as-display fallback.
 */
export function resolveRecordDetailFields(
  uiDescriptor: ResolvedModuleUiDescriptor | undefined,
): Map<string, RecordFormFieldDescriptor> {
  const detailView =
    uiDescriptor?.page?.detail.display ?? uiDescriptor?.page?.detail.editor ?? uiDescriptor?.defaultEditor;
  const references = new Map(
    (uiDescriptor?.fileReferences ?? []).map((reference) => [fieldRefKey(reference.fieldRef), reference]),
  );
  return new Map(
    detailView?.fields.map((field) => [
      field.fieldRef.fieldName,
      {
        ...field,
        ...(references.has(fieldRefKey(field.fieldRef))
          ? { fileReference: references.get(fieldRefKey(field.fieldRef)) }
          : {}),
      },
    ]) ?? [],
  );
}

export function resolveRecordFormGroups(
  uiDescriptor: ResolvedModuleUiDescriptor | undefined,
  resource?: string,
  editorSurface?: string,
): FormGroupDescriptor[] {
  const editor = resource
    ? uiDescriptor?.editorContributions?.find((contribution) => contribution.resource === resource)?.editor
    : editorSurface
      ? uiDescriptor?.editorSurfaces?.find((surface) => surface.key === editorSurface)?.editor
      : (uiDescriptor?.page?.detail.editor ??
        uiDescriptor?.page?.detail.display ??
        uiDescriptor?.defaultEditor);
  return editor?.formGroups ?? [];
}

export function childResourceDefaultFormViewCode(resource: string): string {
  if (!/^[a-z][a-z0-9_]{0,62}$/.test(resource)) {
    throw new Error(`invalid child resource code: ${resource}`);
  }
  return `${resource}_default_form`;
}

export function resolveRecordFormFieldState(
  fieldName: string,
  options: {
    fields?: Map<string, RecordFormFieldDescriptor>;
    fallback?: Record<string, RecordFormFieldFallback>;
    pickerConfigs?: Record<string, RecordFormFieldPickerConfig>;
    placeholderOf?: (fieldName: string, field: RecordFormFieldState) => string | undefined;
    record?: RecordFormRecord;
  } = {},
): RecordFormFieldState {
  const field = options.fields?.get(fieldName);
  const fallback = options.fallback?.[fieldName];
  const label = field?.label ?? fallback?.label ?? fieldName;
  const required = evaluateUiRule(field?.required, options.record, fallback?.required ?? false);
  const readOnly = evaluateUiRule(field?.readOnly, options.record, fallback?.readOnly ?? false);
  const visible = evaluateUiRule(field?.visible, options.record, fallback?.visible ?? true);
  const controlType = controlTypeOf(field, fallback);
  const booleanStatus = controlType === 'booleanStatus' ? field?.booleanStatus : undefined;
  const hasOption = field?.option != null;
  const pickerConfig =
    controlType === 'recordPicker' || controlType === 'recordMultiPicker'
      ? options.pickerConfigs?.[fieldName]
      : undefined;
  const baseState: RecordFormFieldState = {
    fieldName,
    label,
    required,
    readOnly,
    visible,
    controlType,
    columnSpan: field?.columnSpan === 2 ? 2 : 1,
    hasOption,
    pickerConfig,
    ...(field?.fileReference ? { fileReference: field.fileReference } : {}),
    ...(booleanStatus ? { booleanStatus } : {}),
    ...(field?.valuePresentation ? { valuePresentation: field.valuePresentation } : {}),
    ...(field?.formGroup ? { formGroup: field.formGroup } : {}),
    ...((field?.readOnly?.disabledHint ?? fallback?.disabledHint)
      ? { disabledHint: field?.readOnly?.disabledHint ?? fallback?.disabledHint }
      : {}),
  };
  return {
    ...baseState,
    ...(field?.option
      ? {
          optionSelectionMode: field.option.selectionMode,
          ...(field.option.inlineItems?.length ? { optionItems: field.option.inlineItems } : {}),
          ...(field.option.titleField ? { optionTitleField: field.option.titleField } : {}),
        }
      : {}),
    ...(field?.reference?.titleField ? { referenceTitleField: field.reference.titleField } : {}),
    ...(field?.treeRootTitle ? { treeRootTitle: field.treeRootTitle } : {}),
    ...(fallback?.options ? { options: fallback.options } : {}),
    placeholder:
      options.placeholderOf?.(fieldName, baseState) ?? fallback?.placeholder ?? pickerConfig?.placeholder,
  };
}

function evaluateUiRule(
  rule: UiRule<boolean> | undefined,
  record: RecordFormRecord | undefined,
  fallback: boolean,
) {
  if (!rule) return fallback;
  if (typeof rule.constant === 'boolean') return rule.constant;
  return rule.formula ? evaluateUiFormula(rule.formula, record ?? {}) : fallback;
}

export function evaluateUiFormula(formula: UiFormula, record: RecordFormRecord): boolean {
  return new FormulaRuntime().evaluateWebUi(formula.program, record);
}

/**
 * Preserves an absent business value as unknown instead of treating it as true.
 * `enabledStatus` deliberately retains its separate lifecycle default semantics.
 */
export function resolveRecordBooleanStatusValue(value: unknown): RecordBooleanStatusValue {
  return typeof value === 'boolean' ? value : undefined;
}

function controlTypeOf(
  field: RecordFormFieldDescriptor | undefined,
  fallback: RecordFormFieldFallback | undefined,
): RecordFormFieldControlType {
  if (field?.fileReference) {
    return isSingleImageFileReference(field.fileReference) ? 'imageFileTransfer' : 'fileTransfer';
  }
  const referenceControlType = referenceControlTypeOf(field?.reference, field?.uiType);
  if (referenceControlType) {
    return referenceControlType;
  }
  if (field?.uiType === 'enabledStatus') {
    return 'enabledStatus';
  }
  if (field?.uiType === 'booleanStatus' && field.booleanStatus) {
    return 'booleanStatus';
  }
  if (field?.uiType === 'switch') {
    return 'switch';
  }
  if (field?.uiType === 'textarea') {
    return 'textarea';
  }
  if (isNumericUiType(field?.uiType)) {
    return 'numberInput';
  }
  if (field?.uiType === 'colorPicker') {
    return 'colorPicker';
  }
  if (field?.uiType === 'recordPicker') {
    return 'recordPicker';
  }
  if (field?.uiType === 'recordMultiPicker') {
    return 'recordMultiPicker';
  }
  if (field?.uiType === 'select') {
    return 'select';
  }
  if (field?.option) {
    return 'select';
  }
  return fallback?.controlType ?? 'input';
}

/**
 * These stable platform control aliases are scalar number editors.  Their precision, range and
 * presentation properties remain renderer work; this mapping deliberately only provides the
 * safe browser numeric-input affordance that every source-independent standard form can use.
 */
function isNumericUiType(uiType: string | undefined) {
  return uiType === 'number' || uiType === 'integer' || uiType === 'amount' || uiType === 'percentage';
}

function isSingleImageFileReference(reference: ResolvedFileReferenceFieldDescriptor) {
  return (
    reference.maxFiles === 1 &&
    reference.allowedMediaTypes.length > 0 &&
    reference.allowedMediaTypes.every((mediaType) => mediaType.trim().toLowerCase().startsWith('image/'))
  );
}

function fieldRefKey(fieldRef: { relationCode?: string; fieldName: string }) {
  return `${fieldRef.relationCode ?? ''}:${fieldRef.fieldName}`;
}

/** References are semantic fields: their cardinality determines the default editor when metadata has no explicit picker. */
function referenceControlTypeOf(
  reference: ResolvedReferenceFieldDescriptor | undefined,
  uiType: string | undefined,
): Extract<RecordFormFieldControlType, 'recordPicker' | 'recordMultiPicker'> | undefined {
  if (!reference || (uiType != null && uiType !== 'text')) {
    return undefined;
  }
  return reference.cardinality === 'MANY' ? 'recordMultiPicker' : 'recordPicker';
}
