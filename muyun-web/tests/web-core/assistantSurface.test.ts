import { describe, expect, it, vi } from 'vitest';
import {
  createAssistantSurfaceRegistry,
  sameAssistantInvocationToken,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
  StaleAssistantInvocationError,
  type AssistantCapability,
  type AssistantSurface,
} from '@muyun/web-core';

describe('assistant capability input contracts', () => {
  it('keeps parameterless capabilities on one strict empty-object contract', () => {
    expect(emptyAssistantCapabilityInputSchema()).toEqual({
      type: 'object',
      additionalProperties: false,
      properties: {},
    });
    expect(parseEmptyAssistantCapabilityInput({})).toEqual({});
    expect(() => parseEmptyAssistantCapabilityInput(undefined)).toThrow(
      'Assistant capability input must be an empty object',
    );
    expect(() => parseEmptyAssistantCapabilityInput({ unexpected: true })).toThrow(
      'Assistant capability input must be an empty object',
    );
  });
});

it('compares every invocation boundary without depending on property order', () => {
  const token = {
    pageInstanceKey: 'page-a',
    surfaceGeneration: 1,
    contextRevision: 'context-a',
    interactionRevision: 'interaction-a',
    fallback: false,
  };
  const reordered = {
    fallback: token.fallback,
    interactionRevision: token.interactionRevision,
    contextRevision: token.contextRevision,
    surfaceGeneration: token.surfaceGeneration,
    pageInstanceKey: token.pageInstanceKey,
  };
  expect(sameAssistantInvocationToken(token, reordered)).toBe(true);
  for (const change of [
    { pageInstanceKey: 'page-b' },
    { surfaceGeneration: 2 },
    { contextRevision: 'context-b' },
    { interactionRevision: 'interaction-b' },
    { interactionRevision: undefined },
    { fallback: true },
  ]) {
    expect(sameAssistantInvocationToken(token, { ...token, ...change })).toBe(false);
  }
  expect(sameAssistantInvocationToken(token, undefined)).toBe(false);
  expect(sameAssistantInvocationToken(undefined, token)).toBe(false);
  expect(sameAssistantInvocationToken(undefined, undefined)).toBe(true);
});

function fixture(options: {
  pageInstanceKey: string;
  revision: () => string;
  execute?: (
    input: string,
    context: Parameters<AssistantCapability<string>['execute']>[1],
  ) => Promise<unknown>;
  requestTurn?: AssistantSurface['requestTurn'];
}) {
  const execute = options.execute ?? (async (input: string) => input);
  const capability: AssistantCapability<string> = {
    effect: 'page',
    descriptor: {
      code: 'form.patch-draft',
      description: 'Patch the current form draft',
      inputSchema: { type: 'string' },
    },
    parseInput(input) {
      if (typeof input !== 'string') throw new Error('input must be a string');
      return input;
    },
    execute,
  };
  const surface: AssistantSurface = {
    describe: () => ({ surface: 'module-page', facts: { record: options.revision() } }),
    capabilities: () => [capability],
    requestTurn: options.requestTurn ?? (async () => ({ text: 'ok', toolCalls: [], finishReason: 'stop' })),
  };
  return {
    pageInstanceKey: options.pageInstanceKey,
    contextRevision: options.revision,
    surface,
  };
}

