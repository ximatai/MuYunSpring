<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { createModuleContext, type HttpClient } from '@muyun/web-core';
import { UiButton, UiCheckbox, UiInput, UiSelect } from '@muyun/vue-ui-antdv';
import { workflowAccountOptions } from './workflowAccountOptions';
import RecordFieldLabel from './RecordFieldLabel.vue';
import RecordMultiPicker from './RecordMultiPicker.vue';
import type { RecordPickerRecord } from './recordPickerConstraints';
import { workflowParticipantRules, type WorkflowParticipantRule as Rule } from './workflowParticipantPolicy';
const language = useLanguageContext();
const props = defineProps<{
  value?: string;
  http: HttpClient;
  disabled?: boolean;
  fields?: readonly { name: string; label: string; referenceModule?: string }[];
}>();
const emit = defineEmits<{ 'update:value': [value: string] }>();
const rules = ref<Rule[]>([]),
  error = ref(false);
const kinds = computed(() => [
  { value: 'USER', label: language.t('platform.workflow-participant.named-accounts', '指定账号') },
  { value: 'ROLE', label: language.t('platform.workflow-participant.role-members', '角色成员') },
  { value: 'DEPT', label: language.t('platform.workflow-participant.department-members', '部门成员') },
  { value: 'ORG', label: language.t('platform.workflow-participant.organization-members', '组织成员') },
  { value: 'RELATIVE', label: language.t('platform.workflow-participant.relative-participants', '相对人员') },
  { value: 'FIELD', label: language.t('platform.workflow-participant.participant-field', '业务人员字段') },
  { value: 'INITIATOR_SELF', label: language.t('platform.workflow-participant.initiator', '提交人本人') },
]);
const accountOptions = computed(() => workflowAccountOptions(props.http));
const contexts = computed(() =>
  Object.fromEntries(
    Object.entries({
      USER: 'iam.user',
      ROLE: 'iam.role',
      DEPT: 'iam.department',
      ORG: 'iam.organization',
    }).map(([kind, moduleAlias]) => [
      kind,
      createModuleContext<RecordPickerRecord>({
        http: props.http,
        moduleAlias,
        language,
        runtimeAccess: 'VIEW',
      }),
    ]),
  ),
);
watch(
  () => props.value,
  (value) => {
    error.value = false;
    try {
      rules.value = workflowParticipantRules(value);
    } catch {
      error.value = true;
      rules.value = [];
    }
  },
  { immediate: true },
);
function save() {
  if (!props.disabled) emit('update:value', JSON.stringify({ rules: rules.value }));
}
function changeRelation(rule: Rule, relation: unknown) {
  rule.relation = String(relation);
  rule.depth = rule.relation === 'SUPERVISOR' ? 1 : 0;
  save();
}
function title(record: RecordPickerRecord) {
  const values = record as Record<string, unknown>;
  return String(
    (record.title || values.employeeTitle || values.username) ??
      values.account ??
      values.name ??
      record.id ??
      '',
  );
}
</script>
<template>
  <fieldset class="workflow-participants" :disabled="disabled">
    <legend>{{ language.t('platform.workflow-participant.rules', '参与人规则') }}</legend>
    <p v-if="error" role="alert">
      {{ language.t('platform.workflow-participant.invalid-rules', '参与人规则格式无法识别，请重新配置') }}
    </p>
    <div v-for="(rule, index) in rules" :key="index" class="participant-rule">
      <label class="workflow-field"
        >{{ language.t('platform.workflow-participant.source', '人员来源')
        }}<UiSelect
          v-model:value="rule.type"
          :options="kinds"
          :disabled="disabled"
          @update:value="
            rule.ids = [];
            rule.fieldName = undefined;
            rule.relation = rule.type === 'RELATIVE' ? 'SUPERVISOR' : undefined;
            rule.depth = rule.type === 'RELATIVE' ? 1 : undefined;
            save();
          "
      /></label>
      <label class="workflow-field" v-if="contexts[rule.type]"
        >{{ language.t('platform.workflow-participant.targets', '选择目标')
        }}<RecordMultiPicker
          :context="contexts[rule.type]!"
          :load-options="rule.type === 'USER' ? accountOptions : undefined"
          :value="rule.ids ?? []"
          :disabled="disabled"
          :title-of="title"
          @update:value="
            rule.ids = $event;
            save();
          "
      /></label>
      <label class="workflow-field" v-if="['FIELD', 'RELATIVE'].includes(rule.type)"
        ><RecordFieldLabel :required="rule.type === 'FIELD'">{{
          language.t('platform.workflow-participant.field', '人员字段')
        }}</RecordFieldLabel
        ><UiSelect
          v-model:value="rule.fieldName"
          :options="
            (fields ?? [])
              .filter((field) => field.referenceModule === 'iam.user')
              .map((field) => ({ value: field.name, label: field.label }))
          "
          :placeholder="
            language.t('platform.workflow-participant.relative-placeholder', '相对人员留空时以提交人为起点')
          "
          :disabled="disabled"
          @update:value="save"
      /></label>
      <label class="workflow-field" v-if="rule.type === 'RELATIVE'"
        >{{ language.t('platform.workflow-participant.relationship', '相对关系')
        }}<UiSelect
          :value="rule.relation"
          :options="[
            { value: 'SELF', label: language.t('platform.workflow-participant.self', '本人') },
            { value: 'SUPERVISOR', label: language.t('platform.workflow-participant.supervisor', '上级') },
            {
              value: 'DEPARTMENT',
              label: language.t('platform.workflow-participant.current-department', '所在部门成员'),
            },
            {
              value: 'DEPARTMENT_MANAGER',
              label: language.t('platform.workflow-participant.department-manager', '部门负责人'),
            },
            {
              value: 'ORGANIZATION',
              label: language.t('platform.workflow-participant.current-organization', '所在组织成员'),
            },
            {
              value: 'ORGANIZATION_MANAGER',
              label: language.t('platform.workflow-participant.organization-manager', '组织负责人'),
            },
          ]"
          :disabled="disabled"
          @update:value="changeRelation(rule, $event)"
      /></label>
      <label
        class="workflow-field"
        v-if="
          rule.type === 'RELATIVE' &&
          ['SUPERVISOR', 'DEPARTMENT', 'DEPARTMENT_MANAGER', 'ORGANIZATION', 'ORGANIZATION_MANAGER'].includes(
            rule.relation ?? '',
          )
        "
        >{{
          rule.relation === 'SUPERVISOR'
            ? language.t('platform.workflow-participant.supervisor-levels', '上级层数')
            : language.t('platform.workflow-participant.ancestor-levels', '部门/组织上溯层数')
        }}<UiInput
          :value="rule.depth ?? (rule.relation === 'SUPERVISOR' ? 1 : 0)"
          type="number"
          :disabled="disabled"
          @update:value="
            rule.depth = Number($event);
            save();
          "
        /><span v-if="rule.relation !== 'SUPERVISOR'">{{
          language.t(
            'platform.workflow-participant.ancestor-help',
            '0 为当前部门/组织，1 为上一级，依此类推。',
          )
        }}</span></label
      >
      <UiCheckbox
        v-if="['DEPT', 'ORG', 'RELATIVE'].includes(rule.type)"
        :checked="rule.headOnly"
        :disabled="disabled"
        @update:checked="
          rule.headOnly = $event;
          save();
        "
        >{{ language.t('platform.workflow-participant.managers-only', '只选负责人') }}</UiCheckbox
      >
      <UiButton
        :disabled="disabled"
        danger
        @click="
          rules.splice(index, 1);
          save();
        "
        >{{ language.t('platform.workflow-participant.remove-rule', '移除人员规则') }}</UiButton
      >
    </div>
    <UiButton
      :disabled="disabled"
      @click="
        rules.push({ type: 'USER', ids: [] });
        save();
      "
      >{{ language.t('platform.workflow-participant.add-rule', '添加人员规则') }}</UiButton
    >
    <p v-if="rules.some((rule) => rule.type === 'INITIATOR_SELF')">
      {{
        language.t('platform.workflow-participant.self-warning', '当前包含提交人本人，请确认允许本人办理。')
      }}
    </p>
    <p>
      {{
        language.t(
          'platform.workflow-participant.resolution-help',
          '多条规则合并去重；任务创建时解析有效账号并冻结分派。',
        )
      }}
    </p>
  </fieldset>
</template>
<style scoped>
.workflow-participants {
  display: grid;
  gap: 12px;
  grid-column: 1/-1;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  padding: 12px;
}
.workflow-participants > legend {
  width: auto;
  margin: 0;
  padding: 0 4px;
  border: 0;
  color: var(--muyun-content-section-heading-color, var(--muyun-text));
  font-size: var(--muyun-content-section-heading-font-size, var(--muyun-font-size-section, 14px));
  font-weight: var(--muyun-content-section-heading-font-weight, 700);
  line-height: var(--muyun-content-section-heading-line-height, var(--muyun-line-height-section, 20px));
}
.participant-rule {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
  gap: 12px;
}
.workflow-field {
  display: grid;
  gap: 6px;
}
p {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
</style>
