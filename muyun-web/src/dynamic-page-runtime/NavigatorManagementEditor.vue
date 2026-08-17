<script setup lang="ts">
import {
  RecordFormFields,
  RecordPanelButton,
  RecordPanelState,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldValue,
  type RecordFormRecord,
} from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';

defineOptions({ name: 'NavigatorManagementEditor' });

defineProps<{
  open: boolean;
  title: string;
  saving: boolean;
  loading: boolean;
  loadFailed: boolean;
  draft?: RecordFormRecord;
  fields: ReturnType<typeof import('@muyun/platform-components').resolveRecordFormFields>;
  formSessionKey: number;
  context: ModuleContext<unknown>;
  pickerConfigs: Record<string, RecordFormFieldPickerConfig>;
}>();

const emit = defineEmits<{
  close: [];
  save: [];
  updateField: [fieldName: string, value: RecordFormFieldValue];
}>();
</script>

<template>
  <Transition name="navigator-management-drawer">
    <section v-if="open" class="navigator-management-panel">
      <header class="navigator-management-header">
        <h3>{{ title }}</h3>
        <div class="navigator-management-actions">
          <RecordPanelButton :disabled="saving" @click="emit('close')">取消</RecordPanelButton>
          <RecordPanelButton type="primary" :loading="saving" @click="emit('save')">保存</RecordPanelButton>
        </div>
      </header>
      <RecordPanelState v-if="loading" loading loading-tip="加载记录详情" description="" />
      <RecordPanelState v-else-if="loadFailed" description="详情加载失败" />
      <RecordFormFields
        v-else-if="draft"
        :record="draft"
        :fields="fields"
        :form-session-key="formSessionKey"
        :option-context="context"
        :picker-configs="pickerConfigs"
        :exclude-field-names="['enabled']"
        @update:field="(fieldName, value) => emit('updateField', fieldName, value)"
      />
    </section>
  </Transition>
</template>
