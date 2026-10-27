import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import type { AssistantFieldPolicy, WorkflowDesign, WorkflowNode } from '@muyun/web-contracts';
import {
  appendWorkflowBranchPath,
  createWorkflowApprovalNode,
  insertWorkflowBranch,
  insertWorkflowNode,
  removeWorkflowBranch,
  removeWorkflowNode,
  updateWorkflowRoute,
} from './workflowDesignEditing';
import { hasWorkflowParticipants, workflowParticipantRules } from './workflowParticipantPolicy';

export interface WorkflowParticipantField {
  name: string;
  label?: string;
  referenceModule?: string;
  assistantPolicy?: AssistantFieldPolicy;
  fieldControl?: { alias?: string };
}

/** The complete manual directory remains authoritative; only automatic assistant projection is restricted. */
export function workflowParticipantFieldVisible(field: WorkflowParticipantField): boolean {
  return field.assistantPolicy !== 'HIDDEN' && field.fieldControl?.alias !== 'password';
}

/** Formula text is derived configuration: conservatively omit expressions mentioning protected fields. */
export function workflowAssistantExpressionVisible(
  expression: string | undefined,
  fields: readonly WorkflowParticipantField[],
): boolean {
  return (
    !expression ||
    !fields.some((field) => !workflowParticipantFieldVisible(field) && expression.includes(field.name))
  );
}
export type WorkflowDraftReference = string | { created: string; part: string };
export type WorkflowDraftParticipant =
  | { type: 'INITIATOR_SELF' }
  | { type: 'FIELD'; fieldName: string }
  | { type: 'RELATIVE'; relation: 'SUPERVISOR'; depth: number; fieldName?: string };
export interface WorkflowNodeDraftPatch {
  title?: string;
  approvalMode?: 'all' | 'any' | 'ratio' | 'notice';
  approvalRatio?: number;
  participants?: WorkflowDraftParticipant[];
  milestoneType?: 'approval_completed';
}
export type WorkflowDesignOperation =
  | {
      op: 'insert-node';
      as: string;
      route: WorkflowDraftReference;
      node: WorkflowNodeDraftPatch & { kind: 'approval' | 'notice' | 'milestone'; title: string };
    }
  | { op: 'update-node'; node: WorkflowDraftReference; patch: WorkflowNodeDraftPatch }
  | { op: 'delete-node'; node: WorkflowDraftReference }
  | { op: 'insert-branch'; as: string; route: WorkflowDraftReference; title?: string }
  | { op: 'append-path'; as: string; branch: WorkflowDraftReference }
  | { op: 'delete-branch'; branch: WorkflowDraftReference }
  | {
      op: 'update-branch';
      branch: WorkflowDraftReference;
      patch: {
        title?: string;
        routeMode?: 'auto' | 'manual';
        selectorNode?: WorkflowDraftReference;
        requireManualSelectionReason?: boolean;
        convergeMode?: 'all' | 'any' | 'ratio';
        convergeRatio?: number;
      };
    }
  | {
      op: 'update-route';
      route: WorkflowDraftReference;
      patch: { title?: string; conditionExpression?: string; defaultRoute?: boolean };
    };
export interface WorkflowDesignOperationBatch {
  operations: WorkflowDesignOperation[];
}

