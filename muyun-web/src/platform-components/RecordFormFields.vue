<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  UiButton,
  UiColorPicker,
  UiInput,
  UiSelect,
  UiSwitch,
  UiTextArea,
  UiTreeSelect,
} from '@muyun/vue-ui-antdv';
import type { OptionItemDescriptor, OptionValue, OptionValueList } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import RecordStatusSwitch from './RecordStatusSwitch.vue';
import RecordStatusTag from './RecordStatusTag.vue';
import RecordPicker from './RecordPicker.vue';
import RecordMultiPicker from './RecordMultiPicker.vue';
import RecordFileReferenceTransfer from './RecordFileReferenceTransfer.vue';
import SingleImageFileReferenceField from './SingleImageFileReferenceField.vue';
import FileSizeText from './FileSizeText.vue';
import RecordContentSectionHeading from './RecordContentSectionHeading.vue';
import {
  resolveRecordFormFieldNames,
  resolveRecordFormFieldState,
  resolveRecordBooleanStatusValue,
  resolveReferenceSelectionContext,
  decodeDateTimeLocalEditorValue,
  decodeJsonEditorValue,
  decodeNumberEditorValue,
  formatDateTimeLocalEditorValue,
  formatJsonEditorValue,
  type RecordFormFieldDescriptor,
  type RecordFormFieldFallback,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldState,
  type RecordFormFieldValue,
  type RecordFormRecord,
  type RecordFormSelectionContext,
} from './recordFormFieldModel';
import { hasOptionHierarchy, optionItemsToOptions, optionItemsToTree } from './optionFieldOptions';
import { loadOptionFieldItems } from './optionFieldOptionCache';

defineOptions({ name: 'RecordFormFields' });

export interface RecordFormValidity {
  valid: boolean;
  errors: Record<string, string>;
}

const props = withDefaults(
  defineProps<{
    record: RecordFormRecord;
    fieldNames?: string[];
    excludeFieldNames?: string[];
    fields?: Map<string, RecordFormFieldDescriptor>;
    fallback?: Record<string, RecordFormFieldFallback>;
    pickerConfigs?: Record<string, RecordFormFieldPickerConfig>;
    optionContext?: ModuleContext<unknown>;
    fileTransferContext?: ModuleContext<unknown>;
    formSessionKey?: string | number;
    validationRequestKey?: number;
    disabled?: boolean;
    showLabels?: boolean;
    compact?: boolean;
    disabledOf?: (fieldName: string, field: RecordFormFieldState) => boolean;
    placeholderOf?: (fieldName: string, field: RecordFormFieldState) => string | undefined;
    imageUploadHintOf?: (fieldName: string, field: RecordFormFieldState) => string | undefined;
    imageUploadAdvisoryOf?: (
      fieldName: string,
      field: RecordFormFieldState,
    ) => ((file: File) => string | undefined | Promise<string | undefined>) | undefined;
  }>(),
  {
    fieldNames: undefined,
    fields: undefined,
    excludeFieldNames: () => [],
    fallback: () => ({}),
    pickerConfigs: () => ({}),
    optionContext: undefined,
    fileTransferContext: undefined,
    formSessionKey: undefined,
    validationRequestKey: 0,
    disabled: false,
    showLabels: true,
    compact: false,
    disabledOf: undefined,
    placeholderOf: undefined,
    imageUploadHintOf: undefined,
    imageUploadAdvisoryOf: undefined,
  },
);

const emit = defineEmits<{
  'update:field': [fieldName: string, value: RecordFormFieldValue];
  'validity-change': [validity: RecordFormValidity];
}>();

const resolvedFieldNames = computed(
  () =>
    props.fieldNames ??
    resolveRecordFormFieldNames(props.fields, props.fallback, { exclude: props.excludeFieldNames }),
);

