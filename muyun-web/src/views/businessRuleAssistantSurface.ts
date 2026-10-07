import {
  AssistantCapabilityUsageError,
  pageAssistantCatalog,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
  type AssistantCapability,
  type AssistantOperationProposal,
  type AssistantSurface,
  type AssistantTurnRequester,
} from '@muyun/web-core';
import type {
  BusinessRuleProposal,
  BusinessRuleTrialResult,
  BusinessRuleSnapshotRule,
} from './businessRuleGovernance';
import { toProposal, businessRuleTrialValue, businessRuleValidationHelp } from './businessRuleGovernance';

import type {
  BusinessRuleTrialInput,
  BusinessRuleEditor as BusinessRuleAssistantAdapter,
} from './businessRuleEditor';
export type {
  BusinessRuleTrialInput,
  BusinessRuleEditor as BusinessRuleAssistantAdapter,
} from './businessRuleEditor';
const sections = ['fields', 'childFields', 'aggregateFields', 'functions', 'rules', 'forms'] as const;
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
function fail(message: string): never {
  throw new AssistantCapabilityUsageError(message);
}
function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max) return fail('规则参数为空或过长');
  return value.trim();
}
export function parseBusinessRuleProposal(input: unknown): BusinessRuleProposal {
  if (
    !object(input) ||
    Object.keys(input).some(
      (key) =>
        ![
          'code',
          'kind',
          'targetField',
          'expression',
          'enabled',
          'messageTemplate',
          'formKey',
          'targets',
        ].includes(key),
    )
  )
    return fail('规则参数无效');
  const code = text(input.code, 64);
  if (!/^[a-z][A-Za-z0-9]{0,63}$/.test(code))
    return fail(
      '规则编码必须以小写字母开头，只含字母和数字，例如 amountNonNegative；修改已有规则请沿用原编码',
    );
  if (
    !['CALCULATION', 'VALIDATION', 'UI_CONTROL'].includes(String(input.kind)) ||
    typeof input.enabled !== 'boolean'
  )
    return fail('规则类型或启用状态无效');
  const rule: BusinessRuleProposal = {
    code,
    kind: input.kind as BusinessRuleProposal['kind'],
    expression: text(input.expression, 4000),
    enabled: input.enabled,
  };
  if (input.targetField !== undefined) rule.targetField = text(input.targetField, 255);
  if (input.messageTemplate !== undefined) rule.messageTemplate = text(input.messageTemplate, 500);
  if (rule.kind === 'CALCULATION' && !rule.targetField) return fail('请选择计算目标字段');
  if (rule.kind === 'VALIDATION' && rule.targetField?.includes('.'))
    return fail('保存校验只能定位主记录字段；检查明细请使用汇总函数，不要将明细字段作为定位目标');
  if (rule.kind === 'UI_CONTROL') {
    rule.formKey = text(input.formKey, 255);
    if (!Array.isArray(input.targets) || !input.targets.length || input.targets.length > 40)
      return fail('请选择界面控制元素');
    rule.targets = input.targets.map((value) => {
      if (
        !object(value) ||
        Object.keys(value).some((key) => !['elementKey', 'hide', 'readOnly'].includes(key)) ||
        typeof value.hide !== 'boolean' ||
        typeof value.readOnly !== 'boolean'
      )
        return fail('界面控制目标无效');
      return { elementKey: text(value.elementKey, 255), hide: value.hide, readOnly: value.readOnly };
    });
  } else if (input.formKey !== undefined || input.targets !== undefined)
    return fail('只有界面规则可以指定表单元素');
  return rule;
}
function businessRuleProposalInputSchema() {
  const boundedText = (maxLength: number) => ({ type: 'string', minLength: 1, maxLength });
  const common = {
    code: { ...boundedText(64), pattern: '^[a-z][A-Za-z0-9]{0,63}$' },
    expression: boundedText(4000),
    enabled: { type: 'boolean' },
  };
  const branch = (kind: string, properties: Record<string, unknown>, required: string[] = []) => ({
    type: 'object',
    additionalProperties: false,
    required: ['code', 'kind', 'expression', 'enabled', ...required],
    properties: { ...common, kind: { type: 'string', const: kind }, ...properties },
  });
  return {
    type: 'object',
    oneOf: [
      branch('CALCULATION', { targetField: boundedText(255) }, ['targetField']),
      {
        ...branch('VALIDATION', {
          targetField: { ...boundedText(255), pattern: '^[^.]+$' },
          messageTemplate: boundedText(500),
        }),
        description: businessRuleValidationHelp,
      },
      branch(
        'UI_CONTROL',
        {
          formKey: boundedText(255),
          targets: {
            type: 'array',
            minItems: 1,
            maxItems: 40,
            items: {
              type: 'object',
              additionalProperties: false,
              required: ['elementKey', 'hide', 'readOnly'],
              properties: {
                elementKey: boundedText(255),
                hide: { type: 'boolean' },
                readOnly: { type: 'boolean' },
              },
            },
          },
        },
        ['formKey', 'targets'],
      ),
    ],
  };
}