// The schema and trusted parser share the same bounded grammar. This is deliberately
// limited to workflow editing, rather than a general platform schema mechanism.
type InputSchema = {
  type?: 'object' | 'string' | 'integer' | 'boolean' | 'array';
  properties?: Record<string, InputSchema>;
  additionalProperties?: false;
  required?: string[];
  minProperties?: number;
  minLength?: number;
  maxLength?: number;
  minimum?: number;
  maximum?: number;
  minItems?: number;
  maxItems?: number;
  items?: InputSchema;
  enum?: readonly (string | number)[];
  anyOf?: InputSchema[];
};
const text = (maxLength: number, minLength = 1): InputSchema => ({ type: 'string', minLength, maxLength });
const object = (properties: Record<string, InputSchema>, required: string[] = []): InputSchema => ({
  type: 'object',
  additionalProperties: false,
  properties,
  required,
});
const choice = (...values: string[]): InputSchema => ({ type: 'string', enum: values });
const reference: InputSchema = {
  anyOf: [text(128), object({ created: text(40), part: text(40) }, ['created', 'part'])],
};
const ratio: InputSchema = { type: 'integer', minimum: 1, maximum: 100 };
const participants: InputSchema = {
  type: 'array',
  minItems: 0,
  maxItems: 16,
  items: {
    anyOf: [
      object({ type: choice('INITIATOR_SELF') }, ['type']),
      object({ type: choice('FIELD'), fieldName: text(255) }, ['type', 'fieldName']),
      object(
        {
          type: choice('RELATIVE'),
          relation: choice('SUPERVISOR'),
          depth: { type: 'integer', minimum: 1, maximum: 32 },
          fieldName: text(255),
        },
        ['type', 'relation', 'depth'],
      ),
    ],
  },
};
const nodeProperties: Record<string, InputSchema> = {
  title: text(128),
  approvalMode: choice('all', 'any', 'ratio', 'notice'),
  approvalRatio: ratio,
  participants,
  milestoneType: choice('approval_completed'),
};
const patch = (properties: Record<string, InputSchema>): InputSchema => ({
  ...object(properties),
  minProperties: 1,
});
const operationSchema: InputSchema = {
  anyOf: [
    object(
      {
        op: choice('insert-node'),
        as: text(40),
        route: reference,
        node: object({ ...nodeProperties, kind: choice('approval', 'notice', 'milestone') }, [
          'kind',
          'title',
        ]),
      },
      ['op', 'as', 'route', 'node'],
    ),
    object({ op: choice('update-node'), node: reference, patch: patch(nodeProperties) }, [
      'op',
      'node',
      'patch',
    ]),
    object({ op: choice('delete-node'), node: reference }, ['op', 'node']),
    object({ op: choice('insert-branch'), as: text(40), route: reference, title: text(128) }, [
      'op',
      'as',
      'route',
    ]),
    object({ op: choice('append-path'), as: text(40), branch: reference }, ['op', 'as', 'branch']),
    object({ op: choice('delete-branch'), branch: reference }, ['op', 'branch']),
    object(
      {
        op: choice('update-branch'),
        branch: reference,
        patch: patch({
          title: text(128),
          routeMode: choice('auto', 'manual'),
          selectorNode: reference,
          requireManualSelectionReason: { type: 'boolean' },
          convergeMode: choice('all', 'any', 'ratio'),
          convergeRatio: ratio,
        }),
      },
      ['op', 'branch', 'patch'],
    ),
    object(
      {
        op: choice('update-route'),
        route: reference,
        patch: patch({
          title: text(128),
          conditionExpression: text(2000, 0),
          defaultRoute: { type: 'boolean' },
        }),
      },
      ['op', 'route', 'patch'],
    ),
  ],
};
const batchSchema = object(
  { operations: { type: 'array', minItems: 1, maxItems: 40, items: operationSchema } },
  ['operations'],
);

