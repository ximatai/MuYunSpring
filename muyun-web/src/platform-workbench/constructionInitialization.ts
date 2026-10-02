import { constructionObjectKeySchema, type ConstructionPlanState } from './constructionPlanGuard';
import {
  AssistantCapabilityUsageError,
  type AssistantCapability,
  type ConstructionPlanClient,
} from '@muyun/web-core';

/** Reads historical construction receipts; creation belongs to visible standard management pages. */
export function createConstructionInitializationCapabilities(
  client: ConstructionPlanClient,
  current: () => ConstructionPlanState,
): AssistantCapability[] {
  function objectInput(input: unknown) {
    if (!input || typeof input !== 'object' || Array.isArray(input))
      throw new AssistantCapabilityUsageError('初始化参数无效');
    return input as Record<string, unknown>;
  }
  function string(input: Record<string, unknown>, key: string, max: number) {
    const value = input[key];
    if (typeof value !== 'string' || !value.trim() || value.length > max)
      throw new AssistantCapabilityUsageError(`初始化参数 ${key} 无效`);
    return value.trim();
  }
  return [
    {
      effect: 'read',
      descriptor: {
        code: 'construction.initialization-status',
        description:
          'Read the committed initialization receipt and actual runtime activation state for a business object. Initialization is not application completion.',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['objectKey'],
          properties: { objectKey: constructionObjectKeySchema(current) },
        },
      },
      parseInput(input) {
        return { objectKey: string(objectInput(input), 'objectKey', 64) };
      },
      async execute(input) {
        const saved = current().saved;
        if (!saved) throw new AssistantCapabilityUsageError('请先确认或恢复需求方案');
        const result = await client.initialization(saved.planId, (input as { objectKey: string }).objectKey);
        return result ?? { status: 'NO_HISTORICAL_RECEIPT' };
      },
    },
  ];
}
