<script setup lang="ts">
import { Input as AInput } from 'ant-design-vue';
import UiDatePicker from './UiDatePicker.vue';
import type { Primitive } from '@muyun/web-contracts';

defineOptions({ name: 'UiInput', inheritAttrs: false });

defineProps<{
  value?: Primitive;
  type?: 'text' | 'password' | 'email' | 'search' | 'number' | 'url' | 'date' | 'datetime-local';
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
  autofocus?: boolean;
  allowClear?: boolean;
  autocomplete?: string;
  required?: boolean;
  step?: string | number;
  ariaLabel?: string;
}>();

const emit = defineEmits<{
  'update:value': [value: string];
  blur: [event: FocusEvent];
  focus: [event: FocusEvent];
  keydown: [event: KeyboardEvent];
}>();
</script>

<template>
  <UiDatePicker
    v-if="type === 'date' || type === 'datetime-local'"
    :value="value == null ? undefined : String(value)"
    :mode="type === 'date' ? 'date' : 'datetime'"
    :precision="step != null && Number(step) >= 60 ? 'minute' : 'second'"
    :placeholder="placeholder"
    :disabled="disabled"
    :invalid="invalid"
    :autofocus="autofocus"
    :allow-clear="allowClear ?? true"
    :required="required"
    :aria-label="ariaLabel"
    :class="$attrs.class"
    :style="$attrs.style"
    @blur="emit('blur', $event)"
    @focus="emit('focus', $event)"
    @keydown="emit('keydown', $event)"
    @update:value="emit('update:value', $event)"
  />
  <AInput
    v-else
    :value="String(value ?? '')"
    :type="type"
    :placeholder="placeholder"
    :disabled="disabled"
    :status="invalid ? 'error' : undefined"
    :aria-invalid="invalid || undefined"
    :autofocus="autofocus"
    :allow-clear="allowClear"
    :autocomplete="autocomplete"
    :required="required"
    :step="step"
    :aria-label="ariaLabel"
    :class="$attrs.class"
    :style="$attrs.style"
    @blur="emit('blur', $event)"
    @focus="emit('focus', $event)"
    @keydown="emit('keydown', $event)"
    @update:value="emit('update:value', $event)"
  />
</template>
