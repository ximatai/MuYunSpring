import type { WorkflowDefinition, WorkflowDesign, WorkflowNode, WorkflowVersion } from '@muyun/web-contracts';
import {
  AssistantCapabilityUsageError,
  platformLanguageContext,
  type LanguageContext,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
  type AssistantCapability,
  type AssistantSurface,
  type AssistantTurnRequester,
} from '@muyun/web-core';
import {
  diagnoseWorkflowDraft,
  workflowParticipantFieldVisible,
  workflowAssistantExpressionVisible,
  parseWorkflowDesignOperations,
  workflowDesignOperationsSchema,
  type WorkflowDesignOperationBatch,
  type WorkflowParticipantField,
  type prepareWorkflowDesignOperations,
} from '../platform-components/workflowDesignOperations';
import { workflowParticipantRules } from '../platform-components/workflowParticipantPolicy';

export interface WorkflowConfigurationAssistantState {
  ready: boolean;
  busy?: boolean;
  editable: boolean;
  moduleAlias: string;
  definition?: WorkflowDefinition;
  version?: WorkflowVersion;
  design: WorkflowDesign;
  fields: readonly WorkflowParticipantField[];
  selectedNodeKeys: string[];
  selectedRouteKey: string;
  dirty: boolean;
}
export type WorkflowDraftChange = Pick<
  ReturnType<typeof prepareWorkflowDesignOperations>,
  'changes' | 'created' | 'diagnostics'
>;
export interface WorkflowConfigurationAssistantAdapter {
  readonly language?: LanguageContext;
  state(): WorkflowConfigurationAssistantState;
  /** Existing read-only expression endpoint, never the page validate/save pipeline. */
  validateCondition?(expression: string, signal: AbortSignal): Promise<void>;
  /** Fully prepares against the shared draft. Returned synchronous commit rechecks its revision. */
  prepare(batch: WorkflowDesignOperationBatch): () => WorkflowDraftChange;
}

function canWrite(state: WorkflowConfigurationAssistantState) {
  return state.ready && state.editable && !state.busy && state.version?.publishStatus === 'draft';
}

