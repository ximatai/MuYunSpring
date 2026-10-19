<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { UiIcon } from '@muyun/vue-ui-antdv';

defineProps<{ title: string }>();
const emit = defineEmits<{ refresh: [] }>();
const language = useLanguageContext();
</script>
<template>
  <h1 class="workbench-page-title">
    <button
      class="workbench-page-refresh"
      type="button"
      :title="language.t('platform.workbench.refresh-tab', '刷新当前页：{title}', { title })"
      :aria-label="language.t('platform.workbench.refresh-tab', '刷新当前页：{title}', { title })"
      @click="emit('refresh')"
    >
      <span class="workbench-page-title-label">{{ title }}</span>
      <span class="workbench-page-refresh-icon" aria-hidden="true"><UiIcon name="reload" /></span>
    </button>
  </h1>
</template>
<style scoped>
.workbench-page-title {
  flex: 0 1 auto;
  min-width: 0;
  margin: 0;
}
.workbench-page-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  min-width: 32px;
  height: 32px;
  padding: 0 4px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--muyun-text);
  font: inherit;
  font-size: var(--muyun-font-size-panel, 16px);
  font-weight: 700;
  line-height: var(--muyun-line-height-panel, 22px);
  cursor: pointer;
}
.workbench-page-title-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workbench-page-refresh-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 16px;
  width: 16px;
  color: var(--muyun-text-muted);
  font-size: var(--muyun-font-size-section, 14px);
  opacity: 0;
  transition: opacity 120ms ease;
}
.workbench-page-refresh:hover {
  background: var(--muyun-hover);
}
.workbench-page-refresh:focus-visible {
  outline: 2px solid var(--muyun-theme-border);
  outline-offset: 2px;
}
.workbench-page-refresh:hover .workbench-page-refresh-icon,
.workbench-page-refresh:focus-visible .workbench-page-refresh-icon {
  opacity: 1;
}
@media (hover: none), (pointer: coarse), (max-width: 480px) {
  .workbench-page-refresh-icon {
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .workbench-page-refresh-icon {
    transition: none;
  }
}
</style>
