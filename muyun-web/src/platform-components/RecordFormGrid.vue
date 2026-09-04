<script setup lang="ts">
defineOptions({ name: 'RecordFormGrid' });

const emit = defineEmits<{ submit: [event: SubmitEvent] }>();

function submit(event: SubmitEvent) {
  emit('submit', event);
}
</script>

<template>
  <form class="record-form-grid" @submit="submit"><slot /></form>
</template>

<style scoped>
.record-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 12px;
  row-gap: 16px;
  min-width: 0;
  --muyun-record-form-label-gap: 8px;
}

.record-form-grid :slotted(label) {
  display: grid;
  min-width: 0;
  gap: var(--muyun-record-form-label-gap);
  color: var(--muyun-text-body);
  font-size: 13px;
  line-height: 20px;
}

.record-form-grid :slotted(.record-form-full-row) {
  grid-column: 1 / -1;
}

@media (max-width: 720px) {
  .record-form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
