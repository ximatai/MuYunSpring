export type BusinessRuleKind = 'CALCULATION' | 'VALIDATION';

export interface BusinessRuleEditableField {
  fieldName: string;
  title: string;
  fieldSpecAlias: string;
  valueType: string;
}

/**
 * The page-reference-fields response is deliberately a small, UI-neutral catalogue.  Formula
 * governance consumes it as a source directory; it does not treat it as a save-time authority.
 */
export interface BusinessRuleReferenceField {
  id: string;
  name: string;
  label?: string;
  valueType: string;
  referenceModuleAlias?: string;
  referenceCardinality?: string;
  expandable?: boolean;
  readOnly?: boolean;
  systemManaged?: boolean;
  value?: unknown;
  /** Additive catalogue metadata; absent means use the stable conservative fallback below. */
  formulaReadable?: boolean;
  formulaDisabledReason?: string;
}

export interface BusinessRuleFormulaParameter {
  name: string;
  description: string;
}

/** A portable formula capability. A server catalogue can replace this list without changing the surface. */
export interface BusinessRuleFormulaCapability {
  id: string;
  label: string;
  purpose: string;
  category: string;
  signature: string;
  parameters: BusinessRuleFormulaParameter[];
  returnType: string;
  example: string;
  insertion: string;
  firstParameterSelection: { start: number; end: number };
}

/** The additive backend catalogue shape. UI-only insertion details remain derived from the portable name. */
export interface BusinessRuleFormulaCatalogEntry {
  name: string;
  category?: string;
  title?: string;
  description?: string;
  parameters?: BusinessRuleFormulaParameter[];
  returnType?: string;
  example?: string;
}

export const portableFormulaCapabilities: readonly BusinessRuleFormulaCapability[] = [
  {
    id: 'PRESENT',
    label: 'PRESENT',
    purpose: '字段有值',
    category: '空值判断',
    signature: 'PRESENT(value)',
    parameters: [{ name: 'value', description: '需要判断的字段或表达式' }],
    returnType: '布尔值',
    example: 'PRESENT({quantity})',
    insertion: 'PRESENT()',
    firstParameterSelection: { start: 8, end: 8 },
  },
  {
    id: 'ISNULL',
    label: 'ISNULL',
    purpose: '字段为空',
    category: '空值判断',
    signature: 'ISNULL(value)',
    parameters: [{ name: 'value', description: '需要判断的字段或表达式' }],
    returnType: '布尔值',
    example: 'ISNULL({effectiveDate})',
    insertion: 'ISNULL()',
    firstParameterSelection: { start: 7, end: 7 },
  },
  {
    id: 'IN',
    label: 'IN',
    purpose: '匹配候选值',
    category: '集合判断',
    signature: "IN(value, 'candidate', ...)",
    parameters: [
      { name: 'value', description: '需要匹配的字段或表达式' },
      { name: 'candidate', description: '1–20 个字面量候选值' },
    ],
    returnType: '布尔值',
    example: "IN({status}, 'DRAFT', 'ACTIVE')",
    insertion: "IN(, '候选值')",
    firstParameterSelection: { start: 3, end: 3 },
  },
];

export function normalizeFormulaCapabilities(
  entries: readonly (BusinessRuleFormulaCapability | BusinessRuleFormulaCatalogEntry)[] | undefined,
): BusinessRuleFormulaCapability[] {
  if (entries === undefined) return [...portableFormulaCapabilities];
  const portable = new Map(portableFormulaCapabilities.map((capability) => [capability.id, capability]));
  return entries.flatMap((entry) => {
    const id = 'id' in entry ? entry.id : entry.name;
    const base = portable.get(id);
    if ('id' in entry) return [entry];
    if (!base)
      return [
        {
          id,
          label: id,
          purpose: entry.title || entry.description || id,
          category: entry.category || '其他',
          signature: `${id}(${entry.parameters?.map((parameter) => parameter.name).join(', ') ?? ''})`,
          parameters: entry.parameters ?? [],
          returnType: entry.returnType || '由服务端定义',
          example: entry.example || `${id}()`,
          insertion: `${id}()`,
          firstParameterSelection: { start: id.length + 1, end: id.length + 1 },
        },
      ];
    return [
      {
        ...base,
        category: entry.category || base.category,
        purpose: entry.title || entry.description || base.purpose,
        parameters: entry.parameters?.length ? entry.parameters : base.parameters,
        returnType: entry.returnType || base.returnType,
        example: entry.example || base.example,
      },
    ];
  });
}

