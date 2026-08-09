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
  UiFormula,
  UiRule,
  ViewFieldDefinition,
} from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import type { PickerConstraint, RecordPickerRecord } from './recordPickerConstraints';

export type RecordFormFieldDescriptor = (ViewFieldDefinition | ResolvedViewFieldDescriptor) & {
  option?: ResolvedOptionFieldDescriptor;
  reference?: ResolvedReferenceFieldDescriptor;
  fileReference?: ResolvedFileReferenceFieldDescriptor;
};
export type RecordFormRecord = Record<string, unknown>;
export type RecordFormFieldValue = string | number | boolean | OptionValueList | string[] | undefined;
/** A business boolean does not inherit the lifecycle field's implicit enabled default. */
export type RecordBooleanStatusValue = boolean | undefined;
export type RecordFormFieldControlType =
  | 'input'
  | 'textarea'
  | 'colorPicker'
  | 'select'
  | 'enabledStatus'
  | 'booleanStatus'
  | 'switch'
  | 'recordPicker'
  | 'recordMultiPicker'
  | 'fileTransfer';

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
  referenceTitleField?: string;
  fileReference?: ResolvedFileReferenceFieldDescriptor;
  pickerConfig?: RecordFormFieldPickerConfig;
  booleanStatus?: BooleanStatusPresentation;
  valuePresentation?: FieldValuePresentation;
  disabledHint?: string;
  placeholder?: string;
  options?: Option[];
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
  viewCode = 'default_form',
): Map<string, RecordFormFieldDescriptor> {
  const formView = uiDescriptor?.views?.find(
    (view) => view.viewKind === 'FORM' && view.viewCode === viewCode,
  );
  const references = new Map(
    (uiDescriptor?.fileReferences ?? []).map((reference) => [fieldRefKey(reference.fieldRef), reference]),
  );
  return new Map(
    formView?.fields.map((field) => [
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
    ...((field?.readOnly?.disabledHint ?? fallback?.disabledHint)
      ? { disabledHint: field?.readOnly?.disabledHint ?? fallback?.disabledHint }
      : {}),
  };
  return {
    ...baseState,
    ...(field?.option
      ? {
          optionSelectionMode: field.option.selectionMode,
          ...(field.option.titleField ? { optionTitleField: field.option.titleField } : {}),
        }
      : {}),
    ...(field?.reference?.titleField ? { referenceTitleField: field.reference.titleField } : {}),
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
  const expression = formula.expression.replaceAll(/\s/g, '');
  if (expression.startsWith('!(') && expression.endsWith(')')) {
    return !evaluateUiFormula({ expression: expression.slice(2, -1) }, record);
  }
  const conjunction = expression.split('&&');
  if (conjunction.length > 1) {
    return conjunction.every((term) => evaluateUiFormula({ expression: term }, record));
  }
  const present = /^PRESENT\(\{([A-Za-z][A-Za-z0-9_]*)\}\)$/i.exec(expression);
  if (present) {
    const value = record[present[1]];
    return value !== null && value !== undefined && value !== '';
  }
  // Descriptors produced by the platform are validated against the portable UI grammar.  Keep this safe fallback
  // for hand-written or stale remote descriptors instead of silently treating an unsupported server formula as
  // a browser capability.
  return false;
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
    return 'fileTransfer';
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