describe('assistant surface registry', () => {
  it('notifies surface lifecycle changes and releases subscribers', () => {
    const registry = createAssistantSurfaceRegistry();
    const listener = vi.fn();
    const unsubscribe = registry.subscribe(listener);
    const unregister = registry.register(fixture({ pageInstanceKey: 'tab-a', revision: () => '1' }));
    expect(listener).toHaveBeenCalledTimes(1);
    registry.activate('tab-a');
    expect(listener).toHaveBeenCalledTimes(2);
    registry.activate('tab-a');
    expect(listener).toHaveBeenCalledTimes(2);
    unregister();
    expect(listener).toHaveBeenCalledTimes(3);
    expect(registry.snapshot()).toBeUndefined();
    unsubscribe();
    registry.register(fixture({ pageInstanceKey: 'tab-b', revision: () => '1' }));
    registry.activate('tab-b');
    expect(listener).toHaveBeenCalledTimes(3);
  });

  it('settles background page work and returns the refreshed context snapshot', async () => {
    let contextRevision = 'loading';
    const settle = vi.fn(async () => {
      contextRevision = 'ready';
    });
    const registry = createAssistantSurfaceRegistry();
    registry.register({
      ...fixture({ pageInstanceKey: 'tab-a', revision: () => contextRevision }),
      interactionRevision: () => 'same-user-scope',
      settle,
    });
    registry.activate('tab-a');
    const initial = registry.snapshot()!;

    await expect(registry.settleActiveSurface(initial.token)).resolves.toMatchObject({
      token: { contextRevision: 'ready', interactionRevision: 'same-user-scope' },
      context: { facts: { record: 'ready' } },
    });
    expect(settle).toHaveBeenCalledOnce();
  });

  it('rejects settlement when the user-controlled execution scope changes', async () => {
    let interactionRevision = 'record-a';
    const registry = createAssistantSurfaceRegistry();
    registry.register({
      ...fixture({ pageInstanceKey: 'tab-a', revision: () => 'stable' }),
      interactionRevision: () => interactionRevision,
      settle: async () => {
        interactionRevision = 'record-b';
      },
    });
    registry.activate('tab-a');

    await expect(registry.settleActiveSurface(registry.snapshot()!.token)).rejects.toBeInstanceOf(
      StaleAssistantInvocationError,
    );
  });

  it('does not absorb a context change when the surface declares no interaction scope', async () => {
    let contextRevision = 'before';
    const registry = createAssistantSurfaceRegistry();
    registry.register({
      ...fixture({ pageInstanceKey: 'tab-a', revision: () => contextRevision }),
      settle: async () => {
        contextRevision = 'after';
      },
    });
    registry.activate('tab-a');

    await expect(registry.settleActiveSurface(registry.snapshot()!.token)).rejects.toBeInstanceOf(
      StaleAssistantInvocationError,
    );
  });

  it('waits for the active page to replace its workbench fallback surface', async () => {
    const registry = createAssistantSurfaceRegistry();
    registry.activate('tab-a');
    registry.register({
      ...fixture({ pageInstanceKey: 'tab-a', revision: () => 'workbench' }),
      fallback: true,
    });

    const ready = registry.waitForActiveSurface({
      pageInstanceKey: 'tab-a',
      requireFormal: true,
    });
    registry.register(fixture({ pageInstanceKey: 'tab-a', revision: () => 'record-a' }));

    await expect(ready).resolves.toMatchObject({
      token: { pageInstanceKey: 'tab-a', fallback: false },
      context: { surface: 'module-page' },
    });
  });

  it('selects a surface by active page instance instead of last registration', () => {
    const registry = createAssistantSurfaceRegistry();
    registry.register(fixture({ pageInstanceKey: 'tab-a', revision: () => 'record-a' }));
    registry.register(fixture({ pageInstanceKey: 'tab-b', revision: () => 'record-b' }));

    registry.activate('tab-a');

    expect(registry.snapshot()?.token.pageInstanceKey).toBe('tab-a');
    expect(registry.snapshot()?.context.facts.record).toBe('record-a');
  });

  it('restores the workbench fallback after a page-owned surface unregisters', () => {
    const registry = createAssistantSurfaceRegistry();
    registry.activate('tab-a');
    const unregisterFallback = registry.register({
      ...fixture({ pageInstanceKey: 'tab-a', revision: () => 'workbench' }),
      fallback: true,
    });
    const fallbackGeneration = registry.snapshot()!.token.surfaceGeneration;
    const unregisterPage = registry.register(
      fixture({ pageInstanceKey: 'tab-a', revision: () => 'record-a' }),
    );

    expect(registry.snapshot()?.context.facts.record).toBe('record-a');
    expect(registry.snapshot()?.token.surfaceGeneration).not.toBe(fallbackGeneration);

    unregisterFallback();
    registry.register({
      ...fixture({ pageInstanceKey: 'tab-a', revision: () => 'workbench-new' }),
      fallback: true,
    });
    expect(registry.snapshot()?.context.facts.record).toBe('record-a');

    unregisterPage();

    expect(registry.snapshot()?.context.facts.record).toBe('workbench-new');
    expect(registry.snapshot()?.token.surfaceGeneration).not.toBe(fallbackGeneration);
  });

  it('rejects a capability call after the record context changes in the same surface', async () => {
    let revision = 'record-a';
    const execute = vi.fn(async () => undefined);
    const registry = createAssistantSurfaceRegistry();
    registry.register(fixture({ pageInstanceKey: 'tab-a', revision: () => revision, execute }));
    registry.activate('tab-a');
    const token = registry.snapshot()!.token;

    revision = 'record-b';

    await expect(
      registry.invoke({ id: 'call-1', code: 'form.patch-draft', input: 'value' }, token),
    ).rejects.toBeInstanceOf(StaleAssistantInvocationError);
    expect(execute).not.toHaveBeenCalled();
  });

  it('aborts an in-flight call and discards its result when the active page changes', async () => {
    let resolve!: (value: string) => void;
    const observedSignal = vi.fn();
    const registry = createAssistantSurfaceRegistry();
    registry.register(
      fixture({
        pageInstanceKey: 'tab-a',
        revision: () => 'record-a',
        execute: (_input, context) => {
          observedSignal(context.signal);
          return new Promise<string>((accept) => {
            resolve = accept;
          });
        },
      }),
    );
    registry.register(fixture({ pageInstanceKey: 'tab-b', revision: () => 'record-b' }));
    registry.activate('tab-a');
    const token = registry.snapshot()!.token;
    const pending = registry.invoke({ id: 'call-1', code: 'form.patch-draft', input: 'value' }, token);

    registry.activate('tab-b');
    resolve('late result');

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(observedSignal.mock.calls[0][0].aborted).toBe(true);
  });

  it('discards an in-flight passive result when the same surface context changes', async () => {
    let revision = 'record-a';
    let resolve!: (value: string) => void;
    const registry = createAssistantSurfaceRegistry();
    registry.register(
      fixture({
        pageInstanceKey: 'tab-a',
        revision: () => revision,
        execute: () => new Promise<string>((accept) => (resolve = accept)),
      }),
    );
    registry.activate('tab-a');
    const pending = registry.invoke(
      { id: 'call-1', code: 'form.patch-draft', input: 'value' },
      registry.snapshot()!.token,
    );

    revision = 'record-b';
    resolve('late result');

    await expect(pending).rejects.toBeInstanceOf(StaleAssistantInvocationError);
  });

  it('accepts a context change produced through the guarded effect boundary', async () => {
    let revision = 'draft-before';
    const registry = createAssistantSurfaceRegistry();
    registry.register(
      fixture({
        pageInstanceKey: 'tab-a',
        revision: () => revision,
        execute: async (_input, context) => {
          context.applyEffect(() => {
            revision = 'draft-after';
          });
          return 'changed';
        },
      }),
    );
    registry.activate('tab-a');

    await expect(
      registry.invoke({ id: 'call-1', code: 'form.patch-draft', input: 'value' }, registry.snapshot()!.token),
    ).resolves.toEqual({ value: 'changed', contextChanged: true });
  });

  it('commits guarded internal state without reporting a page effect', async () => {
    let remembered = '';
    const registry = createAssistantSurfaceRegistry();
    registry.register(
      fixture({
        pageInstanceKey: 'tab-a',
        revision: () => 'stable',
        execute: async (input, context) => {
          context.commitInternalState(() => {
            remembered = String(input);
          });
          return 'remembered';
        },
      }),
    );
    registry.activate('tab-a');

    await expect(
      registry.invoke({ id: 'call-1', code: 'form.patch-draft', input: 'value' }, registry.snapshot()!.token),
    ).resolves.toEqual({ value: 'remembered', contextChanged: false });
    expect(remembered).toBe('value');
  });

  it('retains the effect fact without obsolete output when context drifts again after the guarded effect', async () => {
    let revision = 'draft-before';
    let resolve!: (value: string) => void;
    const registry = createAssistantSurfaceRegistry();
    registry.register(
      fixture({
        pageInstanceKey: 'tab-a',
        revision: () => revision,
        execute: async (_input, context) => {
          context.applyEffect(() => {
            revision = 'draft-after-effect';
          });
          return new Promise<string>((accept) => (resolve = accept));
        },
      }),
    );
    registry.activate('tab-a');
    const pending = registry.invoke(
      { id: 'call-1', code: 'form.patch-draft', input: 'value' },
      registry.snapshot()!.token,
    );

    revision = 'draft-after-user-edit';
    resolve('stale result');

    await expect(pending).rejects.toMatchObject({
      name: 'AssistantEffectInterruptedError',
      execution: 'effect-applied',
      cause: expect.any(StaleAssistantInvocationError),
    });
  });

  it('retains the effect fact without obsolete output when explicitly cancelled after the guarded effect', async () => {
    let revision = 'draft-before';
    let resolve!: (value: string) => void;
    const registry = createAssistantSurfaceRegistry();
    registry.register(
      fixture({
        pageInstanceKey: 'tab-a',
        revision: () => revision,
        execute: async (_input, context) => {
          context.applyEffect(() => {
            revision = 'draft-after-effect';
          });
          return new Promise<string>((accept) => (resolve = accept));
        },
      }),
    );
    registry.activate('tab-a');
    const cancellation = new AbortController();
    const pending = registry.invoke(
      { id: 'call-1', code: 'form.patch-draft', input: 'value' },
      registry.snapshot()!.token,
      cancellation.signal,
    );

    cancellation.abort();
    resolve('cancelled result');

    await expect(pending).rejects.toMatchObject({
      name: 'AssistantEffectInterruptedError',
      execution: 'effect-applied',
      cause: expect.objectContaining({ name: 'AbortError' }),
    });
  });

  it('uses the active surface opaque turn requester', async () => {
    const requestTurn = vi.fn(async () => ({
      text: 'tenant scoped',
      toolCalls: [],
      finishReason: 'stop',
    }));
    const registry = createAssistantSurfaceRegistry();
    registry.register(fixture({ pageInstanceKey: 'tab-a', revision: () => 'record-a', requestTurn }));
    registry.activate('tab-a');
    const snapshot = registry.snapshot()!;

    const result = await registry.requestTurn({ message: 'hello' }, snapshot.token);

    expect(result.text).toBe('tenant scoped');
    expect(requestTurn).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'hello',
        context: snapshot.context,
        capabilities: snapshot.capabilities,
      }),
      expect.any(AbortSignal),
    );
  });

  it('discards a completed operation after explicit cancellation', async () => {
    let resolve!: (value: string) => void;
    const registry = createAssistantSurfaceRegistry();
    registry.register(
      fixture({
        pageInstanceKey: 'tab-a',
        revision: () => 'record-a',
        execute: () => new Promise<string>((accept) => (resolve = accept)),
      }),
    );
    registry.activate('tab-a');
    const cancellation = new AbortController();
    const pending = registry.invoke(
      { id: 'call-1', code: 'form.patch-draft', input: 'value' },
      registry.snapshot()!.token,
      cancellation.signal,
    );

    cancellation.abort();
    resolve('late result');

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });
});

