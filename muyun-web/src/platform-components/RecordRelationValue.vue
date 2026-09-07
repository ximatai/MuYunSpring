<script setup lang="ts">
import RecordStatusTag from './RecordStatusTag.vue';
import {
  resolveRecordBooleanStatusValue,
  type RecordFormFieldState,
  type RecordFormRecord,
} from './recordFormFieldModel';
import { resolveRecordDetailDisplayValue } from './recordDetailFieldModel';
defineOptions({ name: 'RecordRelationValue' });
defineProps<{ field: RecordFormFieldState; record: RecordFormRecord; text?: string }>();
</script>
<template>
  <RecordStatusTag
    v-if="field.controlType === 'enabledStatus' || field.controlType === 'booleanStatus'"
    :enabled="
      field.controlType === 'booleanStatus'
        ? resolveRecordBooleanStatusValue(record[field.fieldName])
        : record[field.fieldName] !== false
    "
    :enabled-label="field.booleanStatus?.trueLabel"
    :disabled-label="field.booleanStatus?.falseLabel"
    :enabled-tone="field.booleanStatus?.trueTone"
    :disabled-tone="field.booleanStatus?.falseTone"
  />
  <span v-else class="managed-relation-inline__value">{{
    text ?? resolveRecordDetailDisplayValue(field, record)
  }}</span>
</template>
<style scoped>
.managed-relation-inline__value {
  display: block;
  overflow: hidden;
  padding: 0 4px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
