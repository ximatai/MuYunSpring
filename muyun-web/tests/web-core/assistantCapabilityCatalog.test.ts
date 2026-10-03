import { expect, it, vi } from 'vitest';
import { assistantCapabilityCatalog } from '@/web-core/assistantCapabilityCatalog';
import { createAssistantSurfaceRegistry, type AssistantCapability } from '@muyun/web-core';
import type { AssistantCapabilityResult, AssistantTurnInput } from '@muyun/web-contracts';

function capabilities(): AssistantCapability[] {
  return Array.from({ length: 20 }, (_, index) => ({
    effect: index === 1 ? 'draft' : 'read',
    ...(index < 5 ? { schemaDiscovery: 'eager' as const } : {}),
    ...(index === 2 ? { propose: vi.fn() } : {}),
    descriptor: { code: `tool-${index}`, description: `Tool ${index}`, inputSchema: { type: 'object' } },
    parseInput: (input) => input,
    execute: vi.fn(async () => ({})),
  }));
}

function loaded(codes: string[]): AssistantCapabilityResult {
  return {
    callId: 'load',
    capabilityCode: 'assistant.load-capabilities',
    input: { codes },
    execution: 'read',
    output: { codes, definitionsOnly: true },
  };
}

it('keeps current schemas direct and overflow discoverable without wasting the lazy window on overlap', () => {
  const live = capabilities();
  const catalog = assistantCapabilityCatalog(live);
  const initial = catalog.project();
  expect(initial.descriptors.map(({ code }) => code)).toEqual([
    'assistant.load-capabilities',
    'tool-0',
    'tool-1',
    'tool-2',
  ]);
  expect(initial.index!.map(({ code }) => code)).toContain('tool-3');
  const result = catalog.project([
    loaded(['tool-0', 'tool-1', 'tool-2', 'tool-3', 'tool-4', 'tool-5', 'tool-6', 'tool-7']),
    loaded(['tool-0', 'tool-1', 'tool-2', 'tool-8', 'tool-9', 'tool-10']),
  ]);
  expect(result.descriptors).toHaveLength(12);
  const codes = result.descriptors.slice(1).map(({ code }) => code);
  expect(new Set(codes).size).toBe(codes.length);
  expect(codes).toEqual(expect.arrayContaining(Array.from({ length: 11 }, (_, n) => `tool-${n}`)));
  expect(result.index!.every(({ code }) => !codes.includes(code))).toBe(true);
  expect(new Set([...codes, ...result.index!.map(({ code }) => code)])).toEqual(
    new Set(live.map(({ descriptor }) => descriptor.code)),
  );
});

it('rebuilds direct schemas after changes and applies read-only policy before exposing them', async () => {
  let live = capabilities();
  let revision = 'one';
  const requestTurn = vi.fn<(input: AssistantTurnInput) => Promise<{ toolCalls: [] }>>(async () => ({
    toolCalls: [],
  }));
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'current',
    contextRevision: () => revision,
    surface: {
      describe: () => ({ surface: 'module-page', facts: {} }),
      capabilities: () => live,
      requestTurn,
    },
  });
  registry.activate('current');
  const request = async (readOnly = false) => {
    await registry.requestTurn({ message: 'work' }, registry.snapshot()!.token, undefined, undefined, {
      readOnly,
    });
    return requestTurn.mock.calls.at(-1)![0];
  };
  let input = await request();
  expect(input.capabilities.map(({ code }) => code)).toContain('tool-1');
  revision = 'two';
  live[1] = {
    ...live[1]!,
    descriptor: { ...live[1]!.descriptor, inputSchema: { properties: { current: { const: revision } } } },
  };
  input = await request();
  expect(input.capabilities.find(({ code }) => code === 'tool-1')!.inputSchema).toEqual({
    properties: { current: { const: 'two' } },
  });
  input = await request(true);
  expect(JSON.stringify([input.capabilities, input.context.facts.capabilityIndex])).not.toMatch(/tool-[12]"/);
  await expect(
    registry.invoke({ id: 'denied', code: 'tool-1', input: {} }, registry.snapshot()!.token, undefined, {
      readOnly: true,
    }),
  ).rejects.toThrow();
  expect(live.every((item) => vi.mocked(item.execute).mock.calls.length === 0)).toBe(true);
  live = live.filter(({ descriptor }) => descriptor.code !== 'tool-1');
  input = await request();
  expect(JSON.stringify([input.capabilities, input.context.facts.capabilityIndex])).not.toContain('"tool-1"');
  await registry.requestTurn(
    { message: 'summary', executionBudget: { phase: 'summary', step: 9, normalLimit: 8, hardLimit: 12 } },
    registry.snapshot()!.token,
  );
  expect(requestTurn.mock.calls.at(-1)![0].capabilities).toEqual([]);
});