export function searchableFormulaCapabilities(
  capabilities: readonly BusinessRuleFormulaCapability[],
  keyword: string,
): BusinessRuleFormulaCapability[] {
  const normalized = keyword.trim().toLocaleLowerCase();
  if (!normalized) return [...capabilities];
  return capabilities.filter((capability) =>
    [capability.label, capability.purpose, capability.category, capability.signature].some((value) =>
      value.toLocaleLowerCase().includes(normalized),
    ),
  );
}

/** Root fields that cannot safely become scalar formula input have a visible, deterministic reason. */
export function formulaFieldUnusableReason(field: BusinessRuleReferenceField): string | undefined {
  if (field.formulaReadable === false) return field.formulaDisabledReason || '该字段不能用于公式。';
  // The server owns whether every catalogue path is formula-readable. A readable
  // descendant is a read-only value, but must still retain a server-supplied denial.
  if (field.name.includes('.')) return undefined;
  if (field.systemManaged) return '系统管理字段不能用于业务规则。';
  if (field.readOnly) return '受保护字段不能用于业务规则。';
  if (field.referenceCardinality === 'MANY') return '集合引用不能作为标量公式字段。';
  if (field.valueType === 'JSON') return 'JSON 字段不能作为首期公式输入。';
  return undefined;
}

export interface BusinessRuleSnapshotRule {
  code: string;
  kind: BusinessRuleKind | string;
  phase: string;
  targetField?: string;
  expression: string;
  enabled: boolean;
  severity?: string;
  messageTemplate?: string;
  stopOnError?: boolean;
  editable: boolean;
  readOnlyReason?: string;
}

export interface BusinessRuleSnapshot {
  moduleAlias: string;
  baselineFingerprint: string;
  editableFields: BusinessRuleEditableField[];
  /** Already authorised referenced paths used by the persisted snapshot. These are read-only trial facts. */
  referenceFields?: Array<{ path: string; title: string; valueType: string }>;
  /** Additive portable function directory emitted by the server. */
  functions?: BusinessRuleFormulaCatalogEntry[];
  rules: BusinessRuleSnapshotRule[];
}

export interface BusinessRuleProposal {
  code: string;
  kind: BusinessRuleKind;
  targetField?: string;
  expression: string;
  enabled: boolean;
  messageTemplate?: string;
}

export interface BusinessRuleIssue {
  code: string;
  ruleCode?: string;
  field?: string;
  message: string;
}

export interface BusinessRulePreview {
  snapshot: BusinessRuleSnapshot;
  proposalFingerprint: string;
  executionOrder: string[];
  errors: BusinessRuleIssue[];
}

export interface BusinessRuleTrialResult {
  preview: BusinessRulePreview;
  values: Record<string, unknown>;
  changedFields: string[];
  errors: BusinessRuleIssue[];
}

export interface BusinessRuleApplyResult {
  snapshot: BusinessRuleSnapshot;
  preview: BusinessRulePreview;
  activatedModules: string[];
}

export interface FormulaInsertion {
  value: string;
  selectionStart: number;
  selectionEnd: number;
}

export interface BusinessRuleChangeImpact {
  added: BusinessRuleProposal[];
  modified: BusinessRuleProposal[];
  deleted: BusinessRuleProposal[];
}

export interface BusinessRuleFormulaTemplate {
  id: string;
  label: string;
  expression: string;
  help: string;
}

export function editableProposals(snapshot: BusinessRuleSnapshot): BusinessRuleProposal[] {
  return snapshot.rules.filter((rule) => rule.editable && isBusinessRuleKind(rule.kind)).map(toProposal);
}

