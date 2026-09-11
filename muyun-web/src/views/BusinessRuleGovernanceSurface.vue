<script setup lang="ts">
import { computed, nextTick, ref, watch, useId, type ComponentPublicInstance } from 'vue';
import {
  FormulaExpressionEditor,
  RecordDetailDrawer,
  RecordQueryEnumFilter,
  RecordQueryListCell,
  RecordQueryListSurface,
  presentPlatformError,
  presentPlatformMessage,
  type RecordQueryListColumn,
} from '@muyun/platform-components';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import { useModuleContext, withHttpHeaders } from '@muyun/web-core';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import {
  UiActionButton,
  UiButton,
  UiCheckbox,
  UiEmpty,
  UiIcon,
  UiInput,
  UiModal,
  UiPopover,
  UiSelect,
  UiSpin,
  UiSwitch,
  UiTextArea,
  useUiDragSource,
  type UiDataTableColumn,
  type UiDataTableRecord,
  type UiDragSource,
  type UiTreeNode,
  type UiTreeLoadRequest,
  type UiTreeLoadResult,
} from '@muyun/vue-ui-antdv';
import {
  editableProposals,
  businessRuleChangeImpact,
  externalTrialInputFields,
  formulaTemplates,
  insertFormulaText,
  newBusinessRule,
  presentableFormulaExpression,
  proposalFingerprintOf,
  referencedFormulaFields,
  readonlyRules,
  typedSampleValue,
  formulaFieldUnusableReason,
  aggregateFieldInsertionReason,
  normalizeFormulaCapabilities,
  portableFormulaCapabilities,
  type UiControlSnapshot,
  type UiControlTarget,
  type BusinessRuleApplyResult,
  type BusinessRuleEditableField,
  type BusinessRuleFormulaCapability,
  type BusinessRuleFormulaCatalogEntry,
  type BusinessRuleKind,
  type BusinessRulePreview,
  type BusinessRuleProposal,
  type BusinessRuleIssue,
  type BusinessRuleSnapshot,
  type BusinessRuleTrialResult,
  type BusinessRuleReferenceField,
} from './businessRuleGovernance';

import MetadataSourceTree from './MetadataSourceTree.vue';
import { metadataSourceFieldNode, metadataSourceRoot } from './metadataSourceTree';

defineOptions({ name: 'BusinessRuleGovernanceSurface' });

const props = defineProps<{
  moduleAlias: string;
  moduleTitle?: string;
  /** A server-supplied portable catalogue can replace the built-in capability descriptors. */
  formulaCapabilities?: readonly (BusinessRuleFormulaCapability | BusinessRuleFormulaCatalogEntry)[];
}>();
const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const currentUser = useCurrentUserContext();
const snapshot = ref<BusinessRuleSnapshot>();
const rules = ref<BusinessRuleProposal[]>([]);
const uiControlSnapshot = ref<UiControlSnapshot>();
const selectedControlForm = computed(() =>
  uiControlSnapshot.value?.forms.find((form) => form.key === selectedRule.value?.formKey),
);
function setControlForm(value: unknown) {
  updateRule('formKey', value);
  updateRule('targets', []);
}
function setControlEffect(elementKey: string, effect: 'hide' | 'readOnly', checked: boolean) {
  const targets = (selectedRule.value?.targets ?? []).map((target) => ({ ...target }));
  let target = targets.find((item) => item.elementKey === elementKey);
  if (!target) {
    target = { elementKey, hide: false, readOnly: false };
    targets.push(target);
  }
  target[effect] = checked;
  updateRule(
    'targets',
    targets.filter((item) => item.hide || item.readOnly),
  );
}
function controlEffect(elementKey: string, effect: keyof Pick<UiControlTarget, 'hide' | 'readOnly'>) {
  return selectedRule.value?.targets?.find((target) => target.elementKey === elementKey)?.[effect] ?? false;
}
async function mergeUiRules(loaded: BusinessRuleSnapshot) {
  const ui = await moduleContext.http.request<UiControlSnapshot>({
    path: `/platform.module/${encodeURIComponent(loaded.moduleAlias)}/business-rules/ui-controls`,
  });
  return {
    ui,
    snapshot: {
      ...loaded,
      rules: [
        ...loaded.rules,
        ...(ui.rules ?? []).map((rule) => ({ ...rule, kind: 'UI_CONTROL', phase: 'UI', editable: true })),
      ],
    },
  };
}
function ruleKindLabel(kind: BusinessRuleKind) {
  return { CALCULATION: '字段计算', VALIDATION: '保存校验', UI_CONTROL: '界面控制' }[kind];
}
const selectedCode = ref<string>();
const ruleDrawerOpen = ref(false);
const newRuleDraft = ref<BusinessRuleProposal>();
const draftIssues = ref<BusinessRuleIssue[]>([]);
const ruleSearch = ref('');
const rulePageNum = ref(1);
const rulePageSize = ref(10);
const loading = ref(false);
const loadFailed = ref(false);
const applying = ref(false);
const trial = ref<BusinessRuleTrialResult>();
const trialVisible = ref(false);
const trialRunning = ref(false);
const sampleValues = ref<Record<string, string>>({});
const capturedTrialInputs = ref<Record<string, unknown>>({});
const applicationError = ref<string>();
const applicationIssues = ref<BusinessRuleIssue[]>([]);
const activeKind = ref<BusinessRuleKind>('CALCULATION');
const selectedFieldNodeKey = ref<string>();
const referenceDirectories = ref(new Map<string, BusinessRuleReferenceField[]>());
const referenceDirectoryRequests = new Map<string, Promise<BusinessRuleReferenceField[]>>();
const referenceRootLoading = ref(false);
const referenceRootFailed = ref(false);
const formulaInputs = ref<Record<string, string>>({});
const selectedFormula = computed(() => (selectedRule.value ? formulaText(selectedRule.value) : ''));
const expressionSelections = new Map<string, { start: number; end: number }>();
const trialTenantId = ref('');
const trialTenants = ref<Array<{ id: string; title?: string; enabled?: boolean }>>([]);
const trialTenantLoading = ref(false);
const trialTenantError = ref('');
type FormulaTextAreaEditor = ComponentPublicInstance & {
  selection: () => { start: number; end: number };
  focusSelection: (start: number, end?: number) => void;
};
const expressionEditor = ref<FormulaTextAreaEditor>();
const visualExpressionEditor = ref<FormulaTextAreaEditor>();
const formulaMode = ref<'visual' | 'code'>('visual');
const activeExpressionEditor = computed(() =>
  formulaMode.value === 'visual' ? visualExpressionEditor.value : expressionEditor.value,
);
const functionPalette = ref<HTMLElement>();
const functionDragInstanceId = `formula-functions:${useId()}`;
const { begin: beginFunctionDrag, draggingKey: draggingFunctionId } = useUiDragSource(
  functionPalette,
  functionDragInstanceId,
  (key) => {
    const capability = formulaCapabilities.value.find((item) => item.id === key);
    if (!capability || applying.value || !selectedRule.value) return undefined;
    return {
      instanceId: functionDragInstanceId,
      operations: ['copy'],
      node: { key, title: functionPurpose(capability) },
      payloadType: 'formula-function',
      payload: { kind: 'formula-function', moduleAlias: props.moduleAlias, functionId: key },
    };
  },
  { start: () => undefined, end: () => undefined },
);
let baselineProposalFingerprint: string | undefined;
let loadRequest = 0;
let trialRequest = 0;
let applyRequest = 0;
let editRevision = 0;
let expressionSelectionOwner: string | undefined;
let referenceDirectoryEpoch = 0;
let trialTenantRequest = 0;

