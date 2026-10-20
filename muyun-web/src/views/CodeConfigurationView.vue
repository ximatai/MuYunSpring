<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import {
  createModuleContext,
  createNavigatorReferenceCrudClient,
  recordPresentationLabel,
  useLanguageContext,
  useModuleContext,
  userFacingErrorMessage,
  normalizeError,
  OperationUsageError,
  resolveOperationErrorMessage,
  withHttpHeaders,
  presentationLabel,
} from '@muyun/web-core';
import {
  formatPlatformDateTime,
  RecordPicker,
  RecordRelationTable,
  RecordExplorerPanel,
  RecordListExplorer,
  RecordDetailDrawer,
  TenantScopeExplorer,
  ManagementWorkspace,
  ManagementExplorerColumn,
  type QueryListRecord,
  RecordContentSectionHeading,
  RecordDetailExtensionSection,
  RecordRelationActionButton,
  presentPlatformError,
} from '@muyun/platform-components';
import {
  useWorkspaceViewUnsavedState,
  useWorkbenchNavigation,
  createWorkspaceViewDescriptor,
} from '@muyun/platform-workbench';
import {
  UiButton,
  UiCheckbox,
  UiEmpty,
  UiInput,
  UiSelect,
  UiSwitch,
  UiSpin,
  UiError,
  UiHelp,
  confirmAction,
} from '@muyun/vue-ui-antdv';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import CodeRuleFormatEditor from './CodeRuleFormatEditor.vue';
import ModuleRuntimeActivationStatus from './ModuleRuntimeActivationStatus.vue';
import { moduleRuntimeActivationRefreshKey } from './moduleRuntimeActivation';
import { moduleGovernanceWorkspaceView } from './moduleGovernanceWorkspaceView';
import {
  readCodeConfigurationAvailability,
  type CodeConfigurationAvailability,
  codeRuleSupportsRenumbering,
  codeTrialContext,
  codeTrialSourceKind,
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
const navigation = useWorkbenchNavigation();
const unavailableReason = ref<CodeConfigurationAvailability['reason']>();
const unavailableDescription = computed(() => {
  switch (unavailableReason.value) {
    case 'no-model':
      return t('reason.no-model', '尚未关联业务数据模型，请先配置模型，再设置编号规则。');
    case 'no-fields':
      return t('reason.no-fields', '当前数据模型没有可用于编号的字段，请先检查字段配置。');
    case 'not-declared':
      return t('reason.not-declared', '该静态模块尚未声明可配置的编号字段，需由开发者在业务模型中声明。');
    default:
      return props.moduleAlias
        ? t('reason.unavailable', '当前模块没有可配置的编号字段，请返回模块管理检查配置。')
        : t('no-modules', '没有可配置业务编码的模块。');
  }
});
function configureModel() {
  const alias = props.moduleAlias ?? selectedModule.value;
  if (!alias) return;
  navigation?.openPage(
    createWorkspaceViewDescriptor(moduleGovernanceWorkspaceView, {
      moduleAlias: alias,
      moduleTitle: props.moduleTitle ?? target.value?.moduleTitle,
      governanceTab: 'metadata',
    }),
  );
}
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
const selectedModule = ref(props.moduleAlias ?? '');
const moduleKeyword = ref('');
const moduleRecords = computed(() => {
  const records = new Map<string, { id: string; title: string; code: string }>();
  for (const item of targets.value) {
    if (!item.fields.length) continue;
    if (records.has(item.moduleAlias)) continue;
    records.set(item.moduleAlias, {
      id: item.moduleAlias,
      title: presentationLabel(item.moduleTitle || item.moduleAlias, item.moduleTitleText, language),
      code: item.moduleAlias,
    });
  }
  return [...records.values()];
});
const showModuleNavigator = computed(() => hasRuleTenant.value && !props.moduleAlias);
const moduleTargets = computed(() =>
  targets.value.filter((item) => item.moduleAlias === selectedModule.value),
);
const targetKey = ref('');
const fieldName = ref('');
const rules = ref<CodeRule[]>([]);
const draft = ref<CodeRule>();
const original = ref('');
const usageDraft = ref<CodeRule>();
const usageOriginal = ref('');
const loading = ref(false);
const loadFailed = ref(false);
const saving = ref(false);
const trialing = ref(false);
const trialScheduled = ref(false);
const extraTrialFields = ref<string[]>([]);
const error = ref('');
const trialError = ref('');
const trialErrorElement = ref<HTMLElement>();
const editorFeedback = ref<HTMLElement>();
const feedback = ref('');
const preview = ref<CodePreview>();
const previewElement = ref<HTMLOutputElement>();
const trialValues = ref<Record<string, string>>({});
const trialSequence = ref('');
const bindingRole = ref<CodeRole>('NORMAL');
const bindingTrigger = ref<CodeTrigger>('SAVE');
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
const canRenumber = computed(() => !!draft.value && codeRuleSupportsRenumbering(draft.value));
const rulePolicySummary = computed(() =>
  [
    canRenumber.value && draft.value?.linkedUpdate ? t('linked-short', '来源变化时换号') : '',
    draft.value?.allowRecycle ? t('recycle-short', '旧号可复用') : '',
  ]
    .filter(Boolean)
    .join(' · '),
);
const fieldRules = computed(() => rules.value.filter((rule) => rule.fieldName === fieldName.value));
const ruleRows = computed(() => fieldRules.value.map((rule) => ({ ...rule })));
const organizationTitles = ref<Record<string, string>>({});
let organizationRequest = 0;
watch(
  () =>
    [
      scopedHttp.value,
      fieldRules.value.map((rule) => (rule.orgScopeType === 'ORG' ? rule.orgScopeId : '')).join(','),
    ] as const,
  async ([http]) => {
    const request = ++organizationRequest;
    organizationTitles.value = {};
    const ids = [
      ...new Set(
        fieldRules.value
          .filter((rule) => rule.orgScopeType === 'ORG')
          .map((rule) => rule.orgScopeId)
          .filter((id): id is string => !!id),
      ),
    ];
    if (!hasRuleTenant.value || !ids.length) return;
    try {
      const result = await createNavigatorReferenceCrudClient<Record<string, unknown>>(http, {
        moduleAlias: 'iam.organization',
      }).translate(ids);
      if (request === organizationRequest)
        organizationTitles.value = Object.fromEntries(
          result.records.map((record) => [
            String(record.id),
            recordPresentationLabel(record, 'title', language) ?? t('specific-org', '指定机构'),
          ]),
        );
    } catch {
      // Reference access can be unavailable; scope editing remains available through the authorized picker.
    }
  },
);
function ruleScopeTitle(rule: CodeRule) {
  return rule.orgScopeType === 'GLOBAL'
    ? t('all-orgs', '全部机构')
    : (organizationTitles.value[rule.orgScopeId ?? ''] ?? t('specific-org', '指定机构'));
}

const ruleColumns = computed(() => [
  { fieldName: 'title', title: t('rule-title', '规则名称'), width: 150 },
  { fieldName: 'scope', title: t('scope', '适用范围'), width: 152 },
  { fieldName: 'organization', title: t('organization', '适用组织'), width: 120 },
  { fieldName: 'effectiveFrom', title: t('effective-from-short', '开始生效时间'), width: 180 },
  { fieldName: 'effectiveTo', title: t('effective-to-short', '结束生效时间'), width: 180 },
  { fieldName: 'enabled', title: t('enable-rule', '启用规则'), width: 64 },
  { fieldName: 'actions', title: t('actions', '操作'), width: 112 },
]);
const usageDirty = computed(
  () => usageDraft.value != null && JSON.stringify(usageDraft.value) !== usageOriginal.value,
);
const bindingDirty = computed(
  () =>
    target.value?.source === 'DYNAMIC' &&
    field.value?.role != null &&
    (bindingRole.value !== field.value.role || bindingTrigger.value !== (field.value.trigger ?? 'SAVE')),
);
const dirty = computed(
  () =>
    usageDirty.value ||
    bindingDirty.value ||
    (draft.value != null && JSON.stringify(draft.value) !== original.value),
);

const busy = computed(() => loading.value || saving.value);
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
let trialTimer: ReturnType<typeof setTimeout> | undefined;
let trialController: AbortController | undefined;
const trialReady = computed(
  () =>
    editorOpen.value &&
    draft.value?.mode !== 'MANUAL' &&
    !!draft.value &&
    !!target.value &&
    !busy.value &&
    context.can('preview'),
);
const trialSignature = computed(() => {
  if (!trialReady.value || !draft.value) return '';
  const rule = draft.value;
  return JSON.stringify([
    rule.id,
    rule.moduleAlias,
    rule.entityAlias,
    rule.fieldName,
    rule.fieldRole,
    rule.fieldTrigger,
    rule.mode,
    rule.linkedUpdate,
    rule.orgScopeId,
    rule.segments,
    rule.sequencePolicy,
    trialSources.value.map((source) => [source.fieldName, source.type, trialValues.value[source.fieldName]]),
    trialSequence.value,
  ]);
});
function cancelTrial() {
  if (trialTimer != null) clearTimeout(trialTimer);
  trialTimer = undefined;
  trialScheduled.value = false;
  trialController?.abort();
  trialController = undefined;
  trialing.value = false;
  ++trialRequestSequence;
}
watch(
  trialSignature,
  (signature) => {
    cancelTrial();
    preview.value = undefined;
    trialError.value = '';
    if (!signature) return;
    trialScheduled.value = true;
    trialTimer = setTimeout(() => {
      void trial();
    }, 500);
  },
  { flush: 'sync' },
);
onBeforeUnmount(cancelTrial);
const enumOptions = (entries: Record<string, string>) =>
  Object.entries(entries).map(([value, label]) => ({ value, label: t(`enum.${value}`, label) }));
let requestSequence = 0;
function setDraft(rule?: CodeRule) {
  usageDraft.value = undefined;
  usageOriginal.value = '';
  trialSequence.value = '';
  if (!rule) editorOpen.value = false;
  draft.value = rule == null ? undefined : (JSON.parse(JSON.stringify(rule)) as CodeRule);
  if (!field.value?.role) {
    bindingRole.value = rule?.fieldRole ?? 'NORMAL';
    bindingTrigger.value = rule?.fieldTrigger ?? 'SAVE';
  }
  original.value = draft.value == null ? '' : JSON.stringify(draft.value);
}
watch(
  [bindingRole, bindingTrigger],
  () => {
    if (editorOpen.value && !field.value?.role && draft.value) {
      draft.value.fieldRole = bindingRole.value;
      draft.value.fieldTrigger = bindingTrigger.value;
    }
  },
  { flush: 'sync' },
);
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
async function selectModule(alias: string) {
  if (!maySwitch() || !moduleRecords.value.some((item) => item.id === alias)) return;
  selectedModule.value = alias;
  moduleKeyword.value = '';
  feedback.value = '';
  const initial = moduleTargets.value.find((item) => item.fields.length) ?? moduleTargets.value[0];
  await selectTarget(initial ? targetId(initial) : '');
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
  unavailableReason.value = undefined;
  error.value = '';
  try {
    const loaded = await client.value.targets(props.moduleAlias);
    if (sequence !== requestSequence) return;
    targets.value = loaded;
    if (props.moduleAlias && !loaded.some((item) => item.fields.length)) {
      try {
        const availability = await readCodeConfigurationAvailability(scopedHttp.value, props.moduleAlias);
        if (sequence !== requestSequence) return;
        unavailableReason.value = availability.reason;
      } catch {
        // The code query remains usable without permission to read the module directory.
      }
      if (sequence !== requestSequence) return;
    }
    loading.value = false;
    if (!moduleRecords.value.some((item) => item.id === selectedModule.value)) selectedModule.value = '';
    const initial = moduleTargets.value.find((item) => item.fields.length) ?? moduleTargets.value[0];
    await selectTarget(initial ? targetId(initial) : '');
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
    selectedModule.value = props.moduleAlias ?? '';
    moduleKeyword.value = '';
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
    selectedModule.value = props.moduleAlias ?? '';
    moduleKeyword.value = '';
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
  selectedModule.value = props.moduleAlias ?? '';
  moduleKeyword.value = '';
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
  if (maySwitch() && target.value && field.value) {
    const rule = newCodeRule(target.value, field.value);
    rule.fieldRole = bindingRole.value;
    rule.fieldTrigger = bindingTrigger.value;
    setDraft(rule);
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
async function bind() {
  if (!target.value || !field.value || saving.value) return;
  saving.value = true;
  error.value = '';
  feedback.value = '';
  try {
    const updated = await client.value.bind(
      target.value,
      field.value,
      bindingRole.value,
      bindingTrigger.value,
    );
    targets.value = targets.value.map((item) => (targetId(item) === targetId(updated) ? updated : item));
    resetBinding();
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
async function persistRule(rule: CodeRule) {
  const wasUnconfigured = target.value?.source === 'DYNAMIC' && !field.value?.role;
  const saved = await client.value.save(rule);
  rules.value = [...rules.value.filter((item) => item.id !== saved.id), saved];
  if (wasUnconfigured && saved.enabled) {
    try {
      targets.value = await client.value.targets(props.moduleAlias);
      resetBinding();
      if (refreshActivation) await refreshActivation(saved.moduleAlias);
      else await localActivation.value?.refresh();
    } catch {
      error.value = t('saved-refresh-failed', '规则已保存，但状态刷新失败，请刷新页面确认。');
    }
  }
  return saved;
}
async function save() {
  if (!draft.value || saving.value) return;
  saving.value = true;
  error.value = '';
  feedback.value = '';
  try {
    const saved = await persistRule(draft.value);
    setDraft(saved);
    feedback.value = saved.enabled
      ? t('active', '规则已启用，后续业务按适用范围匹配规则。')
      : t('saved-disabled', '规则已保存，可在规则列表中启用。');
  } catch (cause) {
    error.value = messageOf(cause);
    if (!(cause instanceof OperationUsageError))
      presentPlatformError(cause, { language, source: 'business-code', phase: 'action' });
  } finally {
    saving.value = false;
    await nextTick();
    editorFeedback.value?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
  }
}
async function toggleRule(rule: CodeRule, enabled: boolean) {
  if (usageDraft.value || !maySwitch() || !canSave.value || (enabled && !field.value?.role && !canBind.value))
    return;
  saving.value = true;
  error.value = '';
  try {
    const saved = await persistRule({
      ...rule,
      ...(!field.value?.role && enabled
        ? { fieldRole: bindingRole.value, fieldTrigger: bindingTrigger.value }
        : {}),
      enabled,
    });
    if (draft.value?.id === saved.id) setDraft(saved);
    feedback.value = saved.enabled
      ? t('active', '规则已启用，后续业务按适用范围匹配规则。')
      : t('rule-stopped', '规则已停用，已有编码保持不变。');
  } catch (cause) {
    error.value = messageOf(cause);
  } finally {
    saving.value = false;
  }
}
function editUsage(rule: CodeRule) {
  if (!maySwitch()) return;
  setDraft();
  usageDraft.value = JSON.parse(JSON.stringify(rule)) as CodeRule;
  usageOriginal.value = JSON.stringify(usageDraft.value);
  error.value = '';
  feedback.value = '';
}
function cancelUsage() {
  usageDraft.value = undefined;
  usageOriginal.value = '';
  error.value = '';
}
async function saveUsage() {
  if (!usageDraft.value || !canSave.value || busy.value) return;
  saving.value = true;
  error.value = '';
  feedback.value = '';
  try {
    const saved = await persistRule(usageDraft.value);
    setDraft(saved);
    feedback.value = t('usage-saved', '适用配置已保存。');
  } catch (cause) {
    error.value = messageOf(cause);
  } finally {
    saving.value = false;
  }
}
function modeChanged(mode: CodeMode) {
  if (!draft.value) return;
  draft.value.mode = mode;
  if (mode !== 'MANUAL' && draft.value.segments.length === 0) {
    draft.value.segments = [newCodeSegment(), newCodeSegment('SEQUENCE')];
    draft.value.sequencePolicy = newCodeSequence();
  }
}
async function trial(revealResult = false) {
  if (!draft.value || !target.value || !trialReady.value) return;
  cancelTrial();
  const sequence = ++trialRequestSequence;
  const controller = new AbortController();
  trialController = controller;
  trialing.value = true;
  trialError.value = '';
  preview.value = undefined;
  try {
    const result = await client.value.preview(
      draft.value,
      codeTrialContext(trialSources.value, trialValues.value, language),
      trialSequence.value.trim() === ''
        ? (draft.value.sequencePolicy?.startValue ?? 1)
        : Number(trialSequence.value),
      controller.signal,
    );
    if (sequence === trialRequestSequence) {
      preview.value = result;
      if (revealResult) {
        await nextTick();
        if (sequence === trialRequestSequence)
          previewElement.value?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
      }
    }
  } catch (cause) {
    if (sequence === trialRequestSequence && !controller.signal.aborted) {
      trialError.value = messageOf(cause);
      if (revealResult) {
        await nextTick();
        if (sequence === trialRequestSequence) {
          trialErrorElement.value?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
          trialErrorElement.value?.focus({ preventScroll: true });
        }
      }
    }
  } finally {
    if (sequence === trialRequestSequence) {
      trialing.value = false;
      trialController = undefined;
    }
  }
}
</script>

<template>
  <section class="code-config">
    <div v-if="error && !editorOpen" class="code-config__error" role="alert">
      <UiError :title="t('request-failed', '操作未完成')" :message="error" />
      <UiButton v-if="loadFailed" :disabled="busy" @click="load">{{ t('retry', '重试加载') }}</UiButton>
    </div>
    <p v-if="feedback && !editorOpen" role="status">{{ feedback }}</p>
    <ManagementWorkspace
      class="code-config__layout"
      :explorer-count="(currentUser?.system && tenantContext ? 1 : 0) + (showModuleNavigator ? 1 : 0)"
      :editing="busy || dirty || !!usageDraft"
    >
      <ManagementExplorerColumn
        v-if="currentUser?.system && tenantContext"
        collapsible
        :title="t('tenant-short', '租户')"
        :has-selection="!!selectedTenant"
      >
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
      <ManagementExplorerColumn
        v-if="showModuleNavigator"
        collapsible
        :title="t('module', '所属模块')"
        :has-selection="!!selectedModule"
      >
        <RecordExplorerPanel
          :title="t('module', '所属模块')"
          :refresh-disabled="busy || dirty"
          :search-keyword="moduleKeyword"
          @refresh="load"
          @update:search-keyword="moduleKeyword = $event"
        >
          <RecordListExplorer
            :records="moduleRecords"
            :selected-id="selectedModule || undefined"
            :keyword="moduleKeyword"
            :empty-description="t('no-modules', '没有可配置业务编码的模块。')"
            @select="selectModule(String($event.id))"
          />
        </RecordExplorerPanel>
      </ManagementExplorerColumn>
      <div class="code-config__content">
        <UiEmpty
          v-if="!hasRuleTenant"
          :description="t('select-tenant', '选择规则所属租户后，可维护该租户的编号规则。')"
        />
        <UiSpin v-else-if="loading" :spinning="true"
          ><p>{{ t('loading', '正在读取编码配置…') }}</p></UiSpin
        >
        <div v-else-if="!error && moduleRecords.length === 0" class="code-config__unavailable">
          <UiEmpty :description="unavailableDescription" />
          <div v-if="navigation" class="code-config__actions">
            <UiButton
              v-if="unavailableReason === 'no-model' || unavailableReason === 'no-fields'"
              type="primary"
              @click="configureModel"
              >{{ t('configure-model', '配置模型') }}</UiButton
            >
            <UiButton @click="navigation.openRoute('/platform/module')">{{
              t('back-to-modules', '返回模块管理')
            }}</UiButton>
          </div>
        </div>
        <UiEmpty
          v-else-if="!selectedModule && targets.length"
          :description="t('select-module', '选择模块后，配置业务对象的编码规则。')"
        />
        <div v-else-if="selectedModule && moduleTargets.length" class="code-config__overview">
          <RecordExplorerPanel
            class="code-config__directory"
            :title="t('binding-title', '编码对象')"
            :refresh-disabled="busy || dirty"
            :searchable="false"
            @refresh="load"
          >
            <template #actions>
              <ModuleRuntimeActivationStatus
                v-if="!embedded && target?.source === 'DYNAMIC'"
                ref="localActivation"
                :module-alias="target.moduleAlias"
              />
            </template>
            <div class="code-config__directory-fields">
              <label class="code-config__field"
                >{{ t('entity', '业务对象')
                }}<UiSelect
                  :value="targetKey"
                  :disabled="busy"
                  :options="
                    moduleTargets.map((item) => ({
                      value: targetId(item),
                      label: item.title,
                    }))
                  "
                  @update:value="selectTarget(String($event))"
              /></label>
              <label v-if="target?.fields.length" class="code-config__field"
                >{{ t('field', '编码字段')
                }}<UiSelect
                  :value="fieldName"
                  :disabled="busy"
                  :options="
                    target?.fields.map((item) => ({ value: item.fieldName, label: item.title })) ?? []
                  "
                  @update:value="selectField(String($event))"
              /></label>
              <template v-if="target && !field">
                <UiEmpty
                  :description="t('reason.no-fields', '当前数据模型没有可用于编号的字段，请先检查字段配置。')"
                />
                <UiButton v-if="navigation && target.source === 'DYNAMIC'" @click="configureModel">{{
                  t('configure-model', '配置模型')
                }}</UiButton>
              </template>
              <template v-if="field">
                <div v-if="target?.source === 'STATIC'" class="code-config__binding-summary">
                  <span>{{ bindingSummary }}</span>
                  <UiHelp :label="t('binding-info', '字段设置说明')">
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
                <section v-else :key="`${targetKey}/${fieldName}`" class="code-config__binding">
                  <div class="code-config__binding-fields">
                    <label class="code-config__field"
                      >{{ t('role', '编码角色')
                      }}<UiSelect
                        v-model:value="bindingRole"
                        :disabled="busy || (field.role ? !canBind : !canSave)"
                        :options="enumOptions({ NORMAL: '业务编码', PRIMARY: '主编码' })"
                    /></label>
                    <label class="code-config__field"
                      >{{ t('trigger', '生成时机')
                      }}<UiSelect
                        v-model:value="bindingTrigger"
                        :disabled="busy || (field.role ? !canBind : !canSave)"
                        :options="enumOptions({ SAVE: '首次保存', EXPLICIT: '业务操作时生成' })"
                    /></label>
                    <div v-if="field.role && bindingDirty" class="code-config__actions">
                      <UiButton :disabled="!canBind || busy" @click="bind">{{
                        t('save-binding', '保存字段配置')
                      }}</UiButton>
                      <UiButton :disabled="busy" @click="cancelBinding">{{
                        t('cancel-settings', '取消修改')
                      }}</UiButton>
                    </div>
                  </div>
                </section>
                <RecordDetailExtensionSection v-if="field" :title="t('rules', '编号规则')" kind="relation">
                  <template #actions>
                    <RecordRelationActionButton
                      icon-name="plus"
                      :title="t('new-rule', '新增规则')"
                      :aria-label="t('new-rule', '新增规则')"
                      :disabled="!canSave || busy"
                      @click="createRule"
                    />
                  </template>
                  <RecordRelationTable
                    class="code-config__rules-table"
                    :columns="ruleColumns"
                    :rows="ruleRows"
                    density="compact"
                  >
                    <template #cell="{ row: rule, column }">
                      <UiButton
                        v-if="column.fieldName === 'title'"
                        type="link"
                        class="code-config__rule-name"
                        :disabled="busy"
                        @click="selectRule(rule)"
                        >{{ rule.title }}</UiButton
                      >
                      <template v-else-if="column.fieldName === 'scope'">
                        <UiSelect
                          v-if="usageDraft && usageDraft.id === rule.id"
                          v-model:value="usageDraft.orgScopeType"
                          :aria-label="t('scope', '适用范围')"
                          :disabled="!editable"
                          :options="enumOptions({ GLOBAL: '当前租户全部组织', ORG: '指定组织及其下级' })"
                        />
                        <span v-else>{{
                          rule.orgScopeType === 'GLOBAL'
                            ? t('all-orgs', '全部机构')
                            : t('enum.ORG', '指定组织及其下级')
                        }}</span>
                      </template>
                      <template v-else-if="column.fieldName === 'organization'">
                        <label
                          v-if="usageDraft && usageDraft.id === rule.id && usageDraft.orgScopeType === 'ORG'"
                          class="code-config__table-control"
                        >
                          <span class="code-config__cell-label">{{ t('organization', '适用组织') }}</span>
                          <RecordPicker
                            v-if="organizationContext"
                            :value="usageDraft.orgScopeId ?? undefined"
                            :context="organizationContext"
                            mode="tree"
                            :disabled="!editable"
                            @update:value="usageDraft!.orgScopeId = $event"
                          />
                          <span v-else>{{ t('tenant-required', '请使用租户身份选择组织。') }}</span>
                        </label>
                        <span v-else>{{
                          (usageDraft && usageDraft.id === rule.id
                            ? usageDraft.orgScopeType
                            : rule.orgScopeType) === 'GLOBAL'
                            ? '—'
                            : ruleScopeTitle(rule)
                        }}</span>
                      </template>
                      <template
                        v-else-if="column.fieldName === 'effectiveFrom' || column.fieldName === 'effectiveTo'"
                      >
                        <UiInput
                          v-if="usageDraft && usageDraft.id === rule.id"
                          v-model:value="usageDraft[column.fieldName]"
                          :aria-label="column.title"
                          type="datetime-local"
                          :step="60"
                          :disabled="!editable"
                        />
                        <span v-else>{{
                          formatPlatformDateTime(rule[column.fieldName], {
                            language,
                            precision: 'minute',
                            emptyText: t('unlimited', '不限'),
                          }).text
                        }}</span>
                      </template>
                      <div v-else-if="column.fieldName === 'actions'" class="code-config__row-actions">
                        <template v-if="usageDraft && usageDraft.id === rule.id">
                          <UiButton
                            type="link"
                            :disabled="!canSave || busy || !usageDirty"
                            @click="saveUsage"
                            >{{ t('save', '保存') }}</UiButton
                          >
                          <UiButton type="link" :disabled="busy" @click="cancelUsage">{{
                            t('cancel-settings', '取消修改')
                          }}</UiButton>
                        </template>
                        <UiButton
                          v-else
                          type="link"
                          class="code-config__edit-usage"
                          :aria-label="t('edit-usage', '编辑适用配置') + '：' + rule.title"
                          :disabled="!canSave || busy"
                          @click="editUsage(rule)"
                          >{{ t('edit', '编辑') }}</UiButton
                        >
                      </div>
                      <UiSwitch
                        v-else-if="column.fieldName === 'enabled'"
                        :aria-label="
                          language.t('platform.code.config.rule-switch', '启用规则：{name}', {
                            name: rule.title,
                          })
                        "
                        :checked="rule.enabled"
                        :disabled="
                          !canSave ||
                          busy ||
                          dirty ||
                          !!usageDraft ||
                          (!rule.enabled && !field.role && !canBind)
                        "
                        @change="toggleRule(rule, $event)"
                      />
                    </template>
                    <template #empty>{{ t('no-rules', '暂无编号规则') }}</template>
                  </RecordRelationTable>
                </RecordDetailExtensionSection>
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
      :title="draft?.id ? t('rules', '编号规则') : t('create-rule-title', '新增编号规则')"
      :subtitle="[target?.title, field?.title].filter(Boolean).join(' · ')"
    >
      <template v-if="draft" #header-actions>
        <UiButton :disabled="busy" @click="closeEditor">{{ t('cancel', '取消修改') }}</UiButton>
        <UiButton type="primary" :disabled="!editable || bindingDirty" :loading="saving" @click="save">{{
          t('save-rule', '保存规则')
        }}</UiButton>
      </template>
      <div v-if="error || feedback" ref="editorFeedback">
        <UiError v-if="error" :title="t('request-failed', '操作未完成')" :message="error" />
        <p v-if="feedback" role="status">{{ feedback }}</p>
      </div>
      <UiEmpty v-if="!draft" :description="t('choose-rule', '选择或新增编号规则。')" />
      <template v-else>
        <div class="code-config__editor-content">
          <div
            class="code-config__editor-grid"
            :class="{ 'code-config__editor-grid--manual': draft.mode === 'MANUAL' }"
          >
            <div class="code-config__editor-main">
              <div class="code-config__basic">
                <label class="code-config__field"
                  >{{ t('rule-title', '规则名称')
                  }}<UiInput v-model:value="draft.title" :disabled="!editable"
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
              </div>
              <p v-if="draft.mode === 'MANUAL'" class="code-config__muted">
                {{ t('manual-description', '人工填写编号，无需设置编码格式。') }}
              </p>
              <CodeRuleFormatEditor
                v-show="draft.mode !== 'MANUAL'"
                v-model:rule="draft"
                :sources="target?.sources ?? []"
                :trigger="field?.trigger ?? bindingTrigger"
                :editable="editable"
              />
              <details :key="draft.id ?? 'new'" class="code-config__policy">
                <summary>
                  {{ t('numbering-policy', '编号策略')
                  }}<span v-if="rulePolicySummary"> · {{ rulePolicySummary }}</span>
                </summary>
                <div class="code-config__checks code-config__policy-options">
                  <div v-if="canRenumber" class="code-config__policy-option">
                    <UiCheckbox v-model:checked="draft.linkedUpdate" :disabled="!editable">{{
                      t('linked', '修改编号来源后自动换号')
                    }}</UiCheckbox>
                    <UiHelp :label="t('linked-help-title', '自动换号说明')">
                      <p>
                        {{
                          t(
                            'linked-help',
                            '已生成编号后，修改参与编号的业务字段并保存，将重新生成整个编号；包含流水号时会重新取号。未勾选则保留原编号，修改其他字段不受影响。',
                          )
                        }}
                      </p>
                      <p>
                        {{
                          t(
                            'linked-example',
                            '例如编号由“采购类别 + 流水号”组成：类别从“设备”改为“耗材”，编号会从 SB-0001 换为 HC-0002（示例）。',
                          )
                        }}
                      </p>
                    </UiHelp>
                  </div>
                  <div class="code-config__policy-option">
                    <UiCheckbox v-model:checked="draft.allowRecycle" :disabled="!editable">{{
                      t('recycle-short-label', '允许复用旧号')
                    }}</UiCheckbox>
                    <UiHelp :label="t('recycle-help-title', '旧号复用说明')">
                      <p>
                        {{
                          t(
                            'recycle-help',
                            '记录彻底删除或更换编号后，释放的旧号允许再次分配。移入回收站仍会占用原编号；取消勾选后，旧号不再分配给其他记录。',
                          )
                        }}
                      </p>
                    </UiHelp>
                  </div>
                </div>
              </details>
            </div>
            <template v-if="draft.mode !== 'MANUAL'">
              <aside class="code-config__trial" :aria-label="t('trial', '草稿试算')">
                <RecordContentSectionHeading :title="t('trial', '草稿试算')" />
                <p class="code-config__muted">{{ t('auto-trial-short', '自动试算，不占号') }}</p>
                <output v-if="preview" ref="previewElement" class="code-config__preview">
                  <span>{{ t('trial-result', '试算结果') }}</span>
                  <strong>{{ preview.value }}</strong>
                </output>
                <div v-else class="code-config__preview">
                  <span>{{ t('trial-result', '试算结果') }}</span>
                  <p class="code-config__muted">
                    {{
                      trialScheduled || trialing
                        ? t('trial-updating', '正在更新…')
                        : t('auto-trial-placeholder', '修改配置后自动更新')
                    }}
                  </p>
                </div>
                <details v-if="extraTrialOptions.length" class="code-config__trial-extra">
                  <summary>{{ t('trial-extra-short', '补充试算来源') }}</summary>
                  <label class="code-config__field">
                    <span class="code-config__cell-label">{{ t('trial-extra-short', '补充试算来源') }}</span>
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
                </details>
                <div v-if="trialError" ref="trialErrorElement" tabindex="-1" class="code-config__trial-error">
                  <UiError :title="t('trial-failed', '试算未完成')" :message="trialError" />
                </div>
                <div class="code-config__trial-fields">
                  <label class="code-config__field" v-for="source in trialSources" :key="source.fieldName"
                    >{{ codeSourceTitle(source, language)
                    }}<UiSelect
                      v-if="codeTrialSourceKind(source) === 'boolean'"
                      v-model:value="trialValues[source.fieldName]"
                      allow-clear
                      :disabled="busy"
                      :options="[
                        { value: 'true', label: t('boolean-true', '是') },
                        { value: 'false', label: t('boolean-false', '否') },
                      ]"
                    />
                    <UiInput
                      v-else
                      v-model:value="trialValues[source.fieldName]"
                      :step="codeTrialSourceKind(source) === 'number' ? 'any' : undefined"
                      :type="
                        codeTrialSourceKind(source) === 'datetime'
                          ? 'datetime-local'
                          : codeTrialSourceKind(source) === 'number'
                            ? 'number'
                            : codeTrialSourceKind(source) === 'date'
                              ? 'date'
                              : 'text'
                      "
                      :disabled="busy"
                    />
                  </label>
                  <label v-if="draft.sequencePolicy" class="code-config__field"
                    >{{ t('trial-sequence', '试算流水值')
                    }}<UiInput
                      v-model:value="trialSequence"
                      type="number"
                      :placeholder="String(draft.sequencePolicy?.startValue ?? 1)"
                      :disabled="busy"
                  /></label>
                </div>
                <UiButton
                  type="text"
                  icon-name="reload"
                  size="small"
                  :aria-label="t('refresh-trial', '刷新试算')"
                  :disabled="!trialReady || trialing"
                  :loading="trialing"
                  @click="trial(true)"
                  >{{ t('refresh-trial', '刷新试算') }}</UiButton
                >
                <p v-if="preview?.segments.length" class="code-config__muted">
                  {{ t('segment-results', '片段结果（不含分隔符）') }}
                </p>
                <ol
                  v-if="preview?.segments.length"
                  class="code-config__preview-segments"
                  :aria-label="t('segment-results', '片段结果（不含分隔符）')"
                >
                  <li v-for="(segment, index) in preview.segments" :key="index">
                    <span>{{ t(`segment.${segment.segmentType}`, segment.segmentType) }}</span>
                    <code>{{ segment.value }}</code>
                  </li>
                </ol>
              </aside>
            </template>
          </div>
        </div>
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
  margin: 0;
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
.code-config__editor-content {
  container: code-rule-editor / inline-size;
  display: grid;
  gap: 20px;
}
.code-config__basic {
  display: grid;
  gap: 16px;
}
.code-config__editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 16px;
  align-items: start;
}
.code-config__editor-grid--manual {
  grid-template-columns: minmax(0, 1fr);
}
.code-config__editor-main {
  min-width: 0;
  display: grid;
  gap: 20px;
}
.code-config__trial {
  position: sticky;
  top: 0;
  display: grid;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-surface);
  min-width: 0;
  max-height: calc(100dvh - 220px);
  overflow: auto;
}
.code-config__preview-segments {
  display: grid;
  gap: 8px;
  list-style: none;
  margin: 0;
  padding: 0;
}
.code-config__preview-segments li {
  display: grid;
  gap: 4px;
  font-size: 12px;
}
.code-config__preview-segments span {
  color: var(--muyun-text-muted);
}
.code-config__preview-segments code {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.code-config__trial-fields {
  display: grid;
  gap: 12px;
}
@container code-rule-editor (max-width: 720px) {
  .code-config__editor-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .code-config__trial {
    position: static;
    max-height: none;
    overflow: visible;
  }
}
.code-config__rules-table {
  grid-column: 1 / -1;
}
.code-config__rules-table :deep(td) {
  padding: 6px;
}
.code-config__rules-table :deep(.ant-select) {
  width: 100%;
  min-width: 0;
}
.code-config__rules-table :deep(.ant-switch) {
  flex: 0 0 auto;
}
.code-config__rules-table .code-config__rule-name {
  display: block;
  width: 100%;
  height: auto;
  padding: 0;
  white-space: normal;
  overflow-wrap: anywhere;
  text-align: start;
}
.code-config__row-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.code-config__row-actions > * {
  flex: 0 0 auto;
  padding-inline: 4px;
}
.code-config__table-control {
  display: block;
}
.code-config__cell-label {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.code-config__rules-table :deep(th:first-child),
.code-config__rules-table :deep(td:first-child) {
  position: sticky;
  left: 0;
  background: var(--muyun-surface);
  z-index: 1;
}
.code-config__rules-table :deep(th:last-child),
.code-config__rules-table :deep(td:last-child) {
  position: sticky;
  right: 0;
  background: var(--muyun-surface);
  z-index: 1;
}
.code-config__binding-summary > * {
  flex: 0 0 auto;
}
.code-config__binding-summary,
.code-config__policy-option {
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
.code-config__binding {
  margin: 12px 0;
}
.code-config__binding-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.code-config__binding-fields > .code-config__actions {
  grid-column: 1 / -1;
}
.code-config__policy {
  margin: 0;
}
.code-config__policy-options {
  margin-block: 12px 20px;
  align-items: flex-start;
  flex-direction: column;
}
.code-config__policy summary {
  cursor: pointer;
}
.code-config__muted {
  color: var(--muyun-text-muted);
  font-weight: 400;
}
.code-config__unavailable {
  display: grid;
  justify-items: center;
  align-content: start;
  gap: 12px;
}
.code-config__trial summary {
  cursor: pointer;
  font-weight: 600;
}
.code-config__preview {
  display: block;
  min-height: 76px;
  padding: 12px;
  margin: 0;
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
