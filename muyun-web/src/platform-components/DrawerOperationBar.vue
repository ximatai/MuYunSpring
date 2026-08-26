<script setup lang="ts">
defineOptions({ name: 'DrawerOperationBar' });

withDefaults(
  defineProps<{
    label?: string;
  }>(),
  {
    label: '操作区域',
  },
);
</script>

<template>
  <section class="drawer-operation-bar" :aria-label="label">
    <div v-if="$slots.summary" class="drawer-operation-bar-summary"><slot name="summary" /></div>
    <div class="drawer-operation-bar-actions"><slot /></div>
  </section>
</template>

<style scoped>
.drawer-operation-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}

.drawer-operation-bar-summary {
  min-width: 0;
  overflow: hidden;
  color: var(--muyun-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-operation-bar-actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.drawer-operation-bar :deep(.record-action-bar) {
  justify-content: flex-end;
}

.drawer-operation-bar :deep(.ant-btn-primary) {
  order: 1;
}

@media (max-width: 480px) {
  .drawer-operation-bar,
  .drawer-operation-bar-actions,
  .drawer-operation-bar :deep(.record-action-bar) {
    width: 100%;
  }

  .drawer-operation-bar :deep(.ant-btn) {
    flex: 1 1 auto;
  }
}
</style>