const editableFields = computed(() => snapshot.value?.editableFields ?? []);
const activeRules = computed(() => rules.value.filter((rule) => rule.kind === activeKind.value));
const filteredActiveRules = computed(() => {
  const keyword = ruleSearch.value.trim().toLocaleLowerCase();
  if (!keyword) return activeRules.value;
  return activeRules.value.filter((rule) =>
    [ruleChoiceLabel(rule), ruleSummary(rule), rule.code].some((text) =>
      text.toLocaleLowerCase().includes(keyword),
    ),
  );
});
const rulePages = computed(() =>
  Math.max(1, Math.ceil(filteredActiveRules.value.length / rulePageSize.value)),
);
const pagedActiveRules = computed(() =>
  filteredActiveRules.value.slice(
    (rulePageNum.value - 1) * rulePageSize.value,
    rulePageNum.value * rulePageSize.value,
  ),
);
const ruleListColumns = computed<RecordQueryListColumn[]>(() => [
  { key: 'name', title: '业务名称', width: '22%', render: (record) => String(record.name ?? '') },
  { key: 'summary', title: '目标字段或条件摘要', maxDisplayLines: 2 },
  { key: 'enabled', title: '启用状态', type: 'enabledStatus', width: '96px', align: 'center' },
  { key: 'changeState', title: '变更状态', width: '120px' },
]);
const ruleTableColumns = computed<UiDataTableColumn[]>(() => ruleListColumns.value);
const ruleTableRows = computed<UiDataTableRecord[]>(() =>
  pagedActiveRules.value.map((rule) => ({
    id: rule.code,
    name: ruleChoiceLabel(rule),
    summary: ruleSummary(rule),
    enabled: rule.enabled,
    changeState: `${changedRuleCodes.value.has(rule.code) ? '未应用更改' : '已应用'}${isRuleIncomplete(rule) ? ' · 待完善' : ''}`,
    code: rule.code,
  })),
);
const readonlyRuleList = computed(() => (snapshot.value ? readonlyRules(snapshot.value) : []));
const selectedRule = computed(
  () => newRuleDraft.value ?? rules.value.find((rule) => rule.code === selectedCode.value),
);
const referenceRootFields = computed(
  () => referenceDirectories.value.get(referenceDirectoryKey(props.moduleAlias, '')) ?? [],
);
const catalogFields = computed(() => {
  const fields = new Map<string, BusinessRuleReferenceField>();
  editableFields.value.forEach((field) =>
    fields.set(field.fieldName, {
      id: field.fieldName,
      name: field.fieldName,
      label: field.title,
      valueType: field.valueType,
      aggregateFunctions: field.aggregateFunctions,
    }),
  );
  snapshot.value?.referenceFields?.forEach((field) =>
    fields.set(field.path, {
      id: field.path,
      name: field.path,
      label: field.title,
      valueType: field.valueType,
      readOnly: true,
    }),
  );
  snapshot.value?.aggregateFields?.forEach((field) =>
    fields.set(field.fieldName, {
      id: field.fieldName,
      name: field.fieldName,
      label: field.title,
      valueType: field.valueType,
    }),
  );
  referenceDirectories.value.forEach((directory) =>
    directory.forEach((field) => fields.set(field.name, field)),
  );
  return fields;
});
const fieldKeyword = ref('');
const showSystemFields = ref(false);
const metadataExpandedKeys = ref(['metadata:root']);
const metadataTreeReloadKey = ref(0);
watch(showSystemFields, () => {
  metadataTreeReloadKey.value += 1;
});
const fieldTreeNodes = computed(() => [
  metadataSourceRoot(
    props.moduleTitle || props.moduleAlias,
    [
      ...referenceRootFields.value,
      ...(snapshot.value?.aggregateFields ?? []).map((field) => ({
        id: field.fieldName,
        name: field.fieldName,
        label: field.title,
        valueType: field.valueType,
        aggregateFunctions: field.aggregateFunctions,
        systemManaged: false,
      })),
    ]
      .filter((field) => showSystemFields.value || !field.systemManaged)
      .filter(
        (field) =>
          !fieldKeyword.value.trim() ||
          `${field.label || ''} ${field.name}`
            .toLowerCase()
            .includes(fieldKeyword.value.trim().toLowerCase()),
      )
      .map(referenceFieldNode),
  ),
]);
const formulaCapabilities = computed(() =>
  normalizeFormulaCapabilities(
    props.formulaCapabilities ?? snapshot.value?.functions ?? portableFormulaCapabilities,
  ),
);
const functionCapabilityGroups = computed(() => {
  const groups = new Map<string, BusinessRuleFormulaCapability[]>();
  formulaCapabilities.value.forEach((capability) => {
    const current = groups.get(capability.category) ?? [];
    current.push(capability);
    groups.set(capability.category, current);
  });
  return [...groups.entries()].map(([category, capabilities]) => ({ category, capabilities }));
});
const hasUnsavedChanges = computed(
  () =>
    snapshot.value != null &&
    baselineProposalFingerprint != null &&
    proposalFingerprintOf(rules.value) !== baselineProposalFingerprint,
);
const calculatorUnavailableReason = computed(() =>
  editableFields.value.length === 0 ? '当前模块没有可写的主表字段，不能新增字段计算。' : undefined,
);
const fieldOptions = computed(() =>
  editableFields.value.map((field) => ({ value: field.fieldName, label: fieldLabel(field) })),
);
const externalInputFields = computed(() =>
  externalTrialInputFields(
    editableFields.value,
    rules.value.filter((rule) => rule.kind !== 'UI_CONTROL'),
  ),
);
const referencedFormulaPaths = computed(() =>
  rules.value
    .filter((rule) => rule.enabled)
    .flatMap((rule) => referencedFormulaFields(rule.expression))
    .filter((path) => path.includes('.')),
);
const referenceTrialInputFields = computed(() => {
  const direct = new Set(externalInputFields.value.map((field) => field.fieldName));
  const roots = new Set(referencedFormulaPaths.value.map((path) => path.split('.')[0]!));
  return [...roots]
    .filter((root) => !direct.has(root) && !calculatedTargetFields.value.has(root))
    .flatMap((root) => {
      const editable = editableFields.value.find((field) => field.fieldName === root);
      if (editable) return [editable];
      const catalog = catalogFields.value.get(root);
      return catalog && !formulaFieldUnusableReason(catalog)
        ? [
            {
              fieldName: root,
              title: catalog.label || root,
              fieldSpecAlias: catalog.referenceModuleAlias || 'reference',
              valueType: 'REFERENCE',
            },
          ]
        : [];
    });
});
const trialInputFields = computed(() => [...externalInputFields.value, ...referenceTrialInputFields.value]);
const systemIdentity = computed(() => currentUser?.value?.system !== false);
const referenceTrialRequiresTenant = computed(
  () => referencedFormulaPaths.value.length > 0 && systemIdentity.value && !currentUser?.value?.tenantId,
);
const trialTenantReady = computed(() => !referenceTrialRequiresTenant.value || Boolean(trialTenantId.value));
const calculatedTargetFields = computed(
  () =>
    new Set(
      rules.value
        .filter((rule) => rule.enabled && rule.kind === 'CALCULATION')
        .flatMap((rule) => (rule.targetField ? [rule.targetField] : [])),
    ),
);
const otherTrialFields = computed(() =>
  editableFields.value.filter(
    (field) =>
      !trialInputFields.value.some((input) => input.fieldName === field.fieldName) &&
      !calculatedTargetFields.value.has(field.fieldName),
  ),
);
const selectedRuleTemplates = computed(() =>
  selectedRule.value
    ? formulaTemplates(selectedRule.value.kind, editableFields.value, selectedRule.value.targetField).filter(
        (template) => selectedRule.value?.kind !== 'UI_CONTROL' || template.id === 'present-field',
      )
    : [],
);
const hasEnabledValidationRules = computed(() =>
  rules.value.some((rule) => rule.enabled && rule.kind === 'VALIDATION'),
);
const changeImpact = computed(() =>
  snapshot.value
    ? businessRuleChangeImpact(snapshot.value, rules.value)
    : { added: [], modified: [], deleted: [] },
);
const unappliedChangeCount = computed(
  () =>
    changeImpact.value.added.length + changeImpact.value.modified.length + changeImpact.value.deleted.length,
);
const changedRuleCodes = computed(
  () => new Set([...changeImpact.value.added, ...changeImpact.value.modified].map((rule) => rule.code)),
);
const locatorFieldOptions = computed(() => [
  { value: '__none__', label: '不定位字段' },
  ...fieldOptions.value,
]);
useWorkspaceViewUnsavedState('业务规则', () => hasUnsavedChanges.value);

watch(
  () => props.moduleAlias,
  () => void loadSnapshot(true),
  { immediate: true },
);

watch(selectedCode, (code) => {
  expressionSelectionOwner = undefined;
  const rule = rules.value.find((candidate) => candidate.code === code);
  if (rule) activeKind.value = rule.kind;
  const remembered = code ? expressionSelections.get(code) : undefined;
  if (remembered) void nextTick(() => focusExpression(remembered.start, remembered.end));
});

watch(referenceTrialRequiresTenant, (required) => {
  if (required && trialVisible.value && trialTenants.value.length === 0) void loadTrialTenants();
  if (!required) {
    trialTenantId.value = '';
    trialTenantError.value = '';
  }
});

watch([filteredActiveRules, rulePageSize], () => {
  if (rulePageNum.value > rulePages.value) rulePageNum.value = rulePages.value;
});

watch(activeKind, () => {
  rulePageNum.value = 1;
});

function fieldLabel(field: BusinessRuleEditableField): string {
  return `${field.title || field.fieldName}（${field.fieldName}，${fieldTypeLabel(field)}）`;
}

function fieldTypeLabel(field: Pick<BusinessRuleEditableField, 'valueType'>): string {
  return (
    {
      INTEGER: '整数',
      LONG: '长整数',
      DECIMAL: '数值',
      STRING: '文本',
      BOOLEAN: '布尔值',
      DATE: '日期',
      TIMESTAMP: '日期时间',
      ZONED_TIMESTAMP: '日期时间',
    }[field.valueType] ?? field.valueType
  );
}

function fieldTitle(fieldName: string): string {
  const field = catalogFields.value.get(fieldName);
  return field ? `${field.label || field.name}（${field.name}，${fieldTypeLabel(field)}）` : fieldName;
}

function ruleChoiceLabel(rule: BusinessRuleProposal): string {
  if (rule.kind === 'UI_CONTROL')
    return `界面控制 · ${uiControlSnapshot.value?.forms.find((form) => form.key === rule.formKey)?.title ?? '未选择表单'} · ${rule.enabled ? '启用' : '停用'}`;
  const target = editableFields.value.find((field) => field.fieldName === rule.targetField);
  const purpose =
    rule.kind === 'CALCULATION'
      ? `计算${target?.title || rule.targetField || '未选择目标字段'}`
      : `保存校验${target?.title || rule.targetField || '通用条件'}`;
  return `${purpose} · ${rule.enabled ? '启用' : '停用'}`;
}

function isRuleIncomplete(rule: BusinessRuleProposal): boolean {
  return !rule.expression.trim() || (rule.kind === 'CALCULATION' && !rule.targetField?.trim());
}

function issueText(issue: BusinessRuleIssue): string {
  const field = editableFields.value.find((candidate) => candidate.fieldName === issue.field);
  return field ? `${fieldLabel(field)}：${issue.message}` : issue.message;
}

function ruleSummary(rule: BusinessRuleProposal): string {
  if (rule.kind === 'UI_CONTROL') return `公式为真时，控制所选表单中的 ${rule.targets?.length ?? 0} 个元素。`;
  const target = editableFields.value.find((field) => field.fieldName === rule.targetField);
  const targetText = target ? fieldLabel(target) : rule.targetField || '未定位字段';
  const dependencies = referencedFormulaFields(rule.expression).map((name) => {
    const field = editableFields.value.find((candidate) => candidate.fieldName === name);
    return field ? fieldLabel(field) : fieldTitle(name);
  });
  const action = rule.kind === 'CALCULATION' ? `计算并写入${targetText}` : `校验${targetText}`;
  return `${action}：${presentableFormulaExpression(rule.expression, editableFields.value)}。依赖：${dependencies.length ? dependencies.join('、') : '无字段引用'}。`;
}

