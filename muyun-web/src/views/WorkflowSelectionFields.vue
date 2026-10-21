<script setup lang="ts">
import { useLanguageContext, type ModuleContext } from '@muyun/web-core';
import { UiCheckbox, UiInput } from '@muyun/vue-ui-antdv';
import { FormulaExpressionEditor, RecordPicker } from '@muyun/platform-components';
import type { FormulaExpressionTools } from '../platform-components/formulaExpressionTools';
import type { FormulaExpressionField } from '../platform-components/formulaExpressionTokens';

defineOptions({ name: 'WorkflowSelectionFields' });
const language = useLanguageContext();
const props = defineProps<{
  organizationContext?: ModuleContext<Record<string, unknown>>;
  fields: readonly FormulaExpressionField[];
  disabled: boolean;
  conditionTools?: FormulaExpressionTools;
}>();
const organizationId = defineModel<string>('organizationId', { required: true });
const matchExpression = defineModel<string>('matchExpression', { required: true });
const fallback = defineModel<boolean>('fallback', { required: true });
</script>
<template>
  <div class="workflow-selection-fields">
    <label class="workflow-field settings-wide"
      >{{ language.t('platform.screen.WorkflowConfigurationView.applicable-organization', '适用组织')
      }}<RecordPicker
        v-if="props.organizationContext"
        :placeholder="
          language.t('platform.workflow-config.organization-placeholder', '全部组织（可选指定组织）')
        "
        :value="organizationId || undefined"
        :context="props.organizationContext"
        :disabled="props.disabled"
        mode="tree"
        @update:value="organizationId = $event ?? ''"
      />
      <UiInput
        v-else
        :value="organizationId || language.t('platform.workflow-config.all-organizations', '模块内全部组织')"
        disabled
      />
      <small class="settings-help">{{
        props.organizationContext
          ? language.t(
              'platform.workflow-config.organization-help',
              '留空适用于全部组织；指定组织后包含其下级组织。',
            )
          : language.t(
              'platform.workflow-config.global-scope-help',
              '当前为全局流程模板，适用于全部组织；组织选择需使用租户身份。',
            )
      }}</small></label
    >
    <div class="workflow-field settings-wide">
      <span>{{ language.t('platform.screen.WorkflowConfigurationView.matching-condition', '匹配条件') }}</span
      ><FormulaExpressionEditor
        :disabled="props.disabled || fallback"
        v-model:value="matchExpression"
        :fields="props.fields"
        :tools="conditionTools"
        boolean-result
        :placeholder="
          language.t(
            'platform.screen.WorkflowConfigurationView.leave-empty-to-match-all-records',
            '留空匹配所有记录',
          )
        "
      />
    </div>
    <div class="settings-wide workflow-field">
      <UiCheckbox :disabled="props.disabled" v-model:checked="fallback">{{
        language.t(
          'platform.screen.WorkflowConfigurationView.use-as-the-fallback-workflow-for-this',
          '作为当前组织范围的兜底流程',
        )
      }}</UiCheckbox
      ><small class="settings-help">{{
        language.t(
          'platform.workflow-config.fallback-help',
          '同一组织范围内无普通流程命中时使用；兜底流程不判断匹配条件。',
        )
      }}</small>
    </div>
  </div>
</template>
<style scoped>
.workflow-selection-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
  grid-column: 1 / -1;
  gap: 16px;
  min-width: 0;
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
.settings-help {
  margin: 0;
  color: var(--muyun-text-secondary);
  font-size: var(--muyun-font-size-helper, 12px);
  line-height: var(--muyun-line-height-helper, 18px);
}
</style>
