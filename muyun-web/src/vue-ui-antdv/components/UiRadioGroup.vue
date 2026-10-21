<script setup lang="ts">
import { useId } from 'vue';
import { RadioButton as ARadioButton, RadioGroup as ARadioGroup } from 'ant-design-vue';

defineOptions({ name: 'UiRadioGroup', inheritAttrs: false });

export interface UiRadioOption {
  value: string;
  label: string;
  disabled?: boolean;
  description?: string;
  /** Optional categorical accent for card borders; accepts a CSS color or theme variable. */
  color?: string;
}

defineProps<{
  value?: string;
  options: readonly UiRadioOption[];
  disabled?: boolean;
  size?: 'small' | 'default' | 'large';
  appearance?: 'buttons' | 'cards';
}>();

const cardGroupName = useId();
const emit = defineEmits<{ 'update:value': [value: string] }>();
</script>

<template>
  <ARadioGroup
    :class="[$attrs.class, { 'ui-radio-group--cards': appearance === 'cards' }]"
    :name="appearance === 'cards' ? cardGroupName : undefined"
    :role="appearance === 'cards' ? 'radiogroup' : undefined"
    :aria-label="$attrs['aria-label']"
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
      :style="
        appearance === 'cards' && option.color
          ? {
              '--ui-radio-card-color': option.color,
              '--ui-radio-card-selected-bg': `color-mix(in srgb, ${option.color} 10%, var(--muyun-surface))`,
            }
          : undefined
      "
    >
      <span>{{ option.label }}</span>
      <small v-if="appearance === 'cards' && option.description" class="ui-radio-group__description">{{
        option.description
      }}</small>
    </ARadioButton>
  </ARadioGroup>
</template>

<style scoped>
.ui-radio-group--cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
}
.ui-radio-group--cards :deep(.ant-radio-button-wrapper) {
  height: auto;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--ui-radio-card-color, var(--muyun-border));
  border-radius: 6px;
  line-height: var(--muyun-line-height-body, 21px);
  white-space: normal;
}
.ui-radio-group--cards :deep(.ant-radio-button-wrapper::before) {
  display: none;
}
.ui-radio-group--cards :deep(.ant-radio-button-wrapper-checked:not(.ant-radio-button-wrapper-disabled)) {
  background: var(--ui-radio-card-selected-bg, var(--muyun-selected));
  border-color: var(--ui-radio-card-color, var(--muyun-primary));
  color: var(--muyun-text);
  box-shadow: inset 0 0 0 1px var(--ui-radio-card-color, var(--muyun-primary));
}
.ui-radio-group__description {
  display: block;
  margin-top: 2px;
  color: var(--muyun-text-muted);
  font-size: var(--muyun-font-size-helper, 12px);
  line-height: var(--muyun-line-height-helper, 18px);
}
</style>
