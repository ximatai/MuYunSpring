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
    /** Fixed square hit area for compact actions whose content is only an icon. */
    iconOnly?: boolean;
    /** Lightweight persistent selection for non-primary tools such as search and ordering. */
    selected?: boolean;
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
    iconOnly: false,
    selected: false,
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
    :class="[
      $attrs.class,
      solidForegroundClass,
      {
        'ui-button--icon-only': iconOnly,
        'ui-button--icon-only-compact': iconOnly && size === 'small',
        'ui-button--selected': selected,
      },
    ]"
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

/* Selected tools stay visibly active without competing with primary submit actions. */
:global(.ant-btn.ui-button--selected.ui-button--selected:not(:disabled)) {
  border-color: color-mix(in srgb, var(--muyun-primary) 24%, transparent);
  background: var(
    --muyun-primary-surface,
    color-mix(in srgb, var(--muyun-primary) 12%, var(--muyun-surface))
  );
  color: var(--muyun-primary);
}

:global(.ant-btn.ui-button--selected.ui-button--selected:not(:disabled):hover),
:global(.ant-btn.ui-button--selected.ui-button--selected:not(:disabled):focus-visible) {
  border-color: color-mix(in srgb, var(--muyun-primary) 36%, transparent);
  background: var(
    --muyun-primary-surface,
    color-mix(in srgb, var(--muyun-primary) 16%, var(--muyun-surface))
  );
  color: var(--muyun-primary);
}

.ui-button-trailing-icon {
  margin-inline-start: 8px;
}

:deep(.ant-btn.ui-button--icon-only) {
  display: inline-grid;
  width: 30px;
  min-width: 30px;
  height: 30px;
  padding: 0;
  place-items: center;
}

:deep(.ant-btn.ui-button--icon-only-compact) {
  width: 22px;
  min-width: 22px;
  height: 22px;
}
</style>
