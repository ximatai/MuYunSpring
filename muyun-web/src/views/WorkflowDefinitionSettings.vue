<script setup lang="ts">
import { UiInput, UiCheckbox, UiHelp } from '@muyun/vue-ui-antdv';
import { RecordFieldLabel } from '@muyun/platform-components';
import WorkflowSelectionFields from './WorkflowSelectionFields.vue';
import type { useWorkflowConfigurationContext } from './useWorkflowConfigurationContext';
const props = defineProps<{
  configuration: ReturnType<typeof useWorkflowConfigurationContext>;
  creating: boolean;
  busy: boolean;
  actionCodeLocked?: boolean;
}>();
const { language: workflowLanguage, fields, organizationContext, conditionTools } = props.configuration;
const emit = defineEmits<{ submit: [] }>();
const newTitle = defineModel<string>('title', { required: true });
const approvalEnabled = defineModel<boolean>('approvalEnabled', { required: true });
const actionCode = defineModel<string>('actionCode', { required: true });
const organizationId = defineModel<string>('organizationId', { required: true });
const matchExpression = defineModel<string>('matchExpression', { required: true });
const fallback = defineModel<boolean>('fallback', { required: true });
</script>
<template>
  <form class="property-form" @submit.prevent="emit('submit')">
    <p v-if="creating" class="settings-help">
      {{
        workflowLanguage.t(
          'platform.workflow-config.creation-help',
          '先创建草稿，再配置节点和参与人；发布后才会用于后续申请。',
        )
      }}
    </p>
    <label class="workflow-field"
      ><RecordFieldLabel required>{{
        workflowLanguage.t('platform.screen.WorkflowConfigurationView.workflow-name', '流程名称')
      }}</RecordFieldLabel
      ><UiInput
        :disabled="busy"
        v-model:value="newTitle"
        required
        :aria-label="
          workflowLanguage.t('platform.screen.WorkflowConfigurationView.workflow-name', '流程名称')
        "
    /></label>
    <div v-if="creating" class="settings-wide workflow-approval-setting">
      <UiCheckbox :disabled="busy" v-model:checked="approvalEnabled">{{
        workflowLanguage.t(
          'platform.screen.WorkflowConfigurationView.govern-business-approval-status',
          '驱动业务审批状态',
        )
      }}</UiCheckbox>
      <UiHelp :label="workflowLanguage.t('platform.workflow-config.approval-help-title', '审批状态说明')">
        {{
          workflowLanguage.t(
            'platform.workflow-config.approval-help',
            '开启后回写业务记录的审批状态；关闭后仅编排任务流程。',
          )
        }}
      </UiHelp>
    </div>
    <label v-if="!approvalEnabled" class="workflow-field settings-wide">
      <RecordFieldLabel required>{{
        workflowLanguage.t('platform.workflow-config.action-code', '模块动作编码')
      }}</RecordFieldLabel>
      <UiInput
        v-model:value="actionCode"
        required
        :disabled="busy || actionCodeLocked"
        :invalid="!!actionCode.trim() && !/^[a-z][A-Za-z0-9_]{0,63}$/.test(actionCode.trim())"
        :aria-label="workflowLanguage.t('platform.workflow-config.action-code', '模块动作编码')"
      />
      <span class="settings-help">{{
        workflowLanguage.t(
          'platform.workflow-config.action-code-help',
          '用于流程启动动作，以小写字母开头，可含字母、数字、下划线，最多64位；发布后不能修改。',
        )
      }}</span>
    </label>
    <details class="settings-wide" :open="!creating">
      <summary>
        {{ workflowLanguage.t('platform.workflow-config.optional-matching', '适用范围与匹配规则（可选）') }}
      </summary>
      <WorkflowSelectionFields
        :condition-tools="conditionTools"
        class="optional-settings"
        v-model:organization-id="organizationId"
        v-model:match-expression="matchExpression"
        v-model:fallback="fallback"
        :organization-context="organizationContext"
        :fields="fields"
        :disabled="busy"
      />
    </details>
  </form>
</template>
<style scoped>
.property-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
  gap: 16px;
  align-items: start;
  max-width: 850px;
}
.optional-settings {
  margin-top: 16px;
}
.property-form p {
  grid-column: 1/-1;
}
.workflow-field {
  display: grid;
  align-content: start;
  gap: 8px;
  min-width: 0;
}
.settings-wide {
  grid-column: 1 / -1;
}
.workflow-approval-setting {
  display: flex;
  align-items: center;
  gap: 8px;
}
.settings-help {
  margin: 0;
  color: var(--muyun-text-secondary);
  font-size: var(--muyun-font-size-helper, 12px);
  line-height: var(--muyun-line-height-helper, 18px);
}
summary {
  cursor: pointer;
  color: var(--muyun-primary);
}
</style>
