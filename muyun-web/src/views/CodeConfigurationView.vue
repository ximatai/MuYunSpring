<script setup lang="ts">
import { computed, inject, nextTick, ref, watch } from 'vue';
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
  RecordDetailDrawer,
  TenantScopeExplorer,
  ManagementWorkspace,
  ManagementExplorerColumn,
  type QueryListRecord,
  ManagementPanelHeader,
  RecordContentSectionHeading,
  presentPlatformError,
} from '@muyun/platform-components';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import {
  UiButton,
  UiCheckbox,
  UiEmpty,
  UiInput,
  UiSelect,
  UiSpin,
  UiError,
  UiHelp,
  confirmAction,
} from '@muyun/vue-ui-antdv';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import CodeRuleFormatEditor from './CodeRuleFormatEditor.vue';
import ModuleRuntimeActivationStatus from './ModuleRuntimeActivationStatus.vue';
import { moduleRuntimeActivationRefreshKey } from './moduleRuntimeActivation';
import {
  codeTrialContext,
  codeSourceTitle,
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
const tenantReloadKey = ref(0);
const editorOpen = ref(false);
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
const previewElement = ref<HTMLOutputElement>();
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
const bindingSummary = computed(() =>
  [
    t(`enum.${bindingRole.value}`, bindingRole.value === 'PRIMARY' ? '主编码' : '业务编码'),
    t(`enum.${bindingTrigger.value}`, bindingTrigger.value === 'SAVE' ? '首次保存' : '业务操作时生成'),
  ].join(' · '),
);
const rulePolicySummary = computed(() =>
  [
    draft.value?.effectiveFrom || draft.value?.effectiveTo ? t('limited-period', '限时生效') : '',
    draft.value?.linkedUpdate ? t('linked-short', '来源变化重编') : '',
    draft.value?.allowRecycle ? t('recycle-short', '旧号可复用') : '',
  ]
    .filter(Boolean)
    .join(' · '),
);
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
  if (!rule) editorOpen.value = false;
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
function tenantsLoaded(records: QueryListRecord[], initial: boolean, total?: number) {
  if (initial && total === 1 && records.length === 1 && !selectedTenant.value)
    void selectTenant(String(records[0]!.id));
}
async function mayCloseEditor() {
  if (busy.value) return false;
  return (
    !dirty.value ||
    (await confirmAction(
      {
        title: t('discard-rule', '放弃未保存的编号规则修改？'),
        content: t('discard-rule-help', '当前修改尚未保存，放弃后将恢复已保存的规则。'),
        okText: t('discard', '放弃修改'),
        danger: true,
      },
      language,
    ))
  );
}
function closeEditor() {
  cancel();
  editorOpen.value = false;
}
function selectRule(rule: CodeRule) {
  if (maySwitch()) {
    resetBinding();
    setDraft(rule);
    editorOpen.value = true;
    error.value = '';
    feedback.value = '';
  }
}
function createRule() {
  if (maySwitch() && target.value && field.value?.role) {
    setDraft(newCodeRule(target.value, field.value));
    editorOpen.value = true;
    error.value = '';
    feedback.value = '';
  }
}
function cancel() {
  resetBinding();
  setDraft(draft.value?.id ? rules.value.find((rule) => rule.id === draft.value?.id) : undefined);
  error.value = '';
}
function cancelBinding() {
  resetBinding();
  error.value = '';
  feedback.value = '';
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
    if (sequence === trialRequestSequence) {
      preview.value = result;
      await nextTick();
      if (sequence === trialRequestSequence)
        previewElement.value?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
    }
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
        <ModuleRuntimeActivationStatus
          v-if="!embedded && target?.source === 'DYNAMIC'"
          ref="localActivation"
          :module-alias="target.moduleAlias"
        />
        <UiButton :disabled="busy || dirty" @click="load">{{ t('reload', '刷新') }}</UiButton>
      </template>
    </ManagementPanelHeader>
    <div v-if="error && !editorOpen" class="code-config__error" role="alert">
      <UiError :title="t('request-failed', '操作未完成')" :message="error" />
      <UiButton v-if="loadFailed" :disabled="busy" @click="load">{{ t('retry', '重试加载') }}</UiButton>
    </div>
    <p v-if="feedback && !editorOpen" role="status">{{ feedback }}</p>
    <ManagementWorkspace
      class="code-config__layout"
      :explorer-count="currentUser?.system && tenantContext ? 1 : 0"
      :editing="busy || dirty"
      list-surface
    >
      <ManagementExplorerColumn v-if="currentUser?.system && tenantContext">
        <TenantScopeExplorer
          v-if="currentUser?.system && tenantContext"
          :context="tenantContext"
          :selected-id="selectedTenant || undefined"
          :reload-key="tenantReloadKey"
          :disabled="busy || dirty"
          @select="selectTenant(String($event.id))"
          @deselect="selectTenant()"
          @loaded="tenantsLoaded"
          @refresh="tenantReloadKey++"
        />
      </ManagementExplorerColumn>
      <div class="code-config__content">
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
        <div v-else-if="targets.length" class="code-config__overview">
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
              <label class="code-config__field"
                >{{ t('field', '编码字段')
                }}<UiSelect
                  :value="fieldName"
                  :disabled="busy"
                  :options="
                    target?.fields.map((item) => ({ value: item.fieldName, label: item.title })) ?? []
                  "
                  @update:value="selectField(String($event))"
              /></label>
              <template v-if="field">
                <div v-if="target?.source === 'STATIC'" class="code-config__binding-summary">
                  <span>{{ bindingSummary }}</span>
                  <UiHelp :label="t('binding-info', '字段接入说明')">
                    <p>{{ t('java-declaration', '字段角色和生成时机由业务模型预设。') }}</p>
                    <dl class="code-config__identity">
                      <dt>{{ t('module', '所属模块') }}</dt>
                      <dd>{{ target.moduleAlias }}</dd>
                      <dt>{{ t('entity', '业务对象') }}</dt>
                      <dd>{{ target.entityAlias }}</dd>
                      <dt>{{ t('field', '编码字段') }}</dt>
                      <dd>{{ field.fieldName }}</dd>
                    </dl>
                  </UiHelp>
                </div>
                <details
                  v-else
                  :key="`${targetKey}/${fieldName}`"
                  class="code-config__binding"
                  :open="!field.role"
                >
                  <summary>
                    {{ t('binding-settings', '字段接入配置') }} ·
                    {{ field.role ? bindingSummary : t('not-bound', '未接入编码')
                    }}<span v-if="bindingDirty"> · {{ t('unsaved-binding', '待保存') }}</span>
                  </summary>
                  <label class="code-config__field"
                    >{{ t('role', '编码角色')
                    }}<UiSelect
                      v-model:value="bindingRole"
                      :disabled="!canBind || busy"
                      :options="enumOptions({ NORMAL: '业务编码', PRIMARY: '主编码' })"
                  /></label>
                  <label class="code-config__field"
                    >{{ t('trigger', '生成时机')
                    }}<UiSelect
                      v-model:value="bindingTrigger"
                      :disabled="!canBind || busy"
                      :options="enumOptions({ SAVE: '首次保存', EXPLICIT: '业务操作时生成' })"
                  /></label>
                  <div v-if="target?.source === 'DYNAMIC'" class="code-config__actions">
                    <UiButton :disabled="!canBind || busy || (dirty && !bindingDirty)" @click="bind(false)">{{
                      field.role ? t('save-binding', '保存字段配置') : t('enable-binding', '接入编码')
                    }}</UiButton>
                    <UiButton v-if="bindingDirty" :disabled="busy" @click="cancelBinding">{{
                      t('cancel-binding', '取消接入修改')
                    }}</UiButton>
                    <UiButton v-if="field.role" :disabled="!canBind || busy || dirty" @click="bind(true)">{{
                      t('remove-binding', '移除编码')
                    }}</UiButton>
                  </div>
                  <dl v-if="target" class="code-config__identity">
                    <dt>{{ t('module', '所属模块') }}</dt>
                    <dd>{{ target.moduleAlias }}</dd>
                    <dt>{{ t('entity', '业务对象') }}</dt>
                    <dd>{{ target.entityAlias }}</dd>
                    <dt>{{ t('field', '编码字段') }}</dt>
                    <dd>{{ field.fieldName }}</dd>
                  </dl>
                </details>
                <template v-if="field.role">
                  <div class="code-config__rules-heading">
                    <h3>{{ t('rules', '编号规则') }}</h3>
                    <UiButton :disabled="!canSave || busy" @click="createRule">{{
                      t('new-rule', '新增规则')
                    }}</UiButton>
                  </div>
                  <p v-if="!hasRuleTenant">
                    {{ t('select-tenant', '选择规则所属租户后，可维护该租户的编号规则。') }}
                  </p>
                  <UiButton
                    v-for="rule in fieldRules"
                    :key="rule.id"
                    :disabled="busy"
                    :class="{ 'code-config__selected': editorOpen && draft?.id === rule.id }"
                    @click="selectRule(rule)"
                    >{{ rule.title }} ·
                    {{ rule.enabled ? t('enabled', '已启用') : t('disabled', '未启用') }}</UiButton
                  >
                </template>
              </template>
            </div>
          </RecordExplorerPanel>
        </div>
      </div>
    </ManagementWorkspace>
    <RecordDetailDrawer
      v-if="editorOpen"
      :open="editorOpen"
      width="wide"
      :before-close="mayCloseEditor"
      @close="closeEditor"
      class="code-config__editor"
      :title="t('rules', '编号规则')"
      :subtitle="draft?.title || field?.title"
    >
      <template v-if="draft" #header-actions>
        <UiButton v-if="draft.mode !== 'MANUAL'" :disabled="busy" @click="openTrial">{{
          t('trial', '草稿试算')
        }}</UiButton>
        <UiButton :disabled="busy" @click="cancel">{{ t('cancel', '取消修改') }}</UiButton>
        <UiButton type="primary" :disabled="!editable || bindingDirty" :loading="saving" @click="save">{{
          t('save-rule', '保存规则')
        }}</UiButton>
      </template>
      <UiError v-if="error" :title="t('request-failed', '操作未完成')" :message="error" />
      <p v-if="feedback" role="status">{{ feedback }}</p>
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
          <div class="code-config__checks" :class="{ 'code-config__status': draft.orgScopeType !== 'ORG' }">
            <UiCheckbox v-model:checked="draft.enabled" :disabled="!editable">{{
              t('enable-rule', '启用规则')
            }}</UiCheckbox>
          </div>
        </div>
        <details :key="draft.id ?? 'new'" class="code-config__policy">
          <summary>
            {{ t('rule-policy', '生效时间与编号策略')
            }}<span v-if="rulePolicySummary"> · {{ rulePolicySummary }}</span>
          </summary>
          <div class="code-config__policy-help">
            <UiHelp :label="t('rule-policy-help-title', '生效与编号策略说明')">
              <p>
                {{
                  t(
                    'rule-policy-help',
                    '生效时间按业务时区计算，留空表示不限制。来源变化重编适用于保存时生成的自动编号；软删除仍保留编号占用，只有最终清理或改号后才按规则回收旧号。',
                  )
                }}
              </p>
            </UiHelp>
          </div>
          <div class="code-config__grid">
            <label class="code-config__field code-config__date-start"
              >{{ t('effective-from-short', '开始生效时间')
              }}<UiInput v-model:value="draft.effectiveFrom" type="datetime-local" :disabled="!editable"
            /></label>
            <label class="code-config__field"
              >{{ t('effective-to-short', '结束生效时间')
              }}<UiInput v-model:value="draft.effectiveTo" type="datetime-local" :disabled="!editable"
            /></label>
            <div class="code-config__checks">
              <UiCheckbox
                v-if="draft.mode !== 'MANUAL' && field?.trigger !== 'EXPLICIT'"
                v-model:checked="draft.linkedUpdate"
                :disabled="!editable"
                >{{ t('linked', '来源字段变化时重新编号') }}</UiCheckbox
              >
              <UiCheckbox v-model:checked="draft.allowRecycle" :disabled="!editable">{{
                t('recycle-short-label', '允许复用旧号')
              }}</UiCheckbox>
            </div>
          </div>
        </details>
        <template v-if="draft.mode !== 'MANUAL'">
          <CodeRuleFormatEditor v-model:rule="draft" :sources="target?.sources ?? []" :editable="editable" />
          <details ref="trialElement" class="code-config__trial">
            <summary>
              {{ t('trial', '草稿试算')
              }}<span class="code-config__muted"> · {{ t('trial-short', '仅预览，不占号') }}</span>
            </summary>
            <div class="code-config__trial-help">
              <UiHelp :label="t('trial-help-title', '试算说明')">
                {{ t('trial-help', '试算不发号、不占用号码；正式编码以业务保存结果为准。') }}
              </UiHelp>
            </div>
            <label v-if="extraTrialOptions.length" class="code-config__field code-config__trial-extra">
              {{ t('trial-extra-short', '补充试算来源') }}
              <UiSelect
                v-model:value="extraTrialFields"
                mode="multiple"
                show-search
                :disabled="busy"
                :options="
                  extraTrialOptions.map((source) => ({
                    value: source.fieldName,
                    label: codeSourceTitle(source, language),
                  }))
                "
              />
            </label>
            <div class="code-config__grid">
              <label class="code-config__field" v-for="source in trialSources" :key="source.fieldName"
                >{{ codeSourceTitle(source, language)
                }}<UiInput v-model:value="trialValues[source.fieldName]" :disabled="busy"
              /></label>
              <label class="code-config__field"
                >{{ t('trial-sequence', '试算流水值')
                }}<UiInput v-model:value="trialSequence" type="number" :disabled="busy"
              /></label>
            </div>
            <UiButton :disabled="busy || !context.can('preview')" :loading="trialing" @click="trial">{{
              t('run-trial', '试算')
            }}</UiButton>
            <output v-if="preview" ref="previewElement" class="code-config__preview">
              <span>{{ t('trial-result', '试算结果') }}</span>
              <strong>{{ preview.value }}</strong>
            </output>
          </details>
        </template>
      </template>
    </RecordDetailDrawer>
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
.code-config__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.code-config__layout {
  min-height: 0;
  flex: 1;
}
.code-config__content,
.code-config__overview {
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.code-config__overview {
  flex: 1;
}
.code-config__directory {
  flex: 1;
}
.code-config__directory-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-content: start;
  gap: 16px;
  overflow: auto;
}
.code-config__directory-fields > :not(label) {
  grid-column: 1 / -1;
}
.code-config__directory-fields :deep(> .ant-btn) {
  text-align: start;
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
.code-config__status {
  grid-column: auto;
  align-self: end;
  min-height: 30px;
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
  position: relative;
  padding-top: 16px;
  margin-top: 16px;
  border-top: 1px solid var(--muyun-border-subtle);
}
.code-config__rules-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.code-config__rules-heading > *,
.code-config__binding-summary > * {
  flex: 0 0 auto;
}
.code-config__binding-summary,
.code-config__policy-help,
.code-config__trial-help {
  display: flex;
  align-items: center;
  gap: 8px;
}
.code-config__identity dt {
  font-weight: 600;
}
.code-config__identity dd {
  margin-inline-start: 0;
  margin-bottom: 8px;
}
.code-config__policy,
.code-config__binding {
  margin: 12px 0;
}
.code-config__policy {
  position: relative;
}
.code-config__policy-help,
.code-config__trial-help {
  position: absolute;
  inset-inline-end: 0;
  top: 0;
}
.code-config__trial-help {
  top: 16px;
}
.code-config__policy summary,
.code-config__trial summary {
  padding-inline-end: 30px;
}
.code-config__policy summary,
.code-config__binding summary {
  cursor: pointer;
}
.code-config__muted {
  color: var(--muyun-text-muted);
  font-weight: 400;
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
  .code-config__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
