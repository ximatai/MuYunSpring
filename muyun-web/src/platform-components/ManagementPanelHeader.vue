<script setup lang="ts">
import { UiButton, type UiIconName } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'ManagementPanelHeader' });

withDefaults(
  defineProps<{
    title: string;
    subtitle?: string;
    titleActionIcon?: UiIconName;
    titleActionTitle?: string;
  }>(),
  {
    subtitle: undefined,
    titleActionIcon: undefined,
    titleActionTitle: undefined,
  },
);

const emit = defineEmits<{
  titleAction: [];
}>();
</script>

<template>
  <header class="management-panel-header">
    <div class="management-panel-header-title-group">
      <div v-if="$slots['title-prefix']" class="management-panel-header-title-prefix">
        <slot name="title-prefix" />
      </div>
      <div class="management-panel-header-title-copy">
        <h2
          class="management-panel-header-title"
          :class="{ 'management-panel-header-title--action': titleActionIcon }"
          :title="title"
        >
          <UiButton
            v-if="titleActionIcon"
            class="management-panel-header-title-action"
            :aria-label="titleActionTitle"
            :icon-name="titleActionIcon"
            icon-position="end"
            type="text"
            :title="titleActionTitle ? `${titleActionTitle}：${title}` : title"
            @click="emit('titleAction')"
          >
            <span class="management-panel-header-title-action-label">{{ title }}</span>
          </UiButton>
          <template v-else>{{ title }}</template>
        </h2>
        <p v-if="subtitle" class="management-panel-header-subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.status" class="management-panel-header-status">
        <slot name="status" />
      </div>
    </div>
    <div v-if="$slots.actions" class="management-panel-header-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.management-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--muyun-management-panel-header-gap, 8px);
  min-width: 0;
  min-height: var(--muyun-management-panel-header-height, 30px);
}

.management-panel-header-title-group {
  display: inline-flex;
  flex: 1 1 auto;
  align-items: center;
  gap: var(--muyun-management-panel-header-gap, 8px);
  min-width: 0;
}

.management-panel-header-title-copy {
  display: grid;
  flex: 1 1 auto;
  min-width: 0;
}

.management-panel-header-title-prefix {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
}

.management-panel-header-title {
  display: block;
  margin: 0;
  min-width: 0;
  height: var(--muyun-management-panel-header-height, 30px);
  padding: 0;
  color: var(--muyun-text);
  font-size: var(--muyun-management-panel-title-font-size, 16px);
  font-weight: 700;
  line-height: var(--muyun-management-panel-title-line-height, 22px);
}

.management-panel-header-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.management-panel-header-subtitle {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--muyun-text-secondary);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.management-panel-header-title-action {
  position: relative;
  z-index: 0;
  display: inline-flex;
  max-width: 100%;
  min-width: 0;
  height: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  border-radius: 4px;
  color: inherit;
  font: inherit;
}

.management-panel-header-title--action {
  display: flex;
  align-items: center;
  overflow: visible;
}

.management-panel-header-title-action-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.management-panel-header-title-action::before {
  position: absolute;
  z-index: -1;
  inset: 0 -24px 0 -4px;
  border-radius: 4px;
  background: var(--muyun-hover);
  content: '';
  opacity: 0;
  pointer-events: none;
  transition: opacity 120ms ease;
}

:deep(.management-panel-header-title-action.ant-btn-text:not(:disabled):hover) {
  background: transparent;
}

.management-panel-header-title-action :deep(.ui-button-trailing-icon) {
  position: absolute;
  top: 50%;
  left: calc(100% + 6px);
  width: 14px;
  margin-inline: 0;
  color: var(--muyun-text-muted);
  opacity: 0;
  overflow: hidden;
  transform: translate(-4px, -50%);
  transition:
    opacity 120ms ease,
    transform 120ms ease;
}

.management-panel-header-title-action:hover :deep(.ui-button-trailing-icon),
.management-panel-header-title-action:focus-visible :deep(.ui-button-trailing-icon) {
  opacity: 1;
  transform: translate(0, -50%);
}

.management-panel-header-title-action:hover::before,
.management-panel-header-title-action:focus-visible::before {
  opacity: 1;
}

.management-panel-header-status {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  min-width: 0;
}

.management-panel-header-actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: var(--muyun-management-panel-header-gap, 8px);
  min-width: 0;
  max-width: 100%;
}
</style>
