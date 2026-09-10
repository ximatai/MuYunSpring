<script setup lang="ts">
import { computed, nextTick, ref, watch, type ComponentPublicInstance } from 'vue';
import {
  RecordDetailDrawer,
  RecordQueryListCell,
  RecordQueryListSurface,
  presentPlatformError,
  presentPlatformMessage,
  type RecordQueryListColumn,
} from '@muyun/platform-components';
import RecordDetailLayout from '../platform-components/RecordDetailLayout.vue';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import { useModuleContext, withHttpHeaders } from '@muyun/web-core';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import {
  UiButton,
  UiEmpty,
  UiInput,
  UiModal,
  UiSelect,
  UiSpin,
  UiSwitch,
  UiTextArea,
  UiTree,
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
  normalizeFormulaCapabilities,
  portableFormulaCapabilities,
  searchableFormulaCapabilities,
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
const selectedCode = ref<string>();
const ruleDrawerOpen = ref(false);
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
const functionPanelOpen = ref(false);
const functionSearch = ref('');
const inspectedFunctionId = ref<BusinessRuleFormulaCapability['id']>('PRESENT');
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
let baselineProposalFingerprint: string | undefined;
let loadRequest = 0;
let trialRequest = 0;
let applyRequest = 0;
let editRevision = 0;
let expressionSelectionOwner: string | undefined;
let referenceDirectoryEpoch = 0;
let trialTenantRequest = 0;

const editableFields = computed(() => snapshot.value?.editableFields ?? []);
const calculationRules = computed(() => rules.value.filter((rule) => rule.kind === 'CALCULATION'));
const validationRules = computed(() => rules.value.filter((rule) => rule.kind === 'VALIDATION'));
const activeRules = computed(() =>
  activeKind.value === 'CALCULATION' ? calculationRules.value : validationRules.value,
);
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
const selectedRule = computed(() => rules.value.find((rule) => rule.code === selectedCode.value));
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
  referenceDirectories.value.forEach((directory) =>
    directory.forEach((field) => fields.set(field.name, field)),
  );
  return fields;
});
const fieldTreeNodes = computed(() => referenceRootFields.value.map(referenceFieldNode));
const selectedCatalogField = computed(() =>
  selectedFieldNodeKey.value ? fieldOfTreeKey(selectedFieldNodeKey.value) : undefined,
);
const formulaCapabilities = computed(() =>
  normalizeFormulaCapabilities(
    props.formulaCapabilities ?? snapshot.value?.functions ?? portableFormulaCapabilities,
  ),
);
const filteredFormulaCapabilities = computed(() =>
  searchableFormulaCapabilities(formulaCapabilities.value, functionSearch.value),
);
const functionCapabilityGroups = computed(() => {
  const groups = new Map<string, BusinessRuleFormulaCapability[]>();
  filteredFormulaCapabilities.value.forEach((capability) => {
    const current = groups.get(capability.category) ?? [];
    current.push(capability);
    groups.set(capability.category, current);
  });
  return [...groups.entries()].map(([category, capabilities]) => ({ category, capabilities }));
});
const inspectedFunction = computed(
  () =>
    formulaCapabilities.value.find((capability) => capability.id === inspectedFunctionId.value) ??
    filteredFormulaCapabilities.value[0],
);
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
const externalInputFields = computed(() => externalTrialInputFields(editableFields.value, rules.value));
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
    ? formulaTemplates(selectedRule.value.kind, editableFields.value, selectedRule.value.targetField)
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
  ruleSearch.value = '';
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

function updateRule(field: keyof BusinessRuleProposal, value: unknown) {
  if (applying.value) return;
  const code = selectedCode.value;
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
  replaceRules(
    rules.value.map((rule) =>
      rule.code === code
        ? {
            ...rule,
            [field]: normalized,
          }
        : rule,
    ),
  );
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
  const rule = newBusinessRule(kind, [...rules.value, ...readonlyRuleList.value]);
  replaceRules([...rules.value, rule]);
  activeKind.value = kind;
  ruleSearch.value = '';
  rulePageNum.value = Math.max(
    1,
    Math.ceil(rules.value.filter((candidate) => candidate.kind === kind).length / rulePageSize.value),
  );
  selectedCode.value = rule.code;
  ruleDrawerOpen.value = true;
}

