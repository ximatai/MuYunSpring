<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import FormulaExpressionEditor from './FormulaExpressionEditor.vue';
import RecordFieldLabel from './RecordFieldLabel.vue';
import { UiButton, UiInput, UiTextArea, UiSelect, UiCheckbox } from '@muyun/vue-ui-antdv';
defineOptions({ name: 'WorkflowBusinessTaskEditor' });
const props = defineProps<{
  value?: string;
  moduleAlias: string;
  disabled?: boolean;
  fields?: readonly { name: string; label: string; valueType?: string }[];
  actions?: readonly { value: string; label: string }[];
  associations?: readonly { value: string; label: string }[];
  catalog?: {
    queries: { id: string; title: string }[];
    generations: { id: string; title: string; targetModuleAlias: string }[];
  };
}>();
const emit = defineEmits<{ 'update:value': [value: string] }>();
interface Check {
  checkKey: string;
  title?: string;
  enabled: boolean;
  checkKind: string;
  expression?: string;
  failureMessage?: string;
  checkConfigText?: string;
}
interface Guide {
  guideKey: string;
  title?: string;
  enabled: boolean;
  guideKind: string;
  targetModuleAlias?: string;
  targetActionCode?: string;
  guideConfigText?: string;
}
interface Spec {
  definition: Record<string, unknown>;
  checks: Check[];
  guides: Guide[];
}
const spec = ref<Spec>({ definition: { manualConfirm: false, enabled: true }, checks: [], guides: [] });
const error = ref('');
const fieldOptions = computed(() =>
  (props.fields ?? []).map((field) => ({ value: field.name, label: field.label })),
);
const generationTargets = computed(() =>
  [...new Set(props.catalog?.generations.map((rule) => rule.targetModuleAlias))].map((value) => ({
    value,
    label: value,
  })),
);
const checkOptions = [
  { value: 'FORMULA', label: '业务字段公式' },
  { value: 'QUERY_EXISTS', label: '查询模板计数' },
  { value: 'RELATED_QUERY_EXISTS', label: '关联视图计数' },
  { value: 'GENERATED_QUERY_EXISTS', label: '已生成业务计数' },
];
const guideOptions = [
  { value: 'OPEN_FORM', label: '填写当前业务并完成任务' },
  { value: 'EXECUTE_ACTION', label: '执行业务动作并完成任务' },
  { value: 'OPEN_LIST', label: '查看业务列表' },
  { value: 'FOCUS_FIELD', label: '查看指定字段' },
  { value: 'READ_INSTRUCTION', label: '办理说明' },
];
watch(
  () => props.value,
  (value) => {
    try {
      spec.value = value
        ? JSON.parse(value).task
        : {
            definition: { moduleAlias: props.moduleAlias, enabled: true, manualConfirm: false },
            checks: [],
            guides: [],
          };
      if (!spec.value?.definition || !Array.isArray(spec.value.checks) || !Array.isArray(spec.value.guides))
        throw new Error('任务配置格式无效');
      spec.value.definition.moduleAlias = props.moduleAlias;
      spec.value.checks.forEach((item) => (item.checkKind = item.checkKind.toUpperCase()));
      spec.value.guides.forEach((item) => (item.guideKind = item.guideKind.toUpperCase()));
      error.value = '';
    } catch {
      error.value = '任务配置格式无效，请修复配置后再编辑';
    }
  },
  { immediate: true },
);
function save() {
  if (!props.disabled && !error.value) emit('update:value', JSON.stringify({ task: spec.value }));
}
function addCheck() {
  spec.value.checks.push({
    checkKey: `check_${Date.now().toString(36)}`,
    title: '业务完成条件',
    enabled: true,
    checkKind: 'FORMULA',
    expression: '',
    failureMessage: '业务数据尚未满足完成条件',
  });
  save();
}
function addGuide() {
  spec.value.guides.push({
    guideKey: `guide_${Date.now().toString(36)}`,
    title: '填写业务数据并完成任务',
    enabled: true,
    guideKind: 'OPEN_FORM',
    targetModuleAlias: props.moduleAlias,
    guideConfigText: '{"editableFields":[]}',
  });
  save();
}
function payloadText(guide: Guide): string {
  const payload = config(guide).payload;
  return typeof payload === 'string' ? payload : JSON.stringify(payload ?? {});
}
function setPayload(guide: Guide, text: string) {
  try {
    const payload: unknown = JSON.parse(text);
    if (payload === null || Array.isArray(payload) || typeof payload !== 'object')
      throw new Error('object required');
    setConfig(guide, 'payload', payload);
  } catch {
    // Preserve the draft input and let publication validation reject invalid parameters.
    setConfig(guide, 'payload', text);
  }
}
function config(item: Check | Guide): Record<string, unknown> {
  try {
    return JSON.parse('checkKind' in item ? (item.checkConfigText ?? '{}') : (item.guideConfigText ?? '{}'));
  } catch {
    return {};
  }
}
function setConfig(item: Check | Guide, key: string, value: unknown) {
  const next = { ...config(item), [key]: value };
  if ('checkKind' in item) item.checkConfigText = JSON.stringify(next);
  else item.guideConfigText = JSON.stringify(next);
  save();
}
function changeKind(check: Check, kind: string) {
  check.checkKind = kind;
  if (kind !== 'FORMULA') check.expression = undefined;
  check.checkConfigText =
    kind === 'FORMULA'
      ? undefined
      : JSON.stringify({
          checkType:
            kind === 'QUERY_EXISTS'
              ? 'QUERY_TEMPLATE'
              : kind === 'RELATED_QUERY_EXISTS'
                ? 'ASSOCIATION_VIEW'
                : 'GENERATED_RELATION',
          expectedCount: 1,
        });
  save();
}
</script>
<template>
  <div class="task-editor">
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-else>
      <UiCheckbox
        :checked="spec.definition.manualConfirm === true"
        :disabled="disabled"
        @update:checked="
          spec.definition.manualConfirm = $event;
          save();
        "
        >明确人工确认完成</UiCheckbox
      >
      <p>所有检查项必须通过，才允许完成任务。已发布的检查与办理指引随流程版本冻结。</p>
      <fieldset v-for="(check, index) in spec.checks" :key="check.checkKey">
        <legend>完成检查 {{ index + 1 }}</legend>
        <label
          >检查名称<UiInput v-model:value="check.title" :disabled="disabled" @update:value="save"
        /></label>
        <label
          >判定方式<UiSelect
            :value="check.checkKind"
            :options="checkOptions"
            :disabled="disabled"
            @update:value="changeKind(check, String($event))"
        /></label>
        <label v-if="check.checkKind === 'FORMULA'"
          ><RecordFieldLabel required>业务字段公式</RecordFieldLabel
          ><FormulaExpressionEditor
            :value="check.expression ?? ''"
            :fields="fields ?? []"
            :disabled="disabled"
            placeholder="例如 {delivered} == true"
            @update:value="
              check.expression = $event;
              save();
            "
        /></label>
        <template v-else>
          <template v-if="check.checkKind === 'QUERY_EXISTS'">
            <p v-if="!catalog?.queries.length">本模块暂无已发布查询模板，请先发布业务查询模板。</p>
            <label
              >已发布查询模板<UiSelect
                :options="catalog?.queries.map((item) => ({ value: item.id, label: item.title })) ?? []"
                show-search
                :value="String(config(check).queryTemplateId ?? '')"
                :disabled="disabled"
                @update:value="setConfig(check, 'queryTemplateId', $event)"
            /></label>
            <label
              >当前业务 ID 的查询参数名<UiInput
                :value="String(config(check).externalRecordIdKey ?? '')"
                :disabled="disabled"
                @update:value="setConfig(check, 'externalRecordIdKey', $event)"
            /></label>
          </template>
          <p v-if="check.checkKind === 'RELATED_QUERY_EXISTS' && !associations?.length">
            本模块暂无可查询的关联视图，请先配置关联视图。
          </p>
          <label v-if="check.checkKind === 'RELATED_QUERY_EXISTS'"
            >关联视图<UiSelect
              :options="[...(associations ?? [])]"
              show-search
              :value="String(config(check).associationViewCode ?? '')"
              :disabled="disabled"
              @update:value="setConfig(check, 'associationViewCode', $event)"
          /></label>
          <template v-if="check.checkKind === 'GENERATED_QUERY_EXISTS'">
            <p v-if="!catalog?.generations.length">本模块暂无启用的生成规则，请先配置业务生成规则。</p>
            <label
              >生成目标模块<UiSelect
                :options="generationTargets"
                show-search
                :value="String(config(check).targetModuleAlias ?? '')"
                :disabled="disabled"
                @update:value="setConfig(check, 'targetModuleAlias', $event)"
            /></label>
            <label
              >生成规则（可选）<UiSelect
                :options="
                  catalog?.generations
                    .filter((item) => item.targetModuleAlias === config(check).targetModuleAlias)
                    .map((item) => ({ value: item.id, label: item.title })) ?? []
                "
                show-search
                :value="String(config(check).generationRuleId ?? '')"
                :disabled="disabled"
                @update:value="setConfig(check, 'generationRuleId', $event)"
            /></label>
          </template>
          <label
            ><RecordFieldLabel required>最少业务记录数</RecordFieldLabel
            ><UiInput
              type="number"
              :value="String(config(check).expectedCount ?? 1)"
              :disabled="disabled"
              @update:value="setConfig(check, 'expectedCount', Number($event))"
          /></label>
        </template>
        <label
          >未完成提示<UiInput v-model:value="check.failureMessage" :disabled="disabled" @update:value="save"
        /></label>
        <UiButton
          danger
          :disabled="disabled"
          @click="
            spec.checks.splice(index, 1);
            save();
          "
          >删除检查</UiButton
        >
      </fieldset>
      <UiButton :disabled="disabled" @click="addCheck">添加完成检查</UiButton>
      <fieldset v-for="(guide, index) in spec.guides" :key="guide.guideKey">
        <legend>办理指引 {{ index + 1 }}</legend>
        <label
          >指引名称<UiInput v-model:value="guide.title" :disabled="disabled" @update:value="save"
        /></label>
        <label
          >办理方式<UiSelect
            :value="guide.guideKind"
            :options="guideOptions"
            :disabled="disabled"
            @update:value="
              guide.guideKind = String($event);
              guide.targetModuleAlias = moduleAlias;
              save();
            "
        /></label>
        <label v-if="['OPEN_FORM', 'EXECUTE_ACTION'].includes(guide.guideKind)"
          ><RecordFieldLabel required>可编辑字段</RecordFieldLabel
          ><UiSelect
            :value="(config(guide).editableFields as string[] | undefined) ?? []"
            :options="fieldOptions"
            mode="multiple"
            show-search
            :disabled="disabled"
            placeholder="选择允许在任务中修改的业务字段"
            @update:value="setConfig(guide, 'editableFields', $event)"
        /></label>
        <label v-if="guide.guideKind === 'OPEN_LIST'"
          >目标模块<UiInput v-model:value="guide.targetModuleAlias" :disabled="disabled" @update:value="save"
        /></label>
        <template v-if="guide.guideKind === 'EXECUTE_ACTION'">
          <label
            ><RecordFieldLabel required>业务动作</RecordFieldLabel
            ><UiSelect
              :options="[...(actions ?? [])]"
              show-search
              v-model:value="guide.targetActionCode"
              :disabled="disabled"
              @update:value="save"
          /></label>
          <label
            >动作参数（JSON 对象）<UiTextArea
              :value="payloadText(guide)"
              :disabled="disabled"
              @update:value="setPayload(guide, $event)"
          /></label>
          <p v-if="typeof config(guide).payload === 'string'" role="alert">
            动作参数必须是有效 JSON 对象，修正后才能发布。
          </p>
        </template>
        <label v-if="guide.guideKind === 'FOCUS_FIELD'"
          ><RecordFieldLabel required>业务字段</RecordFieldLabel
          ><UiSelect
            :options="fieldOptions"
            show-search
            :value="String(config(guide).field ?? '')"
            :disabled="disabled"
            @update:value="setConfig(guide, 'field', $event)"
        /></label>
        <label v-if="guide.guideKind === 'READ_INSTRUCTION'"
          >办理说明<UiTextArea
            :value="String(config(guide).instruction ?? '')"
            :disabled="disabled"
            @update:value="setConfig(guide, 'instruction', $event)"
        /></label>
        <UiButton
          danger
          :disabled="disabled"
          @click="
            spec.guides.splice(index, 1);
            save();
          "
          >删除指引</UiButton
        >
      </fieldset>
      <UiButton :disabled="disabled" @click="addGuide">添加办理指引</UiButton>
    </template>
  </div>
</template>
<style scoped>
.task-editor,
fieldset,
label {
  display: grid;
  gap: 8px;
}
fieldset {
  padding: 12px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
}
p {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
</style>
