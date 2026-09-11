export type BusinessRuleKind = 'CALCULATION' | 'VALIDATION' | 'UI_CONTROL';

export interface BusinessRuleEditableField {
  fieldName: string;
  title: string;
  fieldSpecAlias: string;
  valueType: string;
  /** Direct child fields declare the aggregate functions that can consume them. */
  aggregateFunctions?: string[];
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
  /** Direct child fields declare the aggregate functions that can consume them. */
  aggregateFunctions?: string[];
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
  /** Explains the business problem the formula solves before showing its example. */
  description: string;
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

function formulaHelp(id: string, fallback: string): string {
  const descriptions: Record<string, string> = {
    PRESENT: '判断字段是否已经填写，可用于必填校验或决定后续计算是否执行。',
    ISNULL: '判断字段是否为空，可用于为空时给出提示或补默认处理。',
    IN: '判断字段是否匹配给定候选项，可用于状态、类型等有限枚举的判断。',
    TODAY: '取得当前日期，适合计算当天生效、截止日期等规则。',
    NOW: '取得当前 UTC 时间，适合记录当前时刻或比较时效。',
    YEAR: '从日期或时间中提取年份，适合按年度计算或校验。',
    MONTH: '从日期或时间中提取月份，适合按月判断或分组计算。',
    DAY: '从日期或时间中提取日，适合按具体日期触发规则。',
    DATE_ADD: '在日期基础上增加天数，适合推算到期日、提醒日。',
    DATE_SUB: '在日期基础上减少天数，适合推算提前提醒日、最早日期。',
    DATETIME_ADD: '在时间基础上增加指定间隔，适合计算精确的截止时间。',
    DATETIME_SUB: '在时间基础上减少指定间隔，适合计算提前触发时间。',
    DATE_DIFF_DAYS: '计算两个日期或时间相差的天数，适合期限与逾期判断。',
    DATE_DIFF_HOURS: '计算两个时间相差的小时数，适合时效与工时判断。',
    COUNT: '统计子表字段中已填写的记录数，适合检查明细是否达到最低数量。',
    SUM: '汇总子表中的数值，适合计算明细总金额、总数量。',
    AVG: '计算子表数值的平均值，适合得到平均单价、平均分等指标。',
    MAX: '取得子表数值中的最大值，适合识别最高金额、最大数量。',
    MIN: '取得子表数值中的最小值，适合识别最低金额、最小数量。',
    ROUND: '对数值四舍五入，适合把计算结果收敛到指定的小数位。',
    FORMAT_DECIMAL: '把数值格式化为固定小数位文本，适合展示或拼接时保留末尾零。',
  };
  return descriptions[id] || fallback;
}

function portableFormulaCapability(
  id: string,
  purpose: string,
  category: string,
  signature: string,
  parameters: BusinessRuleFormulaParameter[],
  returnType: string,
  example: string,
  insertion = `${id}()`,
): BusinessRuleFormulaCapability {
  const cursor = insertion.indexOf('(') + 1;
  return {
    id,
    label: id,
    purpose,
    description: formulaHelp(id, purpose),
    category,
    signature,
    parameters,
    returnType,
    example,
    insertion,
    firstParameterSelection: { start: cursor, end: cursor },
  };
}

const extendedPortableFormulaCapabilities: readonly BusinessRuleFormulaCapability[] = [
  portableFormulaCapability('TODAY', '今天', '日期时间', 'TODAY()', [], '日期', 'TODAY()'),
  portableFormulaCapability('NOW', '当前时间', '日期时间', 'NOW()', [], '时间', 'NOW()'),
  portableFormulaCapability(
    'YEAR',
    '年份',
    '日期时间',
    'YEAR(value)',
    [{ name: 'value', description: '日期或时间字段' }],
    '整数',
    'YEAR({signedAt})',
  ),
  portableFormulaCapability(
    'MONTH',
    '月份',
    '日期时间',
    'MONTH(value)',
    [{ name: 'value', description: '日期或时间字段' }],
    '整数',
    'MONTH({signedAt})',
  ),
  portableFormulaCapability(
    'DAY',
    '日',
    '日期时间',
    'DAY(value)',
    [{ name: 'value', description: '日期或时间字段' }],
    '整数',
    'DAY({signedAt})',
  ),
  portableFormulaCapability(
    'DATE_ADD',
    '日期加天数',
    '日期时间',
    'DATE_ADD(date, days)',
    [
      { name: 'date', description: 'yyyy-MM-dd 日期' },
      { name: 'days', description: '整数天数' },
    ],
    '日期',
    'DATE_ADD({signedDate}, 7)',
    'DATE_ADD(, 0)',
  ),
  portableFormulaCapability(
    'DATE_SUB',
    '日期减天数',
    '日期时间',
    'DATE_SUB(date, days)',
    [
      { name: 'date', description: 'yyyy-MM-dd 日期' },
      { name: 'days', description: '整数天数' },
    ],
    '日期',
    'DATE_SUB({signedDate}, 7)',
    'DATE_SUB(, 0)',
  ),
  portableFormulaCapability(
    'DATETIME_ADD',
    '时间加间隔',
    '日期时间',
    'DATETIME_ADD(dateTime, amount, unit)',
    [
      { name: 'dateTime', description: 'UTC 时间' },
      { name: 'amount', description: '整数间隔' },
      { name: 'unit', description: 'DAY/HOUR/MINUTE/SECOND' },
    ],
    '时间',
    "DATETIME_ADD({signedAt}, 2, 'HOUR')",
    "DATETIME_ADD(, 0, 'DAY')",
  ),
  portableFormulaCapability(
    'DATETIME_SUB',
    '时间减间隔',
    '日期时间',
    'DATETIME_SUB(dateTime, amount, unit)',
    [
      { name: 'dateTime', description: 'UTC 时间' },
      { name: 'amount', description: '整数间隔' },
      { name: 'unit', description: 'DAY/HOUR/MINUTE/SECOND' },
    ],
    '时间',
    "DATETIME_SUB({signedAt}, 2, 'HOUR')",
    "DATETIME_SUB(, 0, 'DAY')",
  ),
  portableFormulaCapability(
    'DATE_DIFF_DAYS',
    '相差天数',
    '日期时间',
    'DATE_DIFF_DAYS(start, end)',
    [
      { name: 'start', description: '开始日期或时间' },
      { name: 'end', description: '结束日期或时间' },
    ],
    '小数',
    'DATE_DIFF_DAYS({startDate}, {endDate})',
    'DATE_DIFF_DAYS(, )',
  ),
  portableFormulaCapability(
    'DATE_DIFF_HOURS',
    '相差小时',
    '日期时间',
    'DATE_DIFF_HOURS(start, end)',
    [
      { name: 'start', description: '开始时间' },
      { name: 'end', description: '结束时间' },
    ],
    '小数',
    'DATE_DIFF_HOURS({startAt}, {endAt})',
    'DATE_DIFF_HOURS(, )',
  ),
  portableFormulaCapability(
    'COUNT',
    '计数',
    '子表汇总',
    'COUNT(childField)',
    [{ name: 'field', description: '子表字段，例如 {lines.amount}' }],
    '整数',
    'COUNT({lines.amount})',
  ),
  portableFormulaCapability(
    'SUM',
    '求和',
    '子表汇总',
    'SUM(childField)',
    [{ name: 'field', description: '子表数值字段，例如 {lines.amount}' }],
    '小数',
    'SUM({lines.amount})',
  ),
  portableFormulaCapability(
    'AVG',
    '平均值',
    '子表汇总',
    'AVG(childField)',
    [{ name: 'field', description: '子表数值字段，例如 {lines.amount}' }],
    '小数',
    'AVG({lines.amount})',
  ),
  portableFormulaCapability(
    'MAX',
    '最大值',
    '子表汇总',
    'MAX(childField)',
    [{ name: 'field', description: '子表数值字段，例如 {lines.amount}' }],
    '小数',
    'MAX({lines.amount})',
  ),
  portableFormulaCapability(
    'MIN',
    '最小值',
    '子表汇总',
    'MIN(childField)',
    [{ name: 'field', description: '子表数值字段，例如 {lines.amount}' }],
    '小数',
    'MIN({lines.amount})',
  ),
  portableFormulaCapability(
    'ROUND',
    '四舍五入',
    '数值',
    'ROUND(value, scale)',
    [
      { name: 'value', description: '数值或表达式' },
      { name: 'scale', description: '保留 0–12 位小数' },
    ],
    '小数',
    'ROUND({amount}, 2)',
    'ROUND(, 2)',
  ),
  portableFormulaCapability(
    'FORMAT_DECIMAL',
    '格式化小数',
    '数值',
    'FORMAT_DECIMAL(value, scale)',
    [
      { name: 'value', description: '数值或表达式' },
      { name: 'scale', description: '保留 0–12 位小数' },
    ],
    '文本',
    'FORMAT_DECIMAL({amount}, 2)',
    'FORMAT_DECIMAL(, 2)',
  ),
];

export const portableFormulaCapabilities: readonly BusinessRuleFormulaCapability[] = [
  {
    id: 'PRESENT',
    label: 'PRESENT',
    purpose: '判断字段有值',
    description: formulaHelp('PRESENT', ''),
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
    purpose: '判断字段为空',
    description: formulaHelp('ISNULL', ''),
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
    purpose: '匹配候选项',
    description: formulaHelp('IN', ''),
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
  ...extendedPortableFormulaCapabilities,
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
          purpose: entry.title || id,
          description: entry.description || formulaHelp(id, entry.title || id),
          category: entry.category || '其他',
          signature: `${id}(${entry.parameters?.map((parameter) => parameter.name).join(', ') ?? ''})`,
          parameters: entry.parameters ?? [],
          returnType: entry.returnType || '由服务端定义',
          example: entry.example || `${id}()`,
          insertion: portableFormulaCapability(id, '', '', '', [], '', '').insertion,
          firstParameterSelection: {
            start: portableFormulaCapability(id, '', '', '', [], '', '').firstParameterSelection.start,
            end: portableFormulaCapability(id, '', '', '', [], '', '').firstParameterSelection.end,
          },
        },
      ];
    return [
      {
        ...base,
        category: entry.category || base.category,
        purpose: entry.title || base.purpose,
        description: entry.description || base.description,
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
    [
      capability.label,
      capability.purpose,
      capability.description,
      capability.category,
      capability.signature,
    ].some((value) => value.toLocaleLowerCase().includes(normalized)),
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

const aggregateFunctionNames = new Set(['COUNT', 'SUM', 'AVG', 'MAX', 'MIN']);

/**
 * Prevents a child field from being inserted as a scalar or into an aggregate whose value type
 * cannot consume it. The server remains authoritative; this only keeps authoring feedback close
 * to the attempted insertion.
 */
export function aggregateFieldInsertionReason(
  field: BusinessRuleReferenceField,
  expression: string,
  selectionStart: number,
): string | undefined {
  const allowed = field.aggregateFunctions;
  if (!allowed?.length) return undefined;
  const aggregate = enclosingAggregateFunction(expression, selectionStart);
  if (!aggregate) return '子表字段只能作为 COUNT、SUM、AVG、MAX 或 MIN 的汇总参数。';
  if (allowed.includes(aggregate)) return undefined;
  return `${field.label || field.name}不能用于 ${aggregate}；该字段仅支持 ${allowed.join('、')}。`;
}

function enclosingAggregateFunction(expression: string, selectionStart: number): string | undefined {
  const stack: Array<string | undefined> = [];
  const limit = Math.max(0, Math.min(selectionStart, expression.length));
  let quote: string | undefined;
  for (let index = 0; index < limit; index += 1) {
    const char = expression[index];
    if (quote) {
      if (char === '\\') index += 1;
      else if (char === quote) quote = undefined;
      continue;
    }
    if (char === "'" || char === '"') {
      quote = char;
      continue;
    }
    if (char === '(') {
      const name = expression
        .slice(0, index)
        .match(/([A-Za-z][A-Za-z0-9_]*)\s*$/)?.[1]
        ?.toUpperCase();
      stack.push(name);
    } else if (char === ')') {
      stack.pop();
    }
  }
  return [...stack]
    .reverse()
    .find((name): name is string => name != null && aggregateFunctionNames.has(name));
}

export interface UiControlTarget {
  elementKey: string;
  hide: boolean;
  readOnly: boolean;
}
export interface UiControlForm {
  key: string;
  title: string;
  elements: Array<{ key: string; label: string }>;
}
export interface UiControlSnapshot {
  baselineFingerprint: string;
  rules: Array<{
    code: string;
    formKey: string;
    expression: string;
    enabled: boolean;
    targets: UiControlTarget[];
  }>;
  forms: UiControlForm[];
}

export interface BusinessRuleSnapshotRule {
  formKey?: string;
  targets?: UiControlTarget[];
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
  /** Direct child fields, which may only appear within a child aggregation function. */
  aggregateFields?: BusinessRuleEditableField[];
  /** Additive portable function directory emitted by the server. */
  functions?: BusinessRuleFormulaCatalogEntry[];
  rules: BusinessRuleSnapshotRule[];
}

export interface BusinessRuleProposal {
  formKey?: string;
  targets?: UiControlTarget[];
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
    ...(rule.kind === 'UI_CONTROL'
      ? { formKey: rule.formKey, targets: rule.targets?.map((target) => ({ ...target })) ?? [] }
      : {}),
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
  const prefix = kind === 'CALCULATION' ? 'calculation' : kind === 'UI_CONTROL' ? 'uiControl' : 'validation';
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
  return value === 'CALCULATION' || value === 'VALIDATION' || value === 'UI_CONTROL';
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