function removeSelectedRule() {
  if (applying.value) return;
  const code = selectedCode.value;
  if (!code) return;
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
      ? expressionEditor.value?.selection?.()
      : expressionSelections.get(rule.code);
  const insertion = insertFormulaText(
    rule.expression,
    selectedStart ?? selection?.start,
    selectedEnd ?? selection?.end,
    text,
  );
  replaceRules(
    rules.value.map((candidate) =>
      candidate.code === rule.code ? { ...candidate, expression: insertion.value } : candidate,
    ),
  );
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
  expressionSelectionOwner = selectedCode.value;
  if (selectedCode.value) expressionSelections.set(selectedCode.value, selection);
}

function applyTemplate(expression: string) {
  if (applying.value || !selectedRule.value || selectedRule.value.expression.trim()) return;
  updateRule('expression', expression);
  void nextTick(() => focusExpression(expression.length));
}

function focusExpression(start: number, end = start) {
  expressionEditor.value?.focusSelection?.(start, end);
}

function referenceDirectoryKey(moduleAlias: string, path: string) {
  return `${moduleAlias}:${path}`;
}

function referenceFieldNode(field: BusinessRuleReferenceField): UiTreeNode {
  const reason = formulaFieldUnusableReason(field);
  return {
    key: `formula-field:${field.name}`,
    title: field.label || field.name.split('.').at(-1) || field.name,
    secondary: [field.name, fieldTypeLabel(field), reason].filter(Boolean).join(' · '),
    disabled: Boolean(reason),
    muted: Boolean(reason),
    isLeaf: !(field.expandable === true && field.referenceCardinality === 'ONE' && !reason),
  };
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
  insertExpressionText(`{${field.name}}`);
}

function handleFormulaDrop(event: { source: UiDragSource; selection: { start: number; end: number } }) {
  const payload = event.source.payload as
    | { kind?: unknown; moduleAlias?: unknown; fieldName?: unknown }
    | undefined;
  if (
    !acceptsFormulaField(event.source) ||
    payload?.kind !== 'formula-field' ||
    typeof payload.fieldName !== 'string'
  )
    return;
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
  if (
    !field ||
    field.expandable !== true ||
    field.referenceCardinality !== 'ONE' ||
    formulaFieldUnusableReason(field)
  )
    return { mode: 'replace', nodes: [], hasMore: false };
  const moduleAlias = props.moduleAlias;
  const epoch = referenceDirectoryEpoch;
  const fields = await loadReferenceDirectory(moduleAlias, field.name);
  if (request.signal.aborted || epoch !== referenceDirectoryEpoch || moduleAlias !== props.moduleAlias)
    return { mode: 'replace', nodes: [], hasMore: false };
  return { mode: 'replace', nodes: fields.map(referenceFieldNode), hasMore: false };
}

