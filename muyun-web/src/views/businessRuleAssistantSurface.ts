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
  BusinessRulePreview,
  BusinessRuleTrialResult,
  BusinessRuleSnapshotRule,
} from './businessRuleGovernance';
import { toProposal, businessRuleTrialValue } from './businessRuleGovernance';

export interface BusinessRuleTrialInput {
  sampleValues: Record<string, unknown>;
  sampleChildren: Record<string, Record<string, unknown>[]>;
}
export interface BusinessRuleAssistantAdapter {
  summary(): { moduleAlias: string; title?: string; editable: boolean };
  catalog(section: string): unknown[];
  revise(rule: BusinessRuleProposal): void;
  preview(signal: AbortSignal): Promise<BusinessRulePreview>;
  trial(input: BusinessRuleTrialInput, signal: AbortSignal): Promise<BusinessRuleTrialResult>;
  prepareConfirmation(signal: AbortSignal): Promise<AssistantOperationProposal>;
}
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
  return { items, total: page.total, nextOffset: page.nextOffset, oversizedIndexes: page.oversizedIndexes };
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
    describe: () => ({
      surface: 'business-rule-governance',
      title: adapter.summary().title,
      facts: {
        ...adapter.summary(),
        fields: catalogPage(adapter.catalog('fields'), 0, 3000),
        rules: catalogPage(adapter.catalog('rules'), 0, 3000),
        catalogCounts: Object.fromEntries(
          sections.map((section) => [section, adapter.catalog(section).length]),
        ),
        guidance:
          'Use these current fields and rules directly when complete. Read only missing pages or relevant catalogs; validation does not need the function or UI form catalog. All user-facing text, including progress, must use the user language.',
      },
    }),
    requestTurn,
    capabilities: () => [
      ...contributed(),
      {
        effect: 'read',
        descriptor: {
          code: 'rules.describe',
          description:
            'Read only missing standard governance facts by section and offset; current fields and rules are already in surface facts. Do not read functions for simple operators or forms for calculations/validations. Fields are writable main targets; childFields are writable direct-child targets such as lines.amount, calculated per row from the same child. aggregateFields can be read by main formulas only inside aggregate functions; forms are UI targets; rules include current unsaved edits and read-only rules. Never infer missing records from a partial page.',
          inputSchema: {
            type: 'object',
            additionalProperties: false,
            required: ['section'],
            properties: {
              section: { type: 'string', enum: sections },
              offset: { type: 'integer', minimum: 0 },
            },
          },
        },
        parseInput(input) {
          if (
            !object(input) ||
            !sections.includes(input.section as (typeof sections)[number]) ||
            !Number.isSafeInteger(input.offset ?? 0) ||
            Number(input.offset ?? 0) < 0
          )
            return fail('目录分页无效');
          return { section: String(input.section), offset: Number(input.offset ?? 0) };
        },
        async execute(input) {
          const { section, offset } = input as { section: string; offset: number };
          return { section, ...catalogPage(adapter.catalog(section), offset) };
        },
      },
      ...(adapter.summary().editable
        ? [
            {
              effect: 'configuration-draft' as const,
              descriptor: {
                code: 'rules.revise',
                description:
                  'Add or replace ONE editable rule in the visible unsaved governance candidate; keep the same code when correcting it. Other rules are preserved. enabled describes behavior AFTER application, not whether saved: for a trial-only request use an enabled unsaved candidate, never add a second disabled copy. Use discovered fields/functions/forms. Field references MUST use braces, e.g. {amount} >= 0; quote text literals. expression is RHS for calculation; targetField is separate. No persistence. Trial already performs server precheck; when samples are requested, trial directly after revision. Otherwise preview. Ask for application confirmation only when requested.',
                inputSchema: {
                  type: 'object',
                  additionalProperties: false,
                  required: ['code', 'kind', 'expression', 'enabled'],
                  properties: {
                    code: { type: 'string', maxLength: 64, pattern: '^[a-z][A-Za-z0-9]{0,63}$' },
                    kind: { type: 'string', enum: ['CALCULATION', 'VALIDATION', 'UI_CONTROL'] },
                    expression: { type: 'string', maxLength: 4000 },
                    enabled: { type: 'boolean' },
                    targetField: { type: 'string' },
                    messageTemplate: { type: 'string' },
                    formKey: { type: 'string' },
                    targets: {
                      type: 'array',
                      maxItems: 40,
                      items: {
                        type: 'object',
                        additionalProperties: false,
                        required: ['elementKey', 'hide', 'readOnly'],
                        properties: {
                          elementKey: { type: 'string' },
                          hide: { type: 'boolean' },
                          readOnly: { type: 'boolean' },
                        },
                      },
                    },
                  },
                },
              },
              parseInput: parseBusinessRuleProposal,
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