const fieldStates = computed<RecordFormFieldState[]>(() =>
  resolvedFieldNames.value.map(fieldState).filter((field) => field.visible),
);
const optionItems = ref<Record<string, OptionItemDescriptor[]>>({});
const loadingOptionFields = ref(new Set<string>());
const optionFieldErrors = ref<Record<string, string>>({});
const INHERIT_OPTION_VALUE = '__muyun_inherit__';
const editorFieldErrors = ref<Record<string, string>>({});
const referenceSelectionContext = ref<RecordFormSelectionContext>({});
const referenceSelectionSourceIds = ref<Record<string, string | undefined>>({});
const referenceSelectionValues = computed(() =>
  [...(props.fields ?? new Map<string, RecordFormFieldDescriptor>())]
    .filter(([, field]) => field.reference?.cardinality === 'ONE')
    .map(([fieldName]) => [fieldName, recordPickerFieldValue(fieldName)] as const),
);
const requiredFieldErrors = computed<Record<string, string>>(() => {
  const errors: Record<string, string> = {};
  for (const field of fieldStates.value) {
    if (!field.required) continue;
    const value = props.record[field.fieldName];
    const missing =
      value == null ||
      (typeof value === 'string' && value.trim() === '') ||
      (Array.isArray(value) && value.length === 0);
    if (missing) errors[field.fieldName] = `请填写${field.label}`;
  }
  return errors;
});
const formValidity = computed<RecordFormValidity>(() => {
  const errors = {
    ...requiredFieldErrors.value,
    ...optionFieldErrors.value,
    ...editorFieldErrors.value,
  };
  for (const field of fieldStates.value) {
    if (field.controlType === 'unsupported') {
      errors[field.fieldName] = field.rendererDiagnostic ?? '该字段控件当前不可编辑';
    }
  }
  return { valid: Object.keys(errors).length === 0, errors };
});

onMounted(() => {
  void loadOptionFields();
  initializeRequiredColorFields();
});
watch(
  () => props.fields,
  () => {
    void loadOptionFields();
    initializeRequiredColorFields();
  },
);
watch([() => props.record.id, () => props.formSessionKey], () => {
  // A new record/session must never inherit parser failures from its predecessor.
  editorFieldErrors.value = {};
  clearReferenceSelectionContext();
});
watch(referenceSelectionValues, (values) => {
  for (const [fieldName, value] of values) {
    if (
      referenceSelectionSourceIds.value[fieldName] != null &&
      referenceSelectionSourceIds.value[fieldName] !== value
    ) {
      updateReferenceSelectionContext(fieldName, undefined);
    }
  }
});
watch(formValidity, (validity) => emit('validity-change', validity), { immediate: true });

function fieldState(fieldName: string): RecordFormFieldState {
  return resolveRecordFormFieldState(fieldName, {
    fields: props.fields,
    fallback: props.fallback,
    pickerConfigs: props.pickerConfigs,
    placeholderOf: props.placeholderOf,
    record: props.record,
    selectionContext: referenceSelectionContext.value,
  });
}

function optionFieldValue(fieldName: string) {
  const value = props.record[fieldName];
  if (Array.isArray(value)) {
    return value.map(optionWireValue).filter((item): item is OptionValue => item !== undefined);
  }
  return optionWireValue(value);
}

function scalarFieldValue(fieldName: string) {
  const value = props.record[fieldName];
  return value === undefined || value === null ? undefined : String(value);
}

/** Mutation responses may retain the display projection ({ code, title }); editors consume its wire code. */
function optionWireValue(value: unknown): OptionValue | undefined {
  if (typeof value === 'string' || typeof value === 'number') {
    return value;
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const code = (value as Record<string, unknown>).code;
    return typeof code === 'string' || typeof code === 'number' ? code : undefined;
  }
  return undefined;
}

/** Record pickers accept identity scalars even when a response includes an enriched reference object. */
function recordPickerFieldValue(fieldName: string) {
  const value = props.record[fieldName];
  if (typeof value === 'string' || typeof value === 'number') {
    return String(value);
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const record = value as Record<string, unknown>;
    const identity = record.id ?? record.alias ?? record.code;
    return typeof identity === 'string' || typeof identity === 'number' ? String(identity) : undefined;
  }
  return undefined;
}

function editorFieldValue(field: RecordFormFieldState) {
  const value = props.record[field.fieldName];
  if (field.controlType === 'dateTimeInput') {
    return formatDateTimeLocalEditorValue(value);
  }
  if (field.fieldControl?.rendererType === 'JSON') {
    return formatJsonEditorValue(value);
  }
  return value === undefined || value === null ? undefined : String(value);
}

