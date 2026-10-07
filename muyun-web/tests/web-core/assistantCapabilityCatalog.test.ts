import { expect, it, vi } from 'vitest';
import { assistantCapabilityCatalog } from '@/web-core/assistantCapabilityCatalog';
import {
  createAssistantSurfaceRegistry,
  createAssistantCapabilitySelection,
  type AssistantCapability,
} from '@muyun/web-core';
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

it('projects decision boundaries from permitted live capabilities, separately from definitions', async () => {
  const live = capabilities();
  live[3] = { ...live[3]!, changesReadState: true };
  const registry = createAssistantSurfaceRegistry();
  const requestTurn = vi.fn<(input: AssistantTurnInput) => Promise<{ toolCalls: [] }>>(async () => ({
    toolCalls: [],
  }));
  registry.register({
    pageInstanceKey: 'boundaries',
    contextRevision: () => 'one',
    surface: {
      describe: () => ({ surface: 'module-page', facts: {} }),
      capabilities: () => live,
      requestTurn,
    },
  });
  registry.activate('boundaries');
  await registry.requestTurn({ message: 'read' }, registry.snapshot()!.token);
  expect(requestTurn.mock.calls[0]![0].context.facts.executionBoundaries).toEqual({
    endDecisionAfter: ['tool-1', 'tool-2', 'tool-3'],
  });
  await registry.requestTurn({ message: 'read' }, registry.snapshot()!.token, undefined, undefined, {
    readOnly: true,
  });
  expect(requestTurn.mock.calls[1]![0].context.facts.executionBoundaries).toEqual({
    endDecisionAfter: ['tool-3'],
  });
});

it('reuses bounded schema choices while rebuilding live definitions and never transporting the selection', async () => {
  let live = capabilities();
  let revision = 'one';
  let interaction = 'one';
  const selection = createAssistantCapabilitySelection();
  const requestTurn = vi.fn<(input: AssistantTurnInput) => Promise<{ toolCalls: [] }>>(async () => ({
    toolCalls: [],
  }));
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'current',
    contextRevision: () => revision,
    interactionRevision: () => interaction,
    surface: {
      describe: () => ({ surface: 'module-page', facts: { revision } }),
      capabilities: () => live,
      requestTurn,
    },
  });
  registry.activate('current');
  const request = async (results: AssistantCapabilityResult[] = [], readOnly = false, summary = false) => {
    await registry.requestTurn(
      {
        message: 'work',
        results,
        capabilitySelection: selection,
        ...(summary
          ? { executionBudget: { phase: 'summary' as const, step: 9, normalLimit: 8, hardLimit: 12 } }
          : {}),
      },
      registry.snapshot()!.token,
      undefined,
      undefined,
      { readOnly },
    );
    return requestTurn.mock.calls.at(-1)![0];
  };
  await request([loaded(['tool-3', 'tool-4', 'tool-5', 'tool-6', 'tool-7', 'tool-8', 'tool-9', 'tool-10'])]);
  revision = 'two';
  interaction = 'manual-change';
  live[5] = {
    ...live[5]!,
    descriptor: { ...live[5]!.descriptor, inputSchema: { properties: { current: { const: revision } } } },
  };
  let input = await request();
  expect(input.capabilities.find(({ code }) => code === 'tool-5')!.inputSchema).toEqual({
    properties: { current: { const: 'two' } },
  });
  expect(input.context.facts.revision).toBe('two');
  expect(input.results).toEqual([]);
  expect(input).not.toHaveProperty('capabilitySelection');
  expect(input.capabilities).toHaveLength(12);
  await request([], false, true);
  input = await request();
  expect(input.capabilities.map(({ code }) => code)).toContain('tool-10');
  live = live.filter(({ descriptor }) => descriptor.code !== 'tool-5');
  input = await request();
  expect(JSON.stringify(input)).not.toContain('"tool-5"');
  live[5] = { ...live[5]!, effect: 'draft' };
  const forbidden = live[5]!.descriptor.code;
  input = await request([], true);
  expect(input.capabilities.map(({ code }) => code)).not.toContain(forbidden);
  await expect(
    registry.invoke({ id: 'denied', code: forbidden, input: {} }, registry.snapshot()!.token, undefined, {
      readOnly: true,
    }),
  ).rejects.toThrow();
  expect(live.every((item) => vi.mocked(item.execute).mock.calls.length === 0)).toBe(true);
});

it.each(['identityScopeKey', 'executionScopeKey', 'executionScopePending'] as const)(
  'discards schema choices at the %s boundary, including returning to the former scope',
  (boundary) => {
    const selection = createAssistantCapabilitySelection();
    const token = {
      identityScopeKey: 'user-a',
      executionScopeKey: 'tenant-a',
      executionScopePending: false,
      pageInstanceKey: 'page-a',
      surfaceGeneration: 1,
      contextRevision: 'draft-a',
      interactionRevision: 'manual-a',
      fallback: false,
    };
    selection.remember(
      token,
      Array.from({ length: 20 }, (_, index) => `tool-${index}`),
    );
    expect(selection.forScope(token)).toHaveLength(8);
    const changed = {
      ...token,
      [boundary]:
        typeof token[boundary] === 'boolean' ? true : typeof token[boundary] === 'number' ? 2 : 'other',
    };
    expect(selection.forScope(changed)).toEqual([]);
    expect(selection.forScope(token)).toEqual([]);
  },
);

