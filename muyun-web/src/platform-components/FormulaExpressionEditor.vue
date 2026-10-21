<script setup lang="ts">
import { useLanguageContext, normalizeError, userFacingErrorMessage } from '@muyun/web-core';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { UiTokenInput, UiButton, UiSelect } from '@muyun/vue-ui-antdv';
import type { UiDragSource } from '@muyun/vue-ui-antdv';
import {
  formulaExpressionTokens,
  type FormulaExpressionField,
  type FormulaExpressionFunction,
} from './formulaExpressionTokens';

import type { FormulaExpressionTools } from './formulaExpressionTools';

const language = useLanguageContext();

defineOptions({ name: 'FormulaExpressionEditor' });

const props = withDefaults(
  defineProps<{
    tools?: FormulaExpressionTools;
    booleanResult?: boolean;
    value: string;
    fields: readonly FormulaExpressionField[];
    functions?: readonly FormulaExpressionFunction[];
    disabled?: boolean;
    placeholder?: string;
    acceptDrop?: (source: UiDragSource) => boolean;
  }>(),
  {
    tools: undefined,
    booleanResult: false,
    functions: () => [],
    disabled: false,
    placeholder: undefined,
    acceptDrop: undefined,
  },
);

const emit = defineEmits<{
  'update:value': [value: string];
  selection: [selection: { start: number; end: number }];
  drop: [event: { source: UiDragSource; selection: { start: number; end: number }; nativeEvent?: Event }];
}>();

const loadedFunctions = ref<readonly FormulaExpressionFunction[]>([]);
const allFunctions = computed(() => [...props.functions, ...loadedFunctions.value]);
const tokens = computed(() => formulaExpressionTokens(props.value, props.fields, allFunctions.value));
const toolsOpen = ref(false);
const loading = ref(false);
const checking = ref(false);
const feedback = ref('');
const failed = ref(false);
const selectedFunction = ref<string>();
const functionChoice = ref<string>();
const selectedField = ref<string>();
let revision = 0;
let catalogRevision = 0;
onBeforeUnmount(() => {
  revision++;
  catalogRevision++;
});
const rememberedSelection = ref({ start: 0, end: 0 });
watch(
  () => [props.value, props.tools, props.disabled],
  () => {
    revision++;
    checking.value = false;
    feedback.value = '';
  },
);
watch(
  () => [props.tools, language.languageTag.value],
  () => {
    revision++;
    catalogRevision++;
    checking.value = false;
    loading.value = false;
    feedback.value = '';
    loadedFunctions.value = [];
    selectedFunction.value = undefined;
    toolsOpen.value = false;
  },
);
async function toggleTools() {
  if (props.disabled) return;
  toolsOpen.value = !toolsOpen.value;
  if (!toolsOpen.value || !props.tools || loadedFunctions.value.length || loading.value) return;
  const tools = props.tools;
  const currentCatalogRevision = ++catalogRevision;
  loading.value = true;
  try {
    const functions = await tools.loadFunctions();
    if (currentCatalogRevision === catalogRevision) loadedFunctions.value = functions;
  } catch (cause) {
    if (currentCatalogRevision === catalogRevision) {
      failed.value = true;
      feedback.value = userFacingErrorMessage(normalizeError(cause), language);
    }
  } finally {
    if (currentCatalogRevision === catalogRevision) loading.value = false;
  }
}
function rememberSelection(value: { start: number; end: number }) {
  rememberedSelection.value = value;
  emit('selection', value);
}
async function insert(source: string, caretOffset = source.length) {
  if (props.disabled) return;
  const start = Math.min(rememberedSelection.value.start, props.value.length);
  const end = Math.min(rememberedSelection.value.end, props.value.length);
  emit('update:value', props.value.slice(0, start) + source + props.value.slice(end));
  await nextTick();
  if (props.disabled) return;
  focusSelection(start + caretOffset);
  rememberedSelection.value = { start: start + caretOffset, end: start + caretOffset };
  selectedField.value = undefined;
  functionChoice.value = undefined;
}
function insertField(value: unknown) {
  const field = props.fields.find((item) => item.name === value);
  if (field && field.available !== false) {
    selectedField.value = field.name;
    void insert(`{${field.name}}`);
  }
}
function insertFunction(value: unknown) {
  const fn = allFunctions.value.find((item) => item.name === value);
  if (!fn) return;
  selectedFunction.value = fn.name;
  functionChoice.value = fn.name;
  void insert(`${fn.name}()`, fn.name.length + 1);
}
const functionHelp = computed(() => allFunctions.value.find((item) => item.name === selectedFunction.value));
async function validate() {
  if (!props.tools || props.disabled || checking.value) return;
  const currentRevision = ++revision;
  const expression = props.value;
  checking.value = true;
  feedback.value = '';
  try {
    await props.tools.validate(expression);
    if (revision !== currentRevision) return;
    failed.value = false;
    feedback.value = language.t('platform.formula-editor.syntax-valid', '语法校验通过');
  } catch (cause) {
    if (revision !== currentRevision) return;
    failed.value = true;
    feedback.value = userFacingErrorMessage(normalizeError(cause), language);
  } finally {
    if (revision === currentRevision) checking.value = false;
  }
}
const tokenInput = ref<InstanceType<typeof UiTokenInput>>();