export function readonlyRules(snapshot: BusinessRuleSnapshot): BusinessRuleSnapshotRule[] {
  return snapshot.rules.filter((rule) => !rule.editable || !isBusinessRuleKind(rule.kind));
}

export function toProposal(
  rule: Pick<BusinessRuleSnapshotRule, keyof BusinessRuleProposal>,
): BusinessRuleProposal {
  return {
    code: rule.code,
    kind: rule.kind as BusinessRuleKind,
    ...(rule.targetField?.trim() ? { targetField: rule.targetField } : {}),
    expression: rule.expression,
    enabled: rule.enabled,
    ...(rule.messageTemplate?.trim() ? { messageTemplate: rule.messageTemplate } : {}),
  };
}

export function newBusinessRule(
  kind: BusinessRuleKind,
  existingRules: readonly Pick<BusinessRuleProposal, 'code'>[],
): BusinessRuleProposal {
  const prefix = kind === 'CALCULATION' ? 'calculation' : 'validation';
  const occupied = new Set(existingRules.map((rule) => rule.code));
  let suffix = 1;
  while (occupied.has(`${prefix}${suffix}`)) suffix += 1;
  return {
    code: `${prefix}${suffix}`,
    kind,
    expression: '',
    enabled: true,
    ...(kind === 'VALIDATION' ? { messageTemplate: '不符合业务规则' } : {}),
  };
}

export function isBusinessRuleKind(value: string): value is BusinessRuleKind {
  return value === 'CALCULATION' || value === 'VALIDATION';
}

export function proposalFingerprintOf(proposals: readonly BusinessRuleProposal[]): string {
  return JSON.stringify(proposals);
}

export function typedSampleValue(value: string, valueType: string): unknown {
  if (value === '') return undefined;
  if (['INTEGER', 'LONG', 'DECIMAL'].includes(valueType)) {
    const number = Number(value);
    return Number.isFinite(number) ? number : value;
  }
  if (valueType === 'BOOLEAN') return value === 'true';
  return value;
}

/**
 * Inserts formula text at the active selection. The returned selection keeps the editor focused
 * immediately after the inserted text, including when a selected range was replaced.
 */
export function insertFormulaText(
  expression: string,
  selectionStart: number | undefined,
  selectionEnd: number | undefined,
  text: string,
): FormulaInsertion {
  const start = clampSelection(selectionStart, expression.length);
  const end = Math.max(start, clampSelection(selectionEnd, expression.length));
  const value = `${expression.slice(0, start)}${text}${expression.slice(end)}`;
  const cursor = start + text.length;
  return { value, selectionStart: cursor, selectionEnd: cursor };
}

/** Reads only real field tokens; references inside string literals are formula text, not dependencies. */
export function referencedFormulaFields(expression: string): string[] {
  const fields = new Set<string>();
  let quote: '"' | "'" | undefined;
  for (let index = 0; index < expression.length; index += 1) {
    const character = expression[index]!;
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = undefined;
      continue;
    }
    if (character === '"' || character === "'") {
      quote = character;
      continue;
    }
    if (character !== '{') continue;
    const closing = expression.indexOf('}', index + 1);
    if (closing < 0) continue;
    const candidate = expression.slice(index + 1, closing);
    if (/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*$/.test(candidate)) {
      fields.add(candidate);
    }
    index = closing;
  }
  return [...fields];
}

/** Replaces only executable field tokens with their business names for read-only UI summaries. */
export function presentableFormulaExpression(
  expression: string,
  fields: readonly BusinessRuleEditableField[],
): string {
  const byName = new Map(fields.map((field) => [field.fieldName, field]));
  let result = '';
  let quote: '"' | "'" | undefined;
  for (let index = 0; index < expression.length; index += 1) {
    const character = expression[index]!;
    if (quote) {
      result += character;
      if (character === '\\' && index + 1 < expression.length) result += expression[++index]!;
      else if (character === quote) quote = undefined;
      continue;
    }
    if (character === '"' || character === "'") {
      quote = character;
      result += character;
      continue;
    }
    if (character !== '{') {
      result += character;
      continue;
    }
    const closing = expression.indexOf('}', index + 1);
    if (closing < 0) {
      result += character;
      continue;
    }
    const fieldName = expression.slice(index + 1, closing);
    const field = byName.get(fieldName);
    result += field
      ? `{${field.title || field.fieldName}（${field.fieldName}）}`
      : expression.slice(index, closing + 1);
    index = closing;
  }
  return result;
}

