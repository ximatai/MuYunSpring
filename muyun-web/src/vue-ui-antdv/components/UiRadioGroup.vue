<script setup lang="ts">
import { RadioButton as ARadioButton, RadioGroup as ARadioGroup } from 'ant-design-vue';

defineOptions({ name: 'UiRadioGroup', inheritAttrs: false });

export interface UiRadioOption {
  value: string;
  label: string;
  disabled?: boolean;
}

defineProps<{
  value?: string;
  options: readonly UiRadioOption[];
  disabled?: boolean;
  size?: 'small' | 'default' | 'large';
}>();

const emit = defineEmits<{ 'update:value': [value: string] }>();
</script>

<template>
  <ARadioGroup
    :class="$attrs.class"
    :style="$attrs.style"
    :value="value"
    :disabled="disabled"
    :size="size"
    button-style="solid"
    @update:value="emit('update:value', $event)"
  >
    <ARadioButton
      v-for="option in options"
      :key="option.value"
      :value="option.value"
      :disabled="option.disabled"
    >
      {{ option.label }}
    </ARadioButton>
  </ARadioGroup>
</template>
