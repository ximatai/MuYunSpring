import { describe, expect, it } from 'vitest';
import { createAssistantSurfaceRegistry, StaleAssistantInvocationError } from '@muyun/web-core';

describe('assistant effect settlement ownership', () => {
  function fixture() {
    let contextRevision = 0;
    let interactionRevision = 0;
    let finishSettlement!: () => void;
    const settlement = new Promise<void>((resolve) => {
      finishSettlement = resolve;
    });
    const registry = createAssistantSurfaceRegistry();
    registry.register({
      pageInstanceKey: 'record-page',
      contextRevision: () => String(contextRevision),
      interactionRevision: () => String(interactionRevision),
      surface: {
        describe: () => ({ surface: 'module-page', facts: {} }),
        requestTurn: async () => ({ toolCalls: [] }),
        capabilities: () => [
          {
            effect: 'page',
            descriptor: { code: 'record.start-create', description: 'Open draft', inputSchema: {} },
            parseInput: () => ({}),
            async execute(_input, context) {
              context.applyEffect(
                () => {
                  contextRevision += 1;
                },
                () => settlement,
              );
              return { opened: true };
            },
          },
        ],
      },
    });
    registry.activate('record-page');
    return {
      invoke: () =>
        registry.invoke(
          { id: 'open-draft', code: 'record.start-create', input: {} },
          registry.snapshot()!.token,
        ),
      refresh() {
        contextRevision += 1;
      },
      editAsUser() {
        interactionRevision += 1;
        contextRevision += 1;
      },
      finishSettlement,
    };
  }

  it('accepts background context refreshes while its draft effect settles', async () => {
    const page = fixture();
    const invocation = page.invoke();
    page.refresh();
    page.finishSettlement();
    await expect(invocation).resolves.toEqual({ value: { opened: true }, contextChanged: true });
  });

  it('does not absorb user edits made while its draft effect settles', async () => {
    const page = fixture();
    const invocation = page.invoke();
    page.editAsUser();
    page.finishSettlement();
    await expect(invocation).rejects.toMatchObject({
      name: 'AssistantEffectInterruptedError',
      execution: 'effect-applied',
      cause: expect.any(StaleAssistantInvocationError),
    });
  });

  it.each([false, true])('requires an adapter-validated replacement token: %s', async (validated) => {
    const registry = createAssistantSurfaceRegistry();
    const destination = {
      pageInstanceKey: 'destination',
      contextRevision: () => 'ready',
      interactionRevision: () => 'unchanged',
      surface: {
        describe: () => ({ surface: 'module-page', facts: {} }),
        capabilities: () => [],
        requestTurn: async () => ({ toolCalls: [] }),
      },
    };
    registry.register({
      ...destination,
      pageInstanceKey: 'source',
      surface: {
        ...destination.surface,
        capabilities: () => [
          {
            effect: 'page',
            descriptor: { code: 'navigate', description: 'Navigate', inputSchema: {} },
            parseInput: () => ({}),
            async execute(_input, context) {
              context.applyEffect(
                () => {},
                async () => {
                  registry.register(destination);
                  registry.activate('destination');
                  return validated ? registry.snapshot()!.token : undefined;
                },
              );
              return { opened: true };
            },
          },
        ],
      },
    });
    registry.activate('source');
    const invocation = registry.invoke(
      { id: 'navigate', code: 'navigate', input: {} },
      registry.snapshot()!.token,
    );
    if (validated) {
      await expect(invocation).resolves.toEqual({ value: { opened: true }, contextChanged: true });
    } else {
      await expect(invocation).rejects.toMatchObject({
        name: 'AssistantEffectInterruptedError',
        execution: 'effect-applied',
        cause: expect.any(StaleAssistantInvocationError),
      });
    }
  });
});
