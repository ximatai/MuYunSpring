<script setup lang="ts">
import { computed } from 'vue';
import { UiRadioGroup } from '@muyun/vue-ui-antdv';
import type { OptionItemDescriptor } from '@muyun/web-contracts';
import { dictionaryOptionCodes } from './dictionaryOptionDialogModel';

defineOptions({ name: 'DictionaryRadioGroup' });

const props = withDefaults(
  defineProps<{
    /** Dictionary radio fields are scalar and persist the dictionary item code. */
    value?: string;
    items: readonly OptionItemDescriptor[];
    disabled?: boolean;
    /** A descriptor string. Candidate-size eligibility remains the host renderer's responsibility. */
    maxOptions?: string;
  }>(),
  {
    value: undefined,
    disabled: false,
    maxOptions: undefined,
  },
);

const emit = defineEmits<{
  'update:value': [value: string | undefined];
}>();

// UiRadioGroup is deliberately string-only. Dictionary codes satisfy that narrower contract,
// unlike generic UiSelect options whose values may also be numeric.
const options = computed(() =>
  props.items.map((item) => ({ label: item.title, value: item.code, disabled: !item.enabled })),
);

function updateValue(value: string) {
  if (props.disabled) return;
  const code = dictionaryOptionCodes(value, 'SINGLE')[0];
  if (!code || !props.items.some((item) => item.code === code && item.enabled)) return;
  emit('update:value', code);
}
</script>

<template>
  <UiRadioGroup
    :value="value"
    :options="options"
    :disabled="disabled"
    :data-max-options="maxOptions"
    @update:value="updateValue"
  />
</template>
