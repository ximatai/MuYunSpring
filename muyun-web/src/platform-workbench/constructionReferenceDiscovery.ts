import {
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
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
        code: 'construction.describe-design-contract',
        description:
          'Read the standard platform contract BEFORE proposing technical field bindings, even before any module exists. Record names used for reference display must use recordName with titleField enabled; labels are customizable. Bind FIELD/REQUIRED/UNIQUE for that same business name to the same standard field. Do not duplicate it under a business-specific alias. inheritedFields already belong to the platform; declarableCapabilities lists optional capabilities and their managed fields, not enabled capabilities. Existing business changes still require reading current metadata. This catalog is not authorization or a replacement for field specifications and publish preflight.',
        inputSchema: emptyAssistantCapabilityInputSchema(),
      },
      parseInput: parseEmptyAssistantCapabilityInput,
      execute: () => client.designContract(),
    },
    {
      effect: 'read',
      descriptor: {
        code: 'construction.find-business-objects',
        description:
          'Discover existing business modules before proposing new objects. referenceReady distinguishes reusable references from existing objects that need configuration work; never treat an unready object as absent or silently duplicate/modify it. After the application choice is settled, use applicationAlias from observed application facts to list its modules before discussing each module. Search module or application titles/aliases and page with offset. Do not infer absence from a search for several module names at once. Results are configuration candidates, not business-record permissions or approval to modify these modules. Respect explicit user decisions to create isolated new objects or reuse existing ones. Ask about reuse only when that choice is unresolved; discovery must not reopen an already answered scope decision.',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          properties: {
            applicationAlias: { type: 'string', pattern: '^[a-z][a-z0-9_]*$', maxLength: 64 },
            search: { type: 'string', maxLength: 120 },
            offset: { type: 'integer', minimum: 0 },
          },
        },
      },
      parseInput(input) {
        const value = input as { applicationAlias?: unknown; search?: unknown; offset?: unknown } | null;
        if (!value || typeof value !== 'object' || Array.isArray(value))
          throw new AssistantCapabilityUsageError('目录查询参数无效');
        const applicationAlias = value.applicationAlias;
        if (
          applicationAlias !== undefined &&
          (typeof applicationAlias !== 'string' ||
            applicationAlias.length > 64 ||
            !/^[a-z][a-z0-9_]*$/.test(applicationAlias))
        )
          throw new AssistantCapabilityUsageError('应用标识无效');
        const search = value.search ?? '',
          offset = value.offset ?? 0;
        if (
          typeof search !== 'string' ||
          search.length > 120 ||
          !Number.isSafeInteger(offset) ||
          Number(offset) < 0
        )
          throw new AssistantCapabilityUsageError('目录查询参数无效');
        return { applicationAlias, search: search.trim().toLowerCase(), offset: Number(offset) };
      },
      async execute(input) {
        const { applicationAlias, search, offset } = input as {
          applicationAlias?: string;
          search: string;
          offset: number;
        };
        const modules = (await client.businessObjects()).filter(
          (module) =>
            (applicationAlias === undefined || module.applicationAlias === applicationAlias) &&
            `${module.title} ${module.alias} ${module.applicationTitle ?? ''} ${module.applicationAlias ?? ''}`
              .toLowerCase()
              .includes(search),
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
