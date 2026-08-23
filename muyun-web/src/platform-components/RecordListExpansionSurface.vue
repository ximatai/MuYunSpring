<script setup lang="ts">
defineOptions({ name: 'RecordListExpansionSurface' });
</script>

<template>
  <section class="record-list-expansion-surface">
    <slot />
  </section>
</template>

<style scoped>
/* Shared chrome for descriptor relations and application-owned list drill-downs. */
.record-list-expansion-surface {
  min-width: 0;
  padding: 16px;
  border-top: 1px solid var(--muyun-border-subtle);
  background: var(--muyun-hover-subtle);
}
@media (max-width: 620px) {
  .record-list-expansion-surface {
    padding: 12px;
  }
}
</style>
