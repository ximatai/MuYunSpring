<script setup lang="ts">
defineOptions({ name: 'RecordContentSectionHeading' });

defineProps<{
  title: string;
  subtitle?: string;
}>();
</script>

<template>
  <header class="record-content-section-heading">
    <div class="record-content-section-heading__copy">
      <h3>{{ title }}</h3>
      <p v-if="subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="record-content-section-heading__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.record-content-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
  min-height: var(--muyun-content-section-heading-line-height, 20px);
}

.record-content-section-heading__copy {
  display: flex;
  flex: 1 1 auto;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

h3,
p {
  margin: 0;
}

h3 {
  overflow: hidden;
  color: var(--muyun-content-section-heading-color, var(--muyun-text));
  font-size: var(--muyun-content-section-heading-font-size, 13px);
  font-weight: var(--muyun-content-section-heading-font-weight, 600);
  line-height: var(--muyun-content-section-heading-line-height, 20px);
  text-overflow: ellipsis;
  white-space: nowrap;
}

p {
  color: var(--muyun-content-section-subtitle-color, var(--muyun-text-muted));
  font-size: var(--muyun-content-section-subtitle-font-size, 12px);
  line-height: var(--muyun-content-section-subtitle-line-height, 18px);
  text-align: right;
}

.record-content-section-heading__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
}

@media (max-width: 720px) {
  .record-content-section-heading,
  .record-content-section-heading__copy {
    align-items: flex-start;
  }

  .record-content-section-heading__copy {
    flex-direction: column;
    gap: 4px;
  }

  p {
    text-align: left;
  }
}
</style>
