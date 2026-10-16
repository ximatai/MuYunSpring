<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import RecordStatusTag from './RecordStatusTag.vue';
import RecordContentSourceField from './RecordContentSourceField.vue';
import {
  resolveRecordBooleanStatusValue,
  type RecordFormFieldState,
  type RecordFormRecord,
} from './recordFormFieldModel';
import { resolveRecordDetailDisplayValue } from './recordDetailFieldModel';
import ReadonlyReferenceValue from './ReadonlyReferenceValue.vue';
import { readonlyReferenceSummaryOf } from './readonlyReferenceDisplay';
defineOptions({ name: 'RecordRelationValue' });
const language = useLanguageContext();
defineProps<{
  field: RecordFormFieldState;
  record: RecordFormRecord;
  text?: string;
  contentContext?: import('@muyun/web-core').ModuleContext<unknown>;
  contentRelation?: import('@muyun/web-core').BusinessContentRelationScope;
}>();
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
  <span v-else-if="field.reference && text == null" class="managed-relation-inline__value"
    ><ReadonlyReferenceValue
      :reference="field.reference"
      :value="record[field.fieldName]"
      :summary="readonlyReferenceSummaryOf(record, field.referenceTitleField, language)"
      empty-text="-"
  /></span>
  <span v-else class="managed-relation-inline__value">{{
    text ?? resolveRecordDetailDisplayValue(field, record)
  }}</span>
  <RecordContentSourceField
    v-if="field.content && field.content.policy !== 'KEEP_ORIGINAL'"
    :record="record"
    :field-name="field.fieldName"
    :content="field.content"
    :context="contentContext"
    :relation="contentRelation"
    mode="view"
    compact
  />
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