export function workflowDesignOperationsSchema(): Record<string, unknown> {
  return JSON.parse(JSON.stringify(batchSchema));
}
function matches(value: unknown, schema: InputSchema): boolean {
  if (schema.anyOf) return schema.anyOf.some((item) => matches(value, item));
  if (schema.enum && !schema.enum.includes(value as string)) return false;
  if (schema.type === 'string')
    return (
      typeof value === 'string' &&
      [...value].length >= (schema.minLength ?? 0) &&
      [...value].length <= (schema.maxLength ?? Infinity) &&
      (!schema.minLength || Boolean(value.trim()))
    );
  if (schema.type === 'integer')
    return (
      typeof value === 'number' &&
      Number.isSafeInteger(value) &&
      value >= (schema.minimum ?? -Infinity) &&
      value <= (schema.maximum ?? Infinity)
    );
  if (schema.type === 'boolean') return typeof value === 'boolean';
  if (schema.type === 'array')
    return (
      Array.isArray(value) &&
      value.length >= (schema.minItems ?? 0) &&
      value.length <= (schema.maxItems ?? Infinity) &&
      value.every((item) => matches(item, schema.items!))
    );
  if (schema.type === 'object') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const record = value as Record<string, unknown>;
    return (
      Object.keys(record).length >= (schema.minProperties ?? 0) &&
      (schema.required ?? []).every((key) => Object.hasOwn(record, key)) &&
      Object.keys(record).every(
        (key) => Object.hasOwn(schema.properties!, key) && matches(record[key], schema.properties![key]!),
      )
    );
  }
  return false;
}
export function parseWorkflowDesignOperations(
  input: unknown,
  language: LanguageContext = platformLanguageContext,
): WorkflowDesignOperationBatch {
  if (!matches(input, batchSchema))
    throw new Error(
      language.t(
        'platform.workflow-assistant.invalid-input',
        '流程操作参数无效：请使用已声明的操作、字段和容量限制',
      ),
    );
  const batch = JSON.parse(JSON.stringify(input)) as WorkflowDesignOperationBatch;
  const aliases = new Set<string>();
  for (const operation of batch.operations) {
    if ('as' in operation) {
      if (aliases.has(operation.as))
        throw new Error(language.t('platform.workflow-assistant.alias-invalid', '批内新建别名无效或重复'));
      aliases.add(operation.as);
    }
  }
  return batch;
}
function participantPolicy(
  rules: WorkflowDraftParticipant[],
  fields: readonly WorkflowParticipantField[],
  language: LanguageContext,
) {
  for (const rule of rules) {
    if (
      'fieldName' in rule &&
      rule.fieldName &&
      !fields.some(
        (field) =>
          field.name === rule.fieldName &&
          field.referenceModule === 'iam.user' &&
          workflowParticipantFieldVisible(field),
      )
    )
      throw new Error(
        language.t(
          'platform.workflow-assistant.participant-field-invalid',
          '参与人字段必须是当前模块已声明的账号引用字段',
        ),
      );
  }
  return JSON.stringify({ rules });
}
function nodePatch(
  node: WorkflowNode,
  patch: WorkflowNodeDraftPatch,
  fields: readonly WorkflowParticipantField[],
  language: LanguageContext,
): WorkflowNode {
  if (!['approval', 'milestone', 'task'].includes(node.nodeType))
    throw new Error(language.t('platform.workflow-assistant.specialized-node', '此节点仅支持专用结构操作'));
  const { participants: rules, ...values } = patch;
  if (
    node.nodeType !== 'approval' &&
    (values.approvalMode !== undefined || values.approvalRatio !== undefined)
  )
    throw new Error(
      language.t('platform.workflow-assistant.approval-node-only', '审批模式仅可用于审批/知会节点'),
    );
  if (node.nodeType !== 'milestone' && values.milestoneType !== undefined)
    throw new Error(language.t('platform.workflow-assistant.milestone-only', '里程碑类型仅可用于里程碑节点'));
  if (node.nodeType === 'milestone' && rules !== undefined)
    throw new Error(language.t('platform.workflow-assistant.milestone-participants', '里程碑不配置参与人'));
  const result = {
    ...node,
    ...values,
    ...(rules === undefined ? {} : { participantPolicyText: participantPolicy(rules, fields, language) }),
  };
  if (values.approvalRatio !== undefined && result.approvalMode !== 'ratio')
    throw new Error(
      language.t('platform.workflow-assistant.approval-ratio-only', '审批比例仅可用于比例审批模式'),
    );
  return result;
}

export interface WorkflowDraftDiagnostics {
  valid: boolean;
  errors: string[];
  warnings: string[];
  truncated: boolean;
  formulaCheck: 'requires-server-validation';
  publishedValidation: false;
}

