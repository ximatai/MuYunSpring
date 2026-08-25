<script setup lang="ts">
import {
  RecordDetailExtensionSection,
  RecordDetailFields,
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
  'children-change': [relationField: string, records: QueryListRecord[]];
  'relations-validity-change': [valid: boolean];
}>();

function updateField(fieldName: string, value: RecordFormFieldValue) {
  emit('update:field', fieldName, value);
}

function updateChildren(relationField: string, records: QueryListRecord[]) {
  emit('children-change', relationField, records);
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
    <RecordDetailExtensionSection
      v-for="section in extensionSections"
      :key="section.key"
      :title="section.title"
    >
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
