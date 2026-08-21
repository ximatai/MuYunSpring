<script setup lang="ts">
import { UiCheckbox } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'RecordSelectionCheckbox' });

defineProps<{
  checked?: boolean;
  indeterminate?: boolean;
  disabled?: boolean;
  ariaLabel?: string;
}>();

defineEmits<{ 'update:checked': [checked: boolean] }>();
</script>

<template>
  <UiCheckbox
    :checked="checked"
    :indeterminate="indeterminate"
    :disabled="disabled"
    :aria-label="ariaLabel"
    @update:checked="$emit('update:checked', $event)"
  />
</template>