/** Local diagnostics describe an incomplete editable draft, never formal publish readiness. */
export function diagnoseWorkflowDraft(
  design: WorkflowDesign,
  fields: readonly WorkflowParticipantField[] = [],
  language: LanguageContext = platformLanguageContext,
): WorkflowDraftDiagnostics {
  const errors: string[] = [],
    warnings: string[] = [];
  if (design.nodes.length > 300 || design.links.length > 600) {
    return {
      valid: false,
      errors: [
        language.t(
          'platform.workflow-assistant.diagnostic-capacity',
          '当前流程超过助手本地诊断容量，请人工复核',
        ),
      ],
      warnings: [],
      truncated: true,
      formulaCheck: 'requires-server-validation',
      publishedValidation: false,
    };
  }
  let fullyChecked = true;
  const nodes = new Map(design.nodes.map((node) => [node.nodeKey, node]));
  if (nodes.size !== design.nodes.length)
    errors.push(language.t('platform.workflow-assistant.duplicate-nodes', '存在重复节点标识'));
  if (new Set(design.links.map((route) => route.routeKey)).size !== design.links.length)
    errors.push(language.t('platform.workflow-assistant.duplicate-routes', '存在重复路径标识'));
  for (const kind of ['start', 'end'])
    if (design.nodes.filter((node) => node.nodeType === kind).length !== 1)
      errors.push(
        language.t('platform.workflow-assistant.unique-node-kind', '流程需要唯一的 {kind} 节点', { kind }),
      );
  for (const node of design.nodes) {
    const name = node.title || node.nodeKey;
    if (!node.title?.trim())
      errors.push(
        language.t('platform.workflow-assistant.title-required', '{name}：标题未填写', { name: name }),
      );
    const incoming = design.links.filter((route) => route.targetNodeKey === node.nodeKey);
    const outgoing = design.links.filter((route) => route.sourceNodeKey === node.nodeKey);
    if (node.nodeType === 'start' && incoming.length)
      errors.push(
        language.t('platform.workflow-assistant.start-incoming', '{name}：开始节点不能有进入路径', {
          name: name,
        }),
      );
    if (node.nodeType === 'end' && outgoing.length)
      errors.push(
        language.t('platform.workflow-assistant.end-outgoing', '{name}：结束节点不能有离开路径', {
          name: name,
        }),
      );
    if (node.nodeType !== 'end' && !outgoing.length)
      errors.push(
        language.t('platform.workflow-assistant.broken-route', '{name}：路径中断，未连接后续节点', {
          name: name,
        }),
      );
    if (['start', 'approval', 'task', 'milestone', 'converge'].includes(node.nodeType) && outgoing.length > 1)
      errors.push(
        language.t(
          'platform.workflow-assistant.multiple-exits',
          '{name}：普通节点不能有多个出口，请使用分支',
          { name: name },
        ),
      );
    if (['approval', 'task'].includes(node.nodeType)) {
      if (!hasWorkflowParticipants(node.participantPolicyText))
        errors.push(
          language.t('platform.workflow-assistant.participants-required', '{name}：参与人未配置或无效', {
            name: name,
          }),
        );
      else {
        try {
          const policy = node.participantPolicyText?.trim();
          if (policy?.startsWith('{') || policy?.startsWith('[')) {
            const raw: unknown = JSON.parse(policy);
            const root = raw as Record<string, unknown>;
            const entries: unknown[] = Array.isArray(raw)
              ? raw
              : Array.isArray(root.rules)
                ? root.rules
                : [raw];
            const knownKeys = [
              'type',
              'ids',
              'userIds',
              'targetIds',
              'userId',
              'targetId',
              'id',
              'value',
              'fieldName',
              'relation',
              'depth',
              'headOnly',
            ];
            const unknownRoot =
              !Array.isArray(raw) &&
              Array.isArray(root.rules) &&
              Object.keys(root).some((key) => key !== 'rules');
            const unknownRules = entries.some(
              (entry) =>
                entry &&
                typeof entry === 'object' &&
                !Array.isArray(entry) &&
                Object.entries(entry).some(
                  ([key, value]) =>
                    !knownKeys.includes(key) || (key === 'headOnly' && typeof value !== 'boolean'),
                ),
            );
            if (unknownRoot || unknownRules) {
              fullyChecked = false;
              warnings.push(
                language.t(
                  'platform.workflow-assistant.participants-preserved',
                  '{name}：参与人保留了本地无法检查的配置，请人工核对',
                  { name: name },
                ),
              );
            }
          }
          for (const rule of workflowParticipantRules(node.participantPolicyText)) {
            // Retained manual rules remain valid facts; hidden fields cannot be diagnosed through assistant output.
            if (
              rule.fieldName &&
              fields.some((field) => field.name === rule.fieldName && !workflowParticipantFieldVisible(field))
            )
              continue;
            if (
              rule.fieldName &&
              !fields.some((field) => field.name === rule.fieldName && field.referenceModule === 'iam.user')
            )
              errors.push(
                language.t(
                  'platform.workflow-assistant.participant-field-unavailable',
                  '{name}：参与人账号字段不可用',
                  { name: name },
                ),
              );
            if (['USER', 'ROLE', 'DEPT', 'ORG'].includes(rule.type))
              warnings.push(
                language.t(
                  'platform.workflow-assistant.identity-manual',
                  '{name}：指定身份候选需人工核对，助手不提供此类写入',
                  { name: name },
                ),
              );
            if (
              rule.type === 'RELATIVE' &&
              ![
                'SELF',
                'SUPERVISOR',
                'DEPARTMENT',
                'DEPARTMENT_MANAGER',
                'ORGANIZATION',
                'ORGANIZATION_MANAGER',
              ].includes(rule.relation ?? '')
            )
              errors.push(
                language.t('platform.workflow-assistant.relative-invalid', '{name}：相对人员关系无效', {
                  name: name,
                }),
              );
            if (rule.headOnly !== undefined) {
              fullyChecked = false;
              warnings.push(
                language.t(
                  'platform.workflow-assistant.head-manual',
                  '{name}：负责人筛选由人工配置，本地未核实筛选结果',
                  { name: name },
                ),
              );
            }
            if (
              rule.type === 'RELATIVE' &&
              rule.relation === 'SUPERVISOR' &&
              (!Number.isInteger(rule.depth ?? 1) || (rule.depth ?? 1) < 0 || (rule.depth ?? 1) > 32)
            )
              errors.push(
                language.t('platform.workflow-assistant.depth-invalid', '{name}：主管链层数无效', {
                  name: name,
                }),
              );
          }
        } catch {
          errors.push(
            language.t('platform.workflow-assistant.participant-rules-invalid', '{name}：参与人规则无效', {
              name: name,
            }),
          );
        }
      }
    }
    if (
      node.nodeType === 'approval' &&
      !['all', 'any', 'ratio', 'notice'].includes(node.approvalMode ?? 'all')
    )
      errors.push(
        language.t('platform.workflow-assistant.approval-mode-invalid', '{name}：审批模式不受支持', {
          name: name,
        }),
      );
    if (
      node.approvalMode === 'ratio' &&
      (!Number.isInteger(node.approvalRatio ?? 100) ||
        (node.approvalRatio ?? 100) < 1 ||
        (node.approvalRatio ?? 100) > 100)
    )
      errors.push(
        language.t('platform.workflow-assistant.approval-ratio-invalid', '{name}：审批比例无效', {
          name: name,
        }),
      );
    if (
      node.nodeType === 'milestone' &&
      (node.milestoneType ?? 'approval_completed') !== 'approval_completed'
    )
      errors.push(
        language.t('platform.workflow-assistant.milestone-invalid', '{name}：里程碑类型不受支持', {
          name: name,
        }),
      );
    if (node.nodeType === 'task')
      warnings.push(
        language.t(
          'platform.workflow-assistant.task-manual',
          '{name}：业务任务办理指引与完成检查由人工配置，需正式发布校验',
          { name: name },
        ),
      );
    if (node.nodeType === 'branch') {
      if (nodes.get(node.convergeNodeKey ?? '')?.nodeType !== 'converge')
        errors.push(
          language.t('platform.workflow-assistant.join-missing', '{name}：缺少配对汇聚', { name: name }),
        );
      const exits = design.links.filter((route) => route.sourceNodeKey === node.nodeKey);
      if (exits.length < 2)
        errors.push(
          language.t('platform.workflow-assistant.branch-paths', '{name}：分支至少需要两条路径', {
            name: name,
          }),
        );
      if (exits.filter((route) => route.defaultRoute).length > 1)
        errors.push(
          language.t('platform.workflow-assistant.default-unique', '{name}：默认出口不唯一', { name: name }),
        );
      if (
        node.routeMode === 'manual' &&
        !['start', 'approval', 'task'].includes(nodes.get(node.selectorNodeKey ?? '')?.nodeType ?? '')
      )
        errors.push(
          language.t('platform.workflow-assistant.selector-missing', '{name}：人工选路节点缺失或无效', {
            name: name,
          }),
        );
      if (!['auto', 'manual'].includes(node.routeMode ?? 'auto'))
        errors.push(
          language.t('platform.workflow-assistant.route-mode-invalid', '{name}：选路模式不受支持', {
            name: name,
          }),
        );
    }
    if (node.nodeType === 'converge') {
      if (!['all', 'any', 'ratio'].includes(node.convergeMode ?? 'all'))
        errors.push(
          language.t('platform.workflow-assistant.converge-mode-invalid', '{name}：汇聚规则不受支持', {
            name: name,
          }),
        );
      if (
        node.convergeMode === 'ratio' &&
        (!Number.isInteger(node.convergeRatio ?? 100) ||
          (node.convergeRatio ?? 100) < 1 ||
          (node.convergeRatio ?? 100) > 100)
      )
        errors.push(
          language.t('platform.workflow-assistant.converge-ratio-invalid', '{name}：汇聚比例无效', {
            name: name,
          }),
        );
    }
  }
  for (const route of design.links) {
    if (!nodes.has(route.sourceNodeKey) || !nodes.has(route.targetNodeKey))
      errors.push(
        language.t('platform.workflow-assistant.endpoints-missing', '{name}：路径端点不存在', {
          name: route.routeKey,
        }),
      );
    if (route.defaultRoute && route.conditionExpression?.trim())
      errors.push(
        language.t(
          'platform.workflow-assistant.default-condition-diagnostic',
          '{name}：默认出口不能同时配置条件',
          { name: route.routeKey },
        ),
      );
    if (route.conditionExpression?.trim())
      warnings.push(
        language.t(
          'platform.workflow-assistant.formula-pending',
          '{name}：条件公式待服务端只读检查及正式发布校验',
          { name: route.routeKey },
        ),
      );
  }
  const visited = new Set<string>(),
    visiting = new Set<string>();
  const adjacency = new Map<string, string[]>();
  for (const route of design.links)
    adjacency.set(route.sourceNodeKey, [...(adjacency.get(route.sourceNodeKey) ?? []), route.targetNodeKey]);
  let cycle = false;
  function visit(key: string) {
    if (visiting.has(key)) {
      cycle = true;
      return;
    }
    if (visited.has(key)) return;
    visited.add(key);
    visiting.add(key);
    for (const target of adjacency.get(key) ?? []) visit(target);
    visiting.delete(key);
  }
  const start = design.nodes.find((node) => node.nodeType === 'start');
  if (start) visit(start.nodeKey);
  if (visited.size < nodes.size)
    errors.push(language.t('platform.workflow-assistant.unreachable-nodes', '存在从开始节点不可达的节点'));
  for (const node of design.nodes) visit(node.nodeKey);
  if (cycle) errors.push(language.t('platform.workflow-assistant.cycle', '流程存在循环路径'));
  const uniqueErrors = [...new Set(errors)],
    uniqueWarnings = [...new Set(warnings)];
  return {
    valid: !errors.length && fullyChecked,
    errors: uniqueErrors.slice(0, 40).map((message) =>
      message.length > 500
        ? language.t('platform.workflow-assistant.truncated-message', '{message}…（已截断）', {
            message: message.slice(0, 480),
          })
        : message,
    ),
    warnings: uniqueWarnings.slice(0, 40).map((message) =>
      message.length > 500
        ? language.t('platform.workflow-assistant.truncated-message', '{message}…（已截断）', {
            message: message.slice(0, 480),
          })
        : message,
    ),
    truncated:
      uniqueErrors.length > 40 ||
      uniqueWarnings.length > 40 ||
      [...uniqueErrors, ...uniqueWarnings].some((message) => message.length > 500),
    formulaCheck: 'requires-server-validation',
    publishedValidation: false,
  };
}

