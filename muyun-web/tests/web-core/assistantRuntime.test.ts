import { expect, it, vi } from 'vitest';
import { createAssistantSurfaceRegistry, runAssistantStep, type AssistantCapability } from '@muyun/web-core';

it('executes declared capabilities and ends the step when their effect changes context', async () => {
  let revision = 'draft-before';
  const patch: AssistantCapability = {
    descriptor: {
      code: 'form.patch-draft',
      description: 'Patch draft',
      inputSchema: { type: 'object' },
    },
    parseInput: (input) => input,
    async execute(_input, context) {
      context.applyEffect(() => {
        revision = 'draft-after';
      });
      return { changed: true };
    },
  };
  const requestTurn = vi.fn(async () => ({
    toolCalls: [{ id: 'call-1', code: 'form.patch-draft', input: { title: 'Done' } }],
    finishReason: 'tool_calls',
  }));
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => revision,
    surface: {
      describe: () => ({ surface: 'module-page', facts: {} }),
      capabilities: () => [patch],
      requestTurn,
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantStep(registry, 'fill title');

  expect(result.results).toEqual([{ callId: 'call-1', output: { changed: true } }]);
  expect(result.contextChanged).toBe(true);
});

it('returns an ordinary capability failure as a structured result', async () => {
  const failing: AssistantCapability = {
    descriptor: { code: 'page.fail', description: 'Fail', inputSchema: {} },
    parseInput: (input) => input,
    async execute() {
      throw new Error('business input is incomplete');
    },
  };
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'page', facts: {} }),
      capabilities: () => [failing],
      requestTurn: async () => ({
        toolCalls: [{ id: 'call-1', code: 'page.fail', input: {} }],
      }),
    },
  });
  registry.activate('tab-a');

  const result = await runAssistantStep(registry, 'try');

  expect(result.results).toEqual([
    {
      callId: 'call-1',
      error: { code: 'CAPABILITY_FAILED', message: 'Capability execution failed' },
    },
  ]);
  expect(result.contextChanged).toBe(false);
});
