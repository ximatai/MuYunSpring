<script setup lang="ts">
defineProps<{ required?: boolean }>();
</script>

<template>
  <span class="record-field-label"> <slot /><strong v-if="required" aria-label="必填">*</strong> </span>
</template>

<style scoped>
.record-field-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.record-field-label strong {
  color: var(--muyun-danger-base);
  font-weight: 600;
}
</style>