it('distinguishes stale scope discovery from malformed selection without loading or executing anything', () => {
  const live = capabilities();
  const discovery = assistantCapabilityCatalog(live).discovery;
  expect(() => discovery.parseInput({ codes: ['removed-tool'] })).toThrow('核对当前操作范围、状态和恢复指引');
  expect(() => discovery.parseInput({ codes: ['removed-tool'] })).toThrow('removed-tool');
  expect(() => discovery.parseInput({ codes: ['tool-0', 'tool-0'] })).toThrow('一至八个不同能力');
  expect(() => discovery.parseInput({ codes: [] })).toThrow('一至八个不同能力');
  expect(discovery.parseInput({ codes: ['tool-0'] })).toEqual(['tool-0']);
  expect(live.every((item) => vi.mocked(item.execute).mock.calls.length === 0)).toBe(true);
});

it('loads current definitions from a partly stale batch and identifies only the unavailable choices', async () => {
  const live = capabilities();
  const catalog = assistantCapabilityCatalog(live);
  const input = catalog.discovery.parseInput({ codes: ['tool-5', 'removed-tool', 'tool-6'] });
  const output = await catalog.discovery.execute(input, {
    signal: new AbortController().signal,
    isCurrent: () => true,
    commitInternalState: () => {
      throw new Error('Definition discovery must not commit state');
    },
    applyEffect: () => {
      throw new Error('Definition discovery must not apply effects');
    },
  });
  expect(output).toEqual({
    codes: ['tool-5', 'tool-6'],
    definitionsOnly: true,
    unavailableCodes: ['removed-tool'],
    recovery: expect.stringContaining('current capabilityIndex'),
  });
  const projection = catalog.project([{ ...loaded([]), input: { codes: input }, output }]);
  expect(projection.selectedCodes).toEqual(['tool-5', 'tool-6']);
  expect(projection.descriptors.map(({ code }) => code)).toContain('tool-5');
  expect(JSON.stringify(projection)).not.toContain('removed-tool');
  expect(live.every((item) => vi.mocked(item.execute).mock.calls.length === 0)).toBe(true);
});

it('partly loads only permitted definitions without restoring a forbidden operation or granting execution', async () => {
  const live = capabilities();
  const requestTurn = vi.fn<(input: AssistantTurnInput) => Promise<{ toolCalls: [] }>>(async () => ({
    toolCalls: [],
  }));
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'discovery',
    contextRevision: () => 'current',
    surface: {
      describe: () => ({ surface: 'module-page', facts: {} }),
      capabilities: () => live,
      requestTurn,
    },
  });
  registry.activate('discovery');
  const result = await registry.invoke(
    { id: 'load-current', code: 'assistant.load-capabilities', input: { codes: ['tool-5', 'tool-1'] } },
    registry.snapshot()!.token,
    undefined,
    { readOnly: true },
  );
  expect(result.value).toMatchObject({ codes: ['tool-5'], unavailableCodes: ['tool-1'] });
  await registry.requestTurn(
    { message: 'continue', results: [{ ...loaded([]), output: result.value }] },
    registry.snapshot()!.token,
    undefined,
    undefined,
    { readOnly: true },
  );
  const request = requestTurn.mock.calls.at(-1)![0];
  expect(request.capabilities.map(({ code }) => code)).toContain('tool-5');
  expect(JSON.stringify([request.capabilities, request.context.facts.capabilityIndex])).not.toContain(
    '"tool-1"',
  );
  await expect(
    registry.invoke({ id: 'forbidden', code: 'tool-1', input: {} }, registry.snapshot()!.token, undefined, {
      readOnly: true,
    }),
  ).rejects.toThrow();
  expect(live.every((item) => vi.mocked(item.execute).mock.calls.length === 0)).toBe(true);
});

it('retains bounded names across page navigation and rebuilds only current permitted schemas', () => {
  const selection = createAssistantCapabilitySelection();
  const token = {
    identityScopeKey: 'a',
    executionScopeKey: 'tenant-a',
    executionScopePending: false,
    pageInstanceKey: 'page-a',
    surfaceGeneration: 1,
    contextRevision: 'before',
    interactionRevision: 'before',
    fallback: false,
  };
  selection.remember(token, ['tool-0', 'tool-1']);
  const destination = { ...token, pageInstanceKey: 'page-b', surfaceGeneration: 2, fallback: true };
  expect(selection.forScope(destination)).toEqual(['tool-0', 'tool-1']);
  const live = capabilities().filter(({ descriptor }) => descriptor.code !== 'tool-1');
  live[0]!.descriptor.inputSchema = { type: 'object', properties: { currentTarget: { const: 'page-b' } } };
  const projection = assistantCapabilityCatalog(live).project([], selection.forScope(destination));
  expect(projection.descriptors.find(({ code }) => code === 'tool-0')?.inputSchema).toEqual(
    live[0]!.descriptor.inputSchema,
  );
  expect(projection.descriptors.some(({ code }) => code === 'tool-1')).toBe(false);
  expect(live.every((capability) => vi.mocked(capability.execute).mock.calls.length === 0)).toBe(true);
  selection.clear();
  expect(selection.forScope(destination)).toEqual([]);
});
