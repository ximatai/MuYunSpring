<script setup lang="ts">
import UiButton from './UiButton.vue';
import type { UiIconName } from './UiIcon.vue';

defineOptions({ name: 'UiActionButton', inheritAttrs: false });

withDefaults(
  defineProps<{
    emphasis?: 'primary' | 'secondary' | 'quiet';
    intent?: 'normal' | 'danger';
    density?: 'regular' | 'compact';
    submit?: boolean;
    disabled?: boolean;
    loading?: boolean;
    title?: string;
    iconName?: UiIconName;
  }>(),
  {
    emphasis: 'secondary',
    intent: 'normal',
    density: 'regular',
    submit: false,
    disabled: false,
    loading: false,
    title: undefined,
    iconName: undefined,
  },
);

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();
</script>

<template>
  <UiButton
    :type="emphasis === 'primary' ? 'primary' : emphasis === 'quiet' ? 'text' : 'default'"
    :html-type="submit ? 'submit' : 'button'"
    :danger="intent === 'danger'"
    :size="density === 'compact' ? 'small' : 'middle'"
    :disabled="disabled"
    :loading="loading"
    :title="title"
    :aria-label="$slots.default ? undefined : title"
    :icon-name="iconName"
    :class="$attrs.class"
    :style="$attrs.style"
    @click="emit('click', $event)"
  >
    <slot />
  </UiButton>
</template>