function resetExecutionResults() {
  editRevision += 1;
  trialRequest += 1;
  applyRequest += 1;
  trialRunning.value = false;
  applying.value = false;
  trial.value = undefined;
  applicationError.value = undefined;
  applicationIssues.value = [];
}

function replaceRules(nextRules: BusinessRuleProposal[]) {
  rules.value = nextRules;
  resetExecutionResults();
}

function formulaText(rule: BusinessRuleProposal): string {
  return (
    formulaInputs.value[rule.code] ??
    (rule.kind === 'CALCULATION' && rule.targetField
      ? `{${rule.targetField}} = ${rule.expression}`
      : rule.expression)
  );
}

function updateRule(field: keyof BusinessRuleProposal, value: unknown) {
  if (applying.value) return;
  const code = selectedRule.value?.code;
  if (!code) return;
  const normalized =
    field === 'expression'
      ? typeof value === 'string'
        ? value
        : ''
      : field === 'enabled'
        ? value === true
        : value === '__none__' || value == null || value === ''
          ? undefined
          : value;
  let changes: Partial<BusinessRuleProposal> = { [field]: normalized };
  if (field === 'expression' && selectedRule.value?.kind === 'CALCULATION') {
    const input = typeof normalized === 'string' ? normalized : '';
    formulaInputs.value[code] = input;
    const assignment = /^\s*\{([^{}]+)\}\s*=(?!=)\s*([\s\S]*)$/.exec(input);
    changes = { targetField: assignment?.[1]?.trim(), expression: assignment ? assignment[2]! : input };
  }
  if (newRuleDraft.value) {
    newRuleDraft.value = { ...newRuleDraft.value, ...changes };
    draftIssues.value = [];
    return;
  }
  replaceRules(
    rules.value.map((rule) =>
      rule.code === code
        ? {
            ...rule,
            ...changes,
          }
        : rule,
    ),
  );
}

function changeDraftKind(kind: unknown) {
  if (
    !newRuleDraft.value ||
    applying.value ||
    (kind !== 'CALCULATION' && kind !== 'VALIDATION' && kind !== 'UI_CONTROL')
  )
    return;
  if (kind === newRuleDraft.value.kind || (kind === 'CALCULATION' && calculatorUnavailableReason.value))
    return;
  delete formulaInputs.value[newRuleDraft.value.code];
  newRuleDraft.value = {
    ...newRuleDraft.value,
    kind,
    targetField: undefined,
    messageTemplate: undefined,
    formKey: undefined,
    targets: undefined,
  };
  draftIssues.value = [];
}

function addRule(kind: BusinessRuleKind) {
  if (applying.value) return;
  if (kind === 'CALCULATION' && calculatorUnavailableReason.value) {
    presentPlatformMessage(calculatorUnavailableReason.value, {
      source: 'business-rule-governance',
      phase: 'validation',
    });
    return;
  }
  formulaMode.value = 'visual';
  newRuleDraft.value = newBusinessRule(kind, [...rules.value, ...readonlyRuleList.value]);
  draftIssues.value = [];
  expressionSelectionOwner = undefined;
  ruleDrawerOpen.value = true;
}

function removeSelectedRule() {
  if (applying.value) return;
  const code = selectedCode.value;
  if (!code) return;
  delete formulaInputs.value[code];
  expressionSelections.delete(code);
  const remaining = rules.value.filter((rule) => rule.code !== code);
  replaceRules(remaining);
  selectedCode.value = remaining.find((rule) => rule.kind === activeKind.value)?.code;
  rulePageNum.value = Math.min(rulePageNum.value, rulePages.value);
  if (!selectedCode.value) ruleDrawerOpen.value = false;
}

function insertExpressionText(
  text: string,
  selectedStart?: number,
  selectedEnd?: number,
  focusOffsetStart?: number,
  focusOffsetEnd?: number,
) {
  if (applying.value) return;
  const rule = selectedRule.value;
  if (!rule) return;
  const selection =
    expressionSelectionOwner === rule.code
      ? activeExpressionEditor.value?.selection?.()
      : expressionSelections.get(rule.code);
  const insertion = insertFormulaText(
    formulaText(rule),
    selectedStart ?? selection?.start,
    selectedEnd ?? selection?.end,
    text,
  );
  updateRule('expression', insertion.value);
  expressionSelectionOwner = rule.code;
  const insertionStart = insertion.selectionStart - text.length;
  const nextSelection = {
    start: focusOffsetStart == null ? insertion.selectionStart : insertionStart + focusOffsetStart,
    end: focusOffsetEnd == null ? insertion.selectionEnd : insertionStart + focusOffsetEnd,
  };
  expressionSelections.set(rule.code, nextSelection);
  void nextTick(() => focusExpression(nextSelection.start, nextSelection.end));
}

function insertFunction(
  capabilityOrName: BusinessRuleFormulaCapability | BusinessRuleFormulaCapability['id'],
) {
  const capability =
    typeof capabilityOrName === 'string'
      ? formulaCapabilities.value.find((candidate) => candidate.id === capabilityOrName)
      : capabilityOrName;
  if (!capability) return;
  insertExpressionText(
    capability.insertion,
    undefined,
    undefined,
    capability.firstParameterSelection.start,
    capability.firstParameterSelection.end,
  );
}

function rememberExpressionSelection(selection: { start: number; end: number }) {
  expressionSelectionOwner = selectedRule.value?.code;
  if (expressionSelectionOwner) expressionSelections.set(expressionSelectionOwner, selection);
}

function switchFormulaMode(codeMode: boolean) {
  const mode = codeMode ? 'code' : 'visual';
  if (applying.value || formulaMode.value === mode) return;
  const selection = activeExpressionEditor.value?.selection?.() ?? { start: 0, end: 0 };
  rememberExpressionSelection(selection);
  formulaMode.value = mode;
  void nextTick(() => focusExpression(selection.start, selection.end));
}

function applyTemplate(expression: string) {
  if (applying.value || !selectedRule.value || selectedRule.value.expression.trim()) return;
  const formula =
    selectedRule.value.kind === 'CALCULATION'
      ? `{${selectedRule.value.targetField || ''}} = ${expression}`
      : expression;
  updateRule('expression', formula);
  const needsTarget = selectedRule.value.kind === 'CALCULATION' && !selectedRule.value.targetField;
  void nextTick(() => (needsTarget ? focusExpression(0, 2) : focusExpression(formula.length)));
}

function functionPurpose(capability: BusinessRuleFormulaCapability): string {
  return capability.purpose;
}

function focusExpression(start: number, end = start) {
  activeExpressionEditor.value?.focusSelection?.(start, end);
}

function referenceDirectoryKey(moduleAlias: string, path: string) {
  return `${moduleAlias}:${path}`;
}

function referenceFieldNode(field: BusinessRuleReferenceField): UiTreeNode {
  const reason = formulaFieldUnusableReason(field);
  const expandable = field.expandable === true && field.referenceCardinality === 'ONE';
  return metadataSourceFieldNode(
    {
      title: field.label || field.name.split('.').at(-1) || field.name,
      fieldName: field.name,
      platformReadOnly: field.readOnly,
      referenceModuleAlias: field.referenceModuleAlias,
      expandable,
    },
    {
      key: `formula-field:${field.name}`,
      disabled: Boolean(reason) && !expandable,
      muted: Boolean(reason),
    },
  );
}

function fieldOfTreeKey(key: string): BusinessRuleReferenceField | undefined {
  const name = key.replace(/^formula-field:/, '');
  return catalogFields.value.get(name);
}

function formulaFieldPayload(field: BusinessRuleReferenceField) {
  return { kind: 'formula-field' as const, moduleAlias: props.moduleAlias, fieldName: field.name };
}

function canDragFormulaField(node: UiTreeNode) {
  const field = fieldOfTreeKey(node.key);
  return Boolean(field && !formulaFieldUnusableReason(field));
}

function dragFormulaFieldPayload(node: UiTreeNode) {
  const field = fieldOfTreeKey(node.key);
  return field ? formulaFieldPayload(field) : undefined;
}

function acceptsFormulaField(source: UiDragSource) {
  const payload = source.payload as
    | { kind?: unknown; moduleAlias?: unknown; fieldName?: unknown }
    | undefined;
  const field =
    typeof payload?.fieldName === 'string' ? catalogFields.value.get(payload.fieldName) : undefined;
  return Boolean(
    selectedRule.value &&
    !applying.value &&
    source.payloadType === 'formula-field' &&
    payload?.kind === 'formula-field' &&
    payload.moduleAlias === props.moduleAlias &&
    typeof payload.fieldName === 'string' &&
    field &&
    !formulaFieldUnusableReason(field),
  );
}

function insertFieldNode(node: UiTreeNode) {
  const field = fieldOfTreeKey(node.key);
  if (!selectedRule.value) {
    presentPlatformMessage('请先在右侧选择或新建一条规则，再插入字段。', {
      source: 'business-rule-governance',
      phase: 'validation',
    });
    return;
  }
  if (!field || formulaFieldUnusableReason(field)) return;
  const selection =
    expressionSelectionOwner === selectedRule.value.code
      ? activeExpressionEditor.value?.selection?.()
      : expressionSelections.get(selectedRule.value.code);
  const aggregateReason = aggregateFieldInsertionReason(
    field,
    selectedFormula.value,
    selection?.start ?? selectedFormula.value.length,
  );
  if (aggregateReason) {
    presentPlatformMessage(aggregateReason, { source: 'business-rule-governance', phase: 'validation' });
    return;
  }
  insertExpressionText(`{${field.name}}`);
}

function acceptsFormulaItem(source: UiDragSource) {
  if (acceptsFormulaField(source)) return true;
  const payload = source.payload as
    | { kind?: unknown; moduleAlias?: unknown; functionId?: unknown }
    | undefined;
  return Boolean(
    selectedRule.value &&
    !applying.value &&
    source.payloadType === 'formula-function' &&
    payload?.kind === 'formula-function' &&
    payload.moduleAlias === props.moduleAlias &&
    formulaCapabilities.value.some((item) => item.id === payload.functionId),
  );
}

