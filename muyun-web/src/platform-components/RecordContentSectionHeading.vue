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
      <div v-if="subtitle || $slots.subtitle" class="record-content-section-heading__subtitle">
        <slot name="subtitle">{{ subtitle }}</slot>
      </div>
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
  min-height: var(--muyun-content-section-heading-line-height, var(--muyun-line-height-section, 20px));
}

.record-content-section-heading__copy {
  display: flex;
  flex: 1 1 auto;
  align-items: baseline;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

h3,
.record-content-section-heading__subtitle {
  margin: 0;
}

h3 {
  flex: 0 0 auto;
  max-width: 100%;
  overflow: hidden;
  color: var(--muyun-content-section-heading-color, var(--muyun-text));
  font-size: var(--muyun-content-section-heading-font-size, var(--muyun-font-size-section, 14px));
  font-weight: var(--muyun-content-section-heading-font-weight, 700);
  line-height: var(--muyun-content-section-heading-line-height, var(--muyun-line-height-section, 20px));
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-content-section-heading__subtitle {
  color: var(--muyun-content-section-subtitle-color, var(--muyun-text-muted));
  font-size: var(--muyun-content-section-subtitle-font-size, var(--muyun-font-size-helper, 12px));
  line-height: var(--muyun-content-section-subtitle-line-height, var(--muyun-line-height-helper, 18px));
  flex: 1 1 280px;
  min-width: 0;
  text-align: left;
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

  .record-content-section-heading__subtitle {
    flex-basis: auto;
    text-align: left;
  }
}
</style>
