<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  RecordFormFields,
  type RecordFormFieldDescriptor,
  type RecordFormFieldState,
  type RecordFormFieldPickerConfig,
  type RecordFormRecord,
  type RecordFormFieldValue,
} from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import ModulePageFormContributionRenderer from './ModulePageFormContributionRenderer.vue';
import {
  type ModulePageFormContribution,
  type ModulePageFormContributionState,
  type ModulePageFormFieldPolicy,
} from './modulePageEnhancements';
import { useModulePageFormContributionRuntime } from './composables/useModulePageFormContributionRuntime';

defineOptions({ name: 'RecordFormSurface' });

const props = defineProps<{
  record: RecordFormRecord;
  fields: Map<string, RecordFormFieldDescriptor>;
  mode: 'create' | 'edit' | 'view';
  formSessionKey: number;
  validationRequestKey?: number;
  optionContext: ModuleContext<QueryListRecord>;
  fileTransferContext: ModuleContext<QueryListRecord>;
  pickerConfigs: Record<string, RecordFormFieldPickerConfig>;
  disabled: boolean;
  excludeFieldNames?: string[];
  contributions?: readonly ModulePageFormContribution[];
  fieldPolicies?: readonly ModulePageFormFieldPolicy[];
}>();

const emit = defineEmits<{
  'update:field': [fieldName: string, value: RecordFormFieldValue];
  'validity-change': [validity: { valid: boolean }];
}>();

const contributionRef = computed(() => props.contributions ?? []);
const draftRef = computed(() => props.record);
const fieldsRef = computed(() => props.fields);
const modeRef = computed(() => props.mode);
const sessionRef = computed(() => props.formSessionKey);
const {
  valid: contributionValid,
  contextFor,
  stateSnapshot,
} = useModulePageFormContributionRuntime({
  contributions: contributionRef,
  mode: modeRef,
  draft: draftRef,
  fields: fieldsRef,
  formSessionKey: sessionRef,
  setField(fieldName, value) {
    emit('update:field', fieldName, value);
  },
  queryRecords(request) {
    return props.optionContext.crud.query(request);
  },
});

function policy(fieldName: string) {
  return props.fieldPolicies?.find((candidate) => candidate.fieldName === fieldName);
}

function visible(fieldName: string, state: ModulePageFormContributionState) {
  return policy(fieldName)?.visible?.(state) !== false;
}

const fieldNames = computed(() =>
  [...props.fields.keys()].filter(
    (fieldName) => !props.excludeFieldNames?.includes(fieldName) && visible(fieldName, stateSnapshot()),
  ),
);
const hiddenFields = computed(() =>
  [...props.fields.keys()]
    .filter((fieldName) => !fieldNames.value.includes(fieldName))
    .map((fieldName) => stateSnapshot().fields.find((field) => field.fieldName === fieldName))
    .filter((field): field is Readonly<RecordFormFieldState> => field !== undefined),
);

function imageUploadHintOf(fieldName: string) {
  return policy(fieldName)?.imageUploadHint?.(stateSnapshot());
}

function imageUploadAdvisoryOf(fieldName: string) {
  return policy(fieldName)?.imageUploadAdvisory?.(stateSnapshot());
}

const descriptorValid = ref(true);
function updateDescriptorValidity(validity: { valid: boolean }) {
  descriptorValid.value = validity.valid;
}

watch(
  [descriptorValid, contributionValid],
  () => emit('validity-change', { valid: descriptorValid.value && contributionValid.value }),
  { immediate: true },
);
</script>

<template>
  <div class="module-form">
    <ModulePageFormContributionRenderer
      :contributions="contributionRef"
      surface="record-card"
      position="before-fields"
      :context-for="contextFor"
    />
    <RecordFormFields
      :record="record"
      :fields="fields"
      :field-names="fieldNames"
      :form-session-key="formSessionKey"
      :validation-request-key="validationRequestKey"
      :option-context="optionContext"
      :file-transfer-context="fileTransferContext"
      :picker-configs="pickerConfigs"
      :disabled="disabled"
      :exclude-field-names="excludeFieldNames"
      :image-upload-hint-of="imageUploadHintOf"
      :image-upload-advisory-of="imageUploadAdvisoryOf"
      @update:field="(fieldName, value) => emit('update:field', fieldName, value)"
      @validity-change="updateDescriptorValidity"
    >
      <template #before-field="{ field }">
        <ModulePageFormContributionRenderer
          :contributions="contributionRef"
          surface="record-card"
          position="before"
          :field="field"
          :context-for="contextFor"
        />
      </template>
      <template #after-field="{ field }">
        <ModulePageFormContributionRenderer
          :contributions="contributionRef"
          surface="record-card"
          position="after"
          :field="field"
          :context-for="contextFor"
        />
      </template>
    </RecordFormFields>
    <ModulePageFormContributionRenderer
      v-for="field in hiddenFields"
      :key="field.fieldName"
      :contributions="contributionRef"
      surface="record-card"
      position="after"
      :field="field"
      :context-for="contextFor"
    />
    <ModulePageFormContributionRenderer
      :contributions="contributionRef"
      surface="record-card"
      position="after-fields"
      :context-for="contextFor"
    />
  </div>
</template>

<style scoped>
.module-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 12px;
  row-gap: 16px;
  --muyun-record-form-label-gap: 8px;
}

@media (max-width: 900px) {
  .module-form {
    grid-template-columns: 1fr;
  }
}
</style>