export function createWorkflowConfigurationAssistantSurface(
  adapter: WorkflowConfigurationAssistantAdapter,
  requestTurn: AssistantTurnRequester,
  contributedCapabilities: () => AssistantCapability[] = () => [],
): AssistantSurface {
  const language = adapter.language ?? platformLanguageContext;
  const ready = () => {
    const state = adapter.state();
    if (!state.ready)
      throw new AssistantCapabilityUsageError(
        language.t('platform.workflow-assistant.not-ready', '流程尚未加载就绪，请稍后重新读取'),
      );
    return state;
  };
  const describe: AssistantCapability = {
    effect: 'read',
    descriptor: {
      code: 'workflow.describe-draft',
      description:
        '读取当前流程定义、版本、同一画布草稿、已选对象、字段和参与人限制。truncated 表示省略或裁剪，不能据此认定对象不存在。未保存草稿不代表已发布。仅已列出的标识可用于已有对象；批内新对象使用 created 引用。',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute() {
      return describeWorkflowDraft(ready(), language);
    },
  };
  const diagnose: AssistantCapability = {
    effect: 'read',
    descriptor: {
      code: 'workflow.diagnose-draft',
      description:
        '只读诊断当前共享草稿的本地结构、必填、受支持参与人和条件配置。不保存、不调用会先保存的页面校验，不复制服务端发布规则。条件公式仍需服务端正式检查，人工保存、校验和发布后才能生效。',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute(_input, context) {
      const state = ready();
      const local = boundedDiagnostics(diagnoseWorkflowDraft(state.design, state.fields, language));
      const expressions = [
        ...new Set(
          state.design.links
            .map((route) => route.conditionExpression)
            .filter((value): value is string => Boolean(value?.trim())),
        ),
      ];
      const eligible = expressions
        .filter(
          (expression) =>
            expression.length <= 2000 && workflowAssistantExpressionVisible(expression, state.fields),
        )
        .slice(0, 16);
      const formulaChecks: Array<{ expression: string; valid: boolean; error?: string }> = [];
      if (adapter.validateCondition) {
        for (const expression of eligible) {
          if (!context.isCurrent())
            throw new AssistantCapabilityUsageError(
              language.t('platform.workflow-assistant.stale-diagnostic', '流程已变更，请重新诊断'),
            );
          let error: string | undefined;
          try {
            await adapter.validateCondition(expression, context.signal);
          } catch (cause) {
            if (context.signal.aborted || !context.isCurrent()) throw cause;
            error = language.t(
              'platform.workflow-assistant.formula-failed',
              '条件公式检查未通过，请在属性面板核对公式',
            );
          }
          if (!context.isCurrent())
            throw new AssistantCapabilityUsageError(
              language.t('platform.workflow-assistant.stale-diagnostic', '流程已变更，请重新诊断'),
            );
          formulaChecks.push({
            expression: expression.slice(0, 500),
            valid: !error,
            ...(error ? { error } : {}),
          });
        }
      }
      const formulaErrors = formulaChecks
        .filter((item) => !item.valid)
        .map((item) => `${item.error}：${item.expression}`);
      const omitted = expressions.length - formulaChecks.length;
      return {
        ...local,
        valid: local.valid && !formulaErrors.length,
        errors: [...local.errors, ...formulaErrors].slice(0, 30),
        warnings: [
          ...local.warnings,
          ...(omitted
            ? [
                language.t(
                  'platform.workflow-assistant.formula-omitted',
                  '部分条件尚未通过只读服务端公式检查，需人工正式校验。',
                ),
              ]
            : []),
        ].slice(0, 30),
        truncated:
          local.truncated ||
          omitted > 0 ||
          local.errors.length + formulaErrors.length > 30 ||
          local.warnings.length + (omitted ? 1 : 0) > 30 ||
          eligible.some((expression) => expression.length > 500),
        formulaChecks: {
          total: expressions.length,
          checked: formulaChecks.length,
          omitted,
          entries: formulaChecks,
          officialPublicationValidation: false,
        },
        unsaved: state.dirty,
        saved: false,
        published: false,
      };
    },
    present(output) {
      const result = output as ReturnType<typeof boundedDiagnostics>;
      return {
        title: language.t('platform.workflow-assistant.diagnostic-title', '流程草稿本地诊断'),
        lines: [
          ...result.errors.slice(0, 8),
          ...result.warnings.slice(0, 8),
          result.valid
            ? language.t(
                'platform.workflow-assistant.diagnostic-passed',
                '本地检查通过；仍需人工保存并执行服务端正式校验和发布。',
              )
            : language.t('platform.workflow-assistant.diagnostic-incomplete', '草稿尚不完整，请继续配置。'),
          ...(result.truncated || result.errors.length > 8 || result.warnings.length > 8
            ? [
                language.t(
                  'platform.workflow-assistant.diagnostic-truncated',
                  '诊断结果已截断，请在画布核对完整配置。',
                ),
              ]
            : []),
        ],
      };
    },
  };
  const revise: AssistantCapability<WorkflowDesignOperationBatch> = {
    effect: 'configuration-draft',
    descriptor: {
      code: 'workflow.revise-draft',
      description:
        '批量调整当前画布未保存草稿，完整校验后一次应用、一次撤销。仅支持审批/知会/审批完成里程碑、分支汇聚和路径条件；不覆盖整图、路径端点、ID、版本或冻结状态。参与人仅提交人、相对主管链和合法人员字段；账号/角色/部门/组织以及业务任务复杂配置须人工维护。新建对象用 as 命名并以 {created:as,part:部件} 引用，不能猜新ID。insert-node部件 node/next；insert-branch部件 branch/join/path1/path2/exit1/exit2/return1/return2/next；append-path部件 node/in/out。未涉及配置保留。不保存或发布，完成后请用户沿现有人工保存、校验、发布入口交付。',
      inputSchema: workflowDesignOperationsSchema(),
    },
    parseInput(input) {
      return usage(() => parseWorkflowDesignOperations(input, language), language);
    },
    async execute(input, context) {
      const state = ready();
      if (!canWrite(state))
        throw new AssistantCapabilityUsageError(
          language.t('platform.workflow-assistant.not-editable', '当前流程草稿不可编辑'),
        );
      const commit = usage(() => adapter.prepare(input), language);
      const result = context.applyEffect(commit);
      return {
        changes: result.changes.slice(0, 40).map((line) => line.slice(0, 500)),
        changesTruncated: result.changes.length > 40 || result.changes.some((line) => line.length > 500),
        created: result.created,
        diagnostics: boundedDiagnostics(result.diagnostics),
        unsaved: adapter.state().dirty,
        saved: false,
        published: false,
      };
    },
    present(output) {
      const result = output as WorkflowDraftChange & { changesTruncated?: boolean };
      return {
        title: language.t('platform.workflow-assistant.revised-title', '流程草稿已调整（未保存）'),
        lines: [
          ...result.diagnostics.errors.slice(0, 5),
          ...result.diagnostics.warnings.slice(0, 5),
          ...result.changes.slice(0, 20).map((line) => line.slice(0, 500)),
          ...(result.changesTruncated ||
          result.changes.length > 20 ||
          result.diagnostics.truncated ||
          result.diagnostics.errors.length > 5 ||
          result.diagnostics.warnings.length > 5
            ? [
                language.t(
                  'platform.workflow-assistant.change-truncated',
                  '变化与诊断摘要已截断，请在画布核对完整草稿。',
                ),
              ]
            : []),
          language.t(
            'platform.workflow-assistant.handoff',
            '尚未保存或发布。请核对画布，并使用现有人工保存、校验和发布入口交付。',
          ),
        ],
      };
    },
  };
  return {
    requestTurn,
    describe: () => {
      const state = adapter.state();
      return {
        surface: 'workflow-configuration',
        title: state.ready ? state.definition?.title?.slice(0, 200) : undefined,
        facts: {
          ready: state.ready,
          editable: canWrite(state),
          ...(state.ready ? { moduleAlias: state.moduleAlias, unsaved: state.dirty } : {}),
          delivery: language.t(
            'platform.workflow-assistant.delivery-fact',
            '共享草稿由人工保存、校验和发布；助手不会执行持久写入',
          ),
        },
      };
    },
    capabilities: () => {
      const state = adapter.state();
      return [
        ...contributedCapabilities(),
        ...(state.ready ? [describe, diagnose, ...(canWrite(state) ? [revise] : [])] : []),
      ];
    },
  };
}

function usage<T>(operation: () => T, language: LanguageContext): T {
  try {
    return operation();
  } catch (cause) {
    if (cause instanceof AssistantCapabilityUsageError) throw cause;
    throw new AssistantCapabilityUsageError(
      cause instanceof Error
        ? cause.message
        : language.t('platform.workflow-assistant.operation-invalid', '流程操作无效'),
    );
  }
}
function boundedDiagnostics(result: ReturnType<typeof diagnoseWorkflowDraft>) {
  return {
    ...result,
    errors: result.errors.slice(0, 30).map((line) => line.slice(0, 300)),
    warnings: result.warnings.slice(0, 30).map((line) => line.slice(0, 300)),
    truncated:
      result.truncated ||
      result.errors.length > 30 ||
      result.warnings.length > 30 ||
      [...result.errors, ...result.warnings].some((line) => line.length > 300),
  };
}

/** Explicit projection, with one shared payload budget. No persistent node IDs, layout JSON or policy text. */
export function describeWorkflowDraft(
  state: WorkflowConfigurationAssistantState,
  language: LanguageContext = platformLanguageContext,
) {
  let truncated = false;
  const text = (value: string | undefined, limit = 300) => {
    if (value && value.length > limit) truncated = true;
    return value?.slice(0, limit);
  };
  const participants = (node: WorkflowNode) => {
    if ((node.participantPolicyText?.length ?? 0) > 12000) {
      truncated = true;
      return { configured: true, omitted: true, manualConfigurationRequired: true };
    }
    try {
      const rules = workflowParticipantRules(node.participantPolicyText);
      if (rules.length > 16) truncated = true;
      return {
        configured: Boolean(rules.length),
        rules: rules.slice(0, 16).map((rule) => {
          if (
            rule.fieldName &&
            state.fields.some(
              (field) => field.name === rule.fieldName && !workflowParticipantFieldVisible(field),
            )
          )
            return { manualConfigurationRequired: true };
          return {
            type: text(rule.type),
            ...(rule.fieldName ? { fieldName: text(rule.fieldName) } : {}),
            ...(rule.relation
              ? { relation: text(rule.relation), depth: rule.depth, headOnly: rule.headOnly }
              : {}),
            ...(rule.ids?.length ? { targetCount: rule.ids.length, manualConfigurationRequired: true } : {}),
          };
        }),
      };
    } catch {
      return { configured: Boolean(node.participantPolicyText), invalid: true };
    }
  };
  const task = (node: WorkflowNode) => {
    if (node.nodeType !== 'task' && !node.nodeConfigText) return undefined;
    const summary = {
      taskDefinitionId: text(node.taskDefinitionId),
      configured: Boolean(node.nodeConfigText),
      manualConfigurationRequired: true,
    };
    if (!node.nodeConfigText) return summary;
    if (node.nodeConfigText.length > 20000) {
      truncated = true;
      return { ...summary, omitted: true };
    }
    try {
      const config: unknown = JSON.parse(node.nodeConfigText);
      if (!record(config)) return { ...summary, invalid: true };
      const entries = (value: unknown, kind: 'check' | 'guide') => {
        if (!Array.isArray(value)) return undefined;
        if (value.length > 6) truncated = true;
        return {
          total: value.length,
          entries: value
            .slice(0, 6)
            .filter(record)
            .map((item) => ({
              title: text(typeof item.title === 'string' ? item.title : undefined),
              kind: text(
                typeof item[`${kind}Kind`] === 'string' ? (item[`${kind}Kind`] as string) : undefined,
              ),
              enabled: item.enabled === true,
              ...(kind === 'check'
                ? {
                    expression: text(
                      typeof item.expression === 'string' &&
                        workflowAssistantExpressionVisible(item.expression, state.fields)
                        ? item.expression
                        : undefined,
                    ),
                  }
                : {}),
              advancedConfigurationPreserved: Boolean(item[`${kind}ConfigText`]),
            })),
        };
      };
      return {
        ...summary,
        definition: record(config.definition)
          ? {
              manualConfirm: config.definition.manualConfirm === true,
              enabled: config.definition.enabled !== false,
            }
          : undefined,
        checks: entries(config.checks, 'check'),
        guides: entries(config.guides, 'guide'),
      };
    } catch {
      return { ...summary, invalid: true };
    }
  };
  let remaining = 10000;
  const bounded = <T, R>(values: readonly T[], limit: number, project: (item: T) => R) => {
    const entries: R[] = [];
    for (const value of values.slice(0, limit)) {
      const entry = project(value);
      const size = JSON.stringify(entry).length;
      if (size > remaining) {
        truncated = true;
        break;
      }
      remaining -= size;
      entries.push(entry);
    }
    if (entries.length < values.length) truncated = true;
    return { total: values.length, entries, omitted: values.length - entries.length };
  };
  const definition = state.definition && {
    id: text(state.definition.id, 128),
    title: text(state.definition.title),
    alias: text(state.definition.alias ?? undefined),
    definitionStatus: text(state.definition.definitionStatus),
    approvalEnabled: state.definition.approvalEnabled,
    version: state.definition.version,
  };
  const version = state.version && {
    id: text(state.version.id, 128),
    version: state.version.version,
    versionNo: state.version.versionNo,
    publishStatus: text(state.version.publishStatus),
  };
  const nodes = bounded(state.design.nodes, 60, (node) => ({
    nodeKey: text(node.nodeKey, 128),
    nodeType: text(node.nodeType),
    title: text(node.title),
    approvalMode: text(node.approvalMode),
    approvalRatio: node.approvalRatio,
    milestoneType: text(node.milestoneType),
    routeMode: text(node.routeMode),
    selectorNodeKey: text(node.selectorNodeKey, 128),
    convergeNodeKey: text(node.convergeNodeKey, 128),
    convergeMode: text(node.convergeMode),
    convergeRatio: node.convergeRatio,
    requireManualSelectionReason: node.requireManualSelectionReason,
    participants: participants(node),
    businessTask: task(node),
  }));
  const routes = bounded(state.design.links, 100, (route) => ({
    routeKey: text(route.routeKey, 128),
    sourceNodeKey: text(route.sourceNodeKey, 128),
    targetNodeKey: text(route.targetNodeKey, 128),
    title: text(route.title),
    conditionExpression: text(
      workflowAssistantExpressionVisible(route.conditionExpression, state.fields)
        ? route.conditionExpression
        : undefined,
      500,
    ),
    defaultRoute: route.defaultRoute,
  }));
  const fields = bounded(state.fields.filter(workflowParticipantFieldVisible), 60, (field) => ({
    name: text(field.name),
    label: text(field.label),
    referenceModule: text(field.referenceModule),
    participantSupported: field.referenceModule === 'iam.user',
  }));
  const selectedNodes = state.selectedNodeKeys.slice(0, 20).map((key) => text(key, 128));
  if (selectedNodes.length < state.selectedNodeKeys.length) truncated = true;
  const selectedRoute = text(state.selectedRouteKey, 128);
  return {
    moduleAlias: text(state.moduleAlias, 128),
    definition,
    version,
    editable: canWrite(state),
    unsaved: state.dirty,
    nodes,
    routes,
    fields,
    selectedNodeKeys: selectedNodes,
    selectedRouteKey: selectedRoute,
    truncated,
    limits: {
      participantWrites: ['INITIATOR_SELF', 'RELATIVE:SUPERVISOR', 'FIELD:iam.user'],
      manualConfiguration: [
        'USER',
        'ROLE',
        'DEPT',
        'ORG',
        language.t('platform.workflow-assistant.manual-task', '业务任务复杂办理指引与完成检查'),
      ],
      delivery: language.t(
        'platform.workflow-assistant.delivery-limit',
        '共享草稿；须人工保存、服务端正式校验和发布。未保存不代表已生效。',
      ),
    },
  };
}
function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
