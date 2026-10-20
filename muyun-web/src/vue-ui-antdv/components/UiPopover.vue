<script setup lang="ts">
import { Popover as APopover } from 'ant-design-vue';

defineOptions({ name: 'UiPopover', inheritAttrs: false });
const open = defineModel<boolean>('open');

withDefaults(
  defineProps<{
    trigger?: 'click' | 'hover' | 'focus';
    placement?:
      | 'top'
      | 'topLeft'
      | 'topRight'
      | 'right'
      | 'rightTop'
      | 'rightBottom'
      | 'bottom'
      | 'bottomLeft'
      | 'bottomRight'
      | 'left'
      | 'leftTop'
      | 'leftBottom';
  }>(),
  { trigger: 'click', placement: 'bottom' },
);
</script>

<template>
  <APopover
    :open="open"
    :trigger="trigger"
    :placement="placement"
    :class="$attrs.class"
    :style="$attrs.style"
    @open-change="open = $event"
  >
    <slot />
    <template #content><slot name="content" /></template>
  </APopover>
</template>