function handleFormulaDrop(event: { source: UiDragSource; selection: { start: number; end: number } }) {
  if (!acceptsFormulaItem(event.source)) return;
  const functionPayload = event.source.payload as { functionId?: string } | undefined;
  if (event.source.payloadType === 'formula-function') {
    const capability = formulaCapabilities.value.find((item) => item.id === functionPayload?.functionId);
    if (capability)
      insertExpressionText(
        capability.insertion,
        event.selection.start,
        event.selection.end,
        capability.firstParameterSelection.start,
        capability.firstParameterSelection.end,
      );
    return;
  }
  const payload = event.source.payload as
    | { kind?: unknown; moduleAlias?: unknown; fieldName?: unknown }
    | undefined;
  if (
    !acceptsFormulaField(event.source) ||
    payload?.kind !== 'formula-field' ||
    typeof payload.fieldName !== 'string'
  )
    return;
  const field = catalogFields.value.get(payload.fieldName);
  const aggregateReason = field
    ? aggregateFieldInsertionReason(field, selectedFormula.value, event.selection.start)
    : undefined;
  if (aggregateReason) {
    presentPlatformMessage(aggregateReason, { source: 'business-rule-governance', phase: 'validation' });
    return;
  }
  insertExpressionText(`{${payload.fieldName}}`, event.selection.start, event.selection.end);
}

function selectFieldNode(node: UiTreeNode) {
  selectedFieldNodeKey.value = node.key;
}

