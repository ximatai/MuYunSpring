<script setup lang="ts">
import { computed, ref } from 'vue';
import { UiTokenInput } from '@muyun/vue-ui-antdv';
import type { UiDragSource } from '@muyun/vue-ui-antdv';
import {
  formulaExpressionTokens,
  type FormulaExpressionField,
  type FormulaExpressionFunction,
} from './formulaExpressionTokens';

defineOptions({ name: 'FormulaExpressionEditor' });

const props = withDefaults(
  defineProps<{
    value: string;
    fields: readonly FormulaExpressionField[];
    functions?: readonly FormulaExpressionFunction[];
    disabled?: boolean;
    placeholder?: string;
    acceptDrop?: (source: UiDragSource) => boolean;
  }>(),
  { functions: () => [], disabled: false, placeholder: undefined, acceptDrop: undefined },
);

const emit = defineEmits<{
  'update:value': [value: string];
  selection: [selection: { start: number; end: number }];
  drop: [event: { source: UiDragSource; selection: { start: number; end: number }; nativeEvent?: Event }];
}>();

const tokens = computed(() => formulaExpressionTokens(props.value, props.fields, props.functions));
const tokenInput = ref<InstanceType<typeof UiTokenInput>>();

function selection() {
  return tokenInput.value?.selection() ?? { start: 0, end: 0 };
}

function focusSelection(start: number, end = start) {
  tokenInput.value?.focusSelection(start, end);
}

defineExpose({ selection, focusSelection });
</script>

<template>
  <UiTokenInput
    ref="tokenInput"
    aria-label="公式"
    :value="value"
    :tokens="tokens"
    :placeholder="placeholder"
    :disabled="disabled"
    :accept-drop="acceptDrop"
    @update:value="emit('update:value', $event)"
    @selection="emit('selection', $event)"
    @drop="emit('drop', $event)"
  />
</template>