function fileSizeValue(fieldName: string) {
  const value = props.record[fieldName];
  return typeof value === 'number' || typeof value === 'string' || typeof value === 'bigint'
    ? value
    : undefined;
}

function stringArrayFieldValue(fieldName: string) {
  const value = props.record[fieldName];
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function optionFieldItems(field: RecordFormFieldState) {
  return field.optionItems ?? optionItems.value[field.fieldName] ?? [];
}

function optionFieldOptions(field: RecordFormFieldState) {
  return field.options ?? optionItemsToOptions(optionFieldItems(field));
}

function isOverrideField(field: RecordFormFieldState) {
  return field.overrideOf != null && (field.valueType === 'BOOLEAN' || field.controlType === 'select');
}

function overrideEditorValue(field: RecordFormFieldState): OptionValue {
  if (field.valueType === 'BOOLEAN') {
    const value = props.record[field.fieldName];
    return value === true ? 'true' : value === false ? 'false' : INHERIT_OPTION_VALUE;
  }
  const value = optionWireValue(props.record[field.fieldName]);
  if (value != null) return value;
  return INHERIT_OPTION_VALUE;
}

function overrideOptions(field: RecordFormFieldState) {
  const inheritedValue = field.overrideOf == null ? undefined : props.record[field.overrideOf];
  const options =
    field.valueType === 'BOOLEAN'
      ? [
          { label: '开启', value: 'true' },
          { label: '关闭', value: 'false' },
        ]
      : optionFieldOptions(field);
  return [
    { label: `继承（${overrideInheritedLabel(field, inheritedValue)}）`, value: INHERIT_OPTION_VALUE },
    ...options,
  ];
}

function overrideInheritedLabel(field: RecordFormFieldState, value: unknown): string {
  if (field.valueType === 'BOOLEAN') return value === true ? '开启' : '关闭';
  const code = optionWireValue(value);
  return (
    optionFieldOptions(field).find((option) => option.value === code)?.label ??
    (code == null ? '未设置' : String(code))
  );
}

function optionFieldTree(field: RecordFormFieldState) {
  return optionItemsToTree(optionFieldItems(field));
}

function optionFieldIsTree(field: RecordFormFieldState) {
  return hasOptionHierarchy(optionFieldItems(field));
}

function optionFieldLoading(field: RecordFormFieldState) {
  return loadingOptionFields.value.has(field.fieldName);
}

function optionFieldError(field: RecordFormFieldState) {
  return optionFieldErrors.value[field.fieldName];
}

function retryOptionField(field: RecordFormFieldState) {
  const descriptor = props.fields?.get(field.fieldName);
  if (descriptor) {
    void loadOptionField(descriptor);
  }
}

function optionFieldMultiple(field: RecordFormFieldState) {
  return field.optionSelectionMode === 'MULTIPLE';
}

async function loadOptionFields() {
  if (!props.optionContext || !props.fields) {
    return;
  }
  for (const field of props.fields.values()) {
    if (!field.option || field.option.inlineItems?.length) {
      continue;
    }
    await loadOptionField(field);
  }
}

async function loadOptionField(field: RecordFormFieldDescriptor) {
  if (!props.optionContext || !field.option) {
    return;
  }
  const fieldName = field.fieldRef.fieldName;
  loadingOptionFields.value = new Set(loadingOptionFields.value).add(fieldName);
  try {
    const items = await loadOptionFieldItems(props.optionContext, fieldName);
    optionItems.value = { ...optionItems.value, [fieldName]: items };
    const errors = { ...optionFieldErrors.value };
    delete errors[fieldName];
    optionFieldErrors.value = errors;
  } catch {
    optionFieldErrors.value = { ...optionFieldErrors.value, [fieldName]: '选项加载失败，请重试' };
  } finally {
    const next = new Set(loadingOptionFields.value);
    next.delete(fieldName);
    loadingOptionFields.value = next;
  }
}

function booleanFieldValue(fieldName: string) {
  // A business boolean is opt-in.  Treating an absent value as enabled makes a fresh
  // aggregate-row checkbox look checked although its payload has never set it to true.
  return props.record[fieldName] === true;
}

function enabledStatusFieldValue(fieldName: string) {
  // Enablement is the platform's opt-out status: a newly created record is enabled
  // unless it explicitly carries `false`.
  return props.record[fieldName] !== false;
}

function businessBooleanStatusValue(fieldName: string) {
  return resolveRecordBooleanStatusValue(props.record[fieldName]);
}

function fieldDisabled(field: RecordFormFieldState) {
  return props.disabled || field.readOnly || props.disabledOf?.(field.fieldName, field) === true;
}

function resolvedFileTransferContext() {
  return props.fileTransferContext ?? props.optionContext;
}

function updateField(fieldName: string, value: RecordFormFieldValue) {
  emit('update:field', fieldName, value);
}

function applyPickerSelection(
  fieldName: string,
  record: import('./recordPickerConstraints').RecordPickerRecord | undefined,
) {
  if (!record?.affectPatch) return;
  for (const [patchField, patchValue] of Object.entries(record.affectPatch)) {
    if (patchField !== fieldName) {
      emit('update:field', patchField, patchValue as RecordFormFieldValue);
    }
  }
}

/**
 * Reference projections are transient presentation facts. They are kept out of the form draft
 * and are only mapped through a descriptor-declared path for WEB_UI formula evaluation.
 */
function updateReferenceSelectionContext(
  fieldName: string,
  record: import('./recordPickerConstraints').RecordPickerRecord | undefined,
) {
  const reference = props.fields?.get(fieldName)?.reference;
  if (!reference || reference.cardinality !== 'ONE') return;
  const next = { ...referenceSelectionContext.value };
  for (const projection of reference.selectionProjections ?? []) {
    if (projection.path.length > 0) {
      delete next[`${fieldName}.${projection.path.join('.')}`];
    }
  }
  Object.assign(next, resolveReferenceSelectionContext(fieldName, reference, record));
  referenceSelectionContext.value = next;
  referenceSelectionSourceIds.value = {
    ...referenceSelectionSourceIds.value,
    [fieldName]: record?.id,
  };
}

function clearReferenceSelectionContext() {
  referenceSelectionContext.value = {};
  referenceSelectionSourceIds.value = {};
}

function applyMultiPickerSelection(
  fieldName: string,
  records: import('./recordPickerConstraints').RecordPickerRecord[],
) {
  for (const record of records) {
    applyPickerSelection(fieldName, record);
  }
}

function updateEditorField(field: RecordFormFieldState, value: string) {
  try {
    const decoded = decodeEditorValue(field, value);
    clearEditorFieldError(field.fieldName);
    emit('update:field', field.fieldName, decoded);
  } catch (error) {
    setEditorFieldError(field.fieldName, error instanceof Error ? error.message : '字段值格式无效');
  }
}

function decodeEditorValue(field: RecordFormFieldState, value: string): RecordFormFieldValue {
  if (field.controlType === 'numberInput') {
    const decoded = decodeNumberEditorValue(value, field.valueType);
    if (decoded === undefined && value.trim()) throw new Error('请输入有效数字');
    return decoded;
  }
  if (field.controlType === 'dateTimeInput') {
    const decoded = decodeDateTimeLocalEditorValue(value);
    if (decoded === undefined && value.trim()) throw new Error('请输入有效日期时间');
    return decoded;
  }
  if (field.fieldControl?.rendererType === 'JSON') {
    try {
      return decodeJsonEditorValue(value);
    } catch {
      throw new Error('请输入有效 JSON');
    }
  }
  return value;
}

function setEditorFieldError(fieldName: string, message: string) {
  editorFieldErrors.value = { ...editorFieldErrors.value, [fieldName]: message };
}

function clearEditorFieldError(fieldName: string) {
  if (!editorFieldErrors.value[fieldName]) return;
  const errors = { ...editorFieldErrors.value };
  delete errors[fieldName];
  editorFieldErrors.value = errors;
}

function editorFieldError(field: RecordFormFieldState) {
  return editorFieldErrors.value[field.fieldName];
}

function requiredFieldError(field: RecordFormFieldState) {
  return requiredFieldErrors.value[field.fieldName];
}

function fieldInvalid(field: RecordFormFieldState) {
  return Boolean(requiredFieldError(field) || optionFieldError(field) || editorFieldError(field));
}

/** Required color fields must persist the same default color that the picker presents. */
function initializeRequiredColorFields() {
  for (const field of fieldStates.value) {
    const value = scalarFieldValue(field.fieldName);
    if (field.controlType === 'colorPicker' && field.required && (!value || !value.trim())) {
      emit('update:field', field.fieldName, '#1677FF');
    }
  }
}

function updateSelectField(field: RecordFormFieldState, value: OptionValue | OptionValueList | null) {
  if (Array.isArray(value)) {
    emit('update:field', field.fieldName, value);
    return;
  }
  emit('update:field', field.fieldName, value ?? undefined);
}

function updateOverrideField(field: RecordFormFieldState, value: OptionValue | OptionValueList | null) {
  if (value === INHERIT_OPTION_VALUE || value == null) {
    emit('update:field', field.fieldName, undefined);
    return;
  }
  if (field.valueType === 'BOOLEAN') {
    emit('update:field', field.fieldName, value === 'true');
    return;
  }
  updateSelectField(field, value);
}

function groupOf(field: RecordFormFieldState | undefined) {
  return field?.formGroup;
}

function groupStartsAt(field: RecordFormFieldState, index: number) {
  return groupOf(field)?.groupCode !== groupOf(fieldStates.value[index - 1])?.groupCode;
}

function groupEndsAt(field: RecordFormFieldState, index: number) {
  return (
    groupOf(field) != null && groupOf(field)?.groupCode !== groupOf(fieldStates.value[index + 1])?.groupCode
  );
}
</script>

<template>
  <template
    v-for="(field, index) in fieldStates"
    :key="`${field.fieldName}:${formSessionKey ?? ''}:${validationRequestKey}`"
  >
    <slot name="before-field" :field="field" />
    <template v-if="groupOf(field) && groupStartsAt(field, index)">
      <div v-if="!groupOf(fieldStates[index - 1])" class="record-form-group-divider" aria-hidden="true" />
      <RecordContentSectionHeading
        class="record-form-group-heading"
        :title="groupOf(field)?.title ?? ''"
        :subtitle="groupOf(field)?.subtitle"
      />
    </template>
    <label
      class="record-form-field"
      :class="{
        'record-form-field-full-row': field.columnSpan === 2,
        'record-form-field--compact': compact,
        'record-form-field--validation-pulse': validationRequestKey > 0 && fieldInvalid(field),
      }"
    >
      <span v-if="showLabels && field.controlType !== 'imageFileTransfer'" class="record-form-field-label">
        {{ field.label }}
        <strong v-if="field.required" aria-hidden="true">*</strong>
      </span>
      <div class="record-form-field-control">
        <RecordStatusSwitch
          v-if="field.controlType === 'enabledStatus'"
          :enabled="enabledStatusFieldValue(field.fieldName)"
          :disabled="fieldDisabled(field)"
          :show-label="false"
          @change="updateField(field.fieldName, $event)"
        />
        <RecordStatusTag
          v-else-if="field.controlType === 'booleanStatus'"
          :enabled="businessBooleanStatusValue(field.fieldName)"
          :enabled-label="field.booleanStatus?.trueLabel"
          :disabled-label="field.booleanStatus?.falseLabel"
          :enabled-tone="field.booleanStatus?.trueTone"
          :disabled-tone="field.booleanStatus?.falseTone"
        />
        <UiSelect
          v-else-if="isOverrideField(field)"
          :value="overrideEditorValue(field)"
          :options="overrideOptions(field)"
          :disabled="fieldDisabled(field)"
          :loading="field.controlType === 'select' && optionFieldLoading(field)"
          :allow-clear="false"
          @update:value="updateOverrideField(field, $event)"
        />
        <UiSwitch
          v-else-if="field.controlType === 'switch'"
          :checked="booleanFieldValue(field.fieldName)"
          :disabled="fieldDisabled(field)"
          @change="updateField(field.fieldName, $event)"
        />
        <RecordPicker
          v-else-if="field.controlType === 'recordPicker' && field.pickerConfig"
          :value="recordPickerFieldValue(field.fieldName)"
          :context="field.pickerConfig.context"
          :load-options="field.pickerConfig.loadOptions"
          :load-tree="field.pickerConfig.loadTree"
          :resolve-options="field.pickerConfig.resolveOptions"
          :reload-key="field.pickerConfig.reloadKey"
          :mode="field.pickerConfig.mode"
          :placeholder="field.placeholder"
          :disabled="fieldDisabled(field)"
          :allow-clear="field.pickerConfig.allowClear"
          :constraints="field.pickerConfig.constraints"
          :title-of="field.pickerConfig.titleOf"
          :description-of="field.pickerConfig.descriptionOf"
          :filter-option="field.pickerConfig.filterOption"
          @update:value="updateField(field.fieldName, $event)"
          @select="applyPickerSelection(field.fieldName, $event)"
          @selection-resolved="updateReferenceSelectionContext(field.fieldName, $event)"
        />
        <RecordMultiPicker
          v-else-if="field.controlType === 'recordMultiPicker' && field.pickerConfig"
          :value="stringArrayFieldValue(field.fieldName)"
          :context="field.pickerConfig.context"
          :load-options="field.pickerConfig.loadOptions"
          :load-tree="field.pickerConfig.loadTree"
          :resolve-options="field.pickerConfig.resolveOptions"
          :reload-key="field.pickerConfig.reloadKey"
          :mode="field.pickerConfig.mode"
          :placeholder="field.placeholder"
          :disabled="fieldDisabled(field)"
          :allow-clear="field.pickerConfig.allowClear"
          :constraints="field.pickerConfig.constraints"
          :title-of="field.pickerConfig.titleOf"
          :description-of="field.pickerConfig.descriptionOf"
          :filter-option="field.pickerConfig.filterOption"
          @update:value="updateField(field.fieldName, $event)"
          @select="applyMultiPickerSelection(field.fieldName, $event)"
        />
        <SingleImageFileReferenceField
          v-else-if="
            field.controlType === 'imageFileTransfer' && field.fileReference && resolvedFileTransferContext()
          "
          :label="field.label"
          :required="field.required"
          :value="record[field.fieldName]"
          :record="record"
          :context="resolvedFileTransferContext()!"
          :definition="field.fileReference"
          :upload-hint="imageUploadHintOf?.(field.fieldName, field)"
          :upload-advisory="imageUploadAdvisoryOf?.(field.fieldName, field)"
          :form-session-key="formSessionKey"
          :disabled="fieldDisabled(field)"
          :disabled-hint="field.disabledHint"
          @update:value="updateField(field.fieldName, $event)"
        />
        <RecordFileReferenceTransfer
          v-else-if="
            field.controlType === 'fileTransfer' && field.fileReference && resolvedFileTransferContext()
          "
          :value="record[field.fieldName]"
          :record="record"
          :context="resolvedFileTransferContext()!"
          :definition="field.fileReference"
          :form-session-key="formSessionKey"
          :disabled="fieldDisabled(field)"
          :disabled-hint="field.disabledHint"
          @update:value="updateField(field.fieldName, $event)"
        />
        <UiTreeSelect
          v-else-if="field.controlType === 'select' && optionFieldIsTree(field)"
          :value="optionFieldValue(field.fieldName)"
          :tree-data="optionFieldTree(field)"
          :mode="optionFieldMultiple(field) ? 'multiple' : undefined"
          :placeholder="field.placeholder"
          :disabled="fieldDisabled(field)"
          :allow-clear="!field.required"
          :loading="optionFieldLoading(field)"
          @update:value="updateSelectField(field, $event)"
        />
        <UiSelect
          v-else-if="
            field.controlType === 'select' && (field.hasOption || optionFieldOptions(field).length > 0)
          "
          :value="optionFieldValue(field.fieldName)"
          :options="optionFieldOptions(field)"
          :mode="optionFieldMultiple(field) ? 'multiple' : undefined"
          :placeholder="field.placeholder"
          :disabled="fieldDisabled(field)"
          :allow-clear="!field.required"
          :loading="optionFieldLoading(field)"
          @update:value="updateSelectField(field, $event)"
        />
        <UiTextArea
          v-else-if="field.controlType === 'textarea'"
          :value="editorFieldValue(field)"
          :disabled="fieldDisabled(field)"
          :placeholder="field.placeholder"
          @update:value="updateEditorField(field, $event)"
        />
        <UiInput
          v-else-if="field.controlType === 'numberInput'"
          :value="editorFieldValue(field)"
          type="number"
          step="any"
          :disabled="fieldDisabled(field)"
          :placeholder="field.placeholder"
          @update:value="updateEditorField(field, $event)"
        />
        <UiInput
          v-else-if="field.controlType === 'dateInput'"
          :value="editorFieldValue(field)"
          type="date"
          :disabled="fieldDisabled(field)"
          :placeholder="field.placeholder"
          @update:value="updateEditorField(field, $event)"
        />
        <UiInput
          v-else-if="field.controlType === 'dateTimeInput'"
          :value="editorFieldValue(field)"
          type="datetime-local"
          step="1"
          :disabled="fieldDisabled(field)"
          :placeholder="field.placeholder"
          @update:value="updateEditorField(field, $event)"
        />
        <UiColorPicker
          v-else-if="field.controlType === 'colorPicker'"
          :value="scalarFieldValue(field.fieldName)"
          :disabled="fieldDisabled(field)"
          @update:value="updateField(field.fieldName, $event)"
        />
        <FileSizeText
          v-else-if="field.valuePresentation === 'FILE_SIZE'"
          :value="fileSizeValue(field.fieldName)"
        />
        <div
          v-else-if="field.controlType === 'unsupported'"
          class="record-form-field-diagnostic"
          role="alert"
        >
          {{ field.rendererDiagnostic }}
        </div>
        <UiInput
          v-else
          :value="scalarFieldValue(field.fieldName)"
          :type="field.fieldControl?.alias === 'password' ? 'password' : 'text'"
          :disabled="fieldDisabled(field)"
          :placeholder="field.placeholder"
          @update:value="updateField(field.fieldName, $event)"
        />
      </div>
      <div v-if="optionFieldError(field)" class="record-form-field-error">
        <span>{{ optionFieldError(field) }}</span>
        <UiButton type="link" :disabled="optionFieldLoading(field)" @click="retryOptionField(field)">
          重试
        </UiButton>
      </div>
      <div v-if="editorFieldError(field)" class="record-form-field-error" role="alert">
        {{ editorFieldError(field) }}
      </div>
    </label>
    <slot name="after-field" :field="field" />
    <div v-if="groupEndsAt(field, index)" class="record-form-group-divider" aria-hidden="true" />
  </template>
