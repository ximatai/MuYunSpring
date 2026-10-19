<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
const language = useLanguageContext();
defineProps<{ required?: boolean }>();
</script>

<template>
  <span class="record-field-label">
    <span class="record-field-label__text"><slot /></span
    ><strong v-if="required" :aria-label="language.t('platform.form.required-marker', '必填')">*</strong>
  </span>
</template>

<style scoped>
.record-field-label {
  font-size: var(--muyun-font-size-body, 13px);
  line-height: var(--muyun-line-height-body, 21px);
  display: inline-flex;
  align-items: baseline;
  min-width: 0;
  max-width: 100%;
  gap: 4px;
}
.record-field-label__text {
  min-width: 0;
  overflow-wrap: anywhere;
}
.record-field-label strong {
  flex: none;
  color: var(--muyun-danger-base);
  font-weight: 600;
}
</style>