/** Pure preparation: all operations succeed before the caller publishes one history entry. */
export function prepareWorkflowDesignOperations(
  design: WorkflowDesign,
  input: WorkflowDesignOperationBatch,
  fields: readonly WorkflowParticipantField[] = [],
  language: LanguageContext = platformLanguageContext,
) {
  const batch = parseWorkflowDesignOperations(input, language);
  if (design.nodes.length > 300 || design.links.length > 600)
    throw new Error(
      language.t('platform.workflow-assistant.edit-capacity', '当前流程超过助手可编辑容量，请人工配置'),
    );
  let next = design;
  const created: Record<string, Record<string, string>> = Object.create(null);
  const keyPrefix = `assistant_${crypto.randomUUID().replaceAll('-', '')}`;
  const resolve = (reference: WorkflowDraftReference) => {
    const key = typeof reference === 'string' ? reference : created[reference.created]?.[reference.part];
    if (!key || !/^[a-zA-Z0-9_.:-]{1,128}$/.test(key))
      throw new Error(
        language.t(
          'platform.workflow-assistant.reference-invalid',
          '对象标识无效，或批内引用尚未创建/部件不存在',
        ),
      );
    return key;
  };
  const getNode = (reference: WorkflowDraftReference) => {
    const node = next.nodes.find((item) => item.nodeKey === resolve(reference));
    if (!node) throw new Error(language.t('platform.workflow-assistant.node-missing', '节点不存在'));
    return node;
  };
  for (const [index, operation] of batch.operations.entries()) {
    const key = `${keyPrefix}_${index}`;
    if (operation.op === 'insert-node') {
      const { kind, ...values } = operation.node;
      const base =
        kind === 'milestone'
          ? { nodeKey: key, nodeType: 'milestone', milestoneType: 'approval_completed' }
          : {
              ...createWorkflowApprovalNode(key, operation.node.title),
              ...(kind === 'notice' ? { approvalMode: 'notice' } : {}),
            };
      if (kind === 'notice' && values.approvalMode !== undefined && values.approvalMode !== 'notice')
        throw new Error(
          language.t('platform.workflow-assistant.notice-mode-only', '知会节点必须使用知会模式'),
        );
      const updated = nodePatch(base, values, fields, language);
      const routeKey = resolve(operation.route);
      next = insertWorkflowNode(next, routeKey, updated, language);
      created[operation.as] = { node: key, next: `${key}_next` };
    } else if (operation.op === 'update-node') {
      const node = getNode(operation.node);
      const updated = nodePatch(node, operation.patch, fields, language);
      next = { ...next, nodes: next.nodes.map((item) => (item === node ? updated : item)) };
    } else if (operation.op === 'delete-node') {
      const node = getNode(operation.node);
      if (!['approval', 'task', 'milestone'].includes(node.nodeType))
        throw new Error(
          language.t(
            'platform.workflow-assistant.ordinary-delete-only',
            '仅可删除普通节点；分支请使用成对删除',
          ),
        );
      next = removeWorkflowNode(next, node.nodeKey, language);
    } else if (operation.op === 'insert-branch') {
      const routeKey = resolve(operation.route);
      next = insertWorkflowBranch(next, routeKey, key, language);
      if (operation.title !== undefined)
        next = {
          ...next,
          nodes: next.nodes.map((node) =>
            node.nodeKey === key ? { ...node, title: operation.title } : node,
          ),
        };
      created[operation.as] = {
        branch: key,
        join: `${key}_join`,
        path1: `${key}_path1`,
        path2: `${key}_path2`,
        exit1: `${key}_exit1`,
        exit2: `${key}_exit2`,
        return1: `${key}_return1`,
        return2: `${key}_return2`,
        next: `${key}_next`,
      };
    } else if (operation.op === 'append-path') {
      const branchKey = resolve(operation.branch);
      next = appendWorkflowBranchPath(next, branchKey, key, language);
      created[operation.as] = { node: key, in: `${key}_in`, out: `${key}_out` };
    } else if (operation.op === 'delete-branch') {
      const branchKey = resolve(operation.branch);
      next = removeWorkflowBranch(next, branchKey, language);
    } else if (operation.op === 'update-branch') {
      const branch = getNode(operation.branch);
      const join = next.nodes.find(
        (node) => node.nodeKey === branch.convergeNodeKey && node.nodeType === 'converge',
      );
      if (branch.nodeType !== 'branch' || !join)
        throw new Error(
          language.t('platform.workflow-assistant.branch-join-invalid', '分支必须具有有效的配对汇聚'),
        );
      const { convergeMode, convergeRatio, selectorNode, ...values } = operation.patch;
      const selector = selectorNode === undefined ? undefined : getNode(selectorNode);
      if (selector && !['start', 'approval', 'task'].includes(selector.nodeType))
        throw new Error(
          language.t(
            'platform.workflow-assistant.selector-invalid',
            '请选择开始、审批或业务任务作为人工选路节点',
          ),
        );
      const updated = {
        ...branch,
        ...values,
        ...(selector ? { selectorNodeKey: selector.nodeKey } : {}),
        ...(values.routeMode === 'auto'
          ? { selectorNodeKey: undefined, requireManualSelectionReason: false }
          : {}),
      };
      if (selector && updated.routeMode !== 'manual')
        throw new Error(
          language.t('platform.workflow-assistant.auto-selector', '自动分支不配置人工选路节点'),
        );
      const updatedJoin = {
        ...join,
        ...(convergeMode === undefined ? {} : { convergeMode }),
        ...(convergeRatio === undefined ? {} : { convergeRatio }),
      };
      if (convergeRatio !== undefined && updatedJoin.convergeMode !== 'ratio')
        throw new Error(
          language.t('platform.workflow-assistant.converge-ratio-only', '汇聚比例仅用于比例汇聚'),
        );
      next = {
        ...next,
        nodes: next.nodes.map((node) => (node === branch ? updated : node === join ? updatedJoin : node)),
      };
    } else {
      const routeKey = resolve(operation.route);
      const route = next.links.find((item) => item.routeKey === routeKey);
      if (!route) throw new Error(language.t('platform.workflow-assistant.route-missing', '路径不存在'));
      if (
        (operation.patch.conditionExpression !== undefined || operation.patch.defaultRoute !== undefined) &&
        next.nodes.find((node) => node.nodeKey === route.sourceNodeKey)?.nodeType !== 'branch'
      )
        throw new Error(
          language.t('platform.workflow-assistant.branch-condition-only', '仅分支出口支持条件和默认出口'),
        );
      if (operation.patch.defaultRoute === true && operation.patch.conditionExpression?.trim())
        throw new Error(
          language.t('platform.workflow-assistant.default-condition', '默认出口不能同时配置条件'),
        );
      if (
        route.defaultRoute &&
        operation.patch.defaultRoute !== false &&
        operation.patch.conditionExpression?.trim()
      )
        throw new Error(
          language.t('platform.workflow-assistant.clear-default-first', '请先取消默认出口，再设置条件'),
        );
      next = updateWorkflowRoute(next, routeKey, operation.patch, language);
    }
    if (next.nodes.length > 300 || next.links.length > 600)
      throw new Error(
        language.t('platform.workflow-assistant.result-capacity', '操作结果超过助手可编辑容量'),
      );
  }
  const changes = workflowDesignChangeLines(design, next, language);
  return { design: next, changes, created, diagnostics: diagnoseWorkflowDraft(next, fields, language) };
}