</template>

<style scoped>
.record-form-field {
  display: grid;
  gap: var(--muyun-record-form-label-gap, 6px);
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.record-form-field-full-row {
  grid-column: 1 / -1;
}

.record-form-field--compact {
  gap: 2px;
  min-width: 120px;
}

.record-form-group-divider {
  grid-column: 1 / -1;
  height: 1px;
  margin: 4px 0 0;
  background: var(--muyun-border-subtle);
}

.record-form-group-heading {
  grid-column: 1 / -1;
}

.record-form-field-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.record-form-field-label strong {
  color: var(--muyun-danger-base);
  font-weight: 600;
}

.record-form-field-error {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--muyun-danger-base);
  font-size: 12px;
}

.record-form-field-control {
  position: relative;
  min-width: 0;
}

.record-form-field-control :deep(.ant-select),
.record-form-field-control :deep(.ant-picker) {
  width: 100%;
}

.record-form-field--validation-pulse .record-form-field-control::after {
  position: absolute;
  z-index: 1;
  inset: 0;
  border: 1px solid transparent;
  border-radius: var(--muyun-radius-control, 4px);
  content: '';
  pointer-events: none;
  animation: record-form-validation-pulse 720ms ease-out;
}

@keyframes record-form-validation-pulse {
  25%,
  75% {
    border-color: var(--muyun-danger-base);
    box-shadow: 0 0 0 2px var(--muyun-danger-soft);
  }
}

.record-form-field-diagnostic {
  border: 1px solid var(--muyun-danger-base);
  border-radius: 4px;
  color: var(--muyun-danger-base);
  line-height: 1.5;
  padding: 8px 10px;
}

@media (max-width: 720px) {
}
</style>
