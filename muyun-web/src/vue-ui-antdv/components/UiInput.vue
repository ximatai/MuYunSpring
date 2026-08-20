<script setup lang="ts">
import { Input as AInput } from 'ant-design-vue';
import type { Primitive } from '@muyun/web-contracts';

defineOptions({ name: 'UiInput', inheritAttrs: false });

defineProps<{
  value?: Primitive;
  type?: 'text' | 'password' | 'email' | 'search' | 'number' | 'url' | 'date' | 'datetime-local';
  placeholder?: string;
  disabled?: boolean;
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
  <AInput
    :value="String(value ?? '')"
    :type="type"
    :placeholder="placeholder"
    :disabled="disabled"
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
