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
import {
  resolveRecordFormFieldNames,
  resolveRecordFormFieldState,
  resolveRecordBooleanStatusValue,
  type RecordFormFieldDescriptor,
  type RecordFormFieldFallback,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldState,
  type RecordFormFieldValue,
  type RecordFormRecord,
} from './recordFormFieldModel';
import { hasOptionHierarchy, optionItemsToOptions, optionItemsToTree } from './optionFieldOptions';
import { loadOptionFieldItems } from './optionFieldOptionCache';

defineOptions({ name: 'RecordFormFields' });

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
    disabled?: boolean;
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
    disabled: false,
    disabledOf: undefined,
    placeholderOf: undefined,
    imageUploadHintOf: undefined,
    imageUploadAdvisoryOf: undefined,
  },
);

const emit = defineEmits<{
  'update:field': [fieldName: string, value: RecordFormFieldValue];
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

function fieldState(fieldName: string): RecordFormFieldState {
  return resolveRecordFormFieldState(fieldName, {
    fields: props.fields,
    fallback: props.fallback,
    pickerConfigs: props.pickerConfigs,
    placeholderOf: props.placeholderOf,
    record: props.record,
  });
}

function optionFieldValue(fieldName: string) {
  const value = props.record[fieldName];
  if (Array.isArray(value)) {
    return value.filter((item): item is OptionValue => typeof item === 'string' || typeof item === 'number');
  }
  return value === undefined || value === null ? undefined : String(value);
}

function scalarFieldValue(fieldName: string) {
  const value = props.record[fieldName];
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
  <template v-for="(field, index) in fieldStates" :key="field.fieldName">
    <template v-if="groupOf(field) && groupStartsAt(field, index)">
      <div v-if="!groupOf(fieldStates[index - 1])" class="record-form-group-divider" aria-hidden="true" />
      <header class="record-form-group-heading">
        <h3>{{ groupOf(field)?.title }}</h3>
        <p v-if="groupOf(field)?.subtitle">{{ groupOf(field)?.subtitle }}</p>
      </header>
    </template>
    <label class="record-form-field" :class="{ 'record-form-field-full-row': field.columnSpan === 2 }">
      <span v-if="field.controlType !== 'imageFileTransfer'" class="record-form-field-label">
        {{ field.label }}
        <strong v-if="field.required" aria-hidden="true">*</strong>
      </span>
      <RecordStatusSwitch
        v-if="field.controlType === 'enabledStatus'"
        :enabled="booleanFieldValue(field.fieldName)"
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
      <UiSwitch
        v-else-if="field.controlType === 'switch'"
        :checked="booleanFieldValue(field.fieldName)"
        :disabled="fieldDisabled(field)"
        @change="updateField(field.fieldName, $event)"
      />
      <RecordPicker
        v-else-if="field.controlType === 'recordPicker' && field.pickerConfig"
        :value="scalarFieldValue(field.fieldName)"
        :context="field.pickerConfig.context"
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
      />
      <RecordMultiPicker
        v-else-if="field.controlType === 'recordMultiPicker' && field.pickerConfig"
        :value="stringArrayFieldValue(field.fieldName)"
        :context="field.pickerConfig.context"
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
        :value="scalarFieldValue(field.fieldName)"
        :disabled="fieldDisabled(field)"
        :placeholder="field.placeholder"
        @update:value="updateField(field.fieldName, $event)"
      />
      <UiInput
        v-else-if="field.controlType === 'numberInput'"
        :value="scalarFieldValue(field.fieldName)"
        type="number"
        :disabled="fieldDisabled(field)"
        :placeholder="field.placeholder"
        @update:value="updateField(field.fieldName, $event)"
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
      <UiInput
        v-else
        :value="scalarFieldValue(field.fieldName)"
        :disabled="fieldDisabled(field)"
        :placeholder="field.placeholder"
        @update:value="updateField(field.fieldName, $event)"
      />
      <div v-if="optionFieldError(field)" class="record-form-field-error">
        <span>{{ optionFieldError(field) }}</span>
        <UiButton type="link" :disabled="optionFieldLoading(field)" @click="retryOptionField(field)">
          重试
        </UiButton>
      </div>
    </label>
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

.record-form-group-divider {
  grid-column: 1 / -1;
  height: 1px;
  margin: 4px 0 0;
  background: var(--muyun-border-subtle);
}

.record-form-group-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  grid-column: 1 / -1;
}

.record-form-group-heading h3,
.record-form-group-heading p {
  margin: 0;
}

.record-form-group-heading h3 {
  color: var(--muyun-text);
  font-size: 14px;
}

.record-form-group-heading p {
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: 1.4;
  text-align: right;
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

@media (max-width: 720px) {
  .record-form-group-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  .record-form-group-heading p {
    text-align: left;
  }
}
</style>