function selection() {
  return tokenInput.value?.selection() ?? { start: 0, end: 0 };
}

function focusSelection(start: number, end = start) {
  tokenInput.value?.focusSelection(start, end);
}

defineExpose({ selection, focusSelection });
</script>

<template>
  <div class="formula-expression-editor">
    <UiTokenInput
      ref="tokenInput"
      :aria-label="language.t('platform.screen.FormulaExpressionEditor.formula', '公式')"
      :value="value"
      :tokens="tokens"
      :placeholder="placeholder"
      :disabled="disabled"
      :accept-drop="acceptDrop"
      @update:value="emit('update:value', $event)"
      @selection="rememberSelection"
      @drop="emit('drop', $event)"
    />
    <template v-if="tools">
      <div class="formula-expression-editor__toolbar">
        <UiButton
          size="small"
          type="text"
          :disabled="disabled"
          :aria-expanded="toolsOpen"
          :aria-label="language.t('platform.formula-editor.assistance', '公式辅助')"
          @click="toggleTools"
          >{{ language.t('platform.formula-editor.assistance', '公式辅助') }}</UiButton
        >
        <UiButton size="small" type="text" :disabled="disabled" :loading="checking" @click="validate">{{
          language.t('platform.formula-editor.validate', '校验公式')
        }}</UiButton>
      </div>
      <div v-if="toolsOpen" class="formula-expression-editor__tools">
        <UiSelect
          :value="selectedField"
          :disabled="disabled"
          :aria-label="language.t('platform.formula-editor.insert-field', '插入字段')"
          :placeholder="language.t('platform.formula-editor.insert-field', '插入字段')"
          :options="
            fields.map((field) => ({
              value: field.name,
              label: field.label,
              disabled: field.available === false,
            }))
          "
          @update:value="insertField"
        />
        <UiSelect
          :value="functionChoice"
          :disabled="disabled || loading"
          :aria-label="language.t('platform.formula-editor.insert-function', '插入函数')"
          :placeholder="language.t('platform.formula-editor.insert-function', '插入函数')"
          :options="allFunctions.map((fn) => ({ value: fn.name, label: fn.title || fn.name }))"
          @update:value="insertFunction"
        />
        <p v-if="functionHelp" class="formula-expression-editor__help">
          <span>{{ functionHelp.description }}</span
          ><code>{{ functionHelp.signature }}</code
          ><code>{{ functionHelp.example }}</code>
        </p>
      </div>
      <p v-if="feedback" :role="failed ? 'alert' : 'status'" class="formula-expression-editor__help">
        {{ feedback }}
      </p>
    </template>
    <p v-if="booleanResult" class="formula-expression-editor__help">
      {{
        language.t(
          'platform.formula-editor.boolean-help',
          '条件须返回真或假，例如 {amount} > 100；实际匹配由服务端按业务记录判断。',
        )
      }}
    </p>
  </div>
</template>
<style scoped>
.formula-expression-editor {
  min-width: 0;
}
.formula-expression-editor__toolbar {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
.formula-expression-editor__tools {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr));
  gap: 8px;
  margin-top: 8px;
}
.formula-expression-editor__help {
  display: grid;
  gap: 4px;
  grid-column: 1 / -1;
  margin: 6px 0 0;
  color: var(--muyun-text-secondary);
  font-size: var(--muyun-font-size-helper, 12px);
  overflow-wrap: anywhere;
}
</style>
