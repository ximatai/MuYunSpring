import {
  AssistantCapabilityUsageError,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
  type AssistantCapability,
  type AssistantSurface,
  type AssistantTurnRequester,
} from '@muyun/web-core';
import type { CodeRule, CodeTarget, CodePreview } from './codeConfiguration';

export interface CodeConfigurationAssistantAdapter {
  state(): {
    ready: boolean;
    previewable?: boolean;
    editable: boolean;
    busy: boolean;
    dirty: boolean;
    recoveryRequired: boolean;
    draft?: CodeRule;
    target?: CodeTarget;
  };
  prepare(patch: Record<string, unknown>, current: () => boolean): () => unknown;
  trial(signal: AbortSignal, current: () => boolean): Promise<CodePreview>;
}
export function createCodeConfigurationAssistantSurface(
  adapter: CodeConfigurationAssistantAdapter,
  requestTurn: AssistantTurnRequester,
): AssistantSurface {
  const ready = () => {
    const state = adapter.state();
    if (!state.ready || !state.draft || !state.target)
      throw new AssistantCapabilityUsageError('请先打开已授权的编号规则');
    return { ...state, draft: state.draft, target: state.target };
  };
  const describe: AssistantCapability = {
    effect: 'read',
    descriptor: {
      code: 'code-rule.describe-draft',
      description:
        '读取当前人工编辑器的编号规则、字段声明和可见来源。未保存草稿不会影响发号。预览不预留号码。',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute() {
      const state = ready();
      const visible = new Set(state.target.sources.map((source) => source.fieldName));
      const rule = {
        ...state.draft,
        segments: state.draft.segments.map((segment) =>
          (segment.sourceRef && !visible.has(segment.sourceRef)) ||
          [...(segment.formulaExpr?.matchAll(/\{([^{}]+)\}/g) ?? [])].some((match) => !visible.has(match[1]!))
            ? {
                segmentType: segment.segmentType,
                restricted: true,
                message: '此片段含当前目录之外的来源，请人工维护；修改其他配置时保留。',
              }
            : segment,
        ),
      };
      const text = JSON.stringify({
        rule,
        target: state.target,
        unsaved: state.dirty,
        recoveryRequired: state.recoveryRequired,
      });
      if (text.length > 16000)
        return {
          truncated: true,
          message: '规则较大，请在页面检查完整配置；不能据此推断规则或来源不存在。',
          title: state.draft.title,
          unsaved: state.dirty,
        };
      return JSON.parse(text) as unknown;
    },
  };
  const revise: AssistantCapability<Record<string, unknown>> = {
    effect: 'configuration-draft',
    descriptor: {
      code: 'code-rule.revise-draft',
      description:
        '调整当前共享编号草稿。仅名称、编号方式、换号/回收策略及格式与流水策略；不改变字段声明、目标、启停、组织或生效区间。格式片段和流水策略替换时须提供完整候选，不携带ID或版本。完成后人工审阅保存。',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          title: { type: 'string' },
          mode: { type: 'string', enum: ['AUTO', 'MANUAL', 'AUTO_WITH_MANUAL_EDIT'] },
          linkedUpdate: { type: 'boolean' },
          allowRecycle: { type: 'boolean' },
          segments: {
            type: 'array',
            maxItems: 40,
            items: {
              type: 'object',
              additionalProperties: false,
              required: ['segmentType', 'mappings'],
              properties: {
                segmentType: {
                  type: 'string',
                  enum: [
                    'CONSTANT',
                    'FIELD_VALUE',
                    'CONTEXT_VAR',
                    'SYSTEM_TIME',
                    'SEQUENCE',
                    'VALUE_MAPPING',
                    'FORMULA',
                  ],
                },
                ...Object.fromEntries(
                  [
                    'sourceRef',
                    'fixedValue',
                    'formulaExpr',
                    'dateFormat',
                    'padMode',
                    'padChar',
                    'separator',
                    'nullPolicy',
                    'truncatePolicy',
                  ].map((key) => [key, { type: ['string', 'null'] }]),
                ),
                length: { type: ['integer', 'null'] },
                sortOrder: { type: 'integer' },
                sequenceBasis: { type: 'boolean' },
                mappings: {
                  type: 'array',
                  maxItems: 100,
                  items: {
                    type: 'object',
                    additionalProperties: false,
                    required: ['sourceValue', 'targetValue', 'defaultMapping', 'enabled'],
                    properties: {
                      sourceValue: { type: 'string' },
                      targetValue: { type: 'string' },
                      defaultMapping: { type: 'boolean' },
                      enabled: { type: 'boolean' },
                    },
                  },
                },
              },
            },
          },
          sequencePolicy: {
            type: ['object', 'null'],
            additionalProperties: false,
            properties: {
              ...Object.fromEntries(
                ['startValue', 'stepValue', 'sequenceLength', 'maxValue'].map((key) => [
                  key,
                  { type: ['integer', 'null'] },
                ]),
              ),
              resetPolicy: { type: 'string', enum: ['NONE', 'DAY', 'MONTH', 'YEAR'] },
              overflowPolicy: { type: 'string', enum: ['ERROR', 'RESET'] },
            },
          },
        },
      },
    },
    parseInput(input) {
      if (!input || typeof input !== 'object' || Array.isArray(input) || JSON.stringify(input).length > 16000)
        throw new AssistantCapabilityUsageError('编号草稿参数无效');
      return input as Record<string, unknown>;
    },
    async execute(input, context) {
      if (!ready().editable) throw new AssistantCapabilityUsageError('当前编号规则不可编辑');
      try {
        return context.applyEffect(adapter.prepare(input, context.isCurrent));
      } catch (cause) {
        if (cause instanceof AssistantCapabilityUsageError) throw cause;
        throw new AssistantCapabilityUsageError('编号候选格式不完整，请核对后重新准备');
      }
    },
    present() {
      return {
        title: '编号规则草稿已调整',
        lines: ['请在页面核对编号格式、流水与换号回收策略，再由人工保存。尚未改变正式发号规则。'],
      };
    },
  };
  const trial: AssistantCapability = {
    effect: 'read',
    descriptor: {
      code: 'code-rule.preview-draft',
      description:
        '使用页面当前明确填写的样例与流水值调用后端同源试算；不分配流水、不占用号码。缺少样例时请用户在试算区补充。',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute(_input, context) {
      const state = ready();
      if (state.busy || state.recoveryRequired || state.previewable === false)
        throw new AssistantCapabilityUsageError('请先完成当前操作或恢复核对');
      const visible = new Set(state.target.sources.map((source) => source.fieldName));
      if (
        state.draft.segments.some(
          (segment) =>
            (segment.sourceRef && !visible.has(segment.sourceRef)) ||
            [...(segment.formulaExpr?.matchAll(/\{([^{}]+)\}/g) ?? [])].some(
              (match) => !visible.has(match[1]!),
            ),
        )
      )
        throw new AssistantCapabilityUsageError('当前规则含目录之外来源，请在页面人工试算');
      return { ...(await adapter.trial(context.signal, context.isCurrent)), reserved: false, saved: false };
    },
  };
  return {
    requestTurn,
    describe: () => {
      const state = adapter.state();
      return {
        surface: 'code-configuration',
        facts: {
          ready: state.ready,
          editable: state.editable,
          unsaved: state.dirty,
          recoveryRequired: state.recoveryRequired,
          delivery: '人工和助手共享编号草稿；人工审阅保存，试算不占号。',
        },
      };
    },
    capabilities: () => {
      const state = adapter.state();
      return state.ready
        ? [
            describe,
            ...(!state.busy && !state.recoveryRequired
              ? [...(state.previewable !== false ? [trial] : []), ...(state.editable ? [revise] : [])]
              : []),
          ]
        : [];
    },
  };
}
