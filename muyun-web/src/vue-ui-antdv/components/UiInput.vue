<script setup lang="ts">
import { Input as AInput } from 'ant-design-vue';
import { EyeOutlined, EyeInvisibleOutlined } from '@ant-design/icons-vue';
import { h } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import UiDatePicker from './UiDatePicker.vue';
import type { Primitive } from '@muyun/web-contracts';

defineOptions({ name: 'UiInput', inheritAttrs: false });

const props = defineProps<{
  value?: Primitive;
  type?: 'text' | 'password' | 'email' | 'search' | 'number' | 'url' | 'date' | 'datetime-local';
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
  autofocus?: boolean;
  allowClear?: boolean;
  /** Enables the reveal/hide icon for password inputs; visibility is local to the mounted input. */
  visibilityToggle?: boolean;
  autocomplete?: string;
  required?: boolean;
  step?: string | number;
  ariaLabel?: string;
}>();

const language = useLanguageContext();

function renderVisibilityToggle(visible: boolean) {
  return h(
    'button',
    {
      type: 'button',
      class: 'ui-input-visibility-toggle',
      disabled: props.disabled,
      'aria-label': visible
        ? language.t('platform.form.hide-password', '隐藏密码')
        : language.t('platform.form.show-password', '显示密码'),
      'aria-pressed': visible,
      // Keyboard activation belongs to this button, not the input's keydown contract.
      onKeydown: (event: KeyboardEvent) => event.stopPropagation(),
    },
    [h(visible ? EyeOutlined : EyeInvisibleOutlined, { 'aria-hidden': true })],
  );
}

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
  <component
    :is="type === 'password' && visibilityToggle ? AInput.Password : AInput"
    v-else
    v-bind="type === 'password' && visibilityToggle ? { iconRender: renderVisibilityToggle } : {}"
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

<style scoped>
:deep(.ui-input-visibility-toggle) {
  display: inline-flex;
  align-items: center;
  padding: 2px;
  border: 0;
  background: transparent;
  font: inherit;
}

:deep(.ui-input-visibility-toggle:focus-visible) {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}
</style>
