<script setup lang="ts">
import { UiButton, type UiIconName } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'ManagementPanelHeader' });

const props = withDefaults(
  defineProps<{
    title: string;
    subtitle?: string;
    titleActionIcon?: UiIconName;
    titleActionTitle?: string;
    titleActionDisabled?: boolean;
  }>(),
  {
    subtitle: undefined,
    titleActionIcon: undefined,
    titleActionTitle: undefined,
    titleActionDisabled: false,
  },
);

function titleActionTooltip() {
  const actionTitle = props.titleActionTitle?.trim();
  if (!actionTitle) return props.title;
  if (!actionTitle.startsWith('刷新')) return `${actionTitle}：${props.title}`;
  return `刷新：${props.title}`;
}

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
      <div
        class="management-panel-header-title-copy"
        :class="{ 'management-panel-header-title-copy--with-status': $slots.status }"
      >
        <h2
          class="management-panel-header-title"
          :class="{
            'management-panel-header-title--action': titleActionIcon,
            'management-panel-header-title--with-subtitle': subtitle,
          }"
          :title="title"
        >
          <UiButton
            v-if="titleActionIcon"
            class="management-panel-header-title-action"
            :aria-label="titleActionTooltip()"
            :icon-name="titleActionIcon"
            icon-position="end"
            type="text"
            :title="titleActionTooltip()"
            :disabled="titleActionDisabled"
            @click="emit('titleAction')"
          >
            <span class="management-panel-header-title-action-label">{{ title }}</span>
          </UiButton>
          <span v-else class="management-panel-header-title-label">{{ title }}</span>
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
  gap: 4px;
  min-width: 0;
}

.management-panel-header-title-copy {
  display: grid;
  flex: 1 1 auto;
  min-width: 0;
  max-width: 100%;
}

.management-panel-header-title-copy--with-status {
  flex: 0 1 auto;
}

.management-panel-header-title-prefix {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
}

.management-panel-header-title {
  display: flex;
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

.management-panel-header-title--with-subtitle {
  height: var(--muyun-management-panel-title-line-height, 22px);
}

.management-panel-header-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.management-panel-header-title-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.management-panel-header-subtitle {
  margin: 1px 0 0;
  overflow: hidden;
  color: var(--muyun-text-secondary);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.management-panel-header-title-action {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  min-width: 0;
  height: 100%;
  margin-inline-start: -4px;
  padding: 0 4px;
  border: 0;
  background: transparent;
  border-radius: 4px;
  color: inherit;
  font: inherit;
  transition: background 120ms ease;
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

:deep(.management-panel-header-title-action.ant-btn-text:not(:disabled):hover),
:deep(.management-panel-header-title-action.ant-btn-text:not(:disabled):focus-visible) {
  background: var(--muyun-hover);
}

.management-panel-header-title-action :deep(.ui-button-trailing-icon) {
  display: inline-block;
  flex: 0 0 auto;
  width: 0;
  margin-inline: 0;
  color: var(--muyun-text-muted);
  opacity: 0;
  overflow: hidden;
  transition:
    opacity 120ms ease,
    width 120ms ease,
    margin-inline-start 120ms ease;
}

.management-panel-header-title-action:hover :deep(.ui-button-trailing-icon),
.management-panel-header-title-action:focus-visible :deep(.ui-button-trailing-icon) {
  width: 14px;
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
  /* Give the adaptive action bar the real space left by the title group.
     Otherwise it sizes to its current content, and measuring a lone 更多
     button makes the overflow decision self-reinforcing. */
  flex: 1 1 auto;
  align-items: center;
  justify-content: flex-end;
  gap: var(--muyun-management-panel-header-gap, 8px);
  min-width: 0;
  max-width: 100%;
}
</style>