/**
 * Trial inputs are the real external dependencies of all enabled rules. Targets produced by an
 * enabled calculation are intentionally omitted, including targets referenced by a later rule.
 */
export function externalTrialInputFields(
  fields: readonly BusinessRuleEditableField[],
  rules: readonly BusinessRuleProposal[],
): BusinessRuleEditableField[] {
  const calculatedTargets = new Set(
    rules
      .filter((rule) => rule.enabled && rule.kind === 'CALCULATION')
      .flatMap((rule) => (rule.targetField ? [rule.targetField] : [])),
  );
  const dependencies = new Set(
    rules.filter((rule) => rule.enabled).flatMap((rule) => referencedFormulaFields(rule.expression)),
  );
  return fields.filter(
    (field) => dependencies.has(field.fieldName) && !calculatedTargets.has(field.fieldName),
  );
}

export function filterBusinessRuleFields(
  fields: readonly BusinessRuleEditableField[],
  keyword: string,
): BusinessRuleEditableField[] {
  const normalized = keyword.trim().toLocaleLowerCase();
  if (!normalized) return [...fields];
  return fields.filter((field) =>
    [field.title, field.fieldName, field.fieldSpecAlias, field.valueType].some((value) =>
      value.toLocaleLowerCase().includes(normalized),
    ),
  );
}

export function formulaTemplates(
  kind: BusinessRuleKind,
  fields: readonly BusinessRuleEditableField[],
  targetField?: string,
): BusinessRuleFormulaTemplate[] {
  const eligible = fields.filter((field) => kind !== 'CALCULATION' || field.fieldName !== targetField);
  const numeric = eligible.filter((field) => ['INTEGER', 'LONG', 'DECIMAL'].includes(field.valueType));
  const templates: BusinessRuleFormulaTemplate[] = [];
  if (numeric.length >= 2) {
    templates.push({
      id: 'multiply-fields',
      label: '两字段相乘',
      expression: `{${numeric[0]!.fieldName}} * {${numeric[1]!.fieldName}}`,
      help: `用${numeric[0]!.title || numeric[0]!.fieldName}乘以${numeric[1]!.title || numeric[1]!.fieldName}`,
    });
  }
  const first = eligible[0];
  if (first) {
    templates.push({
      id: 'present-field',
      label: '字段有值',
      expression: `PRESENT({${first.fieldName}})`,
      help: `${first.title || first.fieldName}已填写时为真`,
    });
  }
  const comparison = numeric[0];
  if (comparison) {
    templates.push({
      id: 'compare-field',
      label: '大于零',
      expression: `{${comparison.fieldName}} > 0`,
      help: `${comparison.title || comparison.fieldName}大于零时为真`,
    });
  }
  return kind === 'CALCULATION'
    ? templates.filter((template) => template.id === 'multiply-fields')
    : templates.filter((template) => template.id !== 'multiply-fields');
}

export function businessRuleChangeImpact(
  snapshot: BusinessRuleSnapshot,
  proposals: readonly BusinessRuleProposal[],
): BusinessRuleChangeImpact {
  const baseline = new Map(editableProposals(snapshot).map((rule) => [rule.code, rule]));
  const next = new Map(proposals.map((rule) => [rule.code, rule]));
  const added = proposals.filter((rule) => !baseline.has(rule.code));
  const modified = proposals.filter((rule) => {
    const before = baseline.get(rule.code);
    return before != null && proposalFingerprintOf([before]) !== proposalFingerprintOf([rule]);
  });
  const deleted = [...baseline.values()].filter((rule) => !next.has(rule.code));
  return { added, modified, deleted };
}

function clampSelection(value: number | undefined, length: number): number {
  if (!Number.isFinite(value)) return length;
  return Math.min(Math.max(0, value as number), length);
}
