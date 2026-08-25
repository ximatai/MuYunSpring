import type {
  Option,
  OptionValueList,
  ResolvedReferenceFieldDescriptor,
  BooleanStatusPresentation,
  ResolvedOptionFieldDescriptor,
  ResolvedFileReferenceFieldDescriptor,
  ResolvedFieldControlDescriptor,
  ResolvedModuleUiDescriptor,
  ResolvedViewFieldDescriptor,
  ViewFieldValueType,
  FieldValuePresentation,
  FormGroupDescriptor,
  UiFormula,
  UiRule,
  ViewFieldDefinition,
} from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import type { PickerConstraint, RecordPickerRecord } from './recordPickerConstraints';
import type { WebTreeNode } from '@muyun/web-contracts';
import type { RecordPickerMode } from './recordPickerModel';
import { FormulaRuntime } from '../formula/FormulaRuntime';

export type RecordFormFieldDescriptor = (ViewFieldDefinition | ResolvedViewFieldDescriptor) & {
  /** Optional during the protocol migration; resolved descriptors take precedence over legacy uiType. */
  fieldControl?: ResolvedFieldControlDescriptor;
  option?: ResolvedOptionFieldDescriptor;
  reference?: ResolvedReferenceFieldDescriptor;
  fileReference?: ResolvedFileReferenceFieldDescriptor;
  formGroup?: FormGroupDescriptor;
  /** Published storage type; resolved descriptors use it to select a lossless editor codec. */
  valueType?: ViewFieldValueType;
};
export type RecordFormRecord = Record<string, unknown>;
/**
 * Ephemeral, descriptor-authorized values from selected ONE references. These are deliberately
 * separate from the draft because they describe the selected target, not fields owned by it.
 */
export type RecordFormSelectionContext = Record<string, unknown>;

/**
 * Applies the platform-owned consequence of changing a reference dependency.
 * The owner of a draft record calls this after accepting a field edit, so the
 * behavior is identical for regular forms and one-cell-at-a-time child grids.
 */
export function applyReferenceDependencyClears(
  record: RecordFormRecord,
  fieldName: string,
  value: RecordFormFieldValue,
  fields: Map<string, RecordFormFieldDescriptor> | undefined,
): RecordFormRecord {
  const next = { ...record, [fieldName]: value };
  if (record[fieldName] === value || !fields) return next;

  // Candidate dependencies form a directed graph. Traverse the whole graph,
  // including an already-empty intermediary, because a lower-level picker can
  // still hold a value invalidated by an ancestor change.
  const pendingSourceFields = [fieldName];
  const visitedSourceFields = new Set<string>();
  while (pendingSourceFields.length > 0) {
    const sourceField = pendingSourceFields.shift();
    if (!sourceField || visitedSourceFields.has(sourceField)) continue;
    visitedSourceFields.add(sourceField);

    for (const [dependentFieldName, descriptor] of fields) {
      if (
        dependentFieldName === fieldName ||
        !descriptor.reference?.candidateDependencies?.some(
          (dependency) => dependency.sourceField === sourceField,
        )
      ) {
        continue;
      }
      if (next[dependentFieldName] != null) {
        next[dependentFieldName] = undefined;
      }
      pendingSourceFields.push(dependentFieldName);
    }
  }
  return next;
}
/**
 * Transport values emitted by the standard editor. JSON is deliberately represented as parsed
 * objects/arrays rather than a display string, so dynamic records retain their JSON column
 * semantics all the way to the HTTP request.
 */
export type RecordFormFieldValue =
  | string
  | number
  | boolean
  | OptionValueList
  | string[]
  | JsonValue
  | undefined;
export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };
/** A business boolean does not inherit the lifecycle field's implicit enabled default. */
export type RecordBooleanStatusValue = boolean | undefined;
export type RecordFormFieldControlType =
  | 'input'
  | 'numberInput'
  | 'dateInput'
  | 'dateTimeInput'
  | 'textarea'
  | 'colorPicker'
  | 'select'
  | 'enabledStatus'
  | 'booleanStatus'
  | 'switch'
  | 'recordPicker'
  | 'recordMultiPicker'
  | 'fileTransfer'
  | 'imageFileTransfer'
  | 'unsupported';

