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
        <UiButton
          v-if="titleActionIcon"
          class="management-panel-header-title-action"
          :aria-label="titleActionTitle"
          :icon-name="titleActionIcon"
          icon-position="end"
          type="text"
          :title="titleActionTitle"
          @click="emit('titleAction')"
        >
          {{ title }}
        </UiButton>
        <h2 v-else class="management-panel-header-title">{{ title }}</h2>
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
  min-width: 0;
}

.management-panel-header-title-prefix {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
}

.management-panel-header-title,
.management-panel-header-title-action {
  display: inline-flex;
  align-items: center;
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
  border: 0;
  background: transparent;
}

:deep(.management-panel-header-title-action.ant-btn-text:not(:disabled):hover) {
  background: transparent;
}

.management-panel-header-title-action :deep(.ui-button-trailing-icon) {
  width: 0;
  margin-inline: 0;
  color: var(--muyun-text-muted);
  opacity: 0;
  overflow: hidden;
  transition:
    width 120ms ease,
    margin-inline-start 120ms ease,
    opacity 120ms ease;
}

.management-panel-header-title-action:hover :deep(.ui-button-trailing-icon),
.management-panel-header-title-action:focus-visible :deep(.ui-button-trailing-icon) {
  width: 12px;
  margin-inline-start: 6px;
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
