<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue';
import {
  createModuleContext,
  useLanguageContext,
  useModuleContext,
  userFacingErrorMessage,
  normalizeError,
  OperationUsageError,
  resolveOperationErrorMessage,
  withHttpHeaders,
} from '@muyun/web-core';
import {
  RecordPicker,
  RecordExplorerPanel,
  RecordDetailPanel,
  ManagementPanelHeader,
  RecordContentSectionHeading,
  presentPlatformError,
} from '@muyun/platform-components';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import { UiButton, UiCheckbox, UiEmpty, UiInput, UiSelect, UiSpin, UiError } from '@muyun/vue-ui-antdv';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import CodeRuleFormatEditor from './CodeRuleFormatEditor.vue';
import ModuleRuntimeActivationStatus from './ModuleRuntimeActivationStatus.vue';
import { moduleRuntimeActivationRefreshKey } from './moduleRuntimeActivation';
import {
  codeTrialContext,
  codeTrialSources,
  createCodeConfigurationClient,
  newCodeRule,
  newCodeSegment,
  newCodeSequence,
  type CodeMode,
  type CodePreview,
  type CodeRole,
  type CodeRule,
  type CodeTarget,
  type CodeTrigger,
} from './codeConfiguration';

defineOptions({ name: 'CodeConfigurationView' });
const props = defineProps<{ moduleAlias?: string; moduleTitle?: string; embedded?: boolean }>();
const language = useLanguageContext();
const t = (key: string, fallback: string) => language.t(`platform.code.config.${key}`, fallback);
function messageOf(cause: unknown) {
  return cause instanceof OperationUsageError
    ? resolveOperationErrorMessage(cause, language)
    : userFacingErrorMessage(normalizeError(cause), language);
}
const context = useModuleContext({ moduleAlias: 'platform.code_rule' });
const currentUser = useCurrentUserContext();
const selectedTenant = ref(currentUser?.value?.tenantId ?? '');
const hasRuleTenant = computed(() => !!selectedTenant.value);
const scopedHttp = computed(() =>
  selectedTenant.value && currentUser?.value?.system
    ? withHttpHeaders(context.http, { 'X-MuYun-Tenant-Id': selectedTenant.value })
    : context.http,
);
const client = computed(() => createCodeConfigurationClient(scopedHttp.value, language));
const tenantContext = computed(() =>
  currentUser?.value?.system
    ? createModuleContext<Record<string, unknown>>({
        http: context.http,
        language,
        moduleAlias: 'iam.tenant',
        runtimeAccess: 'REFERENCE',
      })
    : undefined,
);
const organizationContext = computed(() =>
  hasRuleTenant.value
    ? createModuleContext<Record<string, unknown>>({
        http: scopedHttp.value,
        language,
        moduleAlias: 'iam.organization',
        runtimeAccess: 'REFERENCE',
      })
    : undefined,
);
const canSave = computed(() => hasRuleTenant.value && context.can('saveTree') === true);
const canBind = computed(() => context.can('configureBinding') === true);
const targets = ref<CodeTarget[]>([]);
const targetKey = ref('');
const fieldName = ref('');
const rules = ref<CodeRule[]>([]);
const draft = ref<CodeRule>();
const original = ref('');
const loading = ref(false);
const loadFailed = ref(false);
const saving = ref(false);
const trialing = ref(false);
const extraTrialFields = ref<string[]>([]);
const error = ref('');
const feedback = ref('');
const preview = ref<CodePreview>();
const trialValues = ref<Record<string, string>>({});
const trialSequence = ref('1');
const bindingRole = ref<CodeRole>('NORMAL');
const bindingTrigger = ref<CodeTrigger>('SAVE');
const trialElement = ref<HTMLDetailsElement>();
function openTrial() {
  if (!trialElement.value) return;
  trialElement.value.open = true;
  trialElement.value.scrollIntoView({ block: 'start', behavior: 'smooth' });
}
const localActivation = ref<InstanceType<typeof ModuleRuntimeActivationStatus>>();
const refreshActivation = inject(moduleRuntimeActivationRefreshKey, undefined);
const targetId = (target: CodeTarget) => `${target.moduleAlias}/${target.entityAlias}`;
const target = computed(() => targets.value.find((item) => targetId(item) === targetKey.value));
const field = computed(() => target.value?.fields.find((item) => item.fieldName === fieldName.value));
const fieldRules = computed(() => rules.value.filter((rule) => rule.fieldName === fieldName.value));
const bindingDirty = computed(
  () =>
    target.value?.source === 'DYNAMIC' &&
    field.value?.role != null &&
    (bindingRole.value !== field.value.role || bindingTrigger.value !== (field.value.trigger ?? 'SAVE')),
);
const dirty = computed(
  () => bindingDirty.value || (draft.value != null && JSON.stringify(draft.value) !== original.value),
);

