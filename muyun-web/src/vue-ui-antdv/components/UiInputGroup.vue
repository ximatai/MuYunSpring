<script setup lang="ts">
/** A visual compound field; callers pass disabled/invalid to their slotted controls as well. */
defineOptions({ name: 'UiInputGroup', inheritAttrs: false });
defineProps<{ disabled?: boolean; invalid?: boolean; ariaLabel?: string }>();
</script>

<template>
  <div
    class="ui-input-group"
    :class="[$attrs.class, { 'ui-input-group--disabled': disabled, 'ui-input-group--invalid': invalid }]"
    :style="$attrs.style"
    role="group"
    :aria-label="ariaLabel"
    :aria-disabled="disabled || undefined"
    :aria-invalid="invalid || undefined"
  >
    <div class="ui-input-group__main"><slot /></div>
    <div class="ui-input-group__addon"><slot name="addon" /></div>
  </div>
</template>

<style scoped>
.ui-input-group {
  display: flex;
  width: 100%;
  height: var(--muyun-control-height, 32px);
  box-sizing: border-box;
  min-width: 0;
  border: 1px solid var(--muyun-border);
  border-radius: var(--muyun-radius-control, 4px);
  background: var(--muyun-surface);
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}
.ui-input-group__main {
  flex: 1 1 auto;
  min-width: 0;
}
.ui-input-group__addon {
  flex: 0 0 auto;
  min-width: 76px;
  border-inline-start: 1px solid var(--muyun-border-subtle);
  background: color-mix(in srgb, var(--muyun-text-muted) 4%, var(--muyun-surface));
  border-start-end-radius: inherit;
  border-end-end-radius: inherit;
}
.ui-input-group :deep(.ant-input),
.ui-input-group :deep(.ant-input-affix-wrapper),
.ui-input-group :deep(.ant-select-selector) {
  border: 0 !important;
  border-radius: inherit !important;
  background: transparent !important;
  box-shadow: none !important;
}
.ui-input-group :deep(.ant-input),
.ui-input-group :deep(.ant-input-affix-wrapper) {
  width: 100%;
  height: 100%;
  border-radius: inherit;
}
.ui-input-group :deep(.ant-select) {
  width: 100%;
  height: 100%;
  min-width: 0;
}
.ui-input-group__addon :deep(.ant-btn) {
  height: 100%;
  border: 0;
  border-radius: inherit;
  box-shadow: none;
}
.ui-input-group__addon :deep(.ant-btn:focus-visible) {
  outline: none;
}
.ui-input-group :deep(input[type='number']) {
  appearance: textfield;
}
.ui-input-group :deep(input::-webkit-inner-spin-button),
.ui-input-group :deep(input::-webkit-outer-spin-button) {
  appearance: none;
  margin: 0;
}
.ui-input-group:not(.ui-input-group--disabled):not(.ui-input-group--invalid):hover {
  border-color: var(--muyun-primary);
}
.ui-input-group:not(.ui-input-group--disabled):not(.ui-input-group--invalid):focus-within {
  border-color: var(--muyun-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--muyun-primary) 14%, transparent);
}
.ui-input-group.ui-input-group--invalid {
  border-color: var(--muyun-danger-text);
  box-shadow: none;
}
.ui-input-group.ui-input-group--invalid:focus-within {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--muyun-danger-text) 14%, transparent);
}
.ui-input-group--disabled {
  background: color-mix(in srgb, var(--muyun-text-muted) 6%, var(--muyun-surface));
  cursor: not-allowed;
}
@media (prefers-reduced-motion: reduce) {
  .ui-input-group {
    transition: none;
  }
}
</style>
