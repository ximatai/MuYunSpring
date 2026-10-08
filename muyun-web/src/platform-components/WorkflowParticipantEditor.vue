<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { createModuleContext, type HttpClient } from '@muyun/web-core';
import { UiButton, UiCheckbox, UiInput, UiSelect } from '@muyun/vue-ui-antdv';
import { workflowAccountOptions } from './workflowAccountOptions';
import RecordFieldLabel from './RecordFieldLabel.vue';
import RecordMultiPicker from './RecordMultiPicker.vue';
import type { RecordPickerRecord } from './recordPickerConstraints';
interface Rule {
  type: string;
  ids?: string[];
  fieldName?: string;
  relation?: string;
  depth?: number;
  headOnly?: boolean;
}
const props = defineProps<{
  value?: string;
  http: HttpClient;
  disabled?: boolean;
  fields?: readonly { name: string; label: string; referenceModule?: string }[];
}>();
const emit = defineEmits<{ 'update:value': [value: string] }>();
const rules = ref<Rule[]>([]),
  error = ref('');
const kinds = [
  { value: 'USER', label: '指定账号' },
  { value: 'ROLE', label: '角色成员' },
  { value: 'DEPT', label: '部门成员' },
  { value: 'ORG', label: '组织成员' },
  { value: 'RELATIVE', label: '相对人员' },
  { value: 'FIELD', label: '业务人员字段' },
  { value: 'INITIATOR_SELF', label: '提交人本人' },
];
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
      createModuleContext<RecordPickerRecord>({ http: props.http, moduleAlias, runtimeAccess: 'VIEW' }),
    ]),
  ),
);
watch(
  () => props.value,
  (value) => {
    error.value = '';
    try {
      if (!value) {
        rules.value = [];
        return;
      }
      if (value.startsWith('user:')) {
        rules.value = [{ type: 'USER', ids: [value.slice(5)] }];
        return;
      }
      const parsed = JSON.parse(value);
      rules.value = parsed.rules ?? [
        { type: parsed.type ?? 'USER', ids: parsed.ids ?? parsed.userIds ?? [] },
      ];
    } catch {
      error.value = '参与人规则格式无法识别，请重新配置';
      rules.value = [];
    }
  },
  { immediate: true },
);
function save() {
  if (!props.disabled) emit('update:value', JSON.stringify({ rules: rules.value }));
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
    <legend>参与人规则</legend>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-for="(rule, index) in rules" :key="index" class="participant-rule">
      <label
        >人员来源<UiSelect
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
      <label v-if="contexts[rule.type]"
        >选择目标<RecordMultiPicker
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
      <label v-if="['FIELD', 'RELATIVE'].includes(rule.type)"
        ><RecordFieldLabel :required="rule.type === 'FIELD'">人员字段</RecordFieldLabel
        ><UiSelect
          v-model:value="rule.fieldName"
          :options="
            (fields ?? [])
              .filter((field) => field.referenceModule === 'iam.user')
              .map((field) => ({ value: field.name, label: field.label }))
          "
          placeholder="相对人员留空时以提交人为起点"
          :disabled="disabled"
          @update:value="save"
      /></label>
      <label v-if="rule.type === 'RELATIVE'"
        >相对关系<UiSelect
          v-model:value="rule.relation"
          :options="[
            { value: 'SELF', label: '本人' },
            { value: 'SUPERVISOR', label: '上级' },
            { value: 'DEPARTMENT', label: '所在部门成员' },
            { value: 'DEPARTMENT_MANAGER', label: '部门负责人' },
            { value: 'ORGANIZATION', label: '所在组织成员' },
            { value: 'ORGANIZATION_MANAGER', label: '组织负责人' },
          ]"
          :disabled="disabled"
          @update:value="save"
      /></label>
      <label v-if="rule.type === 'RELATIVE' && rule.relation === 'SUPERVISOR'"
        >上级层数<UiInput
          :value="rule.depth ?? 1"
          type="number"
          :disabled="disabled"
          @update:value="
            rule.depth = Number($event);
            save();
          "
      /></label>
      <UiCheckbox
        v-if="['DEPT', 'ORG', 'RELATIVE'].includes(rule.type)"
        :checked="rule.headOnly"
        :disabled="disabled"
        @update:checked="
          rule.headOnly = $event;
          save();
        "
        >只选负责人</UiCheckbox
      >
      <UiButton
        :disabled="disabled"
        danger
        @click="
          rules.splice(index, 1);
          save();
        "
        >移除人员规则</UiButton
      >
    </div>
    <UiButton
      :disabled="disabled"
      @click="
        rules.push({ type: 'USER', ids: [] });
        save();
      "
      >添加人员规则</UiButton
    >
    <p v-if="rules.some((rule) => rule.type === 'INITIATOR_SELF')">
      当前包含提交人本人，请确认允许本人办理。
    </p>
    <p>多条规则合并去重；任务创建时解析有效账号并冻结分派。</p>
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
.participant-rule {
  display: grid;
  grid-template-columns: repeat(2, minmax(160px, 1fr));
  gap: 12px;
}
label {
  display: grid;
  gap: 6px;
}
p {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
</style>
