<script setup lang="ts">
defineOptions({ name: 'RecordFormGrid' });

withDefaults(defineProps<{ as?: 'form' | 'div'; surface?: 'record' }>(), { as: 'form', surface: undefined });

const emit = defineEmits<{ submit: [event: SubmitEvent] }>();

function submit(event: SubmitEvent) {
  emit('submit', event);
}
</script>

<template>
  <component
    :is="as"
    class="record-form-grid"
    :class="{ 'record-form-grid--record': surface === 'record' }"
    @submit="submit"
  >
    <slot />
  </component>
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
  /* Long control values must shrink within the field rather than size its implicit grid track. */
  grid-template-columns: minmax(0, 1fr);
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
@media (max-width: 900px) {
  .record-form-grid--record {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
