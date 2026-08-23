<script setup lang="ts">
import {
  RecordPanelButton,
  RecordPanelState,
  RecordStatusSwitch,
  type RecordFormFieldPickerConfig,
  type RecordFormFieldValue,
  type RecordFormRecord,
} from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import type { ModulePageFormContribution, ModulePageFormFieldPolicy } from './modulePageEnhancements';
import RecordFormSurface from './RecordFormSurface.vue';

defineOptions({ name: 'NavigatorManagementEditor' });

defineProps<{
  open: boolean;
  title: string;
  saving: boolean;
  loading: boolean;
  loadFailed: boolean;
  draft?: RecordFormRecord;
  fields: ReturnType<typeof import('@muyun/platform-components').resolveRecordFormFields>;
  mode: 'create' | 'edit' | 'view';
  formSessionKey: number;
  validationRequestKey?: number;
  context: ModuleContext<QueryListRecord>;
  pickerConfigs: Record<string, RecordFormFieldPickerConfig>;
  contributions?: readonly ModulePageFormContribution[];
  fieldPolicies?: readonly ModulePageFormFieldPolicy[];
  showEnabled?: boolean;
  enabled?: boolean;
  enabledDisabled?: boolean;
  enabledDisabledReason?: string;
  enabledLoading?: boolean;
}>();

const emit = defineEmits<{
  close: [];
  save: [];
  toggleEnabled: [enabled: boolean];
  updateField: [fieldName: string, value: RecordFormFieldValue];
  validityChange: [validity: { valid: boolean }];
}>();
</script>

<template>
  <Transition name="navigator-management-drawer">
    <section v-if="open" class="navigator-management-panel">
      <header class="navigator-management-header">
        <h3>{{ title }}</h3>
        <div class="navigator-management-actions">
          <RecordStatusSwitch
            v-if="showEnabled"
            :enabled="enabled"
            :disabled="enabledDisabled"
            :disabled-reason="enabledDisabledReason"
            :loading="enabledLoading"
            :show-label="false"
            @change="emit('toggleEnabled', $event)"
          />
          <RecordPanelButton :disabled="saving || enabledLoading" @click="emit('close')">
            取消
          </RecordPanelButton>
          <RecordPanelButton
            type="primary"
            :loading="saving"
            :disabled="enabledLoading"
            @click="emit('save')"
          >
            保存
          </RecordPanelButton>
        </div>
      </header>
      <div class="navigator-management-content">
        <RecordPanelState v-if="loading" loading loading-tip="加载记录详情" description="" />
        <RecordPanelState v-else-if="loadFailed" description="详情加载失败" />
        <RecordFormSurface
          v-else-if="draft"
          :record="draft"
          :fields="fields"
          :mode="mode"
          :form-session-key="formSessionKey"
          :validation-request-key="validationRequestKey"
          :option-context="context"
          :file-transfer-context="context"
          :picker-configs="pickerConfigs"
          :exclude-field-names="['enabled']"
          :disabled="saving || enabledLoading"
          :contributions="contributions"
          :field-policies="fieldPolicies"
          @update:field="(fieldName, value) => emit('updateField', fieldName, value)"
          @validity-change="emit('validityChange', $event)"
        />
      </div>
    </section>
  </Transition>
</template>

<style scoped>
.navigator-management-panel {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 3;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 12px;
  max-height: min(420px, 62%);
  min-height: 0;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px 8px 0 0;
  background: var(--muyun-surface);
  box-shadow:
    0 -1px 0 rgb(15 23 42 / 4%),
    0 -12px 28px rgb(15 23 42 / 12%);
  overflow: hidden;
}

.navigator-management-header,
.navigator-management-actions {
  display: flex;
  align-items: center;
}

.navigator-management-header {
  justify-content: space-between;
  gap: 10px;
}

.navigator-management-content {
  min-height: 0;
  overflow: auto;
}

.navigator-management-header h3 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--muyun-text);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.navigator-management-actions {
  flex: 0 0 auto;
  gap: 8px;
}

/* Navigator panels are intentionally narrow and vertically oriented. Keep their
 * shared standard form in one column without changing the record-card layout. */
.navigator-management-panel :deep(.module-form) {
  grid-template-columns: minmax(0, 1fr);
}

.navigator-management-drawer-enter-active,
.navigator-management-drawer-leave-active {
  transition:
    transform 0.18s ease,
    opacity 0.18s ease;
}

.navigator-management-drawer-enter-from,
.navigator-management-drawer-leave-to {
  opacity: 0;
  transform: translateY(100%);
}
</style>
