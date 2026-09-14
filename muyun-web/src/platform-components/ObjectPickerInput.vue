<script setup lang="ts">
import { ref, watch } from 'vue';
import { UiSearchInput } from '@muyun/vue-ui-antdv';

/**
 * Standard compact entry for a value chosen from a larger candidate surface.
 * It keeps the selected display value in the control and delegates actual candidate browsing
 * to its owner; it does not infer candidate source, authorization, or persisted identity.
 */
defineOptions({ name: 'ObjectPickerInput' });

const props = withDefaults(
  defineProps<{
    value?: string;
    placeholder?: string;
    disabled?: boolean;
    browseLabel?: string;
  }>(),
  {
    value: '',
    placeholder: '搜索并选择',
    disabled: false,
    browseLabel: '打开候选选择',
  },
);

const emit = defineEmits<{
  browse: [keyword: string];
  clear: [];
}>();

const inputValue = ref(props.value);

watch(
  () => props.value,
  (value) => {
    inputValue.value = value;
  },
);

function browse(value: string) {
  // Ant's Input.Search does not consistently expose its clear-origin metadata. The input value
  // is already cleared at this point, which reliably distinguishes its clear affordance.
  if (value === '' && props.value && inputValue.value === '') {
    emit('clear');
    return;
  }
  emit('browse', value === props.value ? '' : value);
}
</script>

<template>
  <UiSearchInput
    class="object-picker-input-control"
    :value="inputValue"
    :placeholder="placeholder"
    :disabled="disabled"
    search-icon-only
    :aria-label="browseLabel"
    @update:value="inputValue = $event"
    @search="browse"
  />
</template>

<style scoped>
.object-picker-input-control {
  width: min(100%, var(--muyun-standard-input-width, 280px));
}
</style>
