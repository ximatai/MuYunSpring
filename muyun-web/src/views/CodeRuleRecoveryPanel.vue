<script setup lang="ts">
import { computed } from 'vue';
import { UiButton, UiError } from '@muyun/vue-ui-antdv';
import { useLanguageContext } from '@muyun/web-core';
import CodeRuleFormatEditor from './CodeRuleFormatEditor.vue';
import type { CodeRule, CodeTarget } from './codeConfiguration';
const props = defineProps<{
  rule?: CodeRule;
  target?: CodeTarget;
  busy: boolean;
  resolved: boolean;
  feedback: string;
  error: string;
}>();
defineEmits<{ check: []; abandon: []; adopt: [] }>();
const language = useLanguageContext();
const t = (key: string, fallback: string) => language.t(`platform.code.config.${key}`, fallback);
// Disabled editor still receives a detached value; inspection cannot mutate recovery facts.
const inspection = computed(() =>
  props.rule ? (JSON.parse(JSON.stringify(props.rule)) as CodeRule) : undefined,
);
</script>
<template>
  <div role="alert" class="code-config__recovery">
    <p>
      {{ t('save-unknown', '原编号保存结果待核实。当前草稿保留；刷新只恢复查询引用，不恢复未保存草稿。') }}
    </p>
    <UiButton :disabled="busy" @click="$emit('check')">{{ t('check-save', '核实原保存结果') }}</UiButton>
    <UiButton :disabled="busy || resolved" @click="$emit('abandon')">{{
      t('abandon-save', '终止原请求并核对')
    }}</UiButton>
    <p>{{ t('abandon-help', '终止不会撤销已提交内容；尚未执行的请求将被阻止。') }}</p>
    <UiButton :disabled="busy || !resolved" @click="$emit('adopt')">{{
      t('adopt-current', '采用当前规则并放弃本地候选')
    }}</UiButton>
    <details v-if="inspection">
      <summary>{{ inspection.title }}</summary>
      <p>
        {{
          inspection.mode === 'MANUAL'
            ? t('manual', '手工编号')
            : inspection.mode === 'AUTO'
              ? t('auto', '自动编号')
              : t('auto-manual', '自动生成后可修改')
        }}
        · {{ inspection.enabled ? t('enabled', '已启用') : t('disabled', '已停用') }} ·
        {{ inspection.orgScopeType === 'GLOBAL' ? t('all-orgs', '全部机构') : t('specific-org', '指定机构') }}
        {{ inspection.orgScopeId }}
      </p>
      <p>{{ inspection.effectiveFrom }} — {{ inspection.effectiveTo }}</p>
      <p>
        {{ t('linked-short', '来源变化时换号') }}: {{ inspection.linkedUpdate }} ·
        {{ t('recycle-short', '旧号可复用') }}: {{ inspection.allowRecycle }}
      </p>
      <CodeRuleFormatEditor
        :rule="inspection"
        :sources="target?.sources ?? []"
        :editable="false"
        :trigger="
          target?.fields.find((field) => field.fieldName === inspection?.fieldName)?.trigger ?? 'SAVE'
        "
      />
      <p v-if="inspection.sequencePolicy">
        {{ t('start', '起始值') }} {{ inspection.sequencePolicy.startValue }} · {{ t('step', '递增步长') }}
        {{ inspection.sequencePolicy.stepValue }} · {{ t('sequence-length', '流水长度') }}
        {{ inspection.sequencePolicy.sequenceLength }} · {{ t('reset', '计数周期') }}
        {{ inspection.sequencePolicy.resetPolicy }} · {{ t('overflow', '溢出策略') }}
        {{ inspection.sequencePolicy.overflowPolicy }}
      </p>
    </details>
    <p v-if="feedback">{{ feedback }}</p>
    <UiError v-if="error" :message="error" />
  </div>
</template>
