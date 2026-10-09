<script setup lang="ts">
import { ref } from 'vue';
import type { OptionItemDescriptor } from '@muyun/web-contracts';
import {
  RecordDetailExtensionSection,
  RecordDetailFields,
  WorkflowRecordPanel,
  RecordMetaSection,
  type QueryListRecord,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldValue,
  type RecordFormRecord,
  type RecordFormFieldDescriptor,
} from '@muyun/platform-components';
import type { HttpClient, ModuleContext } from '@muyun/web-core';
import type { ResolvedDetailRelationDescriptor, ResolvedModuleUiDescriptor } from '@muyun/web-contracts';
import type {
  ModulePageDetailSection,
  ModulePageDetailSectionContext,
  ModulePageFormContribution,
  ModulePageFormFieldPolicy,
} from './modulePageEnhancements';
import ModulePageDetailRelations from './ModulePageDetailRelations.vue';
import RecordFormSurface from './RecordFormSurface.vue';

defineOptions({ name: 'ModulePageRecordContent' });

defineProps<{
  context: ModuleContext<QueryListRecord>;
  /** Neutral transport for reference target modules. */
  crossModuleHttp?: HttpClient;
  mode: 'view' | 'edit' | 'create';
  record: QueryListRecord;
  selectedRecord?: QueryListRecord;
  detailDisplayFields: Map<string, RecordFormFieldDescriptor>;
  formFields: Map<string, RecordFormFieldDescriptor>;
  formSessionKey: number;
  validationRequestKey: number;
  pickerConfigs: Record<string, RecordFormFieldPickerConfig>;
  saving?: boolean;
  uiDescriptor?: ResolvedModuleUiDescriptor;
  relations: ResolvedDetailRelationDescriptor[];
  relationsAvailable: boolean;
  relationReloadKey: number;
  showSystemInfo: boolean;
  extensionSections: ModulePageDetailSection[];
  detailSectionContext(record: QueryListRecord): ModulePageDetailSectionContext;
  formContributions?: readonly ModulePageFormContribution[];
  formFieldPolicies?: readonly ModulePageFormFieldPolicy[];
}>();

const emit = defineEmits<{
  'update:field': [fieldName: string, value: RecordFormFieldValue];
  'validity-change': [validity: { valid: boolean }];
  'reference-display-change': [
    fieldName: string,
    candidates: readonly import('@muyun/platform-components').ReferencePickerCandidate[],
  ];
  'children-change': [
    relationField: string,
    records: QueryListRecord[],
    displayRecords: QueryListRecord[],
    options: Record<string, OptionItemDescriptor[]>,
  ];
  'relations-validity-change': [valid: boolean];
  'workflow-interaction-change': [state: { editing: boolean; busy: boolean; dirty: boolean }];
  'workflow-changed': [];
}>();

const workflowPanel = ref<{ mayLeave: () => Promise<boolean> }>();
defineExpose({ mayLeave: () => workflowPanel.value?.mayLeave() ?? Promise.resolve(true) });

function updateField(fieldName: string, value: RecordFormFieldValue) {
  emit('update:field', fieldName, value);
}

function updateChildren(
  relationField: string,
  records: QueryListRecord[],
  displayRecords: QueryListRecord[],
  options: Record<string, OptionItemDescriptor[]>,
) {
  emit('children-change', relationField, records, displayRecords, options);
}
</script>

<template>
  <template v-if="mode === 'view'">
    <RecordDetailFields
      :record="record as RecordFormRecord"
      :fields="detailDisplayFields"
      :option-context="context"
      :file-transfer-context="context"
      :exclude-field-names="['enabled']"
    />
    <WorkflowRecordPanel
      ref="workflowPanel"
      @interaction-change="emit('workflow-interaction-change', $event)"
      v-if="record.id && context.abilities.has('approval') === true"
      :context="context"
      :record-id="String(record.id)"
      @changed="emit('workflow-changed')"
    />
    <RecordDetailExtensionSection
      v-for="section in extensionSections"
      :key="section.key"
      :title="section.title"
      :subtitle="section.subtitle"
    >
      <template v-if="section.subtitleComponent" #subtitle>
        <component :is="section.subtitleComponent" :context="detailSectionContext(record)" />
      </template>
      <component :is="section.component" :context="detailSectionContext(record)" />
    </RecordDetailExtensionSection>
  </template>
  <RecordFormSurface
    v-else
    :record="record as RecordFormRecord"
    :fields="formFields"
    :mode="mode"
    :form-session-key="formSessionKey"
    :validation-request-key="validationRequestKey"
    :option-context="context"
    :file-transfer-context="context"
    :picker-configs="pickerConfigs"
    :disabled="saving"
    :exclude-field-names="['enabled']"
    :contributions="formContributions"
    :field-policies="formFieldPolicies"
    @update:field="updateField"
    @validity-change="emit('validity-change', $event)"
    @reference-display-change="
      (fieldName, candidates) => emit('reference-display-change', fieldName, candidates)
    "
  />
  <ModulePageDetailRelations
    v-if="uiDescriptor && relationsAvailable"
    :source-context="context"
    :cross-module-http="crossModuleHttp"
    :ui-descriptor="uiDescriptor"
    :relations="relations"
    :parent-record="(mode === 'view' ? selectedRecord : record) ?? record"
    :mutation-enabled="mode !== 'view'"
    :reload-key="relationReloadKey"
    :validation-request-key="validationRequestKey"
    @children-change="updateChildren"
    @validity-change="emit('relations-validity-change', $event)"
  />
  <RecordMetaSection v-if="mode !== 'create' && showSystemInfo" :record="record" show-sort-order />
</template>
