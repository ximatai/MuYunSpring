<script setup lang="ts">
defineOptions({ name: 'RecordDetailExtensionSection' });

defineProps<{
  title: string;
}>();
</script>

<template>
  <section class="record-detail-extension-section">
    <h3>{{ title }}</h3>
    <div class="record-detail-extension-section-content"><slot /></div>
  </section>
</template>

<style scoped>
.record-detail-extension-section {
  display: grid;
  gap: 10px;
  padding-top: 16px;
  border-top: 1px solid var(--muyun-border-subtle);
}

.record-detail-extension-section h3 {
  margin: 0;
  color: var(--muyun-text);
  font-size: 13px;
  font-weight: 600;
}

.record-detail-extension-section-content {
  min-width: 0;
}
</style>
