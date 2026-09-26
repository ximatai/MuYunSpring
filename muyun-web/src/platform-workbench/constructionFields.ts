import { requireConfirmedConstructionPlan, type ConstructionPlanState } from './constructionPlanGuard';
import type {
  ConstructionField,
  ConstructionFieldDescription,
  ConstructionFieldResult,
} from '@muyun/web-contracts';
import {
  AppError,
  AssistantCapabilityUsageError,
  AssistantOperationRejectedError,
  type AssistantCapability,
  type AssistantOperationProposal,
  type ConstructionPlanClient,
} from '@muyun/web-core';

/** Ordinary field additions share metadata governance; the model never receives publication tokens. */
export function createConstructionFieldCapabilities(
  client: ConstructionPlanClient,
  current: () => ConstructionPlanState,
  accept: (result: ConstructionFieldResult, invalidate?: boolean) => void,
): AssistantCapability[] {
  let catalog:
    | { planId: string; generation: number; objectKey: string; description: ConstructionFieldDescription }
    | undefined;
  let prepared: AssistantOperationProposal | undefined;
  function object(value: unknown): Record<string, unknown> {
    if (!value || typeof value !== 'object' || Array.isArray(value))
      throw new AssistantCapabilityUsageError('字段参数无效');
    return value as Record<string, unknown>;
  }
  function text(value: unknown, max: number): string {
    if (typeof value !== 'string' || !value.trim() || value.length > max)
      throw new AssistantCapabilityUsageError('字段参数为空或过长');
    return value.trim();
  }
  const schema = (properties: Record<string, unknown>) => ({
    type: 'object',
    additionalProperties: false,
    required: Object.keys(properties),
    properties,
  });
  const stringSchema = (maxLength: number) => ({ type: 'string', minLength: 1, maxLength });
  const objectKey = stringSchema(64);
  function resultPresentation(result: ConstructionFieldResult) {
    return {
      title: '字段配置已提交',
      lines: [
        `${result.receipt.moduleAlias}：${result.receipt.fields.map((field) => field.title).join('、')}`,
        result.runtime
          ? `运行态：${result.runtime.status}${result.runtime.failureMessage ? `（${result.runtime.failureMessage}）` : ''}`
          : '运行态激活尚待核实，可查询字段建设结果。',
        '本回执只证明字段提交；页面、入口和业务验收请查询实际建设进度。',
      ],
    };
  }
  return [
    {
      effect: 'read',
      descriptor: {
        code: 'construction.describe-fields',
        description:
          'Read current fields and actual enabled field specifications for an initialized object. Must run before preparing ordinary field additions; never invent specification aliases.',
        inputSchema: schema({ objectKey }),
      },
      parseInput: (input) => ({ objectKey: text(object(input).objectKey, 64) }),
      async execute(input, context) {
        const before = requireConfirmedConstructionPlan(current);
        const key = (input as { objectKey: string }).objectKey;
        const description = await client.describeFields(before.saved.planId, key);
        context.commitInternalState(() => {
          catalog = {
            planId: before.saved.planId,
            generation: before.generation,
            objectKey: key,
            description,
          };
        });
        return description;
      },
    },
    {
      effect: 'read',
      descriptor: {
        code: 'construction.prepare-fields',
        description:
          'Preview adding 1–12 ordinary fields for a confirmed initialized object. Read construction.describe-fields first. New field names may be proposed; specAlias must come from its catalog. Supports required, unique and indexed only; no references, formula, extra business rules or pages. Returns a separate human confirmation card, never executes publication.',
        inputSchema: schema({
          objectKey,
          fields: {
            type: 'array',
            minItems: 1,
            maxItems: 12,
            items: schema({
              name: { ...stringSchema(64), pattern: '^[a-z][a-zA-Z0-9_]*$' },
              title: stringSchema(120),
              specAlias: stringSchema(64),
              required: { type: 'boolean' },
              unique: { type: 'boolean' },
              indexed: { type: 'boolean' },
            }),
          },
        }),
      },
      parseInput(input) {
        const value = object(input);
        if (!Array.isArray(value.fields) || !value.fields.length || value.fields.length > 12)
          throw new AssistantCapabilityUsageError('一次新增 1 至 12 个普通字段');
        const fields: ConstructionField[] = value.fields.map((entry) => {
          const field = object(entry);
          if (['required', 'unique', 'indexed'].some((key) => typeof field[key] !== 'boolean'))
            throw new AssistantCapabilityUsageError('字段约束必须明确为是或否');
          const name = text(field.name, 64);
          if (!/^[a-z][a-zA-Z0-9_]*$/.test(name)) throw new AssistantCapabilityUsageError('字段名称格式无效');
          return {
            name,
            title: text(field.title, 120),
            specAlias: text(field.specAlias, 64),
            required: field.required as boolean,
            unique: field.unique as boolean,
            indexed: field.indexed as boolean,
          };
        });
        return { objectKey: text(value.objectKey, 64), fields };
      },
      async execute(input, context) {
        const before = requireConfirmedConstructionPlan(current);
        const value = input as { objectKey: string; fields: ConstructionField[] };
        if (
          !catalog ||
          catalog.planId !== before.saved.planId ||
          catalog.generation !== before.generation ||
          catalog.objectKey !== value.objectKey
        )
          throw new AssistantCapabilityUsageError('请先读取当前对象的字段与规格目录');
        if (
          value.fields.some(
            (field) => !catalog!.description.specs.some((spec) => spec.alias === field.specAlias),
          )
        )
          throw new AssistantCapabilityUsageError('字段规格不在实际目录中');
        const labels = new Map(catalog.description.specs.map((spec) => [spec.alias, spec.title]));
        const preview = await client.previewFields(before.saved.planId, {
          ...value,
          planRevision: before.saved.revision,
          expectedMetadataVersion: catalog.description.metadataVersion,
        });
        if (preview.errors.length)
          throw new AssistantCapabilityUsageError(preview.errors.map((error) => error.message).join('；'));
        const stable = structuredClone(preview);
        const requestId = crypto.randomUUID();
        const { isCurrent } = before;
        context.commitInternalState(() => {
          prepared = {
            confirmLabel: '确认添加登记内容',
            expiresAt: Date.now() + 5 * 60_000,
            isCurrent,
            presentation: {
              title: `添加 ${stable.proposal.fields.length} 项登记内容`,
              lines: [
                ...stable.proposal.fields.map(
                  (field) =>
                    `${field.title}：${labels.get(field.specAlias)}；${field.required ? '必填' : '选填'}${field.unique ? '；不可重复' : ''}`,
                ),
                ...stable.warnings.map((warning) => warning.message),
                '确认后添加这些可填写的内容，保留已有内容。页面和入口仍须核实；不会自动计算金额或推进订单状态。',
              ],
              details: {
                title: '查看字段与存储详情',
                lines: [
                  `模块：${stable.moduleAlias} · 依据需求第 ${stable.proposal.planRevision} 版`,
                  ...stable.proposal.fields.map(
                    (field) =>
                      `${field.title}：${field.name} / ${field.specAlias}${field.indexed ? '；建立索引' : ''}`,
                  ),
                  '将新增实际数据列及上述约束。其他业务规则不在本次变更中。',
                ],
              },
            },
            async execute() {
              if (!isCurrent()) throw new AssistantOperationRejectedError('需求已变化，请重新预检字段');
              try {
                const result = await client.publishFields(before.saved.planId, {
                  requestId,
                  proposal: stable.proposal,
                  fingerprint: stable.fingerprint,
                });
                if (isCurrent()) accept(result);
                return resultPresentation(result);
              } catch (error) {
                if (error instanceof AppError && [400, 401, 403, 404, 409, 422].includes(error.status ?? 0))
                  throw new AssistantOperationRejectedError(error.message);
                throw error;
              }
            },
            async lookup() {
              const result = await client.fieldChange(before.saved.planId, requestId);
              if (!result) return undefined;
              if (isCurrent()) accept(result);
              return resultPresentation(result);
            },
          };
        });
        return { valid: true, awaitingHumanConfirmation: true };
      },
      propose() {
        if (!prepared) throw new AssistantCapabilityUsageError('请先完成字段预检');
        return prepared;
      },
    },
    {
      effect: 'read',
      descriptor: {
        code: 'construction.field-change-status',
        description:
          'Query a saved field publication receipt and actual runtime state. Use requestId from the construction plan fieldChanges, never invent it.',
        inputSchema: schema({ requestId: stringSchema(80) }),
      },
      parseInput: (input) => ({ requestId: text(object(input).requestId, 80) }),
      async execute(input, context) {
        const plan = current().saved;
        if (!plan) throw new AssistantCapabilityUsageError('请先恢复需求方案');
        const result = await client.fieldChange(plan.planId, (input as { requestId: string }).requestId);
        if (result) context.commitInternalState(() => accept(result, false));
        return result ?? { status: 'NOT_FOUND' };
      },
      present: (result) =>
        'receipt' in (result as object)
          ? resultPresentation(result as ConstructionFieldResult)
          : { title: '尚未查到字段回执', lines: ['不能据此推定操作未提交。'] },
    },
  ];
}
