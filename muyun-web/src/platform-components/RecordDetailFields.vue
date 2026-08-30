<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { UiSwitch } from '@muyun/vue-ui-antdv';
import type { OptionItemDescriptor } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import RecordStatusTag from './RecordStatusTag.vue';
import RecordImageFileReferencePreview from './RecordImageFileReferencePreview.vue';
import FileSizeText from './FileSizeText.vue';
import {
  resolveRecordFormFieldNames,
  resolveRecordFormFieldState,
  resolveRecordBooleanStatusValue,
  type RecordFormFieldDescriptor,
  type RecordFormFieldFallback,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldState,
  type RecordFormRecord,
} from './recordFormFieldModel';
import { resolveRecordDetailDisplayValue, type RecordDetailDisplayResolver } from './recordDetailFieldModel';
import { loadOptionFieldItems } from './optionFieldOptionCache';

defineOptions({ name: 'RecordDetailFields' });

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
    displayOf?: RecordDetailDisplayResolver;
    emptyText?: string;
    /**
     * Enables field-level inspection without changing the default read-only detail surface.
     * Consumers receive the stable descriptor fieldName rather than reconstructing it from DOM.
     */
    interactionMode?: 'none' | 'selectable';
    selectedFieldName?: string;
  }>(),
  {
    fieldNames: undefined,
    fields: undefined,
    excludeFieldNames: () => [],
    fallback: () => ({}),
    pickerConfigs: () => ({}),
    optionContext: undefined,
    fileTransferContext: undefined,
    displayOf: undefined,
    emptyText: '-',
    interactionMode: 'none',
    selectedFieldName: undefined,
  },
);

const emit = defineEmits<{
  select: [fieldName: string];
  configure: [fieldName: string];
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

onMounted(() => void loadOptionFields());
watch(
  () => [props.fields, props.optionContext],
  () => void loadOptionFields(),
);

function fieldState(fieldName: string): RecordFormFieldState {
  return resolveRecordFormFieldState(fieldName, {
    fields: props.fields,
    fallback: props.fallback,
    pickerConfigs: props.pickerConfigs,
    record: props.record,
  });
}

function statusFieldValue(field: RecordFormFieldState) {
  const value = props.record[field.fieldName];
  return field.controlType === 'booleanStatus' ? resolveRecordBooleanStatusValue(value) : value !== false;
}

function displayValue(field: RecordFormFieldState) {
  return resolveRecordDetailDisplayValue(field, props.record, {
    displayOf: props.displayOf,
    emptyText: props.emptyText,
    optionItems: optionItems.value[field.fieldName],
  });
}

async function loadOptionFields() {
  if (!props.optionContext || !props.fields) return;
  for (const field of props.fields.values()) {
    if (!field.option || field.option.inlineItems?.length) continue;
    try {
      optionItems.value = {
        ...optionItems.value,
        [field.fieldRef.fieldName]: await loadOptionFieldItems(props.optionContext, field.fieldRef.fieldName),
      };
    } catch {
      // Keep the raw value as a safe fallback; the editable form exposes a retry affordance.
    }
  }
}

function colorValue(field: RecordFormFieldState) {
  const value = props.record[field.fieldName];
  return typeof value === 'string' && /^#[0-9A-F]{6}$/i.test(value) ? value : undefined;
}

function fileSizeValue(field: RecordFormFieldState) {
  const value = props.record[field.fieldName];
  return typeof value === 'number' || typeof value === 'string' || typeof value === 'bigint'
    ? value
    : undefined;
}

function isInteractiveField(field: RecordFormFieldState) {
  return props.interactionMode === 'selectable' && field.fieldName;
}

function selectField(field: RecordFormFieldState) {
  if (isInteractiveField(field)) emit('select', field.fieldName);
}

function configureField(field: RecordFormFieldState) {
  if (isInteractiveField(field)) emit('configure', field.fieldName);
}
</script>

<template>
  <dl class="record-detail-fields">
    <div
      v-for="field in fieldStates"
      :key="field.fieldName"
      class="record-detail-field"
      :class="{
        'record-detail-field-full-row': field.columnSpan === 2,
        'record-detail-field--interactive': isInteractiveField(field),
        'record-detail-field--selected': isInteractiveField(field) && selectedFieldName === field.fieldName,
      }"
      :data-field-name="field.fieldName"
      :role="isInteractiveField(field) ? 'button' : undefined"
      :tabindex="isInteractiveField(field) ? 0 : undefined"
      @click="selectField(field)"
      @dblclick="configureField(field)"
      @keydown.enter="selectField(field)"
      @keydown.space.prevent="configureField(field)"
    >
      <dt>{{ field.label }}</dt>
      <dd>
        <RecordStatusTag
          v-if="field.controlType === 'enabledStatus' || field.controlType === 'booleanStatus'"
          :enabled="statusFieldValue(field)"
          :enabled-label="field.booleanStatus?.trueLabel"
          :disabled-label="field.booleanStatus?.falseLabel"
          :enabled-tone="field.booleanStatus?.trueTone"
          :disabled-tone="field.booleanStatus?.falseTone"
        />
        <UiSwitch
          v-else-if="field.controlType === 'switch'"
          :checked="props.record[field.fieldName] !== false"
          disabled
        />
        <RecordImageFileReferencePreview
          v-else-if="field.controlType === 'imageFileTransfer' && field.fileReference && fileTransferContext"
          :value="props.record[field.fieldName]"
          :record="props.record"
          :context="fileTransferContext"
          :definition="field.fileReference"
        />
        <span v-else-if="field.controlType === 'colorPicker'" class="record-color-value">
          <i :style="{ backgroundColor: colorValue(field) }" aria-hidden="true" />
          {{ displayValue(field) }}
        </span>
        <FileSizeText
          v-else-if="field.valuePresentation === 'FILE_SIZE'"
          :value="fileSizeValue(field)"
          :empty-text="props.emptyText"
        />
        <span v-else>{{ displayValue(field) }}</span>
      </dd>
    </div>
  </dl>
</template>

<style scoped>
.record-detail-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
  margin: 0;
}

.record-detail-field {
  min-width: 0;
}

.record-detail-field-full-row {
  grid-column: 1 / -1;
}

.record-detail-field--interactive {
  cursor: pointer;
  outline: 1px solid transparent;
  outline-offset: 2px;
}

.record-detail-field--interactive:hover,
.record-detail-field--interactive:focus-visible,
.record-detail-field--selected {
  outline: 2px solid var(--muyun-primary);
  background: var(--muyun-primary-surface, var(--muyun-hover));
}

dt {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

dd {
  overflow-wrap: anywhere;
  margin: 4px 0 0;
  color: var(--muyun-text-body);
  font-size: 13px;
  line-height: 20px;
}

.record-color-value {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.record-color-value i {
  width: 14px;
  height: 14px;
  border: 1px solid rgb(15 23 42 / 18%);
  border-radius: 50%;
}

@media (max-width: 900px) {
  .record-detail-fields {
    grid-template-columns: 1fr;
  }
}
</style>