export interface RecordFieldRenderer {
  rendererType: string;
  controlType: Exclude<RecordFormFieldControlType, 'unsupported'>;
  supports: (field: RecordFormFieldDescriptor) => boolean;
}

/**
 * The standard-form renderer catalog maps platform semantics to local form facades. It intentionally
 * contains no UI-adapter component names, so a descriptor cannot choose arbitrary client code.
 */
export const recordFieldRendererRegistry: readonly RecordFieldRenderer[] = [
  { rendererType: 'TEXT', controlType: 'input', supports: () => true },
  { rendererType: 'TEXTAREA', controlType: 'textarea', supports: () => true },
  { rendererType: 'NUMBER', controlType: 'numberInput', supports: () => true },
  { rendererType: 'DECIMAL', controlType: 'numberInput', supports: () => true },
  { rendererType: 'DATE', controlType: 'dateInput', supports: () => true },
  { rendererType: 'DATETIME', controlType: 'dateTimeInput', supports: () => true },
  { rendererType: 'COLOR_PICKER', controlType: 'colorPicker', supports: () => true },
  { rendererType: 'JSON', controlType: 'textarea', supports: () => true },
  { rendererType: 'SWITCH', controlType: 'switch', supports: () => true },
  // Both select variants depend on a published option binding. A missing binding must not
  // silently become a free-text field, otherwise configured enum semantics are lost.
  { rendererType: 'SELECT', controlType: 'select', supports: (field) => field.option != null },
  // A collection has no scalar fallback.  Without its option binding, a select renderer cannot
  // preserve the array transport contract, so reject the editor rather than degrading to UiInput.
  { rendererType: 'MULTI_SELECT', controlType: 'select', supports: (field) => field.option != null },
  { rendererType: 'ENABLED_STATUS', controlType: 'enabledStatus', supports: () => true },
  {
    rendererType: 'BOOLEAN_STATUS',
    controlType: 'booleanStatus',
    supports: (field) => field.booleanStatus != null,
  },
  {
    rendererType: 'RECORD_PICKER',
    controlType: 'recordPicker',
    supports: (field) => field.reference?.cardinality === 'ONE' || isTreeParentPicker(field),
  },
  {
    rendererType: 'RECORD_PICKER',
    controlType: 'recordMultiPicker',
    supports: (field) => field.reference?.cardinality === 'MANY',
  },
  {
    rendererType: 'FILE',
    controlType: 'fileTransfer',
    supports: (field) => field.fileReference != null && !isSingleImageFileReference(field.fileReference),
  },
  {
    rendererType: 'FILE',
    controlType: 'imageFileTransfer',
    supports: (field) => field.fileReference != null && isSingleImageFileReference(field.fileReference),
  },
];

/**
 * Every tree resource owns a `parentId` relationship.  `treeRootTitle` is the descriptor-level
 * declaration that this form field uses the standard tree-parent picker; the host provides the
 * resource-scoped tree client and root sentinel behavior.
 */
