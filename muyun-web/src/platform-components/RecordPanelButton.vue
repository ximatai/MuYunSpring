<script setup lang="ts">
import { UiButton, type UiIconName } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'RecordPanelButton' });

withDefaults(
  defineProps<{
    type?: 'default' | 'primary' | 'dashed' | 'link' | 'text';
    disabled?: boolean;
    loading?: boolean;
    danger?: boolean;
    title?: string;
    ariaLabel?: string;
    iconName?: UiIconName;
  }>(),
  {
    type: 'default',
    disabled: false,
    loading: false,
    danger: false,
    title: undefined,
    ariaLabel: undefined,
    iconName: undefined,
  },
);

const emit = defineEmits<{ click: [event: MouseEvent] }>();
</script>

<template>
  <UiButton
    :type="type"
    :disabled="disabled"
    :loading="loading"
    :danger="danger"
    :title="title"
    :aria-label="ariaLabel"
    :icon-name="iconName"
    @click="emit('click', $event)"
  >
    <slot />
  </UiButton>
</template>
