<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import RecordReferencePicker from './RecordReferencePicker.vue';
import type { RecordReferencePickerSource } from './recordReferencePickerProvider';
import type { RecordPickerRecord } from './recordPickerConstraints';

defineOptions({ name: 'RecordPicker' });
const language = useLanguageContext();
const props = withDefaults(
  defineProps<
    RecordReferencePickerSource & {
      value?: string;
      reloadKey?: string | number;
      placeholder?: string;
      invalid?: boolean;
      disabled?: boolean;
      allowClear?: boolean;
    }
  >(),
  { allowClear: true },
);
const emit = defineEmits<{
  'update:value': [value: string | undefined];
  select: [value: RecordPickerRecord | undefined];
  'selection-resolved': [value: RecordPickerRecord | undefined];
}>();
function updateValue(value: string | string[] | undefined) {
  emit('update:value', Array.isArray(value) ? value[0] : value);
}
</script>

<template>
  <RecordReferencePicker
    v-bind="props"
    :placeholder="placeholder ?? language.t('platform.screen.RecordPicker.placeholder', '请选择')"
    @update:value="updateValue"
    @select="(records) => emit('select', records[0])"
    @selection-resolved="(records) => emit('selection-resolved', records[0])"
  />
</template>
