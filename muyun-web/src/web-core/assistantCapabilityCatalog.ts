import type { AssistantCapabilityResult } from '@muyun/web-contracts';
import type { AssistantCapability } from './assistantSurface';
import { OperationUsageError } from './operationErrors';

export const ASSISTANT_CAPABILITY_LOAD_CODE = 'assistant.load-capabilities';
const FULL_CATALOG_LIMIT = 12;
const LOADED_LIMIT = 8;

/** Discovery changes request context only. The live registry still owns execution and permission. */
export function assistantCapabilityCatalog(capabilities: AssistantCapability[]) {
  if (capabilities.some(({ descriptor }) => descriptor.code === ASSISTANT_CAPABILITY_LOAD_CODE))
    throw new Error('Reserved assistant discovery capability');
  const byCode = new Map(capabilities.map((capability) => [capability.descriptor.code, capability]));
  const discovery: AssistantCapability<string[]> = {
    effect: 'read',
    descriptor: {
      code: ASSISTANT_CAPABILITY_LOAD_CODE,
      description:
        'Load complete input schemas for up to eight capabilities from the current capabilityIndex. Choose the tools needed for the next step; their schemas will be declared in the next model turn. This only reads definitions and does not execute those tools or grant permission. Read current facts through the loaded read tools before preparing changes.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['codes'],
        properties: {
          codes: {
            type: 'array',
            minItems: 1,
            maxItems: LOADED_LIMIT,
            uniqueItems: true,
            items: { type: 'string', enum: [...byCode.keys()] },
          },
        },
      },
    },
    parseInput(input) {
      if (!input || typeof input !== 'object' || Array.isArray(input))
        throw new OperationUsageError('请选择当前目录中的能力');
      const value = input as Record<string, unknown>;
      if (
        Object.keys(value).some((key) => key !== 'codes') ||
        !Array.isArray(value.codes) ||
        !value.codes.length ||
        value.codes.length > LOADED_LIMIT ||
        new Set(value.codes).size !== value.codes.length ||
        value.codes.some((code) => typeof code !== 'string' || !byCode.has(code))
      )
        throw new OperationUsageError('每次请选择当前目录中一至八个不同能力');
      return value.codes as string[];
    },
    async execute(codes) {
      return { codes, definitionsOnly: true };
    },
  };
  return {
    discovery,
    project(results: AssistantCapabilityResult[] = []) {
      if (capabilities.length <= FULL_CATALOG_LIMIT)
        return { descriptors: capabilities.map(({ descriptor }) => descriptor), index: undefined };
      // Reconstruct from bounded observations instead of maintaining another mutable session/cache.
      // Every selected schema is rebuilt from the current allowed catalog, including after navigation.
      const codes = [
        ...new Set(
          results
            .slice()
            .reverse()
            .flatMap((result) => {
              if (result.error || !['read', 'effect-applied'].includes(result.execution)) return [];
              if (result.capabilityCode !== ASSISTANT_CAPABILITY_LOAD_CODE) return [result.capabilityCode];
              const output = result.output as { codes?: unknown } | undefined;
              return Array.isArray(output?.codes)
                ? output.codes.filter((code): code is string => typeof code === 'string')
                : [];
            })
            .filter((code) => byCode.has(code)),
        ),
      ].slice(0, LOADED_LIMIT);
      return {
        descriptors: [
          discovery.descriptor,
          ...[...new Set(codes)].flatMap((code) => (byCode.has(code) ? [byCode.get(code)!.descriptor] : [])),
        ],
        index: capabilities.map(({ descriptor }) => ({
          code: descriptor.code,
          description: descriptor.description,
        })),
      };
    },
  };
}
