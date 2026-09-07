<script setup lang="ts">
import RecordContentSectionHeading from './RecordContentSectionHeading.vue';

defineOptions({ name: 'RecordDetailExtensionSection' });

withDefaults(
  defineProps<{
    title: string;
    headingAttributes?: Record<string, string | number | undefined>;
    kind?: 'default' | 'relation';
  }>(),
  { kind: 'default', headingAttributes: undefined },
);
</script>

<template>
  <section class="record-detail-extension-section" :class="`record-detail-extension-section--${kind}`">
    <RecordContentSectionHeading :title="title" v-bind="headingAttributes">
      <template v-if="$slots.actions" #actions><slot name="actions" /></template>
    </RecordContentSectionHeading>
    <div class="record-detail-extension-section-content"><slot /></div>
  </section>
</template>

<style scoped>
.record-detail-extension-section {
  display: grid;
  gap: var(--muyun-detail-section-inner-gap, 8px);
  margin-top: var(--muyun-detail-section-block-gap, 16px);
  padding-top: var(--muyun-detail-section-inner-gap, 8px);
  border-top: 1px solid var(--muyun-border-subtle);
}

.record-detail-extension-section-content {
  min-width: 0;
}
</style>
