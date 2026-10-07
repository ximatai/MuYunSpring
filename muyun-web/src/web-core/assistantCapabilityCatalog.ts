import type { AssistantCapabilityResult } from '@muyun/web-contracts';
import type { AssistantCapability } from './assistantSurface';
import { OperationUsageError } from './operationErrors';

export const ASSISTANT_CAPABILITY_LOAD_CODE = 'assistant.load-capabilities';
const FULL_CATALOG_LIMIT = 12;
export const ASSISTANT_CAPABILITY_SELECTION_LIMIT = 8;
const LOADED_LIMIT = ASSISTANT_CAPABILITY_SELECTION_LIMIT;
const EAGER_LIMIT = FULL_CATALOG_LIMIT - LOADED_LIMIT - 1;

/** Discovery changes request context only. The live registry still owns execution and permission. */
export function assistantCapabilityCatalog(capabilities: AssistantCapability[]) {
  if (capabilities.some(({ descriptor }) => descriptor.code === ASSISTANT_CAPABILITY_LOAD_CODE))
    throw new Error('Reserved assistant discovery capability');
  const endDecisionAfter = capabilities
    .filter((capability) => capability.effect !== 'read' || capability.changesReadState || capability.propose)
    .map(({ descriptor }) => descriptor.code);
  const byCode = new Map(capabilities.map((capability) => [capability.descriptor.code, capability]));
  // Overflow remains discoverable. Current surface hints do not constrain valid combinations.
  const eager = capabilities
    .filter((capability) => capability.schemaDiscovery === 'eager')
    .slice(0, EAGER_LIMIT);
  const eagerCodes = new Set(eager.map(({ descriptor }) => descriptor.code));
  const discovery: AssistantCapability<string[]> = {
    effect: 'read',
    descriptor: {
      code: ASSISTANT_CAPABILITY_LOAD_CODE,
      description:
        'Load complete input schemas for up to eight capabilities from the current capabilityIndex. If some requested codes are no longer available, load the remaining current definitions and report unavailableCodes; do not repeat the same stale batch or infer that other capabilities are unavailable. Only eight non-eager definitions are retained: a new batch can evict previous definitions, so include the tools you intend to call next. Check the next turn’s declared tools; index entries cannot be called until declared. Schemas are rebuilt from the live catalog after draft changes. Declared tools need no rediscovery. This only reads definitions, not business data, and does not execute tools or grant permission. Reuse current supplied facts; read only missing business facts before preparing changes.',
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
        value.codes.some((code) => typeof code !== 'string')
      )
        throw new OperationUsageError('每次请选择当前目录中一至八个不同能力');
      if (value.codes.every((code) => !byCode.has(code as string)))
        throw new OperationUsageError(
          `所选能力均已不在当前目录：${value.codes.join('、')}。请从当前 capabilityIndex 选择可用能力，核对当前操作范围、状态和恢复指引；不要重复此批次或读取不变的业务事实。`,
        );
      return value.codes as string[];
    },
    async execute(codes) {
      const availableCodes = codes.filter((code) => byCode.has(code));
      const unavailableCodes = codes.filter((code) => !byCode.has(code));
      return {
        codes: availableCodes,
        definitionsOnly: true,
        ...(unavailableCodes.length
          ? {
              unavailableCodes,
              recovery:
                'Use the loaded definitions and current capabilityIndex; do not repeat unavailable codes. Availability depends on the current scope and state, not on previously read business facts.',
            }
          : {}),
      };
    },
  };
  return {
    discovery,
    project(
      results: AssistantCapabilityResult[] = [],
      selectedCodes: string[] = [],
      recoveryCodes: string[] = [],
    ) {
      if (capabilities.length <= FULL_CATALOG_LIMIT)
        return {
          descriptors: capabilities.map(({ descriptor }) => descriptor),
          index: undefined,
          selectedCodes: [],
          endDecisionAfter,
        };
      // Recent results take priority over conversation-local name choices.
      // Every selected schema is rebuilt from the current allowed catalog, including after navigation.
      const codes = [
        ...new Set(
          [
            // A rejected decision may identify an evicted, still-allowed schema. Re-declare it
            // for one fresh decision; this never executes the rejected call or retains its input.
            ...recoveryCodes,
            ...results
              .slice()
              .reverse()
              .flatMap((result) => {
                if (result.error || !['read', 'effect-applied'].includes(result.execution)) return [];
                if (result.capabilityCode !== ASSISTANT_CAPABILITY_LOAD_CODE) return [result.capabilityCode];
                const output = result.output as { codes?: unknown } | undefined;
                return Array.isArray(output?.codes)
                  ? output.codes.filter((code): code is string => typeof code === 'string')
                  : [];
              }),
            ...selectedCodes,
          ].filter((code) => byCode.has(code) && !eagerCodes.has(code)),
        ),
      ].slice(0, LOADED_LIMIT);
      return {
        endDecisionAfter,
        selectedCodes: codes,
        descriptors: [
          discovery.descriptor,
          ...eager.map(({ descriptor }) => descriptor),
          ...[...new Set(codes)].flatMap((code) => (byCode.has(code) ? [byCode.get(code)!.descriptor] : [])),
        ],
        // Declared tools already carry their full description. Keep only the remaining
        // tools in discovery; eviction makes a tool discoverable again on the next turn.
        index: capabilities
          .filter(({ descriptor }) => !eagerCodes.has(descriptor.code) && !codes.includes(descriptor.code))
          .map(({ descriptor }) => ({
            code: descriptor.code,
            description: descriptor.description,
          })),
      };
    },
  };
}