async function loadReferenceDirectory(
  moduleAlias: string,
  path: string,
): Promise<BusinessRuleReferenceField[]> {
  const key = referenceDirectoryKey(moduleAlias, path);
  const cached = referenceDirectories.value.get(key);
  if (cached) return cached;
  const pending = referenceDirectoryRequests.get(key);
  if (pending) return pending;
  const epoch = referenceDirectoryEpoch;
  let request!: Promise<BusinessRuleReferenceField[]>;
  request = moduleContext.http
    .request<{ moduleAlias: string; path?: string; fields?: BusinessRuleReferenceField[] }>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(moduleAlias)}/page-reference-fields${
        path ? `?path=${encodeURIComponent(path)}` : ''
      }`,
    })
    .then((response) => {
      const fields = (response.fields ?? []).filter((field): field is BusinessRuleReferenceField =>
        Boolean(field.id && field.name && field.valueType),
      );
      if (epoch === referenceDirectoryEpoch && moduleAlias === props.moduleAlias) {
        const next = new Map(referenceDirectories.value);
        next.set(key, fields);
        referenceDirectories.value = next;
      }
      return fields;
    })
    .finally(() => {
      if (referenceDirectoryRequests.get(key) === request) referenceDirectoryRequests.delete(key);
    });
  referenceDirectoryRequests.set(key, request);
  return request;
}

async function loadReferenceRoot(retry = false) {
  const moduleAlias = props.moduleAlias;
  const epoch = referenceDirectoryEpoch;
  if (referenceRootLoading.value) return;
  if (!retry && referenceDirectories.value.has(referenceDirectoryKey(moduleAlias, ''))) return;
  referenceRootLoading.value = true;
  referenceRootFailed.value = false;
  try {
    await loadReferenceDirectory(moduleAlias, '');
  } catch (cause) {
    if (epoch !== referenceDirectoryEpoch || moduleAlias !== props.moduleAlias) return;
    referenceRootFailed.value = true;
    presentPlatformError(cause, { source: 'business-rule-governance', phase: 'load' });
  } finally {
    if (epoch === referenceDirectoryEpoch && moduleAlias === props.moduleAlias)
      referenceRootLoading.value = false;
  }
}

async function loadReferenceChildren(
  node: UiTreeNode,
  request: UiTreeLoadRequest,
): Promise<UiTreeLoadResult> {
  const field = fieldOfTreeKey(node.key);
  if (!field || field.expandable !== true || field.referenceCardinality !== 'ONE')
    return { mode: 'replace', nodes: [], hasMore: false };
  const moduleAlias = props.moduleAlias;
  const epoch = referenceDirectoryEpoch;
  const fields = await loadReferenceDirectory(moduleAlias, field.name);
  if (request.signal.aborted || epoch !== referenceDirectoryEpoch || moduleAlias !== props.moduleAlias)
    return { mode: 'replace', nodes: [], hasMore: false };
  return {
    mode: 'replace',
    nodes: fields.filter((field) => showSystemFields.value || !field.systemManaged).map(referenceFieldNode),
    hasMore: false,
  };
}

async function ensureReferencePaths(fieldNames: readonly string[]) {
  const moduleAlias = props.moduleAlias;
  for (const fieldName of fieldNames) {
    const parts = fieldName.split('.');
    for (let index = 1; index < parts.length; index += 1) {
      if (moduleAlias !== props.moduleAlias) return;
      const path = parts.slice(0, index).join('.');
      if (referenceDirectories.value.has(referenceDirectoryKey(moduleAlias, path))) continue;
      try {
        await loadReferenceDirectory(moduleAlias, path);
      } catch {
        // Keep the expression intact. The server remains the authority for resolution and validation.
      }
    }
  }
}

watch([selectedFormula, ruleDrawerOpen], ([source, open]) => {
  if (open) void ensureReferencePaths(referencedFormulaFields(source));
});

function selectRuleKind(kind: unknown) {
  if (kind !== 'CALCULATION' && kind !== 'VALIDATION' && kind !== 'UI_CONTROL') return;
  closeRuleDrawer();
  activeKind.value = kind;
  if (!selectedRule.value || selectedRule.value.kind !== kind)
    selectedCode.value = activeRules.value.at(0)?.code;
}

function selectRuleRow(record: unknown) {
  if (!record || typeof record !== 'object') return;
  const candidate = (record as UiDataTableRecord).code;
  const code = typeof candidate === 'string' ? candidate : undefined;
  if (code) selectedCode.value = code;
}

function editRuleRow(record: unknown) {
  if (applying.value || !record || typeof record !== 'object') return;
  const candidate = (record as UiDataTableRecord).code;
  const code = typeof candidate === 'string' ? candidate : undefined;
  if (code) openRule(code);
}

function openRule(code: string) {
  if (applying.value) return;
  closeRuleDrawer();
  selectedCode.value = code;
  ruleDrawerOpen.value = true;
}

function removeRuleRow(record: unknown) {
  if (applying.value || !record || typeof record !== 'object') return;
  const candidate = (record as UiDataTableRecord).code;
  if (typeof candidate !== 'string') return;
  selectedCode.value = candidate;
  removeSelectedRule();
}

function closeRuleDrawer() {
  if (newRuleDraft.value) {
    expressionSelections.delete(newRuleDraft.value.code);
    delete formulaInputs.value[newRuleDraft.value.code];
  }
  newRuleDraft.value = undefined;
  draftIssues.value = [];
  ruleDrawerOpen.value = false;
}

function saveNewRule() {
  const draft = newRuleDraft.value;
  if (!draft || applying.value) return;
  draftIssues.value = incompleteRuleIssues([draft]);
  if (draftIssues.value.length) return;
  replaceRules([...rules.value, { ...draft }]);
  activeKind.value = draft.kind;
  ruleSearch.value = '';
  rulePageNum.value = Math.max(1, Math.ceil(activeRules.value.length / rulePageSize.value));
  newRuleDraft.value = undefined;
  closeRuleDrawer();
  selectedCode.value = draft.code;
}

async function loadSnapshot(force = false) {
  if (applying.value && !force) return;
  const request = ++loadRequest;
  resetExecutionResults();
  closeRuleDrawer();
  snapshot.value = undefined;
  uiControlSnapshot.value = undefined;
  baselineProposalFingerprint = undefined;
  rules.value = [];
  selectedCode.value = undefined;
  expressionSelections.clear();
  formulaInputs.value = {};
  formulaMode.value = 'visual';
  referenceDirectoryEpoch += 1;
  referenceDirectories.value = new Map();
  referenceDirectoryRequests.clear();
  selectedFieldNodeKey.value = undefined;
  referenceRootLoading.value = false;
  referenceRootFailed.value = false;
  trialTenantRequest += 1;
  trialTenantId.value = '';
  trialTenants.value = [];
  trialTenantError.value = '';
  trialTenantLoading.value = false;
  loading.value = true;
  loadFailed.value = false;
  try {
    const loaded = await moduleContext.http.request<BusinessRuleSnapshot>({
      path: `/platform.module/${encodeURIComponent(props.moduleAlias)}/business-rules`,
    });
    if (request !== loadRequest || loaded.moduleAlias !== props.moduleAlias) return;
    const merged = await mergeUiRules(loaded);
    if (request !== loadRequest) return;
    uiControlSnapshot.value = merged.ui;
    snapshot.value = merged.snapshot;
    formulaInputs.value = {};
    const editable = editableProposals(merged.snapshot);
    rules.value = editable;
    baselineProposalFingerprint = proposalFingerprintOf(editable);
    selectedCode.value =
      editable.find((rule) => rule.kind === activeKind.value)?.code ?? editable.at(0)?.code;
    sampleValues.value = Object.fromEntries(loaded.editableFields.map((field) => [field.fieldName, '']));
    void loadReferenceRoot();
    void ensureReferencePaths(loaded.referenceFields?.map((field) => field.path) ?? []);
  } catch (cause) {
    if (request !== loadRequest) return;
    loadFailed.value = true;
    presentPlatformError(cause, { source: 'business-rule-governance', phase: 'load' });
  } finally {
    if (request === loadRequest) loading.value = false;
  }
}

function discardChanges() {
  if (applying.value) return;
  if (!snapshot.value) return;
  formulaInputs.value = {};
  const editable = editableProposals(snapshot.value);
  rules.value = editable;
  baselineProposalFingerprint = proposalFingerprintOf(editable);
  selectedCode.value = editable.at(0)?.code;
  resetExecutionResults();
}

function selectIssueRule(issue: BusinessRuleIssue) {
  if (!issue.ruleCode || !rules.value.some((rule) => rule.code === issue.ruleCode)) return;
  openRule(issue.ruleCode);
  void nextTick(() => focusExpression(selectedRule.value?.expression.length ?? 0));
}

function selectTrialIssue(issue: BusinessRuleIssue) {
  trialVisible.value = false;
  selectIssueRule(issue);
}

function incompleteRuleIssues(proposals: readonly BusinessRuleProposal[]): BusinessRuleIssue[] {
  return proposals.flatMap((rule) => {
    if (rule.kind === 'UI_CONTROL' && (!rule.formKey || !rule.targets?.length))
      return [
        {
          code: 'INCOMPLETE_RULE',
          ruleCode: rule.code,
          message: '请选择表单，并为需要控制的元素勾选隐藏或只读',
        },
      ];
    const missingTarget = rule.kind === 'CALCULATION' && !rule.targetField?.trim();
    const missingExpression = !rule.expression.trim();
    const invalidTarget =
      rule.kind === 'CALCULATION' &&
      !missingTarget &&
      !editableFields.value.some((field) => field.fieldName === rule.targetField);
    if (!missingTarget && !missingExpression && !invalidTarget) return [];
    return [
      {
        code: 'INCOMPLETE_RULE',
        ruleCode: rule.code,
        message: [
          missingTarget ? '请在公式中指定结果字段，例如：{amount} = {quantity} * {unitPrice}' : undefined,
          missingExpression ? '请补充表达式' : undefined,
          invalidTarget ? '等号左侧必须是当前记录的可写字段标识，请从字段目录选择' : undefined,
        ]
          .filter(Boolean)
          .join('；'),
      },
    ];
  });
}

async function applyRules() {
  const currentSnapshot = snapshot.value;
  if (!currentSnapshot || !hasUnsavedChanges.value || applying.value) return;
  const request = ++applyRequest;
  const revision = editRevision;
  const moduleAlias = props.moduleAlias;
  const baselineFingerprint = currentSnapshot.baselineFingerprint;
  const proposedRules = rules.value.map((rule) => ({ ...rule }));
  const proposalFingerprint = proposalFingerprintOf(proposedRules);
  applying.value = true;
  applicationError.value = undefined;
  applicationIssues.value = [];
  const isCurrent = () =>
    request === applyRequest &&
    revision === editRevision &&
    moduleAlias === props.moduleAlias &&
    snapshot.value?.baselineFingerprint === baselineFingerprint &&
    proposalFingerprintOf(rules.value) === proposalFingerprint;
  try {
    const localIssues = incompleteRuleIssues(proposedRules);
    if (localIssues.length) {
      applicationIssues.value = localIssues;
      return;
    }
    const checked = await moduleContext.http.request<BusinessRulePreview>({
      method: 'POST',
      path: `/platform.module/${encodeURIComponent(moduleAlias)}/business-rules/preview`,
      body: { rules: proposedRules.filter((rule) => rule.kind !== 'UI_CONTROL') },
    });
    if (!isCurrent()) return;
    if (checked.errors.length) {
      applicationIssues.value = checked.errors;
      return;
    }
    if (!checked.proposalFingerprint) {
      applicationError.value = '检查结果无效，请重新加载后再应用。未应用更改仍保留。';
      return;
    }
    const result = await moduleContext.http.request<BusinessRuleApplyResult>({
      method: 'POST',
      path: `/platform.module/${encodeURIComponent(moduleAlias)}/business-rules/apply`,
      body: {
        rules: proposedRules.filter((rule) => rule.kind !== 'UI_CONTROL'),
        uiRules: proposedRules
          .filter((rule) => rule.kind === 'UI_CONTROL')
          .map(({ code, formKey, expression, enabled, targets }) => ({
            code,
            formKey,
            expression,
            enabled,
            targets,
          })),
        uiBaselineFingerprint: uiControlSnapshot.value?.baselineFingerprint,
        baselineFingerprint,
        proposalFingerprint: checked.proposalFingerprint,
      },
    });
    if (!isCurrent()) return;
    const merged = await mergeUiRules(result.snapshot);
    if (!isCurrent()) return;
    uiControlSnapshot.value = merged.ui;
    snapshot.value = merged.snapshot;
    formulaInputs.value = {};
    const editable = editableProposals(merged.snapshot);
    rules.value = editable;
    baselineProposalFingerprint = proposalFingerprintOf(editable);
    selectedCode.value =
      editable.find((rule) => rule.code === selectedCode.value)?.code ?? editable.at(0)?.code;
    resetExecutionResults();
    presentPlatformMessage('业务规则已应用并同步生效。', {
      source: 'business-rule-governance',
      phase: 'action',
    });
  } catch (cause) {
    if (!isCurrent()) return;
    applicationError.value = '应用失败，配置可能已更新；请重新加载后再应用。未应用更改仍保留。';
    presentPlatformError(cause, { source: 'business-rule-governance', phase: 'action' });
  } finally {
    if (request === applyRequest) applying.value = false;
  }
}

function openTrial() {
  if (!snapshot.value || applying.value) return;
  trialVisible.value = true;
  if (referenceTrialRequiresTenant.value && trialTenants.value.length === 0) void loadTrialTenants();
}

function invalidateTrial() {
  trialRequest += 1;
  trialRunning.value = false;
  capturedTrialInputs.value = {};
  trial.value = undefined;
}

async function loadTrialTenants(keyword = '') {
  if (!referenceTrialRequiresTenant.value) return;
  const request = ++trialTenantRequest;
  trialTenantLoading.value = true;
  trialTenantError.value = '';
  try {
    const result = await moduleContext.http.request<{ records: typeof trialTenants.value }>({
      method: 'POST',
      path: '/iam.tenant/navigator/reference/query',
      body: { page: { pageNum: 1, pageSize: 100 }, ...(keyword ? { quickSearch: keyword } : {}) },
    });
    if (request === trialTenantRequest) trialTenants.value = result.records;
  } catch (cause) {
    if (request === trialTenantRequest)
      trialTenantError.value = cause instanceof Error ? cause.message : '租户加载失败';
  } finally {
    if (request === trialTenantRequest) trialTenantLoading.value = false;
  }
}

function changeTrialTenant(value: unknown) {
  const tenantId = typeof value === 'string' ? value : '';
  if (tenantId && !trialTenants.value.some((tenant) => tenant.id === tenantId)) return;
  if (tenantId === trialTenantId.value) return;
  trialTenantId.value = tenantId;
  invalidateTrial();
}

function updateSampleValue(field: BusinessRuleEditableField, value: string) {
  if (applying.value) return;
  invalidateTrial();
  sampleValues.value = { ...sampleValues.value, [field.fieldName]: value };
}

function sampleBoolean(field: BusinessRuleEditableField): boolean {
  return sampleValues.value[field.fieldName] === 'true';
}

function sampleInputType(field: BusinessRuleEditableField) {
  if (['INTEGER', 'LONG', 'DECIMAL'].includes(field.valueType)) return 'number' as const;
  if (field.valueType === 'DATE') return 'date' as const;
  if (field.valueType === 'TIMESTAMP' || field.valueType === 'ZONED_TIMESTAMP')
    return 'datetime-local' as const;
  return 'text' as const;
}

async function runTrial() {
  if (!snapshot.value || applying.value || !trialTenantReady.value) return;
  const request = ++trialRequest;
  const revision = editRevision;
  trialRunning.value = true;
  const values = Object.fromEntries(
    [...trialInputFields.value, ...otherTrialFields.value].flatMap((field) => {
      const value = typedSampleValue(sampleValues.value[field.fieldName] ?? '', field.valueType);
      return value === undefined ? [] : [[field.fieldName, value]];
    }),
  );
  try {
    const trialHttp = trialTenantId.value
      ? withHttpHeaders(moduleContext.http, { 'X-MuYun-Tenant-Id': trialTenantId.value })
      : moduleContext.http;
    const result = await trialHttp.request<BusinessRuleTrialResult>({
      method: 'POST',
      path: `/platform.module/${encodeURIComponent(props.moduleAlias)}/business-rules/trial`,
      body: { rules: rules.value.filter((rule) => rule.kind !== 'UI_CONTROL'), sampleValues: values },
    });
    if (request !== trialRequest || revision !== editRevision) return;
    trial.value = result;
    capturedTrialInputs.value = values;
  } catch (cause) {
    if (request !== trialRequest || revision !== editRevision) return;
    presentPlatformError(cause, { source: 'business-rule-governance', phase: 'action' });
  } finally {
    if (request === trialRequest) trialRunning.value = false;
  }
}
</script>

<template>
  <section class="business-rule-governance">
    <UiSpin v-if="loading" class="business-rule-governance__state" tip="加载业务规则" />
    <div v-else-if="loadFailed" class="business-rule-governance__state">
      <UiEmpty description="业务规则加载失败" />
      <UiButton @click="() => loadSnapshot()">重试</UiButton>
    </div>
    <div v-else>
      <div class="business-rule-governance__content">
        <RecordQueryListSurface
          class="business-rule-governance__rule-list-surface"
          :title="ruleKindLabel(activeKind) + '规则'"
          :show-title="false"
          quick-search-visible
          :quick-search-value="ruleSearch"
          quick-search-placeholder="搜索业务名称、目标字段或条件"
          :quick-search-disabled="applying"
          :columns="ruleTableColumns"
          :rows="ruleTableRows"
          :table-visible="filteredActiveRules.length > 0"
          fill-height
          :horizontal-scroll="false"
          row-key="id"
          :selected-row-key="selectedCode"
          clickable-rows
          show-action-column
          action-column-title="操作"
          :action-column-width="120"
          :action-column-fixed="false"
          pageable
          :total="filteredActiveRules.length"
          :page-num="rulePageNum"
          :pages="rulePages"
          :page-size="rulePageSize"
          :pagination-disabled="applying"
          @update:quick-search-value="
            ruleSearch = $event;
            rulePageNum = 1;
          "
          @page-change="rulePageNum = $event"
          @page-size-change="
            rulePageSize = $event;
            rulePageNum = 1;
          "
          @row-click="selectRuleRow($event)"
          @row-dblclick="editRuleRow($event)"
        >
          <template #operations>
            <div v-if="hasUnsavedChanges" class="business-rule-governance__actions">
              <span class="business-rule-governance__change-state"
                >未应用 {{ unappliedChangeCount }} 项更改</span
              >
              <UiActionButton :disabled="applying" @click="discardChanges">放弃更改</UiActionButton>
              <UiActionButton emphasis="primary" :loading="applying" @click="applyRules"
                >应用更改</UiActionButton
              >
            </div>

            <UiActionButton
              emphasis="primary"
              :disabled="applying || (activeKind === 'CALCULATION' && Boolean(calculatorUnavailableReason))"
              :title="activeKind === 'CALCULATION' ? calculatorUnavailableReason : undefined"
              @click="addRule(activeKind)"
              >新增规则</UiActionButton
            >
            <UiActionButton
              v-if="rules.some((rule) => rule.enabled && !isRuleIncomplete(rule))"
              :disabled="!snapshot || applying"
              @click="openTrial"
              >试算整组规则</UiActionButton
            >
          </template>
          <template #persistentQueries>
            <RecordQueryEnumFilter
              title="规则类型"
              :value="activeKind"
              :options="[
                { value: 'CALCULATION', label: '字段计算' },
                { value: 'VALIDATION', label: '保存校验' },
                { value: 'UI_CONTROL', label: '界面控制' },
              ]"
              :disabled="applying"
              @update:value="selectRuleKind"
            />
          </template>
          <template #cell="{ column, record }">
            <RecordQueryListCell
              :record="record"
              :column="ruleListColumns.find((candidate) => candidate.key === column.key)!"
            />
          </template>
          <template #rowActions="{ record }">
            <UiActionButton density="compact" :disabled="applying" @click.stop="editRuleRow(record)"
              >编辑</UiActionButton
            >
            <UiActionButton
              density="compact"
              intent="danger"
              :disabled="applying"
              @click.stop="removeRuleRow(record)"
              >删除</UiActionButton
            >
          </template>
          <template #beforeTable>
            <UiEmpty
              v-if="filteredActiveRules.length === 0"
              :description="
                ruleSearch ? '没有匹配的规则。' : `暂无${ruleKindLabel(activeKind)}，可新增一条规则。`
              "
            />
          </template>
        </RecordQueryListSurface>

        <RecordDetailDrawer
          :open="ruleDrawerOpen"
          render-mode="inline"
          :width="980"
          :title="newRuleDraft ? '新增规则' : selectedRule ? ruleChoiceLabel(selectedRule) : '规则编辑'"
          close-title="返回规则列表"
          @close="closeRuleDrawer"
        >
          <template v-if="selectedRule" #title-actions>
            <UiSelect
              aria-label="新建规则类型"
              class="business-rule-governance__type-select"
              :value="selectedRule.kind"
              :allow-clear="false"
              :disabled="applying || !newRuleDraft"
              :options="[
                {
                  value: 'CALCULATION',
                  label: '字段计算',
                  disabled: Boolean(calculatorUnavailableReason),
                },
                { value: 'VALIDATION', label: '保存校验' },
                { value: 'UI_CONTROL', label: '界面控制' },
              ]"
              @update:value="changeDraftKind"
            />
          </template>
          <template v-if="selectedRule" #header-actions>
            <label class="business-rule-governance__enabled-setting">
              启用
              <UiSwitch
                :checked="selectedRule.enabled"
                :disabled="applying"
                @update:checked="updateRule('enabled', $event)"
              />
            </label>
          </template>
          <template #operation>
            <template v-if="newRuleDraft">
              <UiActionButton :disabled="applying" @click="closeRuleDrawer">取消</UiActionButton>
              <UiActionButton emphasis="primary" :disabled="applying" @click="saveNewRule"
                >保存</UiActionButton
              >
            </template>
            <UiButton v-else type="primary" :disabled="applying" @click="closeRuleDrawer">完成编辑</UiButton>
          </template>
          <div v-if="selectedRule" class="business-rule-governance__drawer-body">
            <aside class="business-rule-governance__field-directory">
              <MetadataSourceTree
                embedded
                v-model:search-keyword="fieldKeyword"
                v-model:show-system-fields="showSystemFields"
                v-model:expanded-keys="metadataExpandedKeys"
                :reload-key="metadataTreeReloadKey"
                :nodes="fieldTreeNodes"
                :selected-key="selectedFieldNodeKey"
                :loading="referenceRootLoading"
                :unavailable="referenceRootFailed"
                unavailable-description="字段目录加载失败，请刷新重试"
                :refresh-disabled="referenceRootLoading || applying"
                :load-children="loadReferenceChildren"
                :draggable="!applying"
                drag-payload-type="formula-field"
                :drag-payload-of="dragFormulaFieldPayload"
                :can-drag="canDragFormulaField"
                @refresh="loadReferenceRoot(true)"
                @select="selectFieldNode"
                @double-click="insertFieldNode($event.node)"
              />
            </aside>
            <div class="business-rule-governance__editor business-rule-governance__drawer-editor">
              <section
                v-if="selectedRule.kind === 'UI_CONTROL'"
                class="business-rule-governance__flow-section"
              >
                <label
                  >表单
                  <UiSelect
                    aria-label="控制表单"
                    :value="selectedRule.formKey"
                    placeholder="选择表单"
                    :options="
                      (uiControlSnapshot?.forms ?? []).map((form) => ({ value: form.key, label: form.title }))
                    "
                    :disabled="applying"
                    @update:value="setControlForm"
                  />
                </label>
                <p v-if="!uiControlSnapshot?.forms?.length">当前模块还没有可用表单，请先配置并发布表单。</p>
              </section>
              <section
                class="business-rule-governance__flow-section business-rule-governance__expression-step"
              >
                <div class="business-rule-governance__formula-heading">
                  <h2>公式</h2>
                </div>
                <section ref="functionPalette" class="business-rule-governance__function-panel">
                  <section v-for="group in functionCapabilityGroups" :key="group.category">
                    <h3>{{ group.category }}</h3>
                    <div class="business-rule-governance__function-list">
                      <div
                        v-for="capability in group.capabilities"
                        :key="capability.id"
                        class="business-rule-governance__function-option"
                      >
                        <div
                          :class="[
                            'business-rule-governance__function-chip',
                            {
                              'business-rule-governance__function-chip--dragging':
                                draggingFunctionId === capability.id,
                            },
                          ]"
                        >
                          <div
                            role="button"
                            :tabindex="applying ? -1 : 0"
                            :aria-disabled="applying"
                            :data-formula-function="capability.id"
                            :title="capability.signature"
                            class="business-rule-governance__function-token"
                            @mousedown="beginFunctionDrag(capability.id, $event)"
                            @keydown.space="beginFunctionDrag(capability.id, $event)"
                            @keydown.enter.prevent="insertFunction(capability)"
                            @click="insertFunction(capability)"
                          >
                            {{ functionPurpose(capability) }}
                          </div>
                          <UiPopover placement="bottomLeft">
                            <template #content>
                              <div class="business-rule-governance__function-help-popover">
                                <strong>{{ functionPurpose(capability) }}</strong>
                                <span class="business-rule-governance__function-help-purpose">{{
                                  capability.description
                                }}</span>
                                <span class="business-rule-governance__function-help-label">使用示例</span>
                                <code>{{ capability.example }}</code>
                                <span class="business-rule-governance__function-help-label">语法</span>
                                <code>{{ capability.signature }}</code>
                                <span v-for="parameter in capability.parameters" :key="parameter.name"
                                  >{{ parameter.name }}：{{ parameter.description }}</span
                                >
                              </div>
                            </template>
                            <UiButton
                              size="small"
                              type="text"
                              class="business-rule-governance__function-help-trigger"
                              :aria-label="`${functionPurpose(capability)}的帮助`"
                              :title="`${functionPurpose(capability)}的帮助`"
                            >
                              <UiIcon name="help" />
                            </UiButton>
                          </UiPopover>
                        </div>
                      </div>
                    </div>
                  </section>
                  <p v-if="functionCapabilityGroups.length === 0" class="business-rule-governance__empty">
                    未找到函数
                  </p>
                </section>

                <div class="business-rule-governance__expression-layout">
                  <section class="business-rule-governance__expression">
                    <div class="business-rule-governance__expression-input-shell">
                      <label class="business-rule-governance__formula-input-label">
                        {{
                          selectedRule.kind === 'CALCULATION'
                            ? '填写计算方式'
                            : selectedRule.kind === 'UI_CONTROL'
                              ? '填写触发界面控制的公式'
                              : '填写允许保存的条件'
                        }}
                        <FormulaExpressionEditor
                          v-show="formulaMode === 'visual'"
                          class="business-rule-governance__formula-visual-input"
                          :key="`${moduleAlias}:${selectedRule.code}:${selectedRule.kind}`"
                          ref="visualExpressionEditor"
                          :value="selectedFormula"
                          :fields="
                            [...catalogFields.values()].map((field) => ({
                              name: field.name,
                              label: field.label || field.name,
                              valueType: field.valueType,
                              available: !formulaFieldUnusableReason(field),
                              unavailableReason: formulaFieldUnusableReason(field),
                            }))
                          "
                          :functions="
                            formulaCapabilities.map((item) => ({
                              name: item.id,
                              title: functionPurpose(item),
                            }))
                          "
                          placeholder="拖入字段，或直接开始输入公式…"
                          :disabled="applying"
                          :accept-drop="acceptsFormulaItem"
                          @selection="rememberExpressionSelection"
                          @drop="handleFormulaDrop"
                          @update:value="updateRule('expression', $event)"
                        />
                        <UiTextArea
                          v-show="formulaMode === 'code'"
                          class="business-rule-governance__formula-code-input"
                          ref="expressionEditor"
                          :value="selectedFormula"
                          :placeholder="
                            selectedRule.kind === 'CALCULATION'
                              ? '例如：{amount} = {quantity} * {unitPrice}'
                              : '从左侧插入字段，或点击上方常用函数'
                          "
                          :disabled="applying"
                          :accept-drop="acceptsFormulaItem"
                          @selection="rememberExpressionSelection"
                          @drop="handleFormulaDrop"
                          @update:value="updateRule('expression', $event)"
                        />
                      </label>
                      <div class="business-rule-governance__code-mode-toggle">
                        <span>代码模式</span>
                        <UiSwitch
                          aria-label="代码模式"
                          size="small"
                          :checked="formulaMode === 'code'"
                          :disabled="applying"
                          @update:checked="switchFormulaMode"
                        />
                      </div>
                    </div>
                    <p class="business-rule-governance__formula-hint">
                      拖入字段或函数，直接输入 =、*、括号等符号。也可以双击左侧字段插入光标位置。
                    </p>
                    <p
                      v-if="
                        referencedFormulaFields(selectedRule.expression).some((field) => field.includes('.'))
                      "
                      class="business-rule-governance__cursor-help"
                    >
                      引用字段由服务端读取，保存时计算；可先试算。
                    </p>
                  </section>
                </div>

                <section v-if="selectedRuleTemplates.length" class="business-rule-governance__templates">
                  <h3>从示例开始</h3>
                  <p v-if="selectedRule.expression.trim()" class="business-rule-governance__empty">
                    已有表达式不会被模板覆盖；清空后可使用模板。
                  </p>
                  <UiButton
                    v-for="template in selectedRuleTemplates"
                    :key="template.id"
                    size="small"
                    :title="template.help"
                    :disabled="applying || Boolean(selectedRule.expression.trim())"
                    @click="applyTemplate(template.expression)"
                  >
                    {{ template.label }}
                  </UiButton>
                </section>
              </section>
              <section
                v-if="selectedRule.kind === 'UI_CONTROL'"
                class="business-rule-governance__flow-section"
              >
                <h2>控制界面</h2>
                <template v-if="selectedControlForm">
                  <p>公式为真时，对勾选的元素执行以下控制。</p>
                  <div
                    v-for="element in selectedControlForm.elements"
                    :key="element.key"
                    class="business-rule-governance__control-target"
                  >
                    <span>{{ element.label }}</span>
                    <UiCheckbox
                      :aria-label="element.label + '隐藏'"
                      :checked="controlEffect(element.key, 'hide')"
                      :disabled="applying"
                      @update:checked="setControlEffect(element.key, 'hide', $event)"
                      >隐藏</UiCheckbox
                    >
                    <UiCheckbox
                      :aria-label="element.label + '只读'"
                      :checked="controlEffect(element.key, 'readOnly')"
                      :disabled="applying"
                      @update:checked="setControlEffect(element.key, 'readOnly', $event)"
                      >只读</UiCheckbox
                    >
                  </div>
                </template>
              </section>
              <section
                v-if="selectedRule.kind === 'VALIDATION'"
                class="business-rule-governance__flow-section business-rule-governance__purpose"
              >
                <div>
                  <h2>未通过时的提示</h2>
                  <p v-if="selectedRule.kind === 'VALIDATION'">公式为真时允许保存，为假时显示失败提示。</p>
                </div>
                <div class="business-rule-governance__rule-basics">
                  <details class="business-rule-governance__validation-location">
                    <summary>提示位置（可选）</summary>
                    <label>
                      定位字段（可选）
                      <UiSelect
                        :value="selectedRule.targetField || '__none__'"
                        :options="locatorFieldOptions"
                        :disabled="applying"
                        @update:value="updateRule('targetField', $event)"
                      />
                    </label>
                  </details>
                  <label>
                    失败提示
                    <UiInput
                      :value="selectedRule.messageTemplate"
                      :disabled="applying"
                      @update:value="updateRule('messageTemplate', $event)"
                    />
                  </label>
                </div>
              </section>

              <p
                v-for="issue in draftIssues"
                :key="issue.code + ':' + issue.field"
                role="alert"
                class="business-rule-governance__error"
              >
                {{ issueText(issue) }}
              </p>
              <UiButton
                v-if="!newRuleDraft"
                class="business-rule-governance__delete-rule"
                size="small"
                danger
                :disabled="applying"
                @click="removeSelectedRule"
                >删除规则</UiButton
              >
            </div>
          </div>
        </RecordDetailDrawer>

        <section v-if="readonlyRuleList.length" class="business-rule-governance__readonly-summary">
          <strong>保留的只读规则</strong>
          <span v-for="rule in readonlyRuleList" :key="rule.code"
            >{{ rule.code }}：{{ rule.readOnlyReason || '当前规则不属于首期可编辑范围。' }}</span
          >
        </section>

        <section
          v-if="applicationIssues.length"
          class="business-rule-governance__result"
          data-testid="business-rule-issues"
        >
          <h3>无法应用更改</h3>
          <ul>
            <li v-for="issue in applicationIssues" :key="`${issue.code}:${issue.ruleCode}:${issue.field}`">
              <button
                v-if="issue.ruleCode && rules.some((rule) => rule.code === issue.ruleCode)"
                type="button"
                class="business-rule-governance__issue-link"
                @click="selectIssueRule(issue)"
              >
                {{ issue.ruleCode }}：{{ issueText(issue) }}
              </button>
              <span v-else>{{ issueText(issue) }}</span>
            </li>
          </ul>
        </section>
        <p v-if="applicationError" class="business-rule-governance__error">{{ applicationError }}</p>
      </div>
    </div>

    <UiModal
      :open="trialVisible"
      title="样例试算"
      :width="680"
      confirm-text="试算"
      :confirm-loading="trialRunning"
      :confirm-disabled="applying || !trialTenantReady"
      @confirm="runTrial"
      @cancel="trialVisible = false"
    >
      <p>使用当前编辑中的整组启用规则试算，先完成字段计算，再执行校验；不会写入业务记录。</p>
      <label v-if="referenceTrialRequiresTenant" class="business-rule-governance__trial-tenant">
        业务租户
        <UiSelect
          aria-label="业务租户"
          :value="trialTenantId || undefined"
          :options="trialTenants.map((tenant) => ({ value: tenant.id, label: tenant.title ?? tenant.id }))"
          :loading="trialTenantLoading"
          placeholder="请选择引用读取所在租户"
          show-search
          :filter-option="false"
          @search="loadTrialTenants"
          @update:value="changeTrialTenant"
        />
        <span v-if="trialTenantLoading">加载租户中</span>
        <span v-else-if="!trialTenantError && trialTenants.length === 0">没有可访问的活跃租户</span>
      </label>
      <p v-if="trialTenantError" role="alert" class="business-rule-governance__error">
        {{ trialTenantError }} <UiButton size="small" @click="loadTrialTenants()">重试加载租户</UiButton>
      </p>
      <p
        v-if="referenceTrialRequiresTenant && !trialTenantReady"
        role="status"
        class="business-rule-governance__empty"
      >
        请选择业务租户后再读取引用字段试算。
      </p>
      <p v-if="trialInputFields.length === 0" class="business-rule-governance__empty">
        当前启用规则没有需要输入的外部字段，可直接试算。
      </p>
      <div class="business-rule-governance__sample-grid">
        <label v-for="field in trialInputFields" :key="field.fieldName">
          {{ fieldLabel(field) }}
          <small
            v-if="referenceTrialInputFields.some((candidate) => candidate.fieldName === field.fieldName)"
          >
            请输入引用记录 ID；点路径字段由服务端读取。
          </small>
          <UiSwitch
            v-if="field.valueType === 'BOOLEAN'"
            :checked="sampleBoolean(field)"
            checked-text="是"
            unchecked-text="否"
            :disabled="applying"
            @update:checked="updateSampleValue(field, $event ? 'true' : 'false')"
          />
          <UiInput
            v-else
            :value="sampleValues[field.fieldName]"
            :disabled="applying"
            :type="sampleInputType(field)"
            @update:value="updateSampleValue(field, $event)"
          />
        </label>
      </div>
      <details v-if="otherTrialFields.length" class="business-rule-governance__other-inputs">
        <summary>其他字段（非当前启用规则的外部依赖）</summary>
        <div class="business-rule-governance__sample-grid">
          <label v-for="field in otherTrialFields" :key="field.fieldName">
            {{ fieldLabel(field) }}
            <UiSwitch
              v-if="field.valueType === 'BOOLEAN'"
              :checked="sampleBoolean(field)"
              checked-text="是"
              unchecked-text="否"
              :disabled="applying"
              @update:checked="updateSampleValue(field, $event ? 'true' : 'false')"
            />
            <UiInput
              v-else
              :value="sampleValues[field.fieldName]"
              :disabled="applying"
              :type="sampleInputType(field)"
              @update:value="updateSampleValue(field, $event)"
            />
          </label>
        </div>
      </details>
      <section v-if="trial" class="business-rule-governance__result" data-testid="business-rule-trial">
        <h3>本次输入、最终计算值与校验结果</h3>
        <dl v-if="Object.keys(capturedTrialInputs).length">
          <template
            v-for="field in [...editableFields, ...referenceTrialInputFields].filter(
              (field) => field.fieldName in capturedTrialInputs,
            )"
            :key="field.fieldName"
          >
            <dt>{{ fieldLabel(field) }}</dt>
            <dd>输入：{{ String(capturedTrialInputs[field.fieldName] ?? '') }}</dd>
          </template>
        </dl>
        <dl v-if="trial.changedFields.length">
          <template v-for="fieldName in trial.changedFields" :key="fieldName">
            <dt>{{ fieldTitle(fieldName) }}</dt>
            <dd>最终值：{{ String(trial.values[fieldName] ?? '') }}</dd>
          </template>
        </dl>
        <dl v-if="snapshot?.referenceFields?.some((field) => field.path in (trial?.values ?? {}))">
          <template v-for="field in snapshot?.referenceFields ?? []" :key="field.path">
            <template v-if="field.path in (trial?.values ?? {})">
              <dt>{{ field.title }}（{{ field.path }}）</dt>
              <dd>引用读取值：{{ String(trial?.values[field.path] ?? '') }}</dd>
            </template>
          </template>
        </dl>
        <p v-if="trial.errors.length">试算未通过。</p>
        <p v-else-if="hasEnabledValidationRules">试算通过：未发现校验错误。</p>
        <p v-else>试算完成：当前没有启用的业务校验。</p>
        <ul v-if="trial.errors.length">
          <li v-for="error in trial.errors" :key="`${error.code}:${error.ruleCode}:${error.field}`">
            <button
              v-if="error.ruleCode && rules.some((rule) => rule.code === error.ruleCode)"
              type="button"
              class="business-rule-governance__issue-link"
              @click="selectTrialIssue(error)"
            >
              {{ error.ruleCode }}：{{ issueText(error) }}
            </button>
            <span v-else>{{ issueText(error) }}</span>
          </li>
        </ul>
      </section>
    </UiModal>
  </section>
</template>

<style scoped>
.business-rule-governance__control-target {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 0;
}
.business-rule-governance__control-target > span {
  flex: 1;
}

.business-rule-governance,
.business-rule-governance > :not(.business-rule-governance__state) {
  min-height: 0;
  height: 100%;
}

.business-rule-governance__state {
  display: grid;
  place-items: center;
  gap: 12px;
  min-height: 220px;
}

.business-rule-governance__editor,
.business-rule-governance__sample-grid {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.business-rule-governance__templates,
.business-rule-governance__result {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.business-rule-governance__editor {
  align-content: start;
}

.business-rule-governance__templates h3,
.business-rule-governance__result h3 {
  margin: 0;
  font-size: 13px;
}

.business-rule-governance__empty,
.business-rule-governance__other-inputs {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.business-rule-governance__editor > label,
.business-rule-governance__sample-grid > label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-secondary);
  font-size: 13px;
}

.business-rule-governance__error,
.business-rule-governance__result p {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
}

.business-rule-governance__error {
  color: var(--muyun-danger);
}

.business-rule-governance__issue-link {
  padding: 0;
  border: 0;
  color: var(--muyun-primary);
  background: transparent;
  cursor: pointer;
  text-align: left;
  text-decoration: underline;
}

.business-rule-governance__other-inputs {
  display: grid;
  gap: 8px;
}

.business-rule-governance__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}

.business-rule-governance__content {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  min-height: 0;
}

.business-rule-governance__rule-list-surface {
  flex: 1;
  min-height: 0;
}

.business-rule-governance__drawer-body {
  display: grid;
  grid-template-columns: minmax(280px, 0.4fr) minmax(0, 1fr);
  height: 100%;
  min-height: 0;
}

.business-rule-governance__field-directory {
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  align-content: start;
  gap: 8px;
  min-height: 0;
  padding: 14px 14px 14px 0;
  border-right: 1px solid var(--muyun-border-subtle);
  overflow: hidden;
}

.business-rule-governance__field-directory h3 {
  margin: 0;
  font-size: 14px;
}

.business-rule-governance__drawer-editor {
  align-content: start;
  padding: 14px;
  overflow: auto;
}

.business-rule-governance__formula-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.business-rule-governance__function-chip {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  gap: 6px;
  padding: 1px 5px 1px 10px;
  border: 1px solid color-mix(in srgb, var(--muyun-primary) 24%, transparent);
  border-radius: 7px;
  background: color-mix(in srgb, var(--muyun-primary) 7%, var(--muyun-surface));
  color: var(--muyun-primary);
  transition:
    border-color 120ms ease,
    background 120ms ease,
    box-shadow 120ms ease;
}
.business-rule-governance__function-chip:hover:not(
    :has(.business-rule-governance__function-token[aria-disabled='true'])
  ) {
  border-color: color-mix(in srgb, var(--muyun-primary) 44%, transparent);
  background: color-mix(in srgb, var(--muyun-primary) 12%, var(--muyun-surface));
  box-shadow: 0 1px 2px rgb(15 23 42 / 8%);
}
.business-rule-governance__function-token {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 1px;
  cursor: grab;
  user-select: none;
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
}
.business-rule-governance__function-token:focus-visible {
  outline: 2px solid var(--muyun-primary);
}
.business-rule-governance__function-chip:has(
  .business-rule-governance__function-token[aria-disabled='true']
) {
  opacity: 0.5;
  cursor: default;
}
.business-rule-governance__function-chip--dragging {
  opacity: 0.55;
  cursor: grabbing;
}
.business-rule-governance__function-help-trigger {
  width: 22px;
  min-width: 22px;
  height: 22px;
  padding: 0;
  border-radius: 50%;
  color: var(--muyun-primary);
  font-size: 13px;
  line-height: 20px;
}
.business-rule-governance__function-help-trigger:hover {
  color: color-mix(in srgb, var(--muyun-primary) 88%, var(--muyun-text-primary));
}
.business-rule-governance__function-help-trigger :deep(svg) {
  width: 13px;
  height: 13px;
}
.business-rule-governance__formula-hint {
  color: var(--muyun-text-secondary);
  font-size: 12px;
}

.business-rule-governance__delete-rule {
  justify-self: start;
}

.business-rule-governance__change-state {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.business-rule-governance__flow-section {
  display: grid;
  gap: 10px;
  min-width: 0;
  margin: 0;
  padding: 16px 0;
  border-top: 1px solid var(--muyun-border-subtle);
}

/* The formula starts beside the field directory for calculation and validation
 * rules. It is the first editor section in those cases, so it shares the
 * directory's top baseline instead of looking like a following section. */
.business-rule-governance__drawer-editor > .business-rule-governance__expression-step:first-child {
  padding-top: 0;
  border-top: 0;
}

.business-rule-governance__flow-section h2,
.business-rule-governance__flow-section p {
  margin: 0;
}

.business-rule-governance__flow-section h2 {
  font-size: 14px;
}

.business-rule-governance__flow-section p {
  color: var(--muyun-text-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.business-rule-governance__sample-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.business-rule-governance__result ul,
.business-rule-governance__result dl {
  margin: 0;
}

.business-rule-governance__result dl {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 4px 12px;
}

.business-rule-governance__result dt {
  color: var(--muyun-text-secondary);
}

.business-rule-governance__result dd {
  margin: 0;
}

.business-rule-governance__tree-help,
.business-rule-governance__cursor-help,
.business-rule-governance__readonly-summary {
  color: var(--muyun-text-muted);
  font-size: 12px;
}

.business-rule-governance__tree-help,
.business-rule-governance__cursor-help {
  margin: 0 0 8px;
  overflow-wrap: anywhere;
}

.business-rule-governance__field-tree {
  min-height: 0;
  height: 100%;
}

.business-rule-governance__readonly-summary,
.business-rule-governance__function-help-popover {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 8px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
}

.business-rule-governance__enabled-setting {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--muyun-text-secondary);
}

.business-rule-governance__type-select {
  min-width: 140px;
}

.business-rule-governance__rule-basics {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 10px;
}

.business-rule-governance__expression-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  min-width: 0;
}

.business-rule-governance__expression,
.business-rule-governance__function-panel {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.business-rule-governance__expression-input-shell {
  position: relative;
  min-width: 0;
  --formula-editor-height: 120px;
}

.business-rule-governance__formula-input-label {
  display: grid;
  gap: 4px;
}

.business-rule-governance__code-mode-toggle {
  position: absolute;
  z-index: 1;
  top: 34px;
  right: 12px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 28px;
  padding: 2px 5px 2px 9px;
  border: 1px solid color-mix(in srgb, var(--muyun-primary) 12%, var(--muyun-border-subtle));
  border-radius: 999px;
  background: color-mix(in srgb, var(--muyun-primary) 4%, var(--muyun-surface));
  color: var(--muyun-text-secondary);
  font-size: 12px;
  line-height: 20px;
}

.business-rule-governance__formula-visual-input :deep(.ui-token-input__editor),
.business-rule-governance__formula-code-input :deep(textarea) {
  box-sizing: border-box;
  height: var(--formula-editor-height);
  min-height: var(--formula-editor-height);
  max-height: var(--formula-editor-height);
  overflow-y: auto;
  resize: none;
}

.business-rule-governance__function-panel {
  align-content: start;
  padding: 8px 0;
}

.business-rule-governance__function-panel h3 {
  margin: 0 0 4px;
  color: var(--muyun-text-muted);
  font-size: 11px;
  font-weight: 600;
}

.business-rule-governance__function-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.business-rule-governance__function-option {
  display: flex;
  align-items: center;
  gap: 4px;
}

.business-rule-governance__validation-location {
  order: 1;
}

.business-rule-governance__drawer-editor details {
  color: var(--muyun-text-secondary);
  font-size: 12px;
}

.business-rule-governance__drawer-body summary {
  cursor: pointer;
}

.business-rule-governance__function-help-popover code {
  overflow-wrap: anywhere;
}
.business-rule-governance__function-help-purpose {
  color: var(--muyun-text-secondary);
  line-height: 1.55;
}
.business-rule-governance__function-help-label {
  margin-top: 3px;
  color: var(--muyun-text-secondary);
  font-size: 12px;
}

.business-rule-governance__readonly-summary {
  margin-top: 12px;
}

.business-rule-governance__trial-tenant {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-secondary);
  font-size: 13px;
}

@media (max-width: 860px) {
  .business-rule-governance__expression-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .business-rule-governance__rule-basics {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }

  .business-rule-governance__drawer-body {
    grid-template-columns: minmax(0, 1fr);
  }

  .business-rule-governance__field-directory {
    grid-template-rows: minmax(120px, 1fr);
    max-height: 300px;
    border-right: 0;
    border-bottom: 1px solid var(--muyun-border-subtle);
  }
}

@media (max-width: 560px) {
  .business-rule-governance__rule-basics {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
