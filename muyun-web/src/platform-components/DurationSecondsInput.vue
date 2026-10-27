<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiInput, UiInputGroup, UiSelect } from '@muyun/vue-ui-antdv';
import { useLanguageContext } from '@muyun/web-core';
import {
  durationSecondsParts,
  durationSecondsUnits,
  parseDurationSeconds,
  type DurationSecondsInputProps,
} from './durationSeconds';

defineOptions({ name: 'DurationSecondsInput' });
const props = defineProps<DurationSecondsInputProps>();
const localError = ref<string>();
const effectiveInvalid = computed(() => !props.disabled && (props.invalid || Boolean(localError.value)));
const emit = defineEmits<{
  'update:value': [value: number | undefined];
  'error-change': [message: string | undefined];
}>();
const language = useLanguageContext();
const parts = durationSecondsParts(props.value);
const amount = ref(parts.amount);
const unit = ref(parts.unit);
const options = computed(() =>
  durationSecondsUnits.map(({ seconds, key, label }) => ({
    value: seconds,
    label: language.t(key, label),
  })),
);
watch(
  () => props.value,
  (value) => {
    // Echoed values must not replace the unit chosen by the person editing this duration.
    const current = parseDurationSeconds(amount.value, unit.value);
    if (current !== null && current === value) return;
    const next = durationSecondsParts(value);
    amount.value = next.amount;
    unit.value = next.unit;
    reportError(parseDurationSeconds(next.amount, next.unit) === null);
  },
  { immediate: true },
);
function update(nextAmount: string, nextUnit: number) {
  const previous = parseDurationSeconds(amount.value, unit.value);
  amount.value = nextAmount;
  unit.value = nextUnit;
  const value = parseDurationSeconds(nextAmount, nextUnit);
  reportError(value === null);
  if (value === null) return;
  if (value !== previous) emit('update:value', value);
}
function reportError(invalid: boolean) {
  localError.value = invalid
    ? language.t('platform.duration.invalid', '请输入可换算为整数秒的非负时长，且不超过 2147483647 秒')
    : undefined;
  emit('error-change', localError.value);
}
</script>

<template>
  <UiInputGroup
    class="duration-seconds-input"
    :disabled="disabled"
    :invalid="effectiveInvalid"
    :aria-label="ariaLabel ?? language.t('platform.duration.label', '时长')"
  >
    <UiInput
      :value="amount"
      type="number"
      step="any"
      :disabled="disabled"
      :invalid="effectiveInvalid"
      :aria-label="language.t('platform.duration.amount', '时长数值')"
      @update:value="update($event, unit)"
    />
    <template #addon>
      <UiSelect
        :value="unit"
        :invalid="effectiveInvalid"
        :options="options"
        :disabled="disabled"
        :allow-clear="false"
        :aria-label="language.t('platform.duration.unit', '时长单位')"
        @update:value="update(amount, Number($event))"
      />
    </template>
  </UiInputGroup>
</template>