const busy = computed(() => loading.value || saving.value || trialing.value);
useWorkspaceViewUnsavedState(
  () => t('title', '业务编码'),
  () => dirty.value,
  () => busy.value,
);
const editable = computed(() => canSave.value && !busy.value);
const directTrialSources = computed(() => (target.value ? codeTrialSources(target.value, draft.value) : []));
const extraTrialOptions = computed(
  () =>
    target.value?.sources.filter(
      (source) => !directTrialSources.value.some((direct) => direct.fieldName === source.fieldName),
    ) ?? [],
);
const trialSources = computed(() => [
  ...directTrialSources.value,
  ...extraTrialOptions.value.filter((source) => extraTrialFields.value.includes(source.fieldName)),
]);
let trialRequestSequence = 0;
watch(
  () => JSON.stringify([draft.value, trialValues.value, trialSequence.value, extraTrialFields.value]),
  () => {
    preview.value = undefined;
    ++trialRequestSequence;
  },
  { flush: 'sync' },
);
const enumOptions = (entries: Record<string, string>) =>
  Object.entries(entries).map(([value, label]) => ({ value, label: t(`enum.${value}`, label) }));
let requestSequence = 0;
function setDraft(rule?: CodeRule) {
  draft.value = rule == null ? undefined : (JSON.parse(JSON.stringify(rule)) as CodeRule);
  original.value = draft.value == null ? '' : JSON.stringify(draft.value);
  preview.value = undefined;
}
function resetBinding() {
  bindingRole.value = field.value?.role ?? 'NORMAL';
  bindingTrigger.value = field.value?.trigger ?? 'SAVE';
}
function maySwitch() {
  if (dirty.value) {
    error.value = t('unsaved', '请先保存或取消当前修改。');
    return false;
  }
  return !busy.value;
}
function selectField(name: string) {
  if (!maySwitch()) return;
  fieldName.value = name;
  trialValues.value = {};
  extraTrialFields.value = [];
  resetBinding();
  setDraft(fieldRules.value[0]);
  error.value = '';
  feedback.value = '';
}
async function selectTarget(key: string) {
  if (!maySwitch()) return;
  const sequence = ++requestSequence;
  targetKey.value = key;
  trialValues.value = {};
  extraTrialFields.value = [];
  fieldName.value = '';
  rules.value = [];
  setDraft();
  error.value = '';
  loading.value = true;
  try {
    const selected = target.value;
    if (!selected) return;
    const loaded = hasRuleTenant.value ? await client.value.rules(selected) : [];
    if (sequence !== requestSequence) return;
    rules.value = loaded;
    fieldName.value =
      selected.fields.find((item) => item.role != null)?.fieldName ?? selected.fields[0]?.fieldName ?? '';
    resetBinding();
    setDraft(fieldRules.value[0]);
  } catch (cause) {
    if (sequence === requestSequence) error.value = messageOf(cause);
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}
async function load() {
  const sequence = ++requestSequence;
  loadFailed.value = false;
  if (!hasRuleTenant.value) {
    loading.value = false;
    error.value = '';
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const loaded = await client.value.targets(props.moduleAlias);
    if (sequence !== requestSequence) return;
    targets.value = loaded;
    loading.value = false;
    await selectTarget(loaded[0] ? targetId(loaded[0]) : '');
  } catch (cause) {
    if (sequence === requestSequence) {
      error.value = messageOf(cause);
      loadFailed.value = true;
    }
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}
watch(
  () => props.moduleAlias,
  () => {
    setDraft();
    fieldName.value = '';
    targetKey.value = '';
    targets.value = [];
    rules.value = [];
    void load();
  },
  { immediate: true },
);
watch(
  () => [currentUser?.value?.userId, currentUser?.value?.tenantId, currentUser?.value?.system],
  () => {
    selectedTenant.value = currentUser?.value?.tenantId ?? '';
    setDraft();
    targets.value = [];
    rules.value = [];
    fieldName.value = '';
    targetKey.value = '';
    void load();
  },
);
async function selectTenant(value?: string) {
  if (!maySwitch()) return;
  selectedTenant.value = value ?? '';
  targets.value = [];
  rules.value = [];
  fieldName.value = '';
  targetKey.value = '';
  setDraft();
  await load();
}
function selectRule(rule: CodeRule) {
  if (maySwitch()) {
    resetBinding();
    setDraft(rule);
    error.value = '';
    feedback.value = '';
  }
}
function createRule() {
  if (maySwitch() && target.value && field.value?.role) {
    setDraft(newCodeRule(target.value, field.value));
    error.value = '';
    feedback.value = '';
  }
}
function cancel() {
  resetBinding();
  setDraft(draft.value?.id ? rules.value.find((rule) => rule.id === draft.value?.id) : undefined);
  error.value = '';
}
async function bind(remove = false) {
  if (!target.value || !field.value || saving.value) return;
  saving.value = true;
  error.value = '';
  feedback.value = '';
  try {
    const updated = await client.value.bind(
      target.value,
      field.value,
      remove ? null : bindingRole.value,
      bindingTrigger.value,
    );
    targets.value = targets.value.map((item) => (targetId(item) === targetId(updated) ? updated : item));
    resetBinding();
    if (remove) setDraft();
    if (refreshActivation) await refreshActivation(updated.moduleAlias);
    else await localActivation.value?.refresh();
    feedback.value = t('binding-saved', '字段配置已保存，请查看运行态生效状态。');
  } catch (cause) {
    error.value = messageOf(cause);
    if (!(cause instanceof OperationUsageError))
      presentPlatformError(cause, { language, source: 'business-code', phase: 'action' });
  } finally {
    saving.value = false;
  }
}
async function save() {
  if (!draft.value || saving.value) return;
  saving.value = true;
  error.value = '';
  feedback.value = '';
  try {
    const saved = await client.value.save(draft.value);
    rules.value = [...rules.value.filter((rule) => rule.id !== saved.id), saved];
    setDraft(saved);
    feedback.value = saved.enabled
      ? t('active', '规则已启用，后续保存使用新的规则。')
      : t('saved-disabled', '规则已保存，当前未启用。');
  } catch (cause) {
    error.value = messageOf(cause);
    if (!(cause instanceof OperationUsageError))
      presentPlatformError(cause, { language, source: 'business-code', phase: 'action' });
  } finally {
    saving.value = false;
  }
}
function modeChanged(mode: CodeMode) {
  if (!draft.value) return;
  draft.value.mode = mode;
  if (mode === 'MANUAL') {
    draft.value.segments = [];
    draft.value.sequencePolicy = null;
    draft.value.linkedUpdate = false;
  } else if (draft.value.segments.length === 0) {
    draft.value.segments = [newCodeSegment(), newCodeSegment('SEQUENCE')];
    draft.value.sequencePolicy = newCodeSequence();
  }
}
async function trial() {
  if (!draft.value || !target.value || busy.value) return;
  const sequence = ++trialRequestSequence;
  trialing.value = true;
  error.value = '';
  preview.value = undefined;
  try {
    const result = await client.value.preview(
      draft.value,
      codeTrialContext(trialSources.value, trialValues.value, language),
      Number(trialSequence.value),
    );
    if (sequence === trialRequestSequence) preview.value = result;
  } catch (cause) {
    if (sequence === trialRequestSequence) error.value = messageOf(cause);
  } finally {
    trialing.value = false;
  }
}
</script>

<template>
  <section class="code-config">
    <ManagementPanelHeader class="code-config__header" :title="t('title', '业务编码')">
      <template #actions>
        <label class="code-config__field code-config__tenant" v-if="currentUser?.system && tenantContext"
          >{{ t('tenant', '规则所属租户')
          }}<RecordPicker
            :value="selectedTenant || undefined"
            :context="tenantContext"
            :disabled="busy"
            @update:value="selectTenant"
        /></label>
        <ModuleRuntimeActivationStatus
          v-if="!embedded && target?.source === 'DYNAMIC'"
          ref="localActivation"
          :module-alias="target.moduleAlias"
        />
        <UiButton :disabled="busy || dirty" @click="load">{{ t('reload', '刷新') }}</UiButton>
      </template>
    </ManagementPanelHeader>
    <div v-if="error" class="code-config__error" role="alert">
      <UiError :title="t('request-failed', '操作未完成')" :message="error" />
      <UiButton v-if="loadFailed" :disabled="busy" @click="load">{{ t('retry', '重试加载') }}</UiButton>
    </div>
    <p v-if="feedback" role="status">{{ feedback }}</p>
    <UiEmpty
      v-if="!hasRuleTenant"
      :description="t('select-tenant', '选择规则所属租户后，可维护该租户的编号规则。')"
    />
    <UiSpin v-else-if="loading" :spinning="true"
      ><p>{{ t('loading', '正在读取编码配置…') }}</p></UiSpin
    >
    <UiEmpty
      v-else-if="!error && targets.length === 0"
      :description="t('no-target', '当前模块尚未声明可配置的业务编码字段。')"
    />
    <div v-else-if="targets.length" class="code-config__layout">
      <RecordExplorerPanel
        class="code-config__directory"
        :title="t('binding-title', '编码对象')"
        :refreshable="false"
        :searchable="false"
      >
        <div class="code-config__directory-fields">
          <label class="code-config__field"
            >{{ t('entity', '业务对象')
            }}<UiSelect
              :value="targetKey"
              :disabled="busy"
              :options="
                targets.map((item) => ({
                  value: targetId(item),
                  label: item.title,
                }))
              "
              @update:value="selectTarget(String($event))"
          /></label>
          <p v-if="target" class="code-config__identity">
            {{ target.moduleAlias }} · {{ target.entityAlias }}
          </p>
          <label class="code-config__field"
            >{{ t('field', '编码字段')
            }}<UiSelect
              :value="fieldName"
              :disabled="busy"
              :options="target?.fields.map((item) => ({ value: item.fieldName, label: item.title })) ?? []"
              @update:value="selectField(String($event))"
          /></label>
          <template v-if="field">
            <p v-if="target?.source === 'STATIC'">
              {{ t('java-declaration', '字段角色和生成时机由业务模型预设。') }}
            </p>
            <label class="code-config__field"
              >{{ t('role', '编码角色')
              }}<UiSelect
                v-model:value="bindingRole"
                :disabled="target?.source === 'STATIC' || !canBind || busy"
                :options="enumOptions({ NORMAL: '业务编码', PRIMARY: '主编码' })"
            /></label>
            <label class="code-config__field"
              >{{ t('trigger', '生成时机')
              }}<UiSelect
                v-model:value="bindingTrigger"
                :disabled="target?.source === 'STATIC' || !canBind || busy"
                :options="enumOptions({ SAVE: '首次保存', EXPLICIT: '领域动作显式生成' })"
            /></label>
            <div v-if="target?.source === 'DYNAMIC'" class="code-config__actions">
              <UiButton :disabled="!canBind || busy || (dirty && !bindingDirty)" @click="bind(false)">{{
                field.role ? t('save-binding', '保存字段配置') : t('enable-binding', '接入编码')
              }}</UiButton>
              <UiButton v-if="field.role" :disabled="!canBind || busy || dirty" @click="bind(true)">{{
                t('remove-binding', '移除编码')
              }}</UiButton>
            </div>
            <template v-if="field.role">
              <h3>{{ t('rules', '编号规则') }}</h3>
              <p v-if="!hasRuleTenant">
                {{ t('select-tenant', '选择规则所属租户后，可维护该租户的编号规则。') }}
              </p>
              <UiButton
                v-for="rule in fieldRules"
                :key="rule.id"
                :disabled="busy"
                :class="{ 'code-config__selected': draft?.id === rule.id }"
                @click="selectRule(rule)"
                >{{ rule.title }} ·
                {{ rule.enabled ? t('enabled', '已启用') : t('disabled', '未启用') }}</UiButton
              >
              <UiButton :disabled="!canSave || busy" @click="createRule">{{
                t('new-rule', '新增规则')
              }}</UiButton>
            </template>
          </template>
        </div>
      </RecordExplorerPanel>
      <RecordDetailPanel
        class="code-config__editor"
        :title="t('rules', '编号规则')"
        :subtitle="draft?.title || field?.title"
      >
        <template v-if="draft" #actions>
          <UiButton v-if="draft.mode !== 'MANUAL'" :disabled="busy" @click="openTrial">{{
            t('trial', '草稿试算')
          }}</UiButton>
          <UiButton :disabled="busy" @click="cancel">{{ t('cancel', '取消修改') }}</UiButton>
          <UiButton type="primary" :disabled="!editable || bindingDirty" :loading="saving" @click="save">{{
            t('save-rule', '保存规则')
          }}</UiButton>
        </template>
        <UiEmpty
          v-if="!draft"
          :description="
            field?.role
              ? t('choose-rule', '选择或新增编号规则。')
              : t('declare-first', '先接入编码，再配置编号规则。')
          "
        />
        <template v-else>
          <RecordContentSectionHeading :title="t('basic-settings', '基础设置')" />
          <div class="code-config__grid">
            <label class="code-config__field"
              >{{ t('rule-title', '规则名称') }}<UiInput v-model:value="draft.title" :disabled="!editable"
            /></label>
            <label class="code-config__field"
              >{{ t('mode', '编号方式')
              }}<UiSelect
                :value="draft.mode"
                :disabled="!editable"
                :options="
                  enumOptions({
                    AUTO: '自动编号',
                    MANUAL: '人工编号',
                    AUTO_WITH_MANUAL_EDIT: '自动编号，允许人工调整',
                  })
                "
                @update:value="modeChanged($event as CodeMode)"
            /></label>
            <label class="code-config__field"
              >{{ t('scope', '适用范围')
              }}<UiSelect
                v-model:value="draft.orgScopeType"
                :disabled="!editable"
                :options="enumOptions({ GLOBAL: '当前租户全部组织', ORG: '指定组织及其下级' })"
            /></label>
            <label class="code-config__field" v-if="draft.orgScopeType === 'ORG'"
              >{{ t('organization', '适用组织')
              }}<RecordPicker
                v-if="organizationContext"
                :value="draft.orgScopeId ?? undefined"
                :context="organizationContext"
                mode="tree"
                :disabled="!editable"
                @update:value="draft!.orgScopeId = $event"
              /><span v-else>{{ t('tenant-required', '请使用租户身份选择组织。') }}</span></label
            >
            <label class="code-config__field code-config__date-start"
              >{{ t('effective-from', '开始生效时间（业务时区）')
              }}<UiInput v-model:value="draft.effectiveFrom" type="datetime-local" :disabled="!editable"
            /></label>
            <label class="code-config__field"
              >{{ t('effective-to', '结束生效时间（业务时区）')
              }}<UiInput v-model:value="draft.effectiveTo" type="datetime-local" :disabled="!editable"
            /></label>
            <div class="code-config__checks">
              <UiCheckbox v-model:checked="draft.enabled" :disabled="!editable">{{
                t('enable-rule', '启用规则')
              }}</UiCheckbox>
              <UiCheckbox
                v-if="draft.mode !== 'MANUAL' && field?.trigger !== 'EXPLICIT'"
                v-model:checked="draft.linkedUpdate"
                :disabled="!editable"
                >{{ t('linked', '来源字段变化时重新编号') }}</UiCheckbox
              >
              <UiCheckbox v-model:checked="draft.allowRecycle" :disabled="!editable">{{
                t('recycle', '最终清理或改号后允许复用旧号码')
              }}</UiCheckbox>
            </div>
          </div>
          <template v-if="draft.mode !== 'MANUAL'">
            <CodeRuleFormatEditor
              v-model:rule="draft"
              :sources="target?.sources ?? []"
              :editable="editable"
            />
            <details ref="trialElement" class="code-config__trial">
              <summary>{{ t('trial', '草稿试算') }}</summary>
              <p>{{ t('trial-help', '试算不发号、不占用号码；正式编码以业务保存结果为准。') }}</p>
              <label v-if="extraTrialOptions.length" class="code-config__field code-config__trial-extra">
                {{ t('trial-extra', '补充试算来源（公式等按需选择）') }}
                <UiSelect
                  v-model:value="extraTrialFields"
                  mode="multiple"
                  show-search
                  :disabled="busy"
                  :options="
                    extraTrialOptions.map((source) => ({ value: source.fieldName, label: source.title }))
                  "
                />
              </label>
              <div class="code-config__grid">
                <label class="code-config__field" v-for="source in trialSources" :key="source.fieldName"
                  >{{ source.title }}<UiInput v-model:value="trialValues[source.fieldName]" :disabled="busy"
                /></label>
                <label class="code-config__field"
                  >{{ t('trial-sequence', '试算流水值')
                  }}<UiInput v-model:value="trialSequence" type="number" :disabled="busy"
                /></label>
              </div>
              <UiButton :disabled="busy || !context.can('preview')" :loading="trialing" @click="trial">{{
                t('run-trial', '试算')
              }}</UiButton>
              <output v-if="preview" class="code-config__preview">
                <span>{{ t('trial-result', '试算结果') }}</span>
                <strong>{{ preview.value }}</strong>
              </output>
            </details>
          </template>
        </template>
      </RecordDetailPanel>
    </div>
  </section>
</template>

<style scoped>
.code-config {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  height: 100%;
}
.code-config__header {
  flex: 0 0 auto;
}
.code-config__tenant {
  width: 220px;
}
.code-config__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.code-config__layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 12px;
  min-height: 0;
  flex: 1;
}
.code-config__directory-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: auto;
}
.code-config .code-config__directory-fields > * {
  flex: 0 0 auto;
}
.code-config__date-start {
  grid-column: 1;
}
.code-config__identity {
  color: var(--muyun-text-muted);
  overflow-wrap: anywhere;
}
.code-config__trial-extra {
  margin-top: 12px;
}
.code-config__preview strong {
  display: block;
  margin-top: 6px;
  font-size: 16px;
}
.code-config__editor {
  min-width: 0;
}
.code-config__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 12px;
  margin: 12px 0 20px;
}
.code-config__field {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 6px;
  font-size: 13px;
  line-height: 20px;
  min-width: 0;
}
.code-config__checks {
  grid-column: 1/-1;
  display: flex;
  flex-wrap: wrap;
  gap: 12px 24px;
  align-items: center;
}
.code-config h3 {
  font-size: 14px;
  margin: 8px 0;
}
.code-config p {
  margin: 0;
  font-size: 13px;
}
.code-config__trial {
  padding-top: 16px;
  margin-top: 16px;
  border-top: 1px solid var(--muyun-border-subtle);
}
.code-config__trial summary {
  cursor: pointer;
  margin-bottom: 10px;
  font-weight: 600;
}
.code-config__preview {
  display: block;
  padding: 12px;
  margin-top: 10px;
  font-family: monospace;
  overflow-wrap: anywhere;
  background: var(--muyun-hover-subtle);
}
.code-config__error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.code-config__error :deep(.ant-alert) {
  width: 100%;
}
.code-config__selected {
  outline: 1px solid var(--muyun-primary);
}
@media (max-width: 760px) {
  .code-config__layout {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(0, 210px) minmax(0, 1fr);
  }
  .code-config__grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .code-config__tenant {
    width: 160px;
  }
}
</style>
