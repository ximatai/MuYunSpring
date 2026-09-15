<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiInput, UiSelect } from '@muyun/vue-ui-antdv';
import type {
  Option,
  OptionValue,
  OptionValueList,
  QueryOperator,
  QuerySchemaField,
} from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import RecordMultiPicker from './RecordMultiPicker.vue';
import RecordPicker from './RecordPicker.vue';
import type { RecordPickerRecord } from './recordPickerConstraints';
import ReferencePicker from './ReferencePicker.vue';
import { createQueryReferencePickerProvider } from './queryReferencePickerProvider';
import type { ReferencePickerConfig, ReferencePickerValidity } from './referencePickerModel';

defineOptions({ name: 'QueryValueEditor' });

const props = withDefaults(
  defineProps<{
    field: QuerySchemaField;
    operator: QueryOperator;
    values?: unknown[];
    options?: Option[];
    referenceContext?: ModuleContext<RecordPickerRecord>;
    /** Source-owned reference candidates supersede target-module reference delivery. */
    referencePicker?: ReferencePickerConfig;
    loading?: boolean;
    disabled?: boolean;
  }>(),
  {
    values: () => [],
    options: () => [],
    referenceContext: undefined,
    referencePicker: undefined,
    loading: false,
    disabled: false,
  },
);

const emit = defineEmits<{
  'update:values': [values: unknown[]];
  /** A reference input must settle before its owning query can apply its draft IDs. */
  'validity-change': [validity: ReferencePickerValidity];
  /** Lets the owning query surface submit from a value input without coupling it to a page. */
  submit: [];
}>();

const booleanOptions: Option[] = [
  { label: '是', value: 'true' },
  { label: '否', value: 'false' },
];

const targetReferencePicker = ref<ReferencePickerConfig>();
const targetReferencePending = ref(false);
const referencePickerValidity = ref<ReferencePickerValidity>({ valid: true, status: 'ready' });
let targetReferenceRequest = 0;
const resolvedReferencePicker = computed(() => props.referencePicker ?? targetReferencePicker.value);
const referencePickerKey = computed(() => `${props.field.name}:${props.operator}`);
const reportedReferenceValidity = computed<ReferencePickerValidity>(() => {
  if (isValueLess()) return { valid: true, status: 'ready' };
  if (targetReferencePending.value) {
    return { valid: false, status: 'resolving', message: '正在加载引用能力' };
  }
  return resolvedReferencePicker.value ? referencePickerValidity.value : { valid: true, status: 'ready' };
});

watch(reportedReferenceValidity, (validity) => emit('validity-change', validity), {
  immediate: true,
  flush: 'sync',
});

watch(
  () => [props.field.reference, props.referenceContext, props.referencePicker] as const,
  () => void resolveTargetReferencePicker(),
  { immediate: true },
);

async function resolveTargetReferencePicker() {
  const request = ++targetReferenceRequest;
  targetReferencePicker.value = undefined;
  const reference = props.field.reference;
  const context = props.referenceContext;
  if (props.referencePicker || !reference || !context) {
    targetReferencePending.value = false;
    return;
  }
  targetReferencePending.value = true;
  try {
    await context.runtime.ready;
    if (request !== targetReferenceRequest) return;
    // Tree targets retain their existing REFERENCE tree reader. Do not reinterpret a tree as a
    // flat target query just because the common picker only owns the non-tree interaction here.
    if (!context.abilities.tryTree()) {
      targetReferencePicker.value = {
        provider: createQueryReferencePickerProvider({ http: context.http, reference }),
        title: `选择${props.field.title ?? props.field.name}`,
        placeholder: `搜索${props.field.title ?? props.field.name}`,
        searchPlaceholder: `搜索${props.field.title ?? props.field.name}`,
      };
    }
  } catch {
    // The legacy picker keeps the target REFERENCE context's explicit runtime failure visible.
    // Never substitute a MENU context or an ordinary CRUD endpoint after that failure.
  } finally {
    if (request === targetReferenceRequest) targetReferencePending.value = false;
  }
}