it('rejects duplicate composed capabilities instead of executing the first match', () => {
  const registry = createAssistantSurfaceRegistry();
  const capability: AssistantCapability = {
    effect: 'page',
    descriptor: { code: 'read', description: 'read', inputSchema: {} },
    parseInput: (input) => input,
    execute: vi.fn(),
  };
  expect(() =>
    registry.register({
      pageInstanceKey: 'a',
      contextRevision: () => '',
      surface: {
        describe: () => ({ surface: 'test', facts: {} }),
        capabilities: () => [capability, capability],
        requestTurn: vi.fn(),
      },
    }),
  ).toThrow('duplicate');
});

it('invalidates both requests and effects when identity or execution tenant changes', async () => {
  let identity = 'user-a';
  let tenant = 'tenant-a';
  const registry = createAssistantSurfaceRegistry(() => identity);
  registry.register({
    pageInstanceKey: 'a',
    contextRevision: () => '',
    conversationScopeKey: () => tenant,
    surface: {
      describe: () => ({ surface: 'test', facts: {} }),
      capabilities: () => [],
      requestTurn: vi.fn(),
    },
  });
  registry.activate('a');
  const token = registry.snapshot()!.token;
  tenant = 'tenant-b';
  await expect(registry.requestTurn({ message: 'old', history: [] }, token)).rejects.toThrow(
    StaleAssistantInvocationError,
  );
  const next = registry.snapshot()!.token;
  identity = 'user-b';
  await expect(registry.requestTurn({ message: 'old', history: [] }, next)).rejects.toThrow(
    StaleAssistantInvocationError,
  );
});
