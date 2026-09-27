import {
  pageAssistantCatalog,
  AssistantCapabilityUsageError,
  type AssistantCapability,
  type ConstructionPlanClient,
} from '@muyun/web-core';

/** Reads the public configuration catalog; does not create or adopt existing modules. */
export function createConstructionReferenceDiscoveryCapabilities(
  client: ConstructionPlanClient,
): AssistantCapability[] {
  return [
    {
      effect: 'read',
      descriptor: {
        code: 'construction.find-business-objects',
        description:
          'Discover existing business modules before proposing new objects. referenceReady distinguishes reusable references from existing objects that need configuration work; never treat an unready object as absent or silently duplicate/modify it. Search titles or aliases and page with offset. Results are configuration candidates, not business-record permissions or approval to modify these modules. Discuss reuse with the user.',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          properties: { search: { type: 'string', maxLength: 120 }, offset: { type: 'integer', minimum: 0 } },
        },
      },
      parseInput(input) {
        const value = input as { search?: unknown; offset?: unknown } | null;
        if (!value || typeof value !== 'object' || Array.isArray(value))
          throw new AssistantCapabilityUsageError('目录查询参数无效');
        const search = value.search ?? '',
          offset = value.offset ?? 0;
        if (
          typeof search !== 'string' ||
          search.length > 120 ||
          !Number.isSafeInteger(offset) ||
          Number(offset) < 0
        )
          throw new AssistantCapabilityUsageError('目录查询参数无效');
        return { search: search.trim().toLowerCase(), offset: Number(offset) };
      },
      async execute(input) {
        const { search, offset } = input as { search: string; offset: number };
        const modules = (await client.businessObjects()).filter((module) =>
          `${module.title} ${module.alias}`.toLowerCase().includes(search),
        );
        return {
          modules: modules.slice(offset, offset + 20),
          total: modules.length,
          nextOffset: offset + 20 < modules.length ? offset + 20 : null,
        };
      },
    },
    {
      effect: 'read',
      descriptor: {
        code: 'construction.describe-reference-target',
        description:
          'Read real key and display-label candidates for an existing module from discovery or a confirmed initialization receipt. Use these fields in reference proposals; never invent target identifiers. This reads configuration, not business records. Use keyOffset and labelOffset with returned page.nextOffset for larger catalogs; oversized entries are explicitly reported.',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['moduleAlias'],
          properties: {
            moduleAlias: { type: 'string', maxLength: 128 },
            keyOffset: { type: 'integer', minimum: 0 },
            labelOffset: { type: 'integer', minimum: 0 },
          },
        },
      },
      parseInput(input) {
        const alias = (input as { moduleAlias?: unknown } | null)?.moduleAlias;
        if (
          typeof alias !== 'string' ||
          alias.length > 128 ||
          !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(alias)
        )
          throw new AssistantCapabilityUsageError('引用模块标识无效');
        const { keyOffset = 0, labelOffset = 0 } = input as { keyOffset?: unknown; labelOffset?: unknown };
        if (![keyOffset, labelOffset].every((offset) => Number.isSafeInteger(offset) && Number(offset) >= 0))
          throw new AssistantCapabilityUsageError('目录分页位置无效');
        return { moduleAlias: alias, keyOffset: Number(keyOffset), labelOffset: Number(labelOffset) };
      },
      async execute(input) {
        const { moduleAlias, keyOffset, labelOffset } = input as {
          moduleAlias: string;
          keyOffset: number;
          labelOffset: number;
        };
        const target = await client.referenceTarget(moduleAlias);
        const keys = pageAssistantCatalog(target.keyFields, keyOffset),
          labels = pageAssistantCatalog(target.labelFields, labelOffset);
        return {
          ...target,
          keyFields: keys.items,
          labelFields: labels.items,
          keyPage: keys.page,
          labelPage: labels.page,
        };
      },
    },
  ];
}
