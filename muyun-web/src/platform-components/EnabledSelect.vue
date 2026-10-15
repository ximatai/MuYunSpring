<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed } from 'vue';
import { UiSelect } from '@muyun/vue-ui-antdv';

const language = useLanguageContext();

defineOptions({ name: 'EnabledSelect' });

const props = withDefaults(
  defineProps<{
    value?: boolean;
    disabled?: boolean;
  }>(),
  {
    value: true,
    disabled: false,
  },
);

const emit = defineEmits<{
  'update:value': [value: boolean];
}>();

const enabledOptions = computed(() => [
  { label: language.t('platform.value.enabled', '启用'), value: 'true' },
  { label: language.t('platform.value.disabled', '停用'), value: 'false' },
]);

const enabledValue = computed({
  get: () => (props.value === false ? 'false' : 'true'),
  set: (value) => {
    emit('update:value', value !== 'false');
  },
});
</script>

<template>
  <UiSelect
    v-model:value="enabledValue"
    :options="enabledOptions"
    :disabled="disabled"
    :allow-clear="false"
  />
</template>