export function parseBusinessRuleTrial(input: unknown): BusinessRuleTrialInput {
  if (
    !object(input) ||
    Object.keys(input).some((key) => !['sampleValues', 'sampleChildren'].includes(key)) ||
    !object(input.sampleValues)
  )
    return fail('试算输入无效');
  const children = input.sampleChildren ?? {};
  if (!object(children) || Object.keys(children).length > 20 || JSON.stringify(input).length > 24000)
    return fail('试算样例过大');
  for (const rows of Object.values(children)) {
    if (!Array.isArray(rows) || rows.length > 100 || rows.some((row) => !object(row)))
      return fail('明细样例必须是最多100行的列表');
  }
  return {
    sampleValues: structuredClone(input.sampleValues),
    sampleChildren: structuredClone(children) as BusinessRuleTrialInput['sampleChildren'],
  };
}

function catalogPage(records: unknown[], offset = 0, budget = 6000) {
  const { items, page } = pageAssistantCatalog(records, offset, budget);
  return { items, ...page };
}
interface TrialObservation extends Pick<
  BusinessRuleTrialResult,
  'values' | 'children' | 'changedFields' | 'errors'
> {
  sample: BusinessRuleTrialInput;
  labels: Record<string, string>;
  enabledRuleCount: number;
}

export function createBusinessRuleAssistantSurface(
  adapter: BusinessRuleAssistantAdapter,
  requestTurn: AssistantTurnRequester,
  contributed: () => AssistantCapability[] = () => [],
): AssistantSurface {
  let prepared: AssistantOperationProposal | undefined;
  const empty = (code: string, description: string) => ({
    code,
    description,
    inputSchema: emptyAssistantCapabilityInputSchema(),
  });
  return {
    describe: () => {
      const summary = adapter.summary();
      return {
        surface: 'business-rule-governance',
        title: summary.title,
        facts: {
          ...summary,
          ...(summary.factsAvailable === false
            ? {}
            : {
                validationBehavior: businessRuleValidationHelp,
                fields: catalogPage(adapter.catalog('fields'), 0, 3000),
                childFields: catalogPage(adapter.catalog('childFields'), 0, 3000),
                aggregateFields: catalogPage(adapter.catalog('aggregateFields'), 0, 3000),
                rules: catalogPage(adapter.catalog('rules'), 0, 3000),
                catalogCounts: Object.fromEntries(
                  sections.map((section) => [section, adapter.catalog(section).length]),
                ),
                guidance:
                  'Use these current fields, childFields, aggregateFields and rules directly when complete. Read only missing pages or relevant catalogs. Validation runs on the main record, with an optional main-field error location; check child rows through aggregate functions, not scalar child references or child targets. All user-facing text, including progress, must use the user language.',
              }),
        },
      };
    },
    requestTurn,
    capabilities: () =>
      adapter.summary().factsAvailable === false
        ? [
            ...contributed(),
            ...(adapter.readCurrent && adapter.summary().submissionStatus === 'unknown'
              ? [
                  {
                    effect: 'read' as const,
                    changesReadState: true,
                    descriptor: empty(
                      'rules.read-current',
                      'Read current formal rules after an unknown apply response, preserving the candidate for fresh review. Never resubmits or proves the original request succeeded. Any new application needs a new confirmation.',
                    ),
                    parseInput: parseEmptyAssistantCapabilityInput,
                    async execute(_input: unknown, context: Parameters<AssistantCapability['execute']>[1]) {
                      await adapter.readCurrent!(context.signal, context.commitInternalState);
                      return {
                        originalSubmission: 'unknown',
                        currentConfigurationRead: true,
                        candidatePreserved: true,
                      };
                    },
                    present: () => ({
                      title: '已读取当前规则',
                      lines: ['原提交结果仍未知；候选已保留，后续应用须重新审阅。'],
                    }),
                  },
                ]
              : []),
          ]
        : [
            ...contributed(),
            {
              effect: 'read',
              descriptor: {
                code: 'rules.describe',
                description:
                  'Read missing standard governance facts by section and offset. For a specific main, child or aggregate field, use keyword to search its name or business title across the complete current field catalog, including unread pages. total counts matches; catalogTotal counts the unfiltered catalog. coverage is complete only when every matching entry is returned; partial or oversized entries are not absent. Use supplied current facts directly. Fields are writable main targets; childFields are writable direct-child targets calculated per row; aggregateFields are inputs inside main aggregate functions. rules.persistence distinguishes saved rules from drafts. Do not read functions for simple operators or forms for calculations/validations.',
                inputSchema: {
                  type: 'object',
                  additionalProperties: false,
                  required: ['section'],
                  properties: {
                    section: { type: 'string', enum: sections },
                    offset: { type: 'integer', minimum: 0 },
                    keyword: { type: 'string', minLength: 1, maxLength: 100 },
                  },
                },
              },
              parseInput(input) {
                if (
                  !object(input) ||
                  Object.keys(input).some((key) => !['section', 'offset', 'keyword'].includes(key)) ||
                  !sections.includes(input.section as (typeof sections)[number]) ||
                  !Number.isSafeInteger(input.offset ?? 0) ||
                  Number(input.offset ?? 0) < 0
                )
                  return fail('目录分页无效');
                const keyword = input.keyword === undefined ? undefined : text(input.keyword, 100);
                if (keyword && !['fields', 'childFields', 'aggregateFields'].includes(String(input.section)))
                  return fail('按名称查找仅用于主表、直接子表或汇总字段目录');
                return { section: String(input.section), offset: Number(input.offset ?? 0), keyword };
              },
              async execute(input) {
                const { section, offset, keyword } = input as {
                  section: string;
                  offset: number;
                  keyword?: string;
                };
                const catalog = adapter.catalog(section);
                const records = keyword
                  ? catalog.filter(
                      (field) =>
                        object(field) &&
                        [field.fieldName, field.title].some(
                          (value) =>
                            typeof value === 'string' &&
                            value.toLocaleLowerCase().includes(keyword.toLocaleLowerCase()),
                        ),
                    )
                  : catalog;
                return {
                  section,
                  ...(keyword ? { keyword, catalogTotal: catalog.length } : {}),
                  ...catalogPage(records, offset),
                };
              },
            },
            ...(adapter.summary().editable
              ? [
                  {
                    effect: 'configuration-draft' as const,
                    descriptor: {
                      code: 'rules.revise',
                      description:
                        'Add or replace ONE editable rule in the visible unsaved governance candidate; keep the same code when correcting it. Other rules are preserved. enabled describes behavior AFTER application, not whether saved: for a trial-only request use an enabled unsaved candidate, never add a second disabled copy. Use discovered fields/functions/forms. Field references MUST use braces, e.g. {amount} >= 0; quote text literals. expression is RHS for calculation; targetField is separate. VALIDATION blocks saving on false; nonblocking reminders are not supported by this editor. Never substitute blocking validation for a reminder without user agreement. VALIDATION runs on the main record: optional targetField is a main-field error location; child fields require aggregate functions, never scalar child references or child targets. Omit formKey and targets; messageTemplate is the failure message. Omit unused optional properties instead of empty strings. No persistence. Trial already performs server precheck; when samples are requested, trial directly after revision. Otherwise preview. Ask for application confirmation only when requested.',
                      inputSchema: businessRuleProposalInputSchema(),
                    },
                    parseInput: parseBusinessRuleProposal,
                    present(output) {
                      const result = output as { unchanged?: boolean };
                      return {
                        title: result.unchanged ? '业务规则未变更' : '业务规则草稿已更新',
                        lines: [
                          result.unchanged
                            ? '本次没有新增修改；规则的正式状态见当前治理目录。'
                            : '仅更新当前工作区草稿，尚未检查或应用。',
                        ],
                      };
                    },
                    async execute(input: BusinessRuleProposal, context) {
                      const existing = adapter
                        .catalog('rules')
                        .find((value) => object(value) && value.code === input.code);
                      if (
                        object(existing) &&
                        existing.editable !== false &&
                        existing.kind === input.kind &&
                        JSON.stringify(toProposal(existing as unknown as BusinessRuleSnapshotRule)) ===
                          JSON.stringify(toProposal(input))
                      ) {
                        return { saved: false, code: input.code, unchanged: true };
                      }
                      return context.applyEffect(() => {
                        adapter.revise(input);
                        return { saved: false, code: input.code };
                      });
                    },
                  } satisfies AssistantCapability<BusinessRuleProposal>,
                ]
              : []),
            {
              effect: 'read',
              descriptor: empty(
                'rules.preview',
                'Check the complete current calculation/validation candidate with the standard server compiler; preserves read-only rules. Does not apply. UI-control rules are finally validated at standard application.',
              ),
              parseInput: parseEmptyAssistantCapabilityInput,
              present(output) {
                const result = output as { valid: boolean; errors: { message: string }[] };
                return {
                  title: result.valid ? '计算与保存校验检查通过' : '计算与保存校验检查未通过',
                  lines: [
                    ...result.errors.map((error) => error.message),
                    '本次检查没有应用任何更改；界面控制仍需在应用时校验。',
                  ],
                };
              },
              async execute(_input, context) {
                const result = await adapter.preview(context.signal);
                return {
                  valid: !result.errors.length,
                  errors: result.errors,
                  executionOrder: result.executionOrder,
                };
              },
            },
            {
              effect: 'read',
              descriptor: {
                code: 'rules.trial',
                description:
                  'Execute current main and direct-child calculations and validations on explicit sampleValues and sampleChildren without saving. Only enabled candidate rules execute. After each requested sample is trialed, report the results and stop; do not repeatedly read unchanged catalogs. Returned children contain computed row values; main aggregates consume these results. Explicit empty child arrays mean zero rows. Does not trial UI controls. Reference facts are resolved by the server using the business tenant selected in the governance page, never submit dotted reference values.',
                inputSchema: {
                  type: 'object',
                  additionalProperties: false,
                  required: ['sampleValues'],
                  properties: {
                    sampleValues: { type: 'object' },
                    sampleChildren: {
                      type: 'object',
                      additionalProperties: { type: 'array', maxItems: 100, items: { type: 'object' } },
                    },
                  },
                },
              },
              parseInput: parseBusinessRuleTrial,
              present(output) {
                const result = output as TrialObservation;
                const label = (field: string) => result.labels[field] || field;
                const value = (input: unknown) =>
                  input == null ? '空' : typeof input === 'object' ? JSON.stringify(input) : String(input);
                return {
                  title: '规则试算（未保存）',
                  lines: [
                    ...Object.entries(result.sample.sampleValues).map(
                      ([field, input]) => `输入 · ${label(field)}：${value(input)}`,
                    ),
                    ...Object.entries(result.sample.sampleChildren).flatMap(([relation, rows]) => [
                      `明细 ${relation}：${rows.length} 行`,
                      ...rows.map(
                        (row, index) =>
                          `第 ${index + 1} 行：${Object.entries(row)
                            .map(([field, input]) => `${label(relation + '.' + field)}：${value(input)}`)
                            .join('；')}`,
                      ),
                    ]),
                    ...(result.enabledRuleCount
                      ? []
                      : ['当前没有启用的计算或保存校验；本次不能证明预期规则有效。']),
                    ...result.changedFields.map(
                      (field) => `结果 · ${label(field)}：${value(businessRuleTrialValue(result, field))}`,
                    ),
                    ...(result.errors.length
                      ? result.errors.map((error) => error.message)
                      : ['本次样例未发现校验错误；不代表配置已应用或完整业务验收通过。']),
                  ],
                };
              },
              async execute(input, context) {
                const sample = structuredClone(input as BusinessRuleTrialInput);
                const labels = Object.fromEntries(
                  [...adapter.catalog('fields'), ...adapter.catalog('aggregateFields')]
                    .filter(object)
                    .map((field) => [String(field.fieldName), String(field.title || field.fieldName)]),
                );
                const enabledRuleCount = adapter
                  .catalog('rules')
                  .filter(object)
                  .filter(
                    (rule) => rule.enabled && ['CALCULATION', 'VALIDATION'].includes(String(rule.kind)),
                  ).length;
                const result = await adapter.trial(sample, context.signal);
                return {
                  sample,
                  labels,
                  enabledRuleCount,
                  values: result.values,
                  children: result.children,
                  changedFields: result.changedFields,
                  errors: result.errors,
                  saved: false,
                };
              },
            },
            {
              effect: 'read',
              descriptor: empty(
                'rules.prepare-apply',
                'Prepare human confirmation for all visible unsaved rule changes through the standard governance apply boundary. No application before the human clicks. UI effects and existing user edits are included; explain the full scope.',
              ),
              parseInput: parseEmptyAssistantCapabilityInput,
              async execute(_input, context) {
                const proposal = await adapter.prepareConfirmation(context.signal);
                context.commitInternalState(() => {
                  prepared = proposal;
                });
                return { pendingConfirmation: true, saved: false };
              },
              propose() {
                if (!prepared) return fail('请重新准备确认');
                return prepared;
              },
            },
          ],
  };
}
