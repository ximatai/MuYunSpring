<script setup lang="ts">
import { Button as AButton } from 'ant-design-vue';
import { computed } from 'vue';
import UiIcon, { type UiIconName } from './UiIcon.vue';

defineOptions({ name: 'UiButton', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    type?: 'default' | 'primary' | 'dashed' | 'link' | 'text';
    htmlType?: 'button' | 'submit' | 'reset';
    disabled?: boolean;
    loading?: boolean;
    danger?: boolean;
    size?: 'small' | 'middle' | 'large';
    title?: string;
    ariaLabel?: string;
    iconName?: UiIconName;
    iconPosition?: 'start' | 'end';
  }>(),
  {
    type: 'default',
    htmlType: 'button',
    disabled: false,
    loading: false,
    danger: false,
    size: 'middle',
    title: undefined,
    ariaLabel: undefined,
    iconName: undefined,
    iconPosition: 'start',
  },
);

const solidForegroundClass = computed(() => {
  if (props.type !== 'primary') return undefined;
  return props.danger ? 'ui-button--danger-solid' : 'ui-button--theme-solid';
});

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();
</script>

<template>
  <AButton
    :type="type"
    :html-type="htmlType"
    :disabled="disabled"
    :loading="loading"
    :danger="danger"
    :size="size"
    :title="title"
    :aria-label="ariaLabel"
    :class="[$attrs.class, solidForegroundClass]"
    :style="$attrs.style"
    @click="emit('click', $event)"
  >
    <template v-if="iconName && iconPosition === 'start'" #icon>
      <UiIcon :name="iconName" />
    </template>
    <slot />
    <UiIcon v-if="iconName && iconPosition === 'end'" class="ui-button-trailing-icon" :name="iconName" />
  </AButton>
</template>

<style scoped>
:deep(.ant-btn-primary.ui-button--theme-solid:not(:disabled)) {
  color: var(--muyun-theme-on-base);
}

:deep(.ant-btn-primary.ant-btn-dangerous.ui-button--danger-solid:not(:disabled)) {
  color: var(--muyun-danger-on-base);
}

.ui-button-trailing-icon {
  margin-inline-start: 8px;
}
</style>
