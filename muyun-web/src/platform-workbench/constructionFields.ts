import {
  constructionObjectKeySchema,
  requireConfirmedConstructionPlan,
  type ConstructionPlanState,
} from './constructionPlanGuard';
import type { ConstructionFieldResult } from '@muyun/web-contracts';
import {
  pageAssistantCatalog,
  AssistantCapabilityUsageError,
  type AssistantCapability,
  type ConstructionPlanClient,
} from '@muyun/web-core';

/** Construction reads current evidence; field edits belong to the shared metadata workspace. */
export function createConstructionFieldCapabilities(
  client: ConstructionPlanClient,
  current: () => ConstructionPlanState,
  accept: (result: ConstructionFieldResult, invalidate?: boolean) => void,
): AssistantCapability[] {
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
  const objectKey = constructionObjectKeySchema(current);
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
          'Read paginated current fields and actual enabled specifications. Includes direct children and saved calculation rules. Use fieldOffset/specOffset/referenceOffset/childOffset/calculationOffset with nextOffset to continue. For all field changes select the returned moduleAlias with configuration.select-metadata-module and use shared metadata candidates; use rule governance for formulas. Oversized entries are reported separately, never silently truncated. Never invent specification aliases.',
        inputSchema: {
          ...schema({ objectKey }),
          properties: {
            objectKey,
            fieldOffset: { type: 'integer', minimum: 0 },
            specOffset: { type: 'integer', minimum: 0 },
            referenceOffset: { type: 'integer', minimum: 0 },
            childOffset: { type: 'integer', minimum: 0 },
            calculationOffset: { type: 'integer', minimum: 0 },
          },
        },
      },
      parseInput(input) {
        const value = object(input);
        const fieldOffset = value.fieldOffset ?? 0,
          specOffset = value.specOffset ?? 0,
          referenceOffset = value.referenceOffset ?? 0,
          childOffset = value.childOffset ?? 0,
          calculationOffset = value.calculationOffset ?? 0;
        if (
          ![fieldOffset, specOffset, referenceOffset, childOffset, calculationOffset].every(
            (offset) => Number.isSafeInteger(offset) && Number(offset) >= 0,
          )
        )
          throw new AssistantCapabilityUsageError('目录分页位置无效');
        return {
          objectKey: text(value.objectKey, 64),
          fieldOffset: Number(fieldOffset),
          specOffset: Number(specOffset),
          referenceOffset: Number(referenceOffset),
          childOffset: Number(childOffset),
          calculationOffset: Number(calculationOffset),
        };
      },
      async execute(input) {
        const before = requireConfirmedConstructionPlan(current);
        const {
          objectKey: key,
          fieldOffset,
          specOffset,
          referenceOffset,
          childOffset,
          calculationOffset,
        } = input as {
          objectKey: string;
          fieldOffset: number;
          specOffset: number;
          referenceOffset: number;
          childOffset: number;
          calculationOffset: number;
        };
        const description = await client.describeFields(before.saved.planId, key);
        const fields = pageAssistantCatalog(description.fields, fieldOffset);
        const specs = pageAssistantCatalog(description.specs, specOffset);
        const references = pageAssistantCatalog(
          Object.entries(description.references ?? {}),
          referenceOffset,
        );
        const children = pageAssistantCatalog(Object.entries(description.children ?? {}), childOffset);
        const calculations = pageAssistantCatalog(description.calculationRules ?? [], calculationOffset);
        return {
          children: Object.fromEntries(children.items),
          childPage: children.page,
          calculationRules: calculations.items,
          calculationPage: calculations.page,
          moduleAlias: description.moduleAlias,
          planRevision: description.planRevision,
          metadataVersion: description.metadataVersion,
          fields: fields.items,
          references: Object.fromEntries(references.items),
          referencePage: references.page,
          specs: specs.items,
          fieldPage: fields.page,
          specPage: specs.page,
        };
      },
    },
    {
      effect: 'read',
      changesReadState: true,
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
