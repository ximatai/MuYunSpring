<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { UiSwitch } from '@muyun/vue-ui-antdv';
import type { OptionItemDescriptor } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import RecordStatusTag from './RecordStatusTag.vue';
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
    displayOf?: RecordDetailDisplayResolver;
    emptyText?: string;
  }>(),
  {
    fieldNames: undefined,
    fields: undefined,
    excludeFieldNames: () => [],
    fallback: () => ({}),
    pickerConfigs: () => ({}),
    optionContext: undefined,
    displayOf: undefined,
    emptyText: '-',
  },
);

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
</script>

<template>
  <dl class="record-detail-fields">
    <div
      v-for="field in fieldStates"
      :key="field.fieldName"
      class="record-detail-field"
      :class="{ 'record-detail-field-full-row': field.columnSpan === 2 }"
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