function isTreeParentPicker(field: RecordFormFieldDescriptor) {
  return field.fieldRef?.fieldName === 'parentId' && Boolean(field.treeRootTitle);
}

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
  loadOptions?: (keyword: string) => Promise<RecordPickerRecord[]>;
  loadTree?: () => Promise<WebTreeNode<RecordPickerRecord>[]>;
  resolveOptions?: (values: string[]) => Promise<RecordPickerRecord[]>;
  reloadKey?: number;
  mode?: RecordPickerMode;
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
  fieldControl?: ResolvedFieldControlDescriptor;
  /**
   * Published storage semantics drive the editor wire codec. In particular LONG and DECIMAL
   * deliberately remain text in the browser so JSON.stringify cannot round enterprise values.
   */
  valueType?: ViewFieldValueType;
  /** Set only when an authoritative field-control descriptor cannot be executed safely. */
  rendererDiagnostic?: string;
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
  resource?: string,
): Map<string, RecordFormFieldDescriptor> {
  const detailView =
    (resource
      ? uiDescriptor?.editorContributions?.find((contribution) => contribution.resource === resource)?.editor
      : undefined) ??
    uiDescriptor?.page?.detail.display ??
    uiDescriptor?.page?.detail.editor ??
    uiDescriptor?.defaultEditor;
  const references = new Map(
    (uiDescriptor?.fileReferences ?? []).map((reference) => [fieldRefKey(reference.fieldRef), reference]),
  );
  return new Map(
    detailView?.fields
      .filter((field) => field.fieldControl?.alias !== 'password')
      .map((field) => [
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
    selectionContext?: RecordFormSelectionContext;
  } = {},
): RecordFormFieldState {
  const field = options.fields?.get(fieldName);
  const fallback = options.fallback?.[fieldName];
  const label = field?.label ?? fallback?.label ?? fieldName;
  const required = evaluateUiRule(
    field?.required,
    options.record,
    fallback?.required ?? false,
    options.selectionContext,
  );
  const readOnly = evaluateUiRule(
    field?.readOnly,
    options.record,
    fallback?.readOnly ?? false,
    options.selectionContext,
  );
  const visible = evaluateUiRule(
    field?.visible,
    options.record,
    fallback?.visible ?? true,
    options.selectionContext,
  );
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
    ...(field?.fieldControl ? { fieldControl: field.fieldControl } : {}),
    ...(field?.valueType ? { valueType: field.valueType } : {}),
    columnSpan: field?.columnSpan === 2 ? 2 : 1,
    hasOption,
    pickerConfig,
    ...(field?.fieldControl && controlType === 'unsupported'
      ? { rendererDiagnostic: rendererDiagnostic(field.fieldControl) }
      : {}),
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
          optionSelectionMode: fieldControlSelectionMode(field) ?? field.option.selectionMode,
          ...(field.option.inlineItems?.length ? { optionItems: field.option.inlineItems } : {}),
          ...(field.option.titleField ? { optionTitleField: field.option.titleField } : {}),
        }
      : {}),
    ...(!field?.option && fieldControlSelectionMode(field)
      ? { optionSelectionMode: fieldControlSelectionMode(field) }
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
  selectionContext?: RecordFormSelectionContext,
) {
  if (!rule) return fallback;
  if (typeof rule.constant === 'boolean') return rule.constant;
  return rule.formula ? evaluateUiFormula(rule.formula, record ?? {}, selectionContext) : fallback;
}

export function evaluateUiFormula(
  formula: UiFormula,
  record: RecordFormRecord,
  selectionContext?: RecordFormSelectionContext,
): boolean {
  return new FormulaRuntime().evaluateWebUi(formula.program, { ...record, ...selectionContext });
}

/**
 * Produces the only reference-derived values that WEB_UI formulas may observe. The descriptor
 * owns both the source field and relative target paths; browser code never infers a path from a
 * candidate object. The first delivery supports ONE references only.
 */
export function resolveReferenceSelectionContext(
  sourceField: string,
  reference: ResolvedReferenceFieldDescriptor | undefined,
  record: RecordPickerRecord | undefined,
): RecordFormSelectionContext {
  if (reference?.cardinality !== 'ONE' || !record?.projections) return {};
  const context: RecordFormSelectionContext = {};
  for (const projection of reference.selectionProjections ?? []) {
    if (!isReferenceSelectionProjectionPath(projection.path)) continue;
    const projectionKey = projection.path.join('.');
    if (Object.hasOwn(record.projections, projectionKey)) {
      context[`${sourceField}.${projectionKey}`] = record.projections[projectionKey];
    }
  }
  return context;
}

function isReferenceSelectionProjectionPath(path: string[]) {
  return path.length > 0 && path.every((segment) => /^[A-Za-z][A-Za-z0-9_]*$/.test(segment));
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
  // A file-reference declaration is the source-of-truth transport contract. It must never
  // degrade into a generic text renderer merely because an older descriptor also carries an
  // inferred TEXT control for its String storage column.
  if (field?.fileReference) {
    return isSingleImageFileReference(field.fileReference) ? 'imageFileTransfer' : 'fileTransfer';
  }
  if (field?.fieldControl) {
    return resolveFieldControlType(field, field.fieldControl);
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

function resolveFieldControlType(
  field: RecordFormFieldDescriptor,
  fieldControl: ResolvedFieldControlDescriptor,
): RecordFormFieldControlType {
  const renderer = recordFieldRendererRegistry.find(
    (candidate) => candidate.rendererType === fieldControl.rendererType && candidate.supports(field),
  );
  return renderer?.controlType ?? 'unsupported';
}

function rendererDiagnostic(fieldControl: ResolvedFieldControlDescriptor) {
  const rendererRegistered = recordFieldRendererRegistry.some(
    (candidate) => candidate.rendererType === fieldControl.rendererType,
  );
  if (rendererRegistered) {
    return `字段控件“${fieldControl.alias}”的 renderer“${fieldControl.rendererType}”缺少可执行的字段契约，已拒绝编辑。`;
  }
  return `字段控件“${fieldControl.alias}”的 renderer“${fieldControl.rendererType}”未在当前页面运行器登记，已拒绝编辑。`;
}

function fieldControlSelectionMode(field: RecordFormFieldDescriptor | undefined) {
  if (field?.fieldControl?.rendererType === 'MULTI_SELECT') {
    return 'MULTIPLE' as const;
  }
  return undefined;
}

/**
 * Native number inputs emit text. INTEGER is the only standard numeric field that is safe as a
 * JavaScript number; LONG and DECIMAL use an exact textual wire form and are parsed by the
 * field-aware server deserializer. Do not turn these values into `Number` before JSON encoding.
 */
export function decodeNumberEditorValue(
  value: string,
  valueType?: ViewFieldValueType,
): number | string | undefined {
  if (!value.trim()) return undefined;
  const text = value.trim();
  if (valueType === 'LONG') {
    return /^[-+]?\d+$/.test(text) ? canonicalIntegerWireValue(text) : undefined;
  }
  if (valueType === 'DECIMAL') {
    return /^[-+]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(text) ? text : undefined;
  }
  const decoded = Number(text);
  if (!Number.isFinite(decoded)) return undefined;
  // Legacy uiType-only descriptors do not publish a storage type yet. Preserve their previous
  // numeric behavior during protocol migration; resolved INTEGER descriptors take the strict
  // safe-integer path, while resolved LONG/DECIMAL never arrive here.
  return valueType === 'INTEGER' && !Number.isSafeInteger(decoded) ? undefined : decoded;
}

function canonicalIntegerWireValue(value: string) {
  const negative = value.startsWith('-');
  const unsigned = value.replace(/^[-+]/, '').replace(/^0+(?=\d)/, '');
  return negative && unsigned !== '0' ? `-${unsigned}` : unsigned;
}

/** Converts the canonical UTC-second record value to the browser-local datetime-local value. */
export function formatDateTimeLocalEditorValue(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value.trim()) return undefined;
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return value;
  const pad = (part: number) => String(part).padStart(2, '0');
  return (
    `${instant.getFullYear()}-${pad(instant.getMonth() + 1)}-${pad(instant.getDate())}` +
    `T${pad(instant.getHours())}:${pad(instant.getMinutes())}:${pad(instant.getSeconds())}`
  );
}

/** Converts browser-local datetime-local input to the dynamic-record UTC-second wire contract. */
export function decodeDateTimeLocalEditorValue(value: string): string | undefined {
  if (!value.trim()) return undefined;
  const local = new Date(value);
  if (Number.isNaN(local.getTime())) return undefined;
  return local.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

export function formatJsonEditorValue(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return undefined;
  }
}

export function decodeJsonEditorValue(value: string): JsonValue | undefined {
  if (!value.trim()) return undefined;
  const decoded = JSON.parse(value) as JsonValue;
  if (!Array.isArray(decoded) && (decoded === null || typeof decoded !== 'object')) {
    throw new Error('JSON 字段必须是对象或数组');
  }
  return decoded;
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