async function ensureReferencePaths(fieldNames: readonly string[]) {
  const moduleAlias = props.moduleAlias;
  for (const fieldName of fieldNames) {
    const parts = fieldName.split('.');
    for (let index = 1; index < parts.length; index += 1) {
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

function selectMode(kind: BusinessRuleKind) {
  activeKind.value = kind;
  ruleDrawerOpen.value = false;
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
  ruleDrawerOpen.value = false;
}

async function loadSnapshot(force = false) {
  if (applying.value && !force) return;
  const request = ++loadRequest;
  resetExecutionResults();
  snapshot.value = undefined;
  baselineProposalFingerprint = undefined;
  rules.value = [];
  selectedCode.value = undefined;
  expressionSelections.clear();
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
    snapshot.value = loaded;
    const editable = editableProposals(loaded);
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
    const missingTarget = rule.kind === 'CALCULATION' && !rule.targetField?.trim();
    const missingExpression = !rule.expression.trim();
    if (!missingTarget && !missingExpression) return [];
    return [
      {
        code: 'INCOMPLETE_RULE',
        ruleCode: rule.code,
        message: [
          missingTarget ? '请选择计算目标字段' : undefined,
          missingExpression ? '请补充表达式' : undefined,
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
      body: { rules: proposedRules },
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
        rules: proposedRules,
        baselineFingerprint,
        proposalFingerprint: checked.proposalFingerprint,
      },
    });
    if (!isCurrent()) return;
    snapshot.value = result.snapshot;
    const editable = editableProposals(result.snapshot);
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
      body: { rules: rules.value, sampleValues: values },
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
      <RecordDetailLayout :title="moduleTitle ? `${moduleTitle} · 业务规则` : '业务规则'" scrollable-content>
        <template v-if="hasUnsavedChanges" #actions>
          <div class="business-rule-governance__actions">
            <span class="business-rule-governance__change-state"
              >未应用 {{ unappliedChangeCount }} 项更改</span
            >
            <UiButton :disabled="applying" @click="discardChanges">放弃更改</UiButton>
            <UiButton type="primary" :loading="applying" @click="applyRules">应用更改</UiButton>
          </div>
        </template>

        <div class="business-rule-governance__mode-tabs" role="tablist" aria-label="规则类型">
          <button
            type="button"
            role="tab"
            :aria-selected="activeKind === 'CALCULATION'"
            :class="{ 'business-rule-governance__mode-tab--active': activeKind === 'CALCULATION' }"
            @click="selectMode('CALCULATION')"
          >
            字段计算
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activeKind === 'VALIDATION'"
            :class="{ 'business-rule-governance__mode-tab--active': activeKind === 'VALIDATION' }"
            @click="selectMode('VALIDATION')"
          >
            业务校验
          </button>
        </div>

        <RecordQueryListSurface
          class="business-rule-governance__rule-list-surface"
          :title="activeKind === 'CALCULATION' ? '字段计算规则' : '业务校验规则'"
          :show-title="false"
          quick-search-visible
          :quick-search-value="ruleSearch"
          quick-search-placeholder="搜索业务名称、目标字段或条件"
          :quick-search-disabled="applying"
          :columns="ruleTableColumns"
          :rows="ruleTableRows"
          :table-visible="filteredActiveRules.length > 0"
          :fill-height="false"
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
            <UiButton
              size="small"
              :disabled="applying || (activeKind === 'CALCULATION' && Boolean(calculatorUnavailableReason))"
              :title="activeKind === 'CALCULATION' ? calculatorUnavailableReason : undefined"
              @click="addRule(activeKind)"
              >新增规则</UiButton
            >
            <UiButton
              v-if="rules.some((rule) => rule.enabled && !isRuleIncomplete(rule))"
              size="small"
              :disabled="!snapshot || applying"
              @click="openTrial"
              >试算整组规则</UiButton
            >
          </template>
          <template #cell="{ column, record }">
            <RecordQueryListCell
              :record="record"
              :column="ruleListColumns.find((candidate) => candidate.key === column.key)!"
            />
          </template>
          <template #rowActions="{ record }">
            <UiButton size="small" :disabled="applying" @click.stop="editRuleRow(record)">编辑</UiButton>
            <UiButton size="small" danger :disabled="applying" @click.stop="removeRuleRow(record)"
              >删除</UiButton
            >
          </template>
          <template #beforeTable>
            <UiEmpty
              v-if="filteredActiveRules.length === 0"
              :description="
                ruleSearch
                  ? '没有匹配的规则。'
                  : `暂无${activeKind === 'CALCULATION' ? '字段计算' : '业务校验'}，可新增一条规则。`
              "
            />
          </template>
        </RecordQueryListSurface>

        <RecordDetailDrawer
          :open="ruleDrawerOpen"
          render-mode="inline"
          :width="980"
          :title="selectedRule ? ruleChoiceLabel(selectedRule) : '规则编辑'"
          subtitle="关闭抽屉仅返回列表；未应用更改会保留在本次会话中。"
          close-title="返回规则列表"
          @close="closeRuleDrawer"
        >
          <template #operation>
            <UiButton type="primary" :disabled="applying" @click="closeRuleDrawer">完成编辑</UiButton>
          </template>
          <div v-if="selectedRule" class="business-rule-governance__drawer-body">
            <aside class="business-rule-governance__field-directory">
              <h3>字段目录</h3>
              <p class="business-rule-governance__tree-help">
                双击字段或拖到表达式中插入。引用路径只展开 ONE 关系；集合、JSON、系统和受保护字段会说明原因。
              </p>
              <UiSpin v-if="referenceRootLoading" tip="加载字段目录" />
              <template v-else-if="referenceRootFailed">
                <UiEmpty description="字段目录加载失败" />
                <UiButton size="small" @click="loadReferenceRoot(true)">重试</UiButton>
              </template>
              <UiTree
                v-else
                class="business-rule-governance__field-tree"
                :nodes="fieldTreeNodes"
                :selected-key="selectedFieldNodeKey"
                :load-children="loadReferenceChildren"
                :draggable="true"
                drag-payload-type="formula-field"
                :drag-payload-of="dragFormulaFieldPayload"
                :can-drag="canDragFormulaField"
                empty-description="暂无可展示字段"
                @select="selectFieldNode"
                @double-click="insertFieldNode($event.node)"
              />
              <section v-if="selectedCatalogField" class="business-rule-governance__field-inspector">
                <strong>{{ selectedCatalogField.label || selectedCatalogField.name }}</strong>
                <span>路径：{{ selectedCatalogField.name }}</span>
                <span>类型：{{ fieldTypeLabel(selectedCatalogField) }}</span>
                <span v-if="formulaFieldUnusableReason(selectedCatalogField)" role="status">
                  {{ formulaFieldUnusableReason(selectedCatalogField) }}
                </span>
                <UiButton
                  v-else
                  size="small"
                  :disabled="applying"
                  @click="insertFieldNode({ key: selectedFieldNodeKey!, title: '' })"
                  >插入字段</UiButton
                >
              </section>
            </aside>
            <div class="business-rule-governance__editor business-rule-governance__drawer-editor">
              <section class="business-rule-governance__flow-section business-rule-governance__purpose">
                <div>
                  <h2>业务目的</h2>
                  <p v-if="selectedRule.kind === 'CALCULATION'">先选择要由表达式计算并写入的目标字段。</p>
                  <p v-else>满足以下条件才允许保存；不满足时显示失败提示，并可定位到字段。</p>
                </div>
                <div class="business-rule-governance__rule-basics">
                  <label>
                    启用
                    <UiSwitch
                      :checked="selectedRule.enabled"
                      :disabled="applying"
                      @update:checked="updateRule('enabled', $event)"
                    />
                  </label>
                  <label v-if="selectedRule.kind === 'CALCULATION'">
                    目标字段
                    <UiSelect
                      :value="selectedRule.targetField"
                      :options="fieldOptions"
                      placeholder="选择可写字段"
                      :disabled="applying"
                      @update:value="updateRule('targetField', $event)"
                    />
                  </label>
                  <template v-else>
                    <label>
                      定位字段（可选）
                      <UiSelect
                        :value="selectedRule.targetField || '__none__'"
                        :options="locatorFieldOptions"
                        :disabled="applying"
                        @update:value="updateRule('targetField', $event)"
                      />
                    </label>
                    <label>
                      失败提示
                      <UiInput
                        :value="selectedRule.messageTemplate"
                        :disabled="applying"
                        @update:value="updateRule('messageTemplate', $event)"
                      />
                    </label>
                  </template>
                </div>
              </section>

              <section
                v-if="selectedRule.kind !== 'CALCULATION' || selectedRule.targetField"
                class="business-rule-governance__flow-section business-rule-governance__expression-step"
              >
                <div>
                  <h2>表达式</h2>
                  <p>字段和函数会插入到此规则保留的光标位置。</p>
                </div>
                <div
                  :class="[
                    'business-rule-governance__expression-layout',
                    { 'business-rule-governance__expression-layout--functions-open': functionPanelOpen },
                  ]"
                >
                  <section class="business-rule-governance__expression">
                    <label>
                      {{ selectedRule.kind === 'CALCULATION' ? '取值表达式' : '校验条件' }}
                      <UiTextArea
                        ref="expressionEditor"
                        :value="selectedRule.expression"
                        :placeholder="
                          selectedRule.kind === 'CALCULATION'
                            ? '例如：{quantity} * {unitPrice}'
                            : '例如：PRESENT({title})'
                        "
                        :disabled="applying"
                        :accept-drop="acceptsFormulaField"
                        @selection="rememberExpressionSelection"
                        @drop="handleFormulaDrop"
                        @update:value="updateRule('expression', $event)"
                      />
                    </label>
                    <p class="business-rule-governance__cursor-help">
                      引用字段由服务端读取，保存时计算；可先试算。
                    </p>
                    <p class="business-rule-governance__summary">{{ ruleSummary(selectedRule) }}</p>
                  </section>

                  <aside class="business-rule-governance__function-panel">
                    <button
                      type="button"
                      class="business-rule-governance__function-toggle"
                      :aria-expanded="functionPanelOpen"
                      @click="functionPanelOpen = !functionPanelOpen"
                    >
                      函数与运算符
                    </button>
                    <template v-if="functionPanelOpen">
                      <UiInput v-model:value="functionSearch" type="search" placeholder="搜索函数或用途" />
                      <section v-for="group in functionCapabilityGroups" :key="group.category">
                        <h3>{{ group.category }}</h3>
                        <div class="business-rule-governance__function-list">
                          <button
                            v-for="capability in group.capabilities"
                            :key="capability.id"
                            type="button"
                            :class="{
                              'business-rule-governance__function--selected':
                                inspectedFunction?.id === capability.id,
                            }"
                            @click="inspectedFunctionId = capability.id"
                            @dblclick="insertFunction(capability)"
                          >
                            {{ capability.label }}（{{ capability.purpose }}）
                          </button>
                        </div>
                      </section>
                      <p v-if="functionCapabilityGroups.length === 0" class="business-rule-governance__empty">
                        未找到函数
                      </p>
                      <section v-if="inspectedFunction" class="business-rule-governance__function-detail">
                        <strong>{{ inspectedFunction.signature }}</strong>
                        <span>{{ inspectedFunction.purpose }}，返回{{ inspectedFunction.returnType }}</span>
                        <span v-for="parameter in inspectedFunction.parameters" :key="parameter.name"
                          >{{ parameter.name }}：{{ parameter.description }}</span
                        >
                        <code>{{ inspectedFunction.example }}</code>
                        <UiButton size="small" :disabled="applying" @click="insertFunction(inspectedFunction)"
                          >插入函数</UiButton
                        >
                      </section>
                    </template>
                  </aside>
                </div>

                <section class="business-rule-governance__templates">
                  <h3>模板起步</h3>
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
                    {{ template.label }}：{{ template.expression }}
                  </UiButton>
                </section>
              </section>
              <section
                v-else
                class="business-rule-governance__flow-section business-rule-governance__expression-guidance"
              >
                <h2>表达式</h2>
                <p>请先在“业务目的”中选择计算目标字段，再编辑表达式和函数。</p>
              </section>
              <UiButton
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
      </RecordDetailLayout>
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
.business-rule-governance__summary,
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

.business-rule-governance__summary,
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

.business-rule-governance__rule-list-surface {
  height: auto;
  min-height: 0;
}

.business-rule-governance__drawer-body {
  display: grid;
  grid-template-columns: minmax(220px, 0.32fr) minmax(0, 1fr);
  height: 100%;
  min-height: 0;
}

.business-rule-governance__field-directory {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  align-content: start;
  gap: 8px;
  min-height: 0;
  padding: 14px;
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
  margin: 0 0 14px;
  padding: 12px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
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
.business-rule-governance__field-inspector,
.business-rule-governance__cursor-help,
.business-rule-governance__readonly-summary,
.business-rule-governance__function-detail {
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

.business-rule-governance__field-inspector,
.business-rule-governance__readonly-summary,
.business-rule-governance__function-detail {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 8px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
}

.business-rule-governance__mode-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 10px;
  border-bottom: 1px solid var(--muyun-border-subtle);
}

.business-rule-governance__mode-tabs button,
.business-rule-governance__function-toggle,
.business-rule-governance__function-list button {
  border: 0;
  background: transparent;
  color: var(--muyun-text-secondary);
  cursor: pointer;
  font: inherit;
}

.business-rule-governance__mode-tabs button {
  padding: 7px 10px;
  border-bottom: 2px solid transparent;
}

.business-rule-governance__mode-tab--active {
  border-bottom-color: var(--muyun-primary) !important;
  color: var(--muyun-primary) !important;
  font-weight: 600;
}

.business-rule-governance__rule-basics {
  display: grid;
  grid-template-columns: minmax(140px, 0.75fr) minmax(0, 1.25fr) minmax(0, 1.25fr);
  gap: 10px;
}

.business-rule-governance__expression-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
  min-width: 0;
}

.business-rule-governance__expression-layout--functions-open {
  grid-template-columns: minmax(0, 1fr) minmax(220px, 0.44fr);
}

.business-rule-governance__expression,
.business-rule-governance__function-panel {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.business-rule-governance__function-panel {
  align-content: start;
  padding: 9px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 7px;
}

.business-rule-governance__expression-layout:not(.business-rule-governance__expression-layout--functions-open)
  .business-rule-governance__function-panel {
  align-self: start;
  padding: 6px 8px;
}

.business-rule-governance__function-toggle {
  padding: 0;
  color: var(--muyun-text-primary);
  font-size: 13px;
  font-weight: 600;
  text-align: left;
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

.business-rule-governance__function-list button {
  padding: 4px 6px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 5px;
  font-size: 12px;
}

.business-rule-governance__function-list button:hover,
.business-rule-governance__function--selected {
  border-color: var(--muyun-primary) !important;
  background: var(--muyun-primary-soft) !important;
  color: var(--muyun-primary) !important;
}

.business-rule-governance__function-detail code {
  overflow-wrap: anywhere;
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
    grid-template-rows: auto auto minmax(120px, 1fr) auto;
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