function updateValues(values: unknown[]) {
  emit(
    'update:values',
    values.filter(
      (value) =>
        value !== undefined && value !== null && value !== '' && (typeof value !== 'string' || value.trim()),
    ),
  );
}

function submitOnEnter(event: KeyboardEvent) {
  if (event.key !== 'Enter' || props.disabled) return;
  event.preventDefault();
  emit('submit');
}

function scalarValue(index = 0) {
  const value = props.values[index];
  if (value === undefined || value === null) return '';
  return props.field.valueType === 'INSTANT' ? instantInputValue(value) : String(value);
}

function optionValue() {
  const value = props.values[0];
  return typeof value === 'string' || typeof value === 'number' ? value : undefined;
}

function optionValues() {
  return props.values.filter(
    (value): value is OptionValue => typeof value === 'string' || typeof value === 'number',
  );
}

function updateScalar(index: number, value: string) {
  const next = [...props.values];
  next[index] = props.field.valueType === 'INSTANT' ? utcInstantValue(value) : value;
  updateValues(next);
}

function instantInputValue(value: unknown) {
  const instant = new Date(String(value));
  if (Number.isNaN(instant.getTime())) return String(value);
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${instant.getFullYear()}-${pad(instant.getMonth() + 1)}-${pad(instant.getDate())}T${pad(
    instant.getHours(),
  )}:${pad(instant.getMinutes())}:${pad(instant.getSeconds())}`;
}

function utcInstantValue(value: string) {
  if (!value) return value;
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return value;
  return instant.toISOString().replace('.000Z', 'Z');
}

function updateBoolean(value: OptionValue | OptionValueList | null) {
  const selected = Array.isArray(value) ? undefined : value;
  updateValues(selected === 'true' ? [true] : selected === 'false' ? [false] : []);
}

function updateOptions(value: OptionValue | OptionValueList | null) {
  const selected = value === null ? [] : Array.isArray(value) ? value : [value];
  updateValues(selected);
}

function updateDelimited(value: string) {
  updateValues(
    value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean),
  );
}

function updateReferenceValue(value: string | undefined) {
  updateValues(value ? [value] : []);
}

function updateReferenceValues(values: string[]) {
  updateValues(values);
}

function referencePickerValue() {
  const values = props.values.filter((value): value is string => typeof value === 'string');
  return isMultiple() ? values : values[0];
}

function updateReferencePickerValue(value: string | string[] | undefined) {
  updateValues(value === undefined ? [] : Array.isArray(value) ? value : [value]);
}

function updateReferencePickerValidity(validity: ReferencePickerValidity) {
  referencePickerValidity.value = validity;
}

function isValueLess() {
  return ['NULL', 'NOT_NULL', 'EMPTY', 'NOT_EMPTY'].includes(props.operator);
}

function isMultiple() {
  return ['IN', 'NOT_IN', 'CONTAINS_ANY', 'CONTAINS_ALL'].includes(props.operator);
}

function inputType() {
  if (props.field.valueType === 'DATE') return 'date';
  if (props.field.valueType === 'INSTANT') return 'datetime-local';
  if (['INTEGER', 'LONG', 'DECIMAL'].includes(props.field.valueType)) return 'number';
  return 'text';
}

function inputStep() {
  return props.field.valueType === 'INSTANT' ? '1' : props.field.valueType === 'DECIMAL' ? 'any' : undefined;
}

function referenceTitle(record: RecordPickerRecord) {
  const labelField = props.field.reference?.labelField;
  const label = labelField ? (record as Record<string, unknown>)[labelField] : undefined;
  return typeof label === 'string' && label.trim()
    ? label
    : (record.title ?? record.code ?? record.id ?? '未命名记录');
}
</script>

<template>
  <div class="query-value-editor">
    <span v-if="isValueLess()" class="query-value-editor-empty">无需输入值</span>
    <UiSelect
      v-else-if="field.valueType === 'BOOLEAN'"
      :value="values[0] === true ? 'true' : values[0] === false ? 'false' : undefined"
      :options="booleanOptions"
      :disabled="disabled"
      allow-clear
      placeholder="全部"
      @update:value="updateBoolean"
    />
    <UiSelect
      v-else-if="options.length > 0"
      :value="isMultiple() ? optionValues() : optionValue()"
      :options="options"
      :mode="isMultiple() ? 'multiple' : undefined"
      :loading="loading"
      :disabled="disabled"
      allow-clear
      :placeholder="isMultiple() ? '选择一个或多个值' : '选择值'"
      @update:value="updateOptions"
    />
    <ReferencePicker
      v-else-if="field.reference && resolvedReferencePicker"
      :key="referencePickerKey"
      :value="referencePickerValue()"
      :multiple="isMultiple()"
      :max-selection="resolvedReferencePicker.maxSelection"
      :title="resolvedReferencePicker.title"
      :placeholder="resolvedReferencePicker.placeholder"
      :provider="resolvedReferencePicker.provider"
      :mode="resolvedReferencePicker.mode ?? 'dialog'"
      :columns="resolvedReferencePicker.columns"
      :search-placeholder="resolvedReferencePicker.searchPlaceholder"
      :reload-key="resolvedReferencePicker.reloadKey"
      :disabled="disabled"
      @update:value="updateReferencePickerValue"
      @validity-change="updateReferencePickerValidity"
    />
    <UiInput
      v-else-if="field.reference && targetReferencePending"
      value=""
      disabled
      placeholder="正在加载引用能力"
    />
    <RecordMultiPicker
      v-else-if="field.reference && referenceContext && isMultiple()"
      :context="referenceContext"
      :value="values.filter((value): value is string => typeof value === 'string')"
      :title-of="referenceTitle"
      :disabled="disabled"
      @update:value="updateReferenceValues"
    />
    <RecordPicker
      v-else-if="field.reference && referenceContext"
      :context="referenceContext"
      :value="typeof values[0] === 'string' ? values[0] : undefined"
      :title-of="referenceTitle"
      :disabled="disabled"
      @update:value="updateReferenceValue"
    />
    <template v-else-if="operator === 'BETWEEN'">
      <UiInput
        :value="scalarValue(0)"
        :type="inputType()"
        :step="inputStep()"
        :disabled="disabled"
        :aria-label="`${field.title ?? field.name}开始`"
        placeholder="起始"
        @keydown="submitOnEnter"
        @update:value="updateScalar(0, $event)"
      />
      <UiInput
        :value="scalarValue(1)"
        :type="inputType()"
        :step="inputStep()"
        :disabled="disabled"
        :aria-label="`${field.title ?? field.name}结束`"
        placeholder="结束"
        @keydown="submitOnEnter"
        @update:value="updateScalar(1, $event)"
      />
    </template>
    <UiInput
      v-else-if="isMultiple()"
      :value="values.map(String).join(', ')"
      :disabled="disabled"
      placeholder="多个值用逗号分隔"
      @keydown="submitOnEnter"
      @update:value="updateDelimited"
    />
    <UiInput
      v-else
      :value="scalarValue()"
      :type="inputType()"
      :step="inputStep()"
      :disabled="disabled"
      :aria-label="field.title ?? field.name"
      placeholder="请输入条件值"
      @keydown="submitOnEnter"
      @update:value="updateScalar(0, $event)"
    />
  </div>
</template>

<style scoped>
.query-value-editor {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(0, 1fr);
  gap: 8px;
  min-width: 0;
}

.query-value-editor-empty {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 11px;
  border: 1px solid var(--muyun-border);
  border-radius: 6px;
  background: var(--muyun-support-surface);
  color: var(--muyun-text-muted);
  font-size: 14px;
}
</style>
