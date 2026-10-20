<script setup lang="ts">
import { CalendarDatePicker as ADatePicker } from '../datePickerCalendar';
import { computed, h, ref } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { uiLanguageLocales } from '../languageLocales';
import {
  parseDatePickerValue,
  datePickerWireValue,
  type UiDatePickerMode,
  type UiDatePickerPrecision,
} from '../datePickerValue';

defineOptions({ name: 'UiDatePicker', inheritAttrs: false });
const props = withDefaults(
  defineProps<{
    value?: string | null;
    mode?: UiDatePickerMode;
    precision?: UiDatePickerPrecision;
    placeholder?: string;
    disabled?: boolean;
    invalid?: boolean;
    allowClear?: boolean;
    autofocus?: boolean;
    required?: boolean;
    id?: string;
    ariaLabel?: string;
  }>(),
  { mode: 'date', precision: 'second', disabled: false, invalid: false, allowClear: true },
);
const emit = defineEmits<{
  'update:value': [value: string];
  blur: [event: FocusEvent];
  focus: [event: FocusEvent];
  keydown: [event: KeyboardEvent];
}>();
const language = useLanguageContext();
const locale = computed(() =>
  language.fallbackLanguageTags.value.map((tag) => uiLanguageLocales.value[tag]?.DatePicker).find(Boolean),
);
const parsed = computed(() => parseDatePickerValue(props.value, props.mode));
const invalidValue = computed(() => !!props.value && !parsed.value);
const popupOpen = ref(false);
const displayFormat = computed(() =>
  props.mode === 'date'
    ? 'YYYY-MM-DD'
    : props.precision === 'second'
      ? 'YYYY-MM-DD HH:mm:ss'
      : 'YYYY-MM-DD HH:mm',
);
function renderInput(inputProps: Record<string, unknown>) {
  return h('input', {
    ...inputProps,
    'aria-label': props.ariaLabel,
    'aria-invalid': props.invalid || invalidValue.value || undefined,
    required: props.required,
  });
}
function change(value: unknown) {
  if (props.disabled) return;
  const normalized = datePickerWireValue(value, props.mode, props.precision);
  if (normalized !== undefined) emit('update:value', normalized);
}
function keydown(event: KeyboardEvent) {
  // Enter inside the calendar confirms a selection; the owning query submits after it closes.
  if (event.key === 'Enter' && popupOpen.value) return;
  emit('keydown', event);
}
</script>
<template>
  <ADatePicker
    class="ui-date-picker"
    :class="$attrs.class"
    :style="$attrs.style"
    :id="id"
    :value="parsed"
    :input-render="renderInput"
    :format="displayFormat"
    :show-time="mode === 'datetime' ? { format: precision === 'second' ? 'HH:mm:ss' : 'HH:mm' } : false"
    :locale="locale"
    popup-class-name="ui-date-picker-popup"
    :placeholder="placeholder"
    :disabled="disabled"
    :status="invalid || invalidValue ? 'error' : undefined"
    :aria-invalid="invalid || invalidValue || undefined"
    :aria-label="ariaLabel"
    :allow-clear="allowClear"
    :autofocus="autofocus"
    :required="required"
    :title="invalidValue ? (value ?? undefined) : undefined"
    @change="change"
    @open-change="popupOpen = $event"
    @blur="emit('blur', $event)"
    @focus="emit('focus', $event)"
    @keydown="keydown"
  />
</template>
<style>
.ui-date-picker.ant-picker {
  width: 100%;
  min-width: 0;
  font: inherit;
}
.ui-date-picker.ant-picker .ant-picker-input > input {
  min-width: 0;
  font: inherit;
}
@media (max-width: 600px) {
  .ui-date-picker-popup .ant-picker-datetime-panel {
    flex-direction: column;
  }
  .ui-date-picker-popup .ant-picker-panel-container {
    max-width: calc(100vw - 24px);
    max-height: calc(100vh - 24px);
    overflow: auto;
  }
  .ui-date-picker-popup .ant-picker-datetime-panel .ant-picker-time-panel {
    border-inline-start: 0;
    border-block-start: 1px solid var(--muyun-border-subtle);
  }
}
</style>