export function workflowDesignChangeLines(
  before: WorkflowDesign,
  after: WorkflowDesign,
  language: LanguageContext = platformLanguageContext,
): string[] {
  const changes: string[] = [];
  const labels: Record<string, string> = {
    title: language.t('platform.workflow-assistant.label-title', '标题'),
    approvalMode: language.t('platform.workflow-assistant.label-approval-mode', '审批/知会模式'),
    approvalRatio: language.t('platform.workflow-assistant.label-approval-ratio', '审批比例'),
    participantPolicyText: language.t('platform.workflow-assistant.label-participants', '参与人规则'),
    milestoneType: language.t('platform.workflow-assistant.label-milestone', '里程碑'),
    routeMode: language.t('platform.workflow-assistant.label-route-mode', '选路模式'),
    selectorNodeKey: language.t('platform.workflow-assistant.label-selector', '人工选路节点'),
    requireManualSelectionReason: language.t(
      'platform.workflow-assistant.label-route-reason',
      '选路原因要求',
    ),
    convergeMode: language.t('platform.workflow-assistant.label-converge-mode', '汇聚规则'),
    convergeRatio: language.t('platform.workflow-assistant.label-converge-ratio', '汇聚比例'),
    conditionExpression: language.t('platform.workflow-assistant.label-condition', '条件公式'),
    defaultRoute: language.t('platform.workflow-assistant.label-default', '默认出口'),
    sourceNodeKey: language.t('platform.workflow-assistant.label-source', '路径来源连接'),
    targetNodeKey: language.t('platform.workflow-assistant.label-target', '路径目标连接'),
  };
  for (const [kind, previous, current, key] of [
    [language.t('platform.workflow-assistant.label-node', '节点'), before.nodes, after.nodes, 'nodeKey'],
    [language.t('platform.workflow-assistant.label-route', '路径'), before.links, after.links, 'routeKey'],
  ] as const) {
    const old = new Map(
      previous.map((item) => [String((item as unknown as Record<string, unknown>)[key]), item]),
    );
    for (const item of current) {
      const id = String((item as unknown as Record<string, unknown>)[key]);
      const prior = old.get(id);
      if (!prior)
        changes.push(
          language.t('platform.workflow-assistant.change-add', '新增{kind}：{name}', {
            kind,
            name: item.title || id,
          }),
        );
      else if (JSON.stringify(prior) !== JSON.stringify(item)) {
        const changed = Object.keys({ ...prior, ...item }).filter(
          (name) =>
            JSON.stringify((prior as unknown as Record<string, unknown>)[name]) !==
            JSON.stringify((item as unknown as Record<string, unknown>)[name]),
        );
        changes.push(
          language.t('platform.workflow-assistant.change-update', '修改{kind}：{name}（{fields}）', {
            kind,
            name: item.title || id,
            fields: changed
              .map(
                (name) => labels[name] ?? language.t('platform.workflow-assistant.label-other', '其他配置'),
              )
              .join(', '),
          }),
        );
      }
      old.delete(id);
    }
    for (const [id, item] of old)
      changes.push(
        language.t('platform.workflow-assistant.change-delete', '删除{kind}：{name}', {
          kind,
          name: item.title || id,
        }),
      );
  }
  if (before.layoutJson !== after.layoutJson)
    changes.push(language.t('platform.workflow-assistant.layout-changed', '画布布局已修改'));
  return changes;
}
